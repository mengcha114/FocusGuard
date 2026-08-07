package com.focusguard.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.focusguard.app.FocusGuardApp
import com.focusguard.app.MainActivity
import com.focusguard.app.R
import com.focusguard.app.data.LockState
import com.focusguard.app.enforce.AppBlockActivity
import com.focusguard.app.enforce.LockOverlayManager
import com.focusguard.app.enforce.LockScreenActivity
import com.focusguard.app.enforce.UnlockChallengeActivity
import com.focusguard.app.usage.UsageRuleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 锁机守护前台服务 —— 整个防破解体系的核心。
 *
 * ## 为什么需要它
 * 用户反复反馈"无障碍退出后锁机失效""切出去就破解了"。根因是早期实现
 * 把守护逻辑放在 Activity 和无障碍服务里，而这两者都不可靠：
 * - Activity 会被最近任务划掉、被内存回收
 * - 无障碍服务会被各家 ROM 的省电策略随时关闭
 *
 * 前台服务是 Android 上唯一"官方认可的长时运行"方式，被杀概率最低，
 * 且只依赖「使用情况访问」权限即可探测前台应用（见 [ForegroundAppDetector]）。
 *
 * ## 三层保活
 * 1. `startForeground` + 常驻通知（系统几乎不回收）
 * 2. `START_STICKY` + `onDestroy` 自重启 + `onTaskRemoved` 自恢复
 * 3. [GuardWatchdogWorker] 每 15 分钟兜底重启（进程被杀也能复活）
 *
 * ## 两个守护职责
 * A. 锁机中 → 前台必须是锁机页/答题页，否则立即拉回
 * B. 任何时候 → 前台应用若已超硬限额，立即拉起封锁页
 */
class LockGuardService : Service() {

    companion object {
        private const val TAG = "LockGuardService"
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_START = "com.focusguard.app.LOCK_GUARD_START"
        private const val ACTION_STOP = "com.focusguard.app.LOCK_GUARD_STOP"

        /** 前台巡检间隔。300ms：上滑后覆盖层出现得更快，破解窗口更小。 */
        private const val CHECK_INTERVAL_MS = 300L

        /** 拉起界面的最短间隔，避免刷屏与动画抖动。 */
        private const val REASSERT_COOLDOWN_MS = 1000L

        /** 通知刷新间隔（每 10 次巡检刷一次，约 6 秒）。 */
        private const val NOTIFY_EVERY_N_TICKS = 10

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 启动守护服务。重复调用安全。 */
        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, LockGuardService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动锁机守护失败：${e.message}")
            }
        }

        /** 若未运行则启动（幂等入口，供各处随手调用）。 */
        fun ensureRunning(context: Context) {
            if (!isRunning) start(context)
        }

        /** 停止守护服务（仅在解锁且无封锁规则时调用）。 */
        fun stop(context: Context) {
            val app = context.applicationContext
            try {
                app.startService(
                    Intent(app, LockGuardService::class.java).apply { action = ACTION_STOP }
                )
            } catch (e: Exception) {
                Log.w(TAG, "停止锁机守护失败：${e.message}")
            }
        }
    }

    private lateinit var lockState: LockState
    private lateinit var usageRuleStore: UsageRuleStore

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var guardJob: Job? = null

    private var lastLockReassertAt = 0L
    private var lastBlockReassertAt = 0L
    private var tickCount = 0

    /** 是否被用户主动停止（主动停止时 onDestroy 不自重启）。 */
    private var stoppedByUser = false

    override fun onCreate() {
        super.onCreate()
        lockState = LockState(this)
        usageRuleStore = UsageRuleStore(this)
        Log.d(TAG, "锁机守护服务已创建")

        // Shizuku 权限自愈（可选增强，静默失败）：
        // 服务每次启动时尝试自动授权使用情况访问 + 电池优化白名单
        Thread {
            try {
                com.focusguard.app.enhance.ShizukuEnhancer.selfHeal(applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku 自愈失败（可忽略）：${e.message}")
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 仍有硬封锁规则时不能真的停：封锁需要持续守护
            val hasBlockRule = usageRuleStore.allRules().any { it.hardBlockMinutes != null }
            if (!lockState.isLocked && !hasBlockRule) {
                Log.d(TAG, "无锁机也无封锁规则，停止守护")
                stoppedByUser = true
                stopSelf()
                return START_NOT_STICKY
            }
            Log.d(TAG, "仍有封锁规则或锁机中，忽略停止请求")
        }

        // ACTION_START、系统重建（intent=null）、被忽略的 STOP 都进入守护
        stoppedByUser = false
        startForegroundSafely()
        startGuardLoop()

        // START_STICKY：被杀后系统会重建，守护自动恢复
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            isRunning = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败：${e.message}")
            // 即使 startForeground 失败也要让巡检跑起来（后台服务仍可短期存活）
            isRunning = true
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 状态行
        val statusLine = when {
            lockState.isPaused ->
                "暂停中 · 剩余 ${lockState.pauseRemainingSeconds / 60 + 1} 分钟"
            lockState.isLocked ->
                "锁机中 · 剩余 ${lockState.remainingSeconds / 60 + 1} 分钟"
            else ->
                "应用时长守护运行中"
        }

        // 检测行：显示最近一次检测结果（reason 即模型按角色口吻写的提醒语）
        val detectLine = buildString {
            val outcome = MonitorService.lastOutcome
            if (outcome == null) {
                append("等待首次检测…")
            } else {
                val label = when (outcome.classification) {
                    "ENTERTAINMENT" -> "娱乐"
                    "STUDY_WORK" -> "学习/工作"
                    else -> "中性"
                }
                append(label)
                append("（${(outcome.confidence * 100).toInt()}%）")
                if (outcome.appLabel.isNotBlank()) {
                    append(" · ")
                    append(outcome.appLabel)
                }
                if (outcome.reason.isNotBlank()) {
                    append(" · ")
                    append(outcome.reason.replace('\n', ' ').take(60))
                }
            }
        }

        val full = "$statusLine\n$detectLine"
        return Notification.Builder(this, FocusGuardApp.CHANNEL_ID)
            .setContentTitle("专注卫士 · 守护")
            .setContentText(detectLine)
            .setStyle(android.app.Notification.BigTextStyle().bigText(full))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun refreshNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // 通知刷新失败不影响守护主流程
        }
    }

    private fun startGuardLoop() {
        if (guardJob?.isActive == true) return
        guardJob = scope.launch {
            Log.d(TAG, "守护循环启动，巡检间隔 ${CHECK_INTERVAL_MS}ms")
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (!isActive) break
                try {
                    guardTick()
                    tickCount++
                    if (tickCount % NOTIFY_EVERY_N_TICKS == 0) refreshNotification()
                } catch (e: Exception) {
                    Log.w(TAG, "守护巡检异常：${e.message}")
                }
            }
        }
    }

    /**
     * 确保覆盖层在锁机期间可见。
     * - 有 SYSTEM_ALERT_WINDOW 权限：显示 TYPE_APPLICATION_OVERLAY 覆盖，盖住小窗
     * - 无权限：静默跳过，Activity + 无障碍防线仍有效
     */
    private fun ensureOverlay() {
        if (LockOverlayManager.isShowing) return
        try {
            LockOverlayManager.show(
                context = applicationContext,
                lockState = lockState,
                onStartChallenge = {
                    // 覆盖层按钮：按解锁强度直接进入解锁流程
                    // （强度 1/2 直接开答题页——若绕道锁机页，guardTick 可能在
                    //  锁机页显示前重新盖住覆盖层，表现为"点了没反应"）
                    com.focusguard.app.enforce.LockScreenActivity.startChallengeFromOverlay(
                        applicationContext,
                        lockState
                    )
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "启动覆盖层失败（权限未授权或已有实例）：${e.message}")
        }
    }

    /** 单次守护巡检。 */
    private fun guardTick() {
        val now = System.currentTimeMillis()
        val foreground = ForegroundAppDetector.current(this)

        // ── A. 锁机守护 ─────────────────────────────
        if (lockState.isLocked && lockState.shouldBlockNow) {
            // 答题流程活跃时绝对放行：隐藏覆盖层，否则会挡住答题页输入
            if (UnlockChallengeActivity.active) {
                if (LockOverlayManager.isShowing) LockOverlayManager.hide()
                return
            }

            // 锁机页在前台 → 无需覆盖层，正常交互
            if (LockScreenActivity.foreground) {
                if (LockOverlayManager.isShowing) LockOverlayManager.hide()
                return
            }

            // 锁机页不在前台（被上滑/销毁/切后台）：
            // 立即用 TYPE_APPLICATION_OVERLAY 覆盖层堵住桌面/小窗/最近任务，
            // 同时把锁机页拉回前台；锁机页回来后覆盖层自动隐藏。
            ensureOverlay()

            if (now - lastLockReassertAt < REASSERT_COOLDOWN_MS) return
            lastLockReassertAt = now
            Log.d(TAG, "锁机中但锁机页不在前台（前台=$foreground），拉起锁机页")
            LockScreenActivity.show(applicationContext)
            return
        }

        // 锁机结束或暂停 → 撤销覆盖层
        if (LockOverlayManager.isShowing) {
            LockOverlayManager.hide()
        }

        // ── B. 应用硬封锁守护 ───────────────────────
        // 锁机结束后仍要守护应用限额，因此不 return
        if (foreground == null || foreground == packageName) return

        val rule = usageRuleStore.getRule(foreground) ?: return
        val limit = rule.hardBlockMinutes ?: return
        val usedMinutes = (usageRuleStore.getTodaySeconds(foreground) / 60).toInt()
        if (usedMinutes < limit) return

        if (now - lastBlockReassertAt < REASSERT_COOLDOWN_MS) return
        lastBlockReassertAt = now

        val label = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(foreground, 0)
            ).toString()
        }.getOrDefault(foreground)

        Log.d(TAG, "$label 今日已用 $usedMinutes 分钟，超过上限 $limit 分钟，拉起封锁页")
        AppBlockActivity.show(
            context = applicationContext,
            packageName = foreground,
            appLabel = label,
            usedMinutes = usedMinutes,
            limitMinutes = limit
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        guardJob?.cancel()
        scope.cancel()
        // 服务销毁时撤销覆盖层，避免残留
        try { LockOverlayManager.hide() } catch (_: Exception) {}
        Log.d(TAG, "锁机守护被销毁（用户主动停止=$stoppedByUser）")

        if (stoppedByUser) return

        // 非主动停止 → 自重启。锁机中或有封锁规则时尤其重要。
        val needGuard = try {
            lockState.isLocked || usageRuleStore.allRules().any { it.hardBlockMinutes != null }
        } catch (e: Exception) {
            false
        }
        if (needGuard) {
            try {
                start(applicationContext)
                Log.d(TAG, "守护被意外销毁，已尝试自重启")
            } catch (e: Exception) {
                Log.w(TAG, "自重启失败：${e.message}")
            }
        }
    }

    /** 用户在最近任务里滑掉应用 → 自恢复守护并拉回锁机页。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "应用任务被移除")
        try {
            if (lockState.isLocked && lockState.shouldBlockNow) {
                start(applicationContext)
                LockScreenActivity.show(applicationContext)
            }
        } catch (e: Exception) {
            Log.w(TAG, "任务移除后恢复失败：${e.message}")
        }
    }
}
