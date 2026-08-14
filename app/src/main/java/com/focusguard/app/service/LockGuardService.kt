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
            // 自愈闹钟：进程被杀后由系统闹钟在 5 秒内拉活守护并恢复锁机
            // （华为/荣耀 ROM 的 START_STICKY 重启延迟 5-10 秒——破解窗口）。
            // 闹钟是自续环：锁机结束/暂停后 Receiver 不再续，自然停止。
            try {
                LockGuardAlarm.schedule(app, LockGuardAlarm.RECOVERY_INTERVAL_MS)
            } catch (e: Exception) {
                Log.w(TAG, "注册自愈闹钟失败：${e.message}")
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

    /** 卸载阻止开关状态（与 Dhizuku setUninstallBlocked 同步）。 */
    private var uninstallBlocked = false

    /** 媒体键抢占开关状态（与 AudioManager 注册同步）。 */
    private var mediaButtonRegistered = false

    /** 心跳存储（SharedPreferences 同步写，进程重建后仍可读）。 */
    private val heartbeatPrefs by lazy {
        getSharedPreferences("focus_guard_heartbeat", Context.MODE_PRIVATE)
    }

    /** 强停判定阈值：心跳陈旧超过此值（毫秒）即视为被强行停止。 */
    private val forceStopThresholdMs = 10_000L

    /** 写心跳（每 ~3s 一次即可，apply 异步落盘）。 */
    private var lastHeartbeatAt = 0L
    private fun beatHeartbeat() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatAt < 3_000L) return
        lastHeartbeatAt = now
        heartbeatPrefs.edit().putLong("last_beat", now).apply()
    }

    /** 服务复活时检测心跳是否陈旧（曾被执行「强行停止」）。 */
    private fun checkForceStopped() {
        try {
            val lastBeat = heartbeatPrefs.getLong("last_beat", 0L)
            if (lastBeat > 0L && System.currentTimeMillis() - lastBeat > forceStopThresholdMs) {
                Log.w(TAG, "检测到守护曾被强制停止（心跳中断 ${(System.currentTimeMillis() - lastBeat) / 1000}s），已恢复")
                val state = lockState
                if (state.isLocked) {
                    val notification = android.app.Notification.Builder(
                        this, com.focusguard.app.FocusGuardApp.CHANNEL_ID
                    )
                        .setSmallIcon(com.focusguard.app.R.drawable.ic_shield)
                        .setContentTitle("守护曾被强制停止，现已恢复")
                        .setContentText("锁机倒计时以设备运行时间为准，强制停止不会缩短锁机时长")
                        .setAutoCancel(true)
                        .build()
                    getSystemService(NotificationManager::class.java).notify(1007, notification)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "强停检测失败：${e.message}")
        }
    }

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

        // ── 强制停止检测：服务每次复活都比对上次心跳 ──
        // 正常重启（划后台自恢复等）间隔 <2s；被「强行停止」后闹钟/看门狗
        // 全部失效，只有用户重新打开应用才会走到这里，心跳必然陈旧。
        checkForceStopped()

        // Shizuku 权限自愈（可选增强，静默失败）：
        // 服务每次启动时尝试自动授权使用情况访问 + 电池优化白名单
        Thread {
            try {
                com.focusguard.app.enhance.ShizukuEnhancer.selfHeal(applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku 自愈失败（可忽略）：${e.message}")
            }
        }.start()

        // Dhizuku 预热：服务创建时一次性完成 Binder 绑定与 DPM 包装构造。
        // 必须放在独立线程且只做一次——guardTick 运行在后台协程里，
        // 若在 tick 中调用 ensureReady 触发首次 Binder 初始化，会与
        // 锁机页 Activity 的 onResume（主线程）争抢 Dhizuku 服务，
        // 表现为「第一次开启锁机白屏无响应」。预热后 guardTick 只读缓存。
        com.focusguard.app.enhance.DhizukuEnhancer.warmUpAsync(applicationContext)
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

        // 锁机中收紧通知内容：不向锁屏/旁人泄露「剩余分钟 + 刚检测到什么」，
        // 只暴露「守护运行中」这一必要事实（防窥探，DESIGN.md 安全项）。
        val lockActive = lockState.isLocked && lockState.shouldBlockNow
        val body = if (lockActive) "专注守护运行中" else detectLine
        val full = if (lockActive) "锁机进行中 · 专注守护运行中" else "$statusLine\n$detectLine"
        return Notification.Builder(this, FocusGuardApp.CHANNEL_ID)
            .setContentTitle("专注卫士 · 守护")
            .setContentText(body)
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

    /** 时间篡改警告：通知用户倒计时不受改时间影响，并已追加惩罚时长。 */
    private fun notifyTimeTamper(tamperMs: Long) {        try {
            val notification = android.app.Notification.Builder(
                this, com.focusguard.app.FocusGuardApp.CHANNEL_ID
            )
                .setSmallIcon(com.focusguard.app.R.drawable.ic_shield)
                .setContentTitle("检测到系统时间被修改")
                .setContentText("锁机倒计时以设备运行时间为准，并已追加惩罚时长；改时间无法提前解锁")
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(1006, notification)
        } catch (e: Exception) {
            Log.w(TAG, "时间篡改通知失败：${e.message}")
        }
    }

    /** 锁机中抢占媒体键：蓝牙/线控长按（唤醒语音助手的物理入口）在广播层被吞掉。 */
    private fun registerMediaButtonBlocker() {
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            @Suppress("DEPRECATION")
            am.registerMediaButtonEventReceiver(
                android.content.ComponentName(this, MediaButtonBlocker::class.java)
            )
            Log.d(TAG, "媒体键抢占已注册")
        } catch (e: Exception) {
            Log.w(TAG, "媒体键抢占注册失败（忽略）：${e.message}")
        }
    }

    private fun unregisterMediaButtonBlocker() {
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            @Suppress("DEPRECATION")
            am.unregisterMediaButtonEventReceiver(
                android.content.ComponentName(this, MediaButtonBlocker::class.java)
            )
            Log.d(TAG, "媒体键抢占已注销")
        } catch (e: Exception) {
            Log.w(TAG, "媒体键抢占注销失败（忽略）：${e.message}")
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
                force = true, // 锁机主体显示：不走冷却，确保立即覆盖
                onStartChallenge = {
                    // 覆盖层按钮：按解锁强度直接进入解锁流程
                    // （强度 1/2 直接开答题页——若绕道锁机页，guardTick 可能在
                    //  锁机页显示前重新盖住覆盖层，表现为"点了没反应"）
                    com.focusguard.app.enforce.LockScreenActivity.startChallengeFromOverlay(
                        applicationContext,
                        lockState
                    )
                },
                onRequestPause = {
                    // 「暂停（答题）」：进锁机页触发答题换取暂停（悬浮窗让位给输入法）
                    com.focusguard.app.enforce.LockScreenActivity.showForPause(
                        applicationContext
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

        // 状态栏物理拦截条双保险：无障碍存活时随锁机状态挂载/移除
        // （事件驱动之外，锁机到期/解除也能及时摘掉拦截条）
        com.focusguard.app.access.GuardAccessibilityService.instance?.ensureStatusBarBlock()

        // 心跳（强停检测数据源）
        beatHeartbeat()

        // 卸载阻止随锁机状态切换（Dhizuku Device Owner 能力，防"卸载=绕过锁机"）
        val blockingNow = lockState.isLocked && lockState.shouldBlockNow
        if (blockingNow != uninstallBlocked) {
            uninstallBlocked = blockingNow
            // 只读缓存门槛：未就绪（无 Dhizuku）直接跳过，避免后台线程
            // 触发 HiddenApiBypass/Dhizuku.init Binder 初始化（死锁/ANR 隐患）
            if (com.focusguard.app.enhance.DhizukuEnhancer.isReadyCached()) {
                com.focusguard.app.enhance.DhizukuEnhancer.setUninstallBlocked(
                    applicationContext, blockingNow
                )
            }
        }

        // 媒体键抢占随锁机状态切换（蓝牙/线控长按语音助手在源头失效）
        if (blockingNow != mediaButtonRegistered) {
            mediaButtonRegistered = blockingNow
            if (blockingNow) {
                registerMediaButtonBlocker()
            } else {
                unregisterMediaButtonBlocker()
            }
        }

        // ── A. 锁机守护 ─────────────────────────────
        if (lockState.isLocked && lockState.shouldBlockNow) {
            // ── 时间篡改检测（单调钟基准，每 tick 校验墙钟推进是否一致） ──
            val tamper = lockState.detectTimeTamper()
            if (tamper > com.focusguard.app.data.LockState.TIME_TAMPER_THRESHOLD_MS) {
                Log.w(TAG, "检测到系统时间被篡改（偏差 ${tamper / 1000}s），追加锁定时长")
                lockState.applyTamperPenalty(tamper)
                notifyTimeTamper(tamper)
            }
            // 屏幕已息屏（用户按电源键/自动息屏）：尊重用户，不做任何拉起动作。
            // 否则拉起覆盖层会重新点亮屏幕——"锁机后无法息屏"的根因。
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isInteractive) {
                return
            }

            // ── 悬浮窗内答题模式：完全放行（v3.0.0） ────────
            // 答题 UI 就画在悬浮窗里，窗口本身即防线。此时不做任何
            // 拉起/隐藏动作，只在窗口被 ROM 回收时重建（重建保留进度）。
            if (LockOverlayManager.isChallengeMode) {
                LockOverlayManager.verifyAttached(applicationContext, lockState)
                // 空内容自愈：界面构建异常被清空时重建（防锁死空白）
                LockOverlayManager.rebuildIfEmpty()
                return
            }

            // 强度 3 的朋友密码正在锁机 Activity 内输入；此 Activity 本身即防线，
            // 不补挂悬浮窗、不重新路由锁机页，否则会盖住输入界面。
            // 看门狗：若 startActivity 被 ROM 静默吞掉（实例始终未创建且超过
            // 启动窗口），收回让位标志，防止守护永久放行造成空窗。
            if (LockScreenActivity.friendUnlockActive) {
                val stale = LockScreenActivity.instance == null &&
                    now - LockScreenActivity.friendUnlockActiveSince > 4_000L
                if (stale) {
                    Log.w(TAG, "朋友解锁会话超时未创建实例，收回让位标志")
                    LockScreenActivity.clearFriendUnlockActive()
                } else {
                    return
                }
            }

            // 答题页让位规则（无缝接替架构）：
            // 点击答题后悬浮窗**保持显示**（目标页面在悬浮窗下方创建并绘制，
            // 其 onResume 就绪后自行撤下悬浮窗——桌面 0 毫秒暴露）。
            // 因此这里**不再 hide 悬浮窗**（过早隐藏 = 露桌死穴）：
            // - foreground（答题页在前台）→ 悬浮窗已由答题页 onResume 撤下，放行
            // - active 且尚未 created / created 但 <1.5s（启动窗口期）→ 放行，
            //   悬浮窗保持遮挡，不重复拉起也不提前隐藏
            // - created 且超过 1.5s 且被切走（foreground=false）→ 不满足让位，
            //   走下方覆盖层拉起逻辑锁屏（堵住"切走答题页 = 自由使用"）
            val challengeForeground = UnlockChallengeActivity.foreground
            val challengeLaunching = UnlockChallengeActivity.active &&
                !UnlockChallengeActivity.isCreated
            val challengeJustCreated = UnlockChallengeActivity.isCreated &&
                UnlockChallengeActivity.instanceCreatedAt > 0 &&
                now - UnlockChallengeActivity.instanceCreatedAt < 1_500L
            // 锁机页启动窗口（悬浮窗「暂停」/强度3 路径）
            val lockScreenLaunching = LockScreenActivity.launching
            if (challengeForeground || challengeLaunching || challengeJustCreated ||
                lockScreenLaunching
            ) {
                return
            }

            // 冷启动尚无可信 Dhizuku 探测结果：等待后台预热后再选页面，避免
            // 已授权用户先闪悬浮窗，也避免无 Dhizuku 用户先闪 Activity。
            if (com.focusguard.app.enhance.DhizukuEnhancer
                    .isReadinessUnknown(applicationContext)
            ) {
                com.focusguard.app.enhance.DhizukuEnhancer.warmUpAsync(applicationContext)
                // 未知态立即用 Activity 建立可见防线；后台探测失败后 Activity
                // 自身会降级悬浮窗，等待期间绝不留空窗。
                LockScreenActivity.show(applicationContext, forceActivity = true)
                return
            }

            // ── Dhizuku 优先：系统级 Lock Task 方案 ──────────
            // Lock Task 生效后系统禁用 Home/上滑/最近任务，锁机页**无法被任何
            // 手势退出**——这是最强的锁死（比悬浮窗更彻底，且 UI 用回完整好看的
            // Compose 锁机页）。启动 LockScreenActivity，其 onResume 会自动
            // enter Lock Task（见 LockScreenActivity.onResume）。
            //
            // 注意：v2.0.0 起锁机主体一度改为悬浮窗，导致 LockScreenActivity
            // 从不启动、LockTaskEnhancer.enter 从未执行——用户"配置了 Dhizuku
            // 依然非常容易退出"的根因。
            // ── Dhizuku 优先：系统级 Lock Task 方案 ──────────
            // 当 Dhizuku 已连接且已授权时，优先启动 LockScreenActivity 并进入系统级 Lock Task 模式！
            // 此模式下系统在底层彻底禁用 Home / 上滑 / 最近任务 / 通知栏下拉 / 状态栏展开，
            // 这是最强的系统级锁死。
            if (com.focusguard.app.enhance.DhizukuEnhancer.isReadyCached() ||
                com.focusguard.app.enhance.DhizukuEnhancer
                    .shouldPreferActivity(applicationContext)
            ) {
                val lockTaskOn = com.focusguard.app.enhance.LockTaskEnhancer.lockTaskActive

                // ── Lock Task 已生效：系统级锁死，撤下悬浮窗（消除双页交错） ──
                if (lockTaskOn) {
                    if (LockOverlayManager.isShowing) LockOverlayManager.hideNow()
                    if (LockScreenActivity.foreground) return
                    // 极端情况：LockTask 标记为真但 Activity 掉出前台 → 置顶
                    if (now - lastLockReassertAt < REASSERT_COOLDOWN_MS) return
                    lastLockReassertAt = now
                    Log.d(TAG, "LockTask 生效但锁机页掉出前台，置顶")
                    LockScreenActivity.show(applicationContext, forceActivity = true)
                    return
                }

                // ── Lock Task 尚未生效：立即拉起 Activity ─────────────
                // Dhizuku 已就绪时绝不再抢先挂旧悬浮窗，否则首次必然出现
                // “悬浮窗 → Activity”切换。Activity 本身是可见防线，并负责
                // startLockTask 三次重试；明确失败后才由 Activity 降级悬浮窗。
                if (LockScreenActivity.instance == null || !LockScreenActivity.foreground) {
                    if (now - lastLockReassertAt < REASSERT_COOLDOWN_MS) return
                    lastLockReassertAt = now
                    Log.d(TAG, "Dhizuku 模式下 LockTask 尚未生效，拉起 Activity 等待续锁")
                    LockScreenActivity.show(applicationContext, forceActivity = true)
                }
                return
            }

            // ── 无 Dhizuku：全屏悬浮窗常驻（主防线） ──────────
            // 悬浮窗不属于任何 Task：上滑手势、最近任务、清后台都动不了它，
            // z-order 也高于普通 Activity 与小窗。只要权限在手就让它一直挂着，
            // 不再等"锁机页掉出前台"才补位——那个间隙正是破解窗口。
            if (LockOverlayManager.canShow(applicationContext)) {
                if (LockOverlayManager.isShowing) {
                    // 已显示：校验窗口是否真的还挂着（ROM 清理/进程重建会掉）
                    LockOverlayManager.verifyAttached(applicationContext, lockState)
                } else {
                    ensureOverlay()
                }
                // 悬浮窗本身已是完整锁机界面，不必再拉 Activity 到前台：
                // 少一次 startActivity 就少一次闪烁和被手势销毁的机会。
                return
            }

            // ── 兜底：无悬浮窗权限时退回 Activity 方案 ─────
            if (LockScreenActivity.foreground) return
            if (now - lastLockReassertAt < REASSERT_COOLDOWN_MS) return
            lastLockReassertAt = now
            Log.d(TAG, "无悬浮窗权限，改用锁机页兜底（前台=$foreground）")
            LockScreenActivity.show(applicationContext)
            return
        }

        // 锁机结束或暂停 → 撤销覆盖层
        if (LockOverlayManager.isShowing) {
            LockOverlayManager.hide()
            // 会话状态复位：防止第二次锁机复现旧答题界面（白屏/旧题）
            LockOverlayManager.resetChallengeState()
        }

        // ── B. 应用硬封锁守护 ───────────────────────
        // 锁机结束后仍要守护应用限额，因此不 return
        if (foreground == null || foreground == packageName) return

        val label = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(foreground, 0)
            ).toString()
        }.getOrDefault(foreground)

        // B1. 「仅锁该软件」临时封锁（AI 执法下发）：打开即挡，直到截止时间
        val appBlockStore = com.focusguard.app.data.AppBlockStore(applicationContext)
        val blockUntil = appBlockStore.blockedUntil(foreground)
        if (blockUntil > 0L) {
            if (now - lastBlockReassertAt < REASSERT_COOLDOWN_MS) return
            lastBlockReassertAt = now
            Log.d(TAG, "$label 处于临时封锁期，拉起封锁页")
            AppBlockActivity.show(
                context = applicationContext,
                packageName = foreground,
                appLabel = label,
                usedMinutes = 0,
                limitMinutes = 0,
                blockUntil = blockUntil
            )
            return
        }

        // B2. 每日使用时长硬封锁（用户配置的长期规则）
        val rule = usageRuleStore.getRule(foreground) ?: return
        val limit = rule.hardBlockMinutes ?: return
        val usedMinutes = (usageRuleStore.getTodaySeconds(foreground) / 60).toInt()
        if (usedMinutes < limit) return

        if (now - lastBlockReassertAt < REASSERT_COOLDOWN_MS) return
        lastBlockReassertAt = now

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
        try {
            LockGuardAlarm.cancel(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "取消自愈闹钟失败：${e.message}")
        }
        // 服务销毁时解除卸载阻止（否则用户永远无法卸载应用）
        if (uninstallBlocked) {
            uninstallBlocked = false
            com.focusguard.app.enhance.DhizukuEnhancer.setUninstallBlocked(applicationContext, false)
        }
        isRunning = false
        guardJob?.cancel()
        scope.cancel()
        // 服务销毁时撤销覆盖层，避免残留
        try { LockOverlayManager.hide() } catch (_: Exception) {}
        Log.d(TAG, "锁机守护被销毁（用户主动停止=$stoppedByUser）")

        if (stoppedByUser) return

        // 非主动停止 → 自重启。锁机中或有封锁规则时尤其重要。
        val needGuard = try {
            val settings = com.focusguard.app.data.Settings(applicationContext)
            settings.serviceRunning ||
                lockState.isLocked ||
                usageRuleStore.allRules().any { it.hardBlockMinutes != null } ||
                usageRuleStore.allRules().any {
                    com.focusguard.app.data.AppBlockStore(applicationContext).isBlocked(it.packageName)
                }
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
        Log.d(TAG, "应用任务被移除，尝试自恢复")

        // 清后台 = 进程被杀。只要用户曾开启过守护/锁机/封锁规则，
        // 就立即重启守护（前台服务通知马上回来），并注册看门狗兜底。
        val shouldRevive = try {
            val settings = com.focusguard.app.data.Settings(applicationContext)
            settings.serviceRunning ||
                lockState.isLocked ||
                usageRuleStore.allRules().any { it.hardBlockMinutes != null } ||
                com.focusguard.app.data.AppBlockStore(applicationContext)
                    .let { store ->
                        usageRuleStore.allRules().any { store.isBlocked(it.packageName) }
                    }
        } catch (e: Exception) {
            false
        }

        try {
            if (shouldRevive) {
                start(applicationContext)
                GuardWatchdogWorker.schedule(applicationContext)
                Log.d(TAG, "任务被移除，守护已重启")

                // 锁机中 → 立即拉回锁机页（朋友密码会话期间让位：会话页被划掉
                // 会走 onDestroy 恢复悬浮窗，无需在此抢跑）
                if (lockState.isLocked && lockState.shouldBlockNow &&
                    !com.focusguard.app.enforce.LockScreenActivity.friendUnlockActive
                ) {
                    LockScreenActivity.show(applicationContext)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "任务移除后恢复失败：${e.message}")
        }
    }
}
