package com.focusguard.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.focusguard.app.FocusGuardApp
import com.focusguard.app.MainActivity
import com.focusguard.app.R
import com.focusguard.app.ai.AiClient
import com.focusguard.app.capture.ScreenCapturer
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LockState
import com.focusguard.app.data.LogStore
import com.focusguard.app.data.Settings
import com.focusguard.app.detection.AppCategoryStore
import com.focusguard.app.detection.AppClassifier
import com.focusguard.app.detection.DetectionOutcome
import com.focusguard.app.detection.DetectionPipeline
import com.focusguard.app.detection.DetectionSource
import com.focusguard.app.enforce.AppBlockActivity
import com.focusguard.app.enforce.Enforcer
import com.focusguard.app.token.AdaptiveScheduler
import com.focusguard.app.token.DecisionCache
import com.focusguard.app.token.TokenBudget
import com.focusguard.app.usage.UsageRuleStore
import com.focusguard.app.usage.UsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台守护服务：周期性执行三级检测，并在判定为娱乐时执法。
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val ACTION_START = "com.focusguard.app.START"
        private const val ACTION_STOP = "com.focusguard.app.STOP"
        private const val ACTION_TEST = "com.focusguard.app.TEST"

        /** 使用时长闸门的采样间隔（毫秒）。比 AI 检测间隔密得多，才能及时拦截。 */
        const val USAGE_TICK_MS = 15_000L

        /** 服务是否正在运行，UI 可据此显示真实状态。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 最近一次检测结果，供"测试识别"等界面读取。 */
        @Volatile
        var lastOutcome: DetectionOutcome? = null
            private set

        fun startService(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MonitorService::class.java).apply {
                action = ACTION_START
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).apply {
                action = ACTION_STOP
            })
        }

        /** 立即执行一次检测，不等待下一个周期。 */
        fun requestImmediateCheck(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).apply {
                action = ACTION_TEST
            })
        }
    }

    private lateinit var settings: Settings
    private lateinit var logStore: LogStore
    private lateinit var lockState: LockState
    private lateinit var aiClient: AiClient
    private lateinit var enforcer: Enforcer
    private lateinit var categoryStore: AppCategoryStore
    private lateinit var tokenBudget: TokenBudget
    private lateinit var decisionCache: DecisionCache
    private lateinit var usageRuleStore: UsageRuleStore
    private lateinit var usageTracker: UsageTracker
    private var scheduler: AdaptiveScheduler? = null

    private var screenCapturer: ScreenCapturer? = null
    private var mediaProjection: MediaProjection? = null
    private var pipeline: DetectionPipeline? = null
    /** 是否处于「使用时长触发」的加密检测模式。 */
    private var usageTriggeredDetection = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null
    private var consecutiveViolations = 0

    /** MediaProjection 被系统或用户回收时同步停止服务，避免无声空转。 */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection 已被系统停止")
            stopMonitoring()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        logStore = LogStore(this)
        lockState = LockState(this)
        aiClient = AiClient()
        enforcer = Enforcer(this)
        categoryStore = AppCategoryStore(this)
        tokenBudget = TokenBudget(this).apply { dailyCallLimit = settings.dailyCallLimit }
        decisionCache = DecisionCache(this)
        usageRuleStore = UsageRuleStore(this)
        usageTracker = UsageTracker(usageRuleStore)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra("resultCode", 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("data")
                if (data != null) {
                    startMonitoring(resultCode, data)
                } else {
                    Log.e(TAG, "缺少 MediaProjection 授权数据，无法启动")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopMonitoring()
            ACTION_TEST -> serviceScope.launch { performDetection(isManualTest = true) }
            // 系统在资源紧张时杀掉服务后重建（START_STICKY）：
            // MediaProjection 授权无法自动重放，此时不做检测，
            // 但锁机状态仍由 LockState 持久化 + LockScreenActivity 兜底，
            // 不会被这次重建绕过。
            null -> {
                Log.w(TAG, "服务被系统重建（无授权数据），保持运行但等待重新授权")
                startForeground(FocusGuardApp.NOTIFICATION_ID, buildNotification(null))
            }
        }
        // START_STICKY：服务被杀后系统会重建。
        // 重建后虽无 MediaProjection 无法检测，但前台服务身份保持，
        // 用户重新点击"开始守护"即可恢复完整功能。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring(resultCode: Int, data: Intent) {
        startForeground(FocusGuardApp.NOTIFICATION_ID, buildNotification(null))

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "获取 MediaProjection 失败", e)
            null
        }

        if (projection == null) {
            stopSelf()
            return
        }

        // Android 14 起必须在 createVirtualDisplay 之前注册回调
        projection.registerCallback(projectionCallback, null)

        val capturer = ScreenCapturer(this)
        mediaProjection = projection
        screenCapturer = capturer
        pipeline = DetectionPipeline(
            context = this,
            settings = settings,
            aiClient = aiClient,
            screenCapturer = capturer,
            tokenBudget = tokenBudget,
            decisionCache = decisionCache,
            categoryStore = categoryStore
        )

        settings.serviceRunning = true
        isRunning = true
        consecutiveViolations = 0
        scheduler = AdaptiveScheduler(settings.intervalMinutes)

        startMonitoringLoop()
        Log.d(TAG, "守护已启动，检测间隔 ${settings.intervalMinutes} 分钟")
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        screenCapturer?.close()
        mediaProjection?.stop()

        screenCapturer = null
        mediaProjection = null
        pipeline = null

        settings.serviceRunning = false
        isRunning = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "守护已停止")
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            // 使用时长闸门的检查频率必须比 AI 检测密得多，
            // 否则用户超限后还能继续用好几分钟才被拦下
            var msUntilNextDetection = currentDetectionDelayMs()

            while (isActive) {
                delay(USAGE_TICK_MS)
                if (!isActive) break

                // 锁机兜底：锁机状态激活但锁机页不在前台（被最近任务/滑动销毁、
                // 进程被杀后服务重建）时，自动重新拉起锁机页
                enforceLockReassert()

                // 每个 tick 都累计使用时长并检查是否触发闸门
                when (val verdict = usageTick()) {
                    is UsageTracker.Verdict.ShouldHardBlock -> {
                        handleHardBlock(verdict)
                        // 已封锁，本轮不必再花 token 做 AI 检测
                        msUntilNextDetection = currentDetectionDelayMs()
                        continue
                    }
                    is UsageTracker.Verdict.ShouldDetect -> {
                        // 越过触发阈值，立刻转入检测模式而不等原定周期
                        if (!usageTriggeredDetection) {
                            usageTriggeredDetection = true
                            Log.d(
                                TAG,
                                "${verdict.packageName} 使用 ${verdict.usedMinutes} 分钟" +
                                    "（阈值 ${verdict.triggerMinutes}），开始 AI 检测"
                            )
                            msUntilNextDetection = 0L
                        }
                    }
                    UsageTracker.Verdict.Idle -> usageTriggeredDetection = false
                }

                msUntilNextDetection -= USAGE_TICK_MS
                if (msUntilNextDetection <= 0) {
                    performDetection(isManualTest = false)
                    msUntilNextDetection = currentDetectionDelayMs()
                }
            }
        }
    }

    /**
     * 锁机兜底：锁机状态激活、锁机页不在前台且无答题页时，
     * 重新拉起锁机页。防止用户通过最近任务/滑动销毁锁机页绕过锁机。
     */
    private fun enforceLockReassert() {
        try {
            if (!lockState.isLocked || !lockState.shouldBlockNow) return
            // 锁机页或答题页任一在前台都算受控
            if (com.focusguard.app.enforce.LockScreenActivity.instance != null) return
            if (com.focusguard.app.enforce.UnlockChallengeActivity.active) return

            Log.d(TAG, "锁机状态激活但锁机页不在前台，自动重新拉起")
            com.focusguard.app.enforce.LockScreenActivity.show(this)
        } catch (e: Exception) {
            Log.w(TAG, "锁机兜底拉起失败：${e.message}")
        }
    }

    /** 当前应等待的检测间隔，自适应开启时由调度器决定。 */
    private fun currentDetectionDelayMs(): Long {        val sched = scheduler
        return if (settings.adaptiveIntervalEnabled && sched != null) {
            sched.nextDelaySeconds() * 1000L
        } else {
            settings.intervalMinutes.coerceAtLeast(1) * 60_000L
        }
    }

    /**
     * 采样一次前台应用并累计使用时长。
     *
     * 排除自身与封锁界面：否则用户被封锁后停留在封锁页，
     * 封锁页自己的驻留时间会被算进被封应用的时长。
     */
    private fun usageTick(): UsageTracker.Verdict {
        val foreground = AppClassifier.classifyForegroundApp(this, categoryStore)
        val pkg = foreground?.packageName
        val countable = pkg?.takeUnless { it == packageName }
        return usageTracker.tick(countable)
    }

    /** 触发应用硬封锁：拉起全屏封锁页，并记入日志。 */
    private fun handleHardBlock(verdict: UsageTracker.Verdict.ShouldHardBlock) {
        val label = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(verdict.packageName, 0)
            ).toString()
        }.getOrDefault(verdict.packageName)

        Log.d(
            TAG,
            "$label 已用 ${verdict.usedMinutes} 分钟，超过上限 ${verdict.limitMinutes} 分钟，执行封锁"
        )

        AppBlockActivity.show(
            context = this,
            packageName = verdict.packageName,
            appLabel = label,
            usedMinutes = verdict.usedMinutes,
            limitMinutes = verdict.limitMinutes
        )

        logStore.addLog(
            DetectionLog(
                classification = "ENTERTAINMENT",
                confidence = 1f,
                reason = "$label 使用时长 ${verdict.usedMinutes} 分钟已达上限 ${verdict.limitMinutes} 分钟",
                action = "APP_BLOCK",
                source = DetectionSource.APP_CATEGORY.name,
                appLabel = label
            )
        )
    }

    private suspend fun performDetection(isManualTest: Boolean) {
        val activePipeline = pipeline ?: return

        // 锁机期间无需继续检测，省电也省 token
        if (!isManualTest && lockState.isLocked) {
            Log.d(TAG, "锁机中，跳过本轮检测")
            return
        }

        val outcome = try {
            activePipeline.detect(mediaProjection)
        } catch (e: Exception) {
            Log.e(TAG, "检测流程异常", e)
            DetectionOutcome(
                classification = "NEUTRAL",
                confidence = 0f,
                reason = "检测异常：${e.message}",
                source = DetectionSource.ERROR
            )
        }

        lastOutcome = outcome
        Log.d(TAG, "检测结果 ${outcome.classification} ${outcome.confidence} 来源=${outcome.source}")

        val isViolation = outcome.classification == "ENTERTAINMENT" &&
            outcome.confidence >= settings.confidenceThreshold

        var action = "NONE"
        if (isViolation) {
            consecutiveViolations++
            action = if (consecutiveViolations >= settings.consecutiveViolations.coerceAtLeast(1)) {
                consecutiveViolations = 0
                if (isManualTest) {
                    // 手动测试只报告结果，不真的锁机
                    "WARN"
                } else {
                    val performed = enforcer.enforce(settings.enforcementMode, outcome.reason)
                    if (settings.enforcementMode != Settings.EnforcementMode.WARN) {
                        lockState.startLock(settings.lockMinutesOnViolation, "AI")
                    }
                    performed
                }
            } else {
                "WARN"
            }
        } else if (outcome.source != DetectionSource.ERROR) {
            consecutiveViolations = 0
        }

        logStore.addLog(
            DetectionLog(
                classification = outcome.classification,
                confidence = outcome.confidence,
                reason = outcome.reason,
                action = action,
                source = outcome.source.name,
                appLabel = outcome.appLabel
            )
        )

        updateNotification(outcome)
    }

    private fun updateNotification(outcome: DetectionOutcome) {
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.notify(FocusGuardApp.NOTIFICATION_ID, buildNotification(outcome))
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败: ${e.message}")
        }
    }

    private fun buildNotification(outcome: DetectionOutcome?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            outcome == null -> getString(R.string.notification_text)
            else -> buildString {
                append("判断：")
                append(
                    when (outcome.classification) {
                        "ENTERTAINMENT" -> "娱乐"
                        "STUDY_WORK" -> "学习/工作"
                        else -> "中性"
                    }
                )
                append("（${(outcome.confidence * 100).toInt()}%）")
                if (outcome.appLabel.isNotBlank()) {
                    append(" · ")
                    append(outcome.appLabel)
                }
                append("\n来源：")
                append(outcome.source.name)
                if (outcome.reason.isNotBlank()) {
                    append(" · ")
                    append(outcome.reason.replace('\n', ' ').take(80))
                }
            }
        }

        return Notification.Builder(this, FocusGuardApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(
                android.app.Notification.BigTextStyle()
                    .bigText(text)
            )
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        settings.serviceRunning = false
        isRunning = false
    }
}
