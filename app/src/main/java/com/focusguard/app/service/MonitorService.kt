package com.focusguard.app.service

import android.app.Notification
import android.app.NotificationManager
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

        /**
         * 巡检循环心跳时间戳（每 15 秒更新一次）。
         *
         * 用于排查"守护显示开着但不出日志"：若 isRunning=true 却
         * lastTickAt 长时间不变，说明循环已死（协程被取消/异常吞掉），
         * 而不是 AI 接口的问题。主页会显示这个状态。
         */
        @Volatile
        var lastTickAt: Long = 0L
            private set

        /** 最近一次真正执行 AI 检测的时间戳。 */
        @Volatile
        var lastDetectionAt: Long = 0L
            private set

        /** 巡检循环是否活着（心跳在 3 个 tick 周期内）。 */
        fun isLoopAlive(): Boolean =
            lastTickAt > 0 && System.currentTimeMillis() - lastTickAt < USAGE_TICK_MS * 3

        /** 诊断文本：给用户看的守护健康状态。 */
        fun healthText(): String {
            if (!isRunning) return "守护未运行"
            val now = System.currentTimeMillis()
            val tickAgo = if (lastTickAt == 0L) -1 else ((now - lastTickAt) / 1000)
            val detectAgo = if (lastDetectionAt == 0L) -1 else ((now - lastDetectionAt) / 1000)
            return buildString {
                append("巡检心跳：")
                append(if (tickAgo < 0) "尚未开始" else "${tickAgo}s 前")
                append(" · AI 检测：")
                append(if (detectAgo < 0) "尚未执行" else "${detectAgo}s 前")
                if (!isLoopAlive()) append(" ⚠️ 循环已停止")
            }
        }

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

    /** 提醒→锁机 宽限期任务（用户切回学习可免锁）。 */
    private var gracePeriodJob: Job? = null

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
            // 不会被这次重建绕过。发通知提醒用户一键恢复。
            null -> {
                Log.w(TAG, "服务被系统重建（无授权数据），保持运行但等待重新授权")
                startForeground(FocusGuardApp.NOTIFICATION_ID, buildNotification(null))
                notifyNeedReauth()
            }
        }
        // START_STICKY：服务被杀后系统会重建。
        // 重建后虽无 MediaProjection 无法检测，但前台服务身份保持，
        // 用户重新点击"开始守护"即可恢复完整功能。
        return START_STICKY
    }

    /** 检测服务中断（无 MediaProjection）时发通知，点击通知回应用自动重新授权。 */
    private fun notifyNeedReauth() {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this,
                5,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, FocusGuardApp.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("AI 守护已中断")
                .setContentText("点击打开应用，将自动重新请求屏幕录制授权并恢复检测")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1004, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发送恢复提醒失败：${e.message}")
        }
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
                // 整个循环体加保护：任何环节抛异常（部分 ROM 的 queryUsageStats、
                // startActivity 等）都会终止协程 → 检测永久停止 → "开启守护后
                // 卡住、不弹日志"。异常记录后继续循环。
                try {
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

                    // 心跳：证明循环还活着（排查"守护开着但不检测"用）
                    lastTickAt = System.currentTimeMillis()

                    msUntilNextDetection -= USAGE_TICK_MS
                    if (msUntilNextDetection <= 0) {
                        performDetection(isManualTest = false)
                        lastDetectionAt = System.currentTimeMillis()
                        msUntilNextDetection = currentDetectionDelayMs()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 真正的协程取消（服务停止）→ 正常退出循环
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "巡检循环异常（已恢复，继续检测）：${e.message}")
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 仅当协程确实被取消（服务停止/被杀）时向上传播；
            // 若协程仍活跃（个别库抛的"假取消"，如 TimeoutCancellationException），
            // 按普通异常记录，避免整个检测循环被误杀
            if (kotlin.coroutines.coroutineContext.isActive) throw e
            Log.e(TAG, "检测流程异常（假取消）", e)
            DetectionOutcome(
                classification = "NEUTRAL",
                confidence = 0f,
                reason = "检测异常：${e.message}",
                source = DetectionSource.ERROR
            )
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
                    enforceWithAlert(outcome)
                }
            } else {
                // 未达连续次数：只弹提醒，不锁机（提前给用户信号）
                if (settings.aiAlertEnabled) {
                    AlertNotifier.alertEntertainment(
                        context = this,
                        title = "⚠️ 检测到娱乐行为",
                        message = outcome.reason,
                        countdownSeconds = 0
                    )
                }
                "WARN"
            }
        } else if (outcome.source != DetectionSource.ERROR) {
            consecutiveViolations = 0
            // 已回到学习/中性状态：撤掉之前的提醒横幅
            AlertNotifier.cancelAlert(this)
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

    /**
     * 达到执法条件时的处理：先弹横幅提醒 + 宽限期，再真正锁机。
     *
     * 宽限期内用户切回学习类应用即可免锁——由 [gracePeriodJob] 复检前台应用。
     * 关闭"提醒后锁机"或宽限秒数为 0 时立即锁机（旧行为）。
     */
    private fun enforceWithAlert(outcome: DetectionOutcome): String {
        val delaySeconds = if (settings.aiAlertEnabled) settings.aiAlertDelaySeconds else 0

        if (delaySeconds <= 0) {
            AlertNotifier.alertEntertainment(
                context = this,
                title = "🔒 已锁机",
                message = outcome.reason
            )
            return doEnforce(outcome)
        }

        // 弹提醒 + 倒计时说明
        AlertNotifier.alertEntertainment(
            context = this,
            title = "⚠️ 检测到娱乐行为",
            message = outcome.reason,
            countdownSeconds = delaySeconds
        )

        // 宽限期结束后复检：仍在娱乐 → 锁机；已收手 → 免锁
        gracePeriodJob?.cancel()
        gracePeriodJob = serviceScope.launch {
            try {
                delay(delaySeconds * 1000L)
                // 复检前台应用类别：已切到学习/中性应用则放行
                val fg = AppClassifier.classifyForegroundApp(this@MonitorService, categoryStore)
                val stillEntertainment = fg == null ||
                    AppClassifier.classifyByAppInfo(fg) != "STUDY_WORK"

                if (!stillEntertainment) {
                    Log.d(TAG, "宽限期内已切回学习状态，免除锁机")
                    AlertNotifier.cancelAlert(this@MonitorService)
                    logStore.addLog(
                        DetectionLog(
                            classification = "STUDY_WORK",
                            confidence = 1f,
                            reason = "宽限期内主动切回学习状态，免除锁机",
                            action = "NONE",
                            source = DetectionSource.APP_CATEGORY.name,
                            appLabel = fg?.label.orEmpty()
                        )
                    )
                    return@launch
                }

                Log.d(TAG, "宽限期结束仍在娱乐，执行锁机")
                AlertNotifier.alertEntertainment(
                    context = this@MonitorService,
                    title = "🔒 已锁机",
                    message = outcome.reason
                )
                doEnforce(outcome)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 服务停止，正常退出
            } catch (e: Exception) {
                Log.w(TAG, "宽限期处理异常：${e.message}")
            }
        }
        return "WARN"
    }

    /** 真正执行执法动作（锁机 / 仅锁该软件 / 警告）。 */
    private fun doEnforce(outcome: DetectionOutcome): String {
        val performed = enforcer.enforce(
            settings.enforcementMode,
            outcome.reason,
            outcome.packageName,
            outcome.appLabel
        )
        if (settings.enforcementMode != Settings.EnforcementMode.WARN) {
            // 「仅锁该软件」模式：不触发全局锁机，只封锁该应用
            if (settings.enforcementMode == Settings.EnforcementMode.APP_BLOCK) {
                return performed
            }
            lockState.startLock(settings.lockMinutesOnViolation, "AI")
            // 应用设置里配置的 AI 执法解锁强度
            lockState.unlockStrength = settings.aiLockStrength
            // 强度 3（朋友辅助）需要生成密文
            if (settings.aiLockStrength == 3) {
                try {
                    lockState.setupFriendChallenge()
                } catch (e: Exception) {
                    Log.w(TAG, "生成朋友辅助密文失败，退回答题解锁：${e.message}")
                    lockState.unlockStrength = 1
                }
            }
        }
        return performed
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
