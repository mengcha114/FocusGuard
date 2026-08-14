package com.focusguard.app.enforce

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LockState
import com.focusguard.app.service.LockGuardService

/**
 * 全局强制锁机界面（全屏覆盖）。
 *
 * ## 职责边界（重要）
 * 本 Activity **只负责显示**。防退出的守护职责已完全移交给
 * [LockGuardService]（独立前台服务）。原因：
 *
 * - Activity 会被最近任务划掉、被系统内存回收、被 ROM 清理，
 *   把守护逻辑放在这里等于把门锁挂在门板上——门被拆了锁就没了。
 * - 早期实现在 `onDestroy` 里用已销毁的 Activity Context 重新
 *   `startActivity` 自己，形成"拉起→销毁→再拉起"的循环，
 *   这正是"破解后再打开软件直接闪退"的根因。
 *
 * 现在：Activity 被销毁 → 前台服务在下一个巡检周期（1.2s 内）
 * 用 applicationContext 重新拉起，安全且必然生效。
 */
class LockScreenActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LockScreenActivity"
        private const val EXTRA_REQUEST_PAUSE = "request_pause"
        private const val EXTRA_FRIEND_UNLOCK = "friend_unlock"
        private const val LAUNCH_WINDOW_MS = 4_000L

        /** 最近一次发起启动的时间戳（延迟启动窗口，供 guardTick 让位）。 */
        @Volatile
        private var launchPendingAt: Long = 0L

        /** 悬浮窗/锁机页答题按钮连点去抖：300ms 内忽略重复点击。 */
        @Volatile
        private var lastStartClickAt: Long = 0L

        /** 锁机页是否处于"即将启动"窗口内（悬浮窗按钮点击后 ~4s 内）。 */
        val launching: Boolean
            get() {
                val elapsed = System.currentTimeMillis() - launchPendingAt
                return elapsed in 0..LAUNCH_WINDOW_MS
            }

        /** 标记"即将启动"：悬浮窗按钮链路移除悬浮窗后延迟 150ms 才启动，
         *  这期间 guardTick 不会重新拉起悬浮窗盖住锁机页。 */
        fun markLaunchPending() {
            launchPendingAt = System.currentTimeMillis()
        }

        /** 当前锁机页实例，供答题页与守护服务查询。 */
        @Volatile
        var instance: LockScreenActivity? = null
            private set

        /** 实例创建时间戳：守护服务据此给 onResume 的 Lock Task enter 留宽限期。 */
        @Volatile
        var instanceCreatedAt: Long = 0L
            private set

        /** 锁机页是否在前台可见（守护服务据此判断是否需要拉起）。 */
        @Volatile
        var foreground: Boolean = false
            private set

        /** 无 Dhizuku 强度 3：Activity 正在承载朋友密码输入，守护入口必须让位。 */
        @Volatile
        var friendUnlockActive: Boolean = false
            private set

        /** 最近一次置位 [friendUnlockActive] 的时间戳，供守护侧超时看门狗复位。 */
        @Volatile
        var friendUnlockActiveSince: Long = 0L
            private set

        fun setFriendUnlockActive() {
            friendUnlockActive = true
            friendUnlockActiveSince = System.currentTimeMillis()
        }

        fun clearFriendUnlockActive() {
            friendUnlockActive = false
            friendUnlockActiveSince = 0L
        }

        /**
         * 显示锁机。
         *
         * **悬浮窗优先**：有悬浮窗权限且锁机生效时，锁机主体由
         * [LockOverlayManager] 承担（锁得更死），Activity 只用于
         * 答题/暂停/朋友解锁等交互场景。
         *
         * 所有旧调用点（守护服务、看门狗、开机广播、无障碍、Enforcer）
         * 都经过这里，因此行为自动统一——不再出现"时而悬浮窗、
         * 时而锁机页"的交替。
         *
         * @param forceActivity true 时强制走 Activity（答题/暂停交互场景，
         *                      由 [startChallengeFromOverlay] / [showForPause] 使用）
         */
        fun show(context: Context, forceActivity: Boolean = false) {
            val appCtx = context.applicationContext
            val readinessUnknown = com.focusguard.app.enhance.DhizukuEnhancer
                .isReadinessUnknown(appCtx)
            if (readinessUnknown) {
                // 未知态也立即显示 Activity：它本身就是可见防线，不能为了等待
                // Dhizuku 探测而留下空窗。后台确认失败后再降级悬浮窗。
                com.focusguard.app.enhance.DhizukuEnhancer.warmUpAsync(appCtx)
            }
            // 悬浮窗优先仅限无 Dhizuku 场景：Dhizuku（Lock Task）可用时
            // 必须走 Activity——Lock Task 让 Activity 无法被任何手势退出，
            // 且 UI 是完整的 Compose 锁机页；此时显示悬浮窗反而会盖住它，
            // 并造成"侧滑先闪悬浮窗页、再被 Activity 盖住"的双页交错闪烁。
            // Dhizuku 曾成功就绪时，即使本次进程的内存缓存还没预热完成，也直接
            // 展示 Activity。否则首次锁机会先露出悬浮窗，准备完成后才切回来。
            val lockTaskOn = com.focusguard.app.enhance.LockTaskEnhancer.lockTaskActive
            val preferActivity = com.focusguard.app.enhance.DhizukuEnhancer
                .shouldPreferActivity(appCtx) || readinessUnknown
            if (!forceActivity && !lockTaskOn && !preferActivity &&
                LockOverlayManager.canShow(appCtx)
            ) {
                try {
                    val ls = LockState(appCtx)
                    if (ls.isLocked && ls.shouldBlockNow) {
                        LockOverlayManager.show(
                            context = appCtx,
                            lockState = ls,
                            force = true,
                            onStartChallenge = {
                                startChallengeFromOverlay(appCtx, LockState(appCtx))
                            },
                            onRequestPause = {
                                showForPause(appCtx)
                            }
                        )
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "悬浮窗优先显示失败，退回 Activity：${e.message}")
                }
            }
            try {
                val intent = Intent(appCtx, LockScreenActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                appCtx.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "拉起锁机页失败：${e.message}")
            }
        }

        /**
         * 答题换取暂停（悬浮窗「暂停」按钮入口）。
         *
         * 与 [show] 不同：**必须**进 Activity（答题流程需要输入法，
         * 悬浮窗必须让位），因此先隐藏悬浮窗，再直接构造带
         * EXTRA_REQUEST_PAUSE 的 Intent。
         */
        fun showForPause(context: Context) {
            val appCtx = context.applicationContext
            val ls = LockState(appCtx)
            // 悬浮窗内答题换取暂停（同样不启动 Activity，手势无法退出）
            if (LockOverlayManager.canShow(appCtx)) {
                try {
                    LockOverlayManager.enterChallengeMode(
                        context = appCtx,
                        lockState = ls,
                        requiredCorrect = 1,
                        forPause = true,
                        onPassed = { forPause -> handleOverlayChallengePassed(appCtx, forPause) }
                    )
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "悬浮窗暂停答题失败，退回 Activity：${e.message}")
                }
            }
            // 无悬浮窗权限 → Activity 兜底
            markLaunchPending()
            try {
                val intent = Intent(appCtx, LockScreenActivity::class.java).apply {
                    putExtra(EXTRA_REQUEST_PAUSE, true)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                appCtx.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "拉起暂停请求页失败：${e.message}")
            }
        }

        /** 兼容旧调用点：语义与 [show] 相同（都是置顶而非重建）。 */
        fun reassert(context: Context) = show(context)

        /**
         * 覆盖层按钮的统一解锁入口（供 LockGuardService 回调）。
         *
         * 按解锁强度分流：
         * - 强度 1/2：直接启动答题页（绕开锁机页，避免 guardTick 在锁机页
         *   显示前重新盖住覆盖层的竞态——"点了没反应"的根因）
         * - 强度 3（朋友辅助）：需要锁机页的密文输入界面，先隐藏覆盖层再拉起锁机页
         * - 强度 4：覆盖层不显示按钮，不会走到这里
         */
        fun startChallengeFromOverlay(context: Context, lockState: LockState) {
            // 连点去抖：300ms 内忽略重复点击，避免重复启动
            val clickNow = System.currentTimeMillis()
            if (clickNow - lastStartClickAt < 300L) {
                Log.d(TAG, "答题按钮连点已忽略")
                return
            }
            lastStartClickAt = clickNow

            val appCtx = context.applicationContext
            try {
                if (lockState.unlockStrength == 3) {
                    // 悬浮窗优先：朋友密码验证直接画进悬浮窗（内置键盘 + 吞键/
                    // 下拉拦截），不启动 Activity——手势退出物理上不可能。
                    if (LockOverlayManager.canShow(appCtx)) {
                        LockOverlayManager.enterFriendUnlockMode(appCtx, lockState) {
                            handleOverlayChallengePassed(appCtx, false)
                        }
                        return
                    }
                    // 无悬浮窗权限 → Activity 会话兜底
                    setFriendUnlockActive()
                    LockScreenActivity.markLaunchPending()
                    val intent = Intent(appCtx, LockScreenActivity::class.java).apply {
                        putExtra(EXTRA_FRIEND_UNLOCK, true)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                    }
                    appCtx.startActivity(intent)
                    return
                }
                // 优先走悬浮窗内自绘键盘答题（0 露桌、手势无法退出）
                if (LockOverlayManager.canShow(appCtx)) {
                    val required = if (lockState.unlockStrength == 2) 5 else 1
                    LockOverlayManager.enterChallengeMode(
                        context = appCtx,
                        lockState = lockState,
                        requiredCorrect = required,
                        forPause = false,
                        onPassed = { forPause -> handleOverlayChallengePassed(appCtx, forPause) }
                    )
                } else {
                    // 无悬浮窗权限 → 退回 Activity 答题（内部自绘键盘）
                    UnlockChallengeActivity.markLaunchPending()
                    val required = if (lockState.unlockStrength == 2) 5 else 1
                    UnlockChallengeActivity.show(appCtx, required)
                }
            } catch (e: Exception) {
                // 强度 3 的 Activity 未能拉起时收回让位标志，守护立即接管，
                // 防止 flag 悬挂导致守护永久放行。
                clearFriendUnlockActive()
                Log.e(TAG, "启动解锁流程失败：${e.message}")
            }
        }

        /**
         * 悬浮窗内答题通过的统一处理（不依赖任何 Activity 存活）。
         *
         * @param forPause true=换取一次暂停；false=解除锁机
         */
        fun handleOverlayChallengePassed(context: Context, forPause: Boolean) {
            val appCtx = context.applicationContext
            val ls = LockState(appCtx)
            try {
                if (forPause) {
                    ls.startPause()
                    Log.d(TAG, "悬浮窗答题通过：获得 ${ls.pauseMinutes} 分钟暂停")
                } else {
                    ls.releaseLock()
                    Log.d(TAG, "悬浮窗答题通过：已解除锁机")
                }
                // 若锁机页 Activity 恰好存在（强度 3 路径遗留），退出 Lock Task 并关闭
                instance?.let { act ->
                    try {
                        com.focusguard.app.enhance.LockTaskEnhancer.exit(act)
                        act.finish()
                    } catch (e: Exception) {
                        Log.w(TAG, "关闭锁机页失败：${e.message}")
                    }
                }
                if (!forPause) {
                    LockGuardService.stop(appCtx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理答题通过失败：${e.message}")
            } finally {
                // 悬浮窗撤下：解锁后不再遮挡；暂停期间同样放行
                try {
                    LockOverlayManager.hideNow()
                } catch (e: Exception) {
                    Log.w(TAG, "撤下悬浮窗失败：${e.message}")
                }
            }
        }
    }

    private lateinit var lockState: LockState

    /** 答题成功后是解锁（false）还是换取一次暂停（true）。 */
    private var pendingPause = false

    /** 强度 3 专用会话；只显示朋友密码 UI，不再落到普通锁机主页。 */
    private var friendUnlockSession by mutableStateOf(false)

    /** 失焦置顶节流：防止覆盖层/窗口动画触发失焦→置顶→再失焦 的高频循环（ANR/闪退源）。 */
    private var lastFocusReassertAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockState = LockState(this)
        friendUnlockSession = intent.getBooleanExtra(EXTRA_FRIEND_UNLOCK, false)
        if (friendUnlockSession) setFriendUnlockActive() else clearFriendUnlockActive()
        instance = this
        instanceCreatedAt = System.currentTimeMillis()
        launchPendingAt = 0L

        // 锁屏上硬压制：突破系统 keyguard 锁屏屏障
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.w(TAG, "设置窗口标志失败：${e.message}")
        }

        // 全屏沉浸：隐藏状态栏与导航栏，阻止下拉通知栏
        applyImmersiveMode()

        // 锁机已到期 → 直接退出
        if (!lockState.isLocked) {
            Log.d(TAG, "锁机已到期，关闭锁机页")
            finish()
            return
        }

        // 确保守护服务在运行（负责防退出巡检）
        LockGuardService.ensureRunning(applicationContext)

        // 悬浮窗「暂停」入口：直接进入答题流程（count=-1 → pendingPause=true，
        // 答对后换取一次暂停而不是解锁）
        if (intent.getBooleanExtra(EXTRA_REQUEST_PAUSE, false)) {
            startChallenge(-1)
        }

        setContent {
            val unlockNow = {
                    // 先退出系统级 Lock Task，再释放锁机
                    com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
                    lockState.releaseLock()
                    LockGuardService.stop(applicationContext)
                    notifyGuardInterrupted()
                    finish()
            }
            if (friendUnlockSession) {
                FriendUnlockOnlyScreen(
                    lockState = lockState,
                    onVerified = unlockNow,
                    onExpired = { finish() }
                )
            } else {
                LockScreenContent(
                    lockState = lockState,
                    onStartChallenge = { count -> startChallenge(count) },
                    onUnlocked = unlockNow
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask 复用时整体覆盖会话标志：普通 show() 复用旧实例会把
        // 朋友会话复位回普通锁机主页，避免会话残留跳过 LockTask。
        friendUnlockSession = intent.getBooleanExtra(EXTRA_FRIEND_UNLOCK, false)
        if (friendUnlockSession) setFriendUnlockActive() else clearFriendUnlockActive()
        if (intent.getBooleanExtra(EXTRA_REQUEST_PAUSE, false)) {
            startChallenge(-1)
        }
    }

    /** 解锁后发现 AI 守护已中断 → 提示用户打开应用会自动恢复。 */
    private fun notifyGuardInterrupted() {
        try {
            val settings = com.focusguard.app.data.Settings(applicationContext)
            if (settings.serviceRunning &&
                !com.focusguard.app.service.MonitorService.isRunning
            ) {
                Toast.makeText(
                    this,
                    "AI 守护已中断（进程被杀或授权被回收），打开应用将自动恢复检测",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "守护中断提示失败：${e.message}")
        }
    }

    /**
     * 发起答题。
     *
     * @param count 正数=需答对的题数（解锁）；-1=申请暂停
     */
    private fun startChallenge(count: Int) {

        pendingPause = count < 0
        val required = kotlin.math.abs(count).coerceAtLeast(1)
        // 使用 Activity Context，让答题页压在锁机页同一个 task 中；
        // applicationContext 会强制 NEW_TASK，正是答题页被顶回的根因之一。
        val launched = UnlockChallengeActivity.show(this, required)
        if (!launched) {
            pendingPause = false
            Toast.makeText(this, "无法打开答题界面，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    /** 答题页答对全部题目后回调：暂停申请则开始暂停，否则解锁。 */
    fun onUnlockedExternally() {
        if (pendingPause) {
            pendingPause = false
            lockState.startPause()
            Log.d(TAG, "答题成功，获得 ${lockState.pauseMinutes} 分钟暂停")
            // 暂停期间退出系统级 Lock Task，让用户自由使用；
            // 守护服务会在暂停结束后自动把锁机页拉回来并重新进入 Lock Task。
            com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
            moveTaskToBack(true)
        } else {
            Log.d(TAG, "答题成功，解除锁机")
            com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
            lockState.releaseLock()
            LockGuardService.stop(applicationContext)
            notifyGuardInterrupted()
            finish()
        }
    }

    private fun applyImmersiveMode() {
        try {
            // Edge-to-edge：让内容绘制延伸到状态栏/导航栏区域。
            // 不设置的话系统会为状态栏保留一条区域，Compose 背景画不到那里，
            // 表现为「通知栏位置黑一块」。
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            // 状态栏/导航栏透明，露出下面的渐变背景
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        } catch (e: Exception) {
            Log.w(TAG, "进入沉浸模式失败：${e.message}")
        }
    }

    @Deprecated("Back blocked during lock")
    override fun onBackPressed() {
        if (friendUnlockSession) {
            if (lockState.shouldBlockNow) restoreOverlayAfterFriendUnlock()
            friendUnlockSession = false
            clearFriendUnlockActive()
            finish()
        }
        // 普通锁机期间拦截返回键
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (!lockState.isLocked) {
            finish()
            return
        }
        // ── 无缝接替（0 露桌） ─────────────────────────
        // 悬浮窗不先隐藏，锁机页在悬浮窗下方完成创建与绘制；
        // 此处 onResume = 锁机页已就绪，撤下悬浮窗露出锁机页
        // （强度 3 密文输入路径依赖这一撤）。
        // 但**答题模式下绝不撤**：答题 UI 就在悬浮窗里，撤了等于关掉答题。
        if (!LockOverlayManager.isChallengeMode) {
            try {
                LockOverlayManager.hideNow()
            } catch (e: Exception) {
                Log.w(TAG, "撤下悬浮窗失败：${e.message}")
            }
        }
        // 每次回到前台重新应用沉浸模式（部分 ROM 会重置系统栏状态）
        applyImmersiveMode()

        // Dhizuku 增强：应封锁时进入系统级 Lock Task（Home/上滑/最近任务全部失效）；
        // 暂停/番茄钟休息阶段退出 Lock Task，让用户自由使用。
        //
        // 关键：**不在主线程同步做 Dhizuku IPC**。
        // ensureReady / setLockTaskFeatures / setStatusBarDisabled 都是跨进程
        // Binder 调用，首次调用要等 Dhizuku 服务端进程唤醒（数百毫秒到数秒）。
        // 过去在 onResume 里同步执行 → 主线程被 Binder 阻塞 → Compose 首帧
        // 画不出来 = 「第一次开启锁机白屏无响应」，第二次因已缓存所以正常。
        // 现在：DPM 配置放后台线程，startLockTask() 回主线程执行（必须主线程）。
        if (friendUnlockSession) {
            // 无 Dhizuku 的朋友密码会话只需 Activity 输入，不尝试 LockTask，
            // 否则 prepare 失败后会重新挂悬浮窗盖住输入界面。
            setFriendUnlockActive()
            lockTaskRequested = false
        } else if (lockState.shouldBlockNow) {
            enterLockTaskAsync()
        } else {
            com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
        }
    }

    /** Lock Task 是否已发起（避免 onResume 反复触发导致闪烁）。 */
    private var lockTaskRequested = false

    /** 异步准备代次：新请求使旧后台任务失效，防止旧任务晚到后误锁。 */
    @Volatile
    private var lockTaskRequestGeneration = 0L

    /** 供 Composable（番茄钟阶段切换）调用的异步进入入口。 */
    fun requestEnterLockTask() {
        lockTaskRequested = false
        enterLockTaskAsync()
    }

    /**
     * 异步进入 Lock Task：后台线程做 Dhizuku Binder 配置，主线程只调
     * startLockTask()。
     *
     * 关键设计（修复"Dhizuku 已授权却能直接退出"）：
     * 1. **Dhizuku 就绪期间必须有防线**：prepare 期间（首次数百毫秒到数秒）
     *    没有任何防线，用户此刻上滑就能直接退出。因此 prepare 前先挂悬浮窗
     *    临时兜底，LockTask 真正生效后再撤下（悬浮窗只在这段过渡期存在）。
     * 2. **startLockTask 失败要重试**：Activity 未 resumed / 白名单刚写入尚未
     *    生效等原因会让首次 startLockTask 抛异常。此前失败就永久放弃了，
     *    导致完全没有 Lock Task 保护。现在最多重试 3 次（间隔 400ms）。
     * 3. **重试全部失败才交给悬浮窗常驻**（不再是"什么都没有"）。
     */
    private fun enterLockTaskAsync() {
        // 已生效 → 撤下过渡期悬浮窗即可
        if (com.focusguard.app.enhance.LockTaskEnhancer.lockTaskActive) {
            hideOverlayIfShowing()
            return
        }
        if (lockTaskRequested) return
        lockTaskRequested = true
        val generation = ++lockTaskRequestGeneration

        // 只有从未确认过 Dhizuku 可用时才显示悬浮窗。曾成功授权/就绪的设备
        // 首次冷启动直接保留 Activity 页面，避免“先旧悬浮窗、再新 Activity”。
        val preferActivity = com.focusguard.app.enhance.DhizukuEnhancer
            .shouldPreferActivity(applicationContext) ||
            com.focusguard.app.enhance.DhizukuEnhancer
                .isReadinessUnknown(applicationContext)
        if (!preferActivity &&
            !com.focusguard.app.enhance.LockTaskEnhancer.lockTaskActive &&
            LockOverlayManager.canShow(this) && !LockOverlayManager.isShowing
        ) {
            try {
                LockOverlayManager.show(
                    context = this,
                    lockState = lockState,
                    force = true,
                    onStartChallenge = {
                        startChallengeFromOverlay(applicationContext, lockState)
                    },
                    onRequestPause = {
                        showForPause(applicationContext)
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "过渡期悬浮窗挂载失败：${e.message}")
            }
        }

        Thread {
            // 后台完成 Dhizuku 连接与 DPM 策略配置。手机刚重启时 Dhizuku
            // Provider 可能晚几秒启动；历史上成功使用过 Dhizuku 的设备在此
            // 有界重试，期间 Activity 始终是可见防线，不先切旧悬浮窗页面。
            val preferDhizuku = com.focusguard.app.enhance.DhizukuEnhancer
                .shouldPreferActivity(applicationContext)
            val maxPrepareAttempts = if (preferDhizuku) 8 else 1
            var ready = false
            for (attempt in 1..maxPrepareAttempts) {
                if (!isLockTaskRequestCurrent(generation)) {
                    handleStaleLockTaskRequest(generation, prepared = false)
                    return@Thread
                }
                ready = try {
                    com.focusguard.app.enhance.LockTaskEnhancer.prepare(applicationContext)
                } catch (e: Throwable) {
                    Log.w(TAG, "Dhizuku 准备失败：${e.message}")
                    false
                }
                if (ready) break
                if (attempt < maxPrepareAttempts) {
                    Log.d(TAG, "重启续锁等待 Dhizuku 就绪：$attempt/$maxPrepareAttempts")
                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            if (!ready) {
                // Dhizuku 不可用 → 悬浮窗常驻接管（过渡期挂的那个就地留用）
                runOnUiThread {
                    lockTaskRequested = false
                    Log.w(TAG, "Dhizuku 不可用，悬浮窗常驻接管锁机")
                    if (LockOverlayManager.canShow(this) && !LockOverlayManager.isShowing) {
                        try {
                            LockOverlayManager.show(
                                context = this,
                                lockState = lockState,
                                force = true,
                                onStartChallenge = {
                                    startChallengeFromOverlay(applicationContext, lockState)
                                },
                                onRequestPause = {
                                    showForPause(applicationContext)
                                }
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "悬浮窗接管失败：${e.message}")
                        }
                    }
                }
                return@Thread
            }
            // prepare 已写入状态栏/Keyguard 策略；紧邻 startLockTask 前再次验证。
            // 若等待期间锁机已结束或 Activity 已失效，必须补偿撤销策略。
            if (!isLockTaskRequestCurrent(generation)) {
                handleStaleLockTaskRequest(generation, prepared = true)
                return@Thread
            }
            // 就绪 → 主线程尝试 startLockTask，最多重试 3 次
            tryStartLockTask(attempt = 1, generation = generation)
        }.start()
    }

    private fun isLockTaskRequestCurrent(generation: Long): Boolean =
        generation == lockTaskRequestGeneration &&
            instance === this &&
            !isFinishing &&
            !isDestroyed &&
            lockState.shouldBlockNow

    private fun handleStaleLockTaskRequest(generation: Long, prepared: Boolean) {
        // 旧代任务被新请求替代时不能撤策略，新请求可能正在使用同一套 DPM 配置。
        if (generation != lockTaskRequestGeneration) return
        lockTaskRequested = false
        if (prepared) {
            com.focusguard.app.enhance.LockTaskEnhancer
                .releasePreparedPolicies(applicationContext)
        }
    }

    /**
     * 主线程重试 startLockTask（Activity 尚未 resumed / 白名单刚生效时首次会失败）。
     */
    private fun tryStartLockTask(attempt: Int, generation: Long) {
        runOnUiThread {
            if (!isLockTaskRequestCurrent(generation)) {
                handleStaleLockTaskRequest(generation, prepared = true)
                return@runOnUiThread
            }
            if (com.focusguard.app.enhance.LockTaskEnhancer.lockTaskActive) {
                hideOverlayIfShowing()
                return@runOnUiThread
            }
            val ok = com.focusguard.app.enhance.LockTaskEnhancer.startOnUi(this)
            if (ok) {
                Log.d(TAG, "Lock Task 已生效（第 $attempt 次尝试），撤下过渡期悬浮窗")
                hideOverlayIfShowing()
                return@runOnUiThread
            }
            if (attempt < 3) {
                Log.w(TAG, "startLockTask 第 $attempt 次失败，400ms 后重试")
                window.decorView.postDelayed(
                    { tryStartLockTask(attempt + 1, generation) },
                    400L
                )
                return@runOnUiThread
            }
            // 三次都失败 → 悬浮窗常驻接管（过渡期那个继续留着，不再撤）
            Log.w(TAG, "startLockTask 重试耗尽，悬浮窗常驻接管锁机")
            lockTaskRequested = false
            if (LockOverlayManager.canShow(this) && !LockOverlayManager.isShowing) {
                try {
                    LockOverlayManager.show(
                        context = this,
                        lockState = lockState,
                        force = true,
                        onStartChallenge = {
                            startChallengeFromOverlay(applicationContext, lockState)
                        },
                        onRequestPause = {
                            showForPause(applicationContext)
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "悬浮窗接管失败：${e.message}")
                }
            }
        }
    }

    /** 撤下悬浮窗（Lock Task 生效后调用，消除双页交错）。 */
    private fun hideOverlayIfShowing() {
        if (!LockOverlayManager.isShowing) return
        try {
            LockOverlayManager.hideNow()
        } catch (e: Exception) {
            Log.w(TAG, "隐藏悬浮窗失败：${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        // 离开前台立即取消守护让位：Home/息屏等情况下由守护巡检恢复悬浮窗防线，
        // 避免会话标志悬挂造成空窗；回到前台时 onResume 会重新置位。
        clearFriendUnlockActive()
        // 注意：这里**不再**直接拉起覆盖层。
        // 早期实现为"0 延迟堵破解窗口"在 onPause 里 addView 覆盖层，
        // 用户反复上滑-回来时造成高频窗口增删 → 主线程卡死（倒计时停、按钮无响应）。
        // 覆盖层统一由 LockGuardService 巡检（≤300ms）拉起，窗口期可接受。
    }

    /**
     * 用户尝试上滑/按 Home 离开锁机页时的即时拦截。
     *
     * 这里不调 startActivity（后台 Activity 启动受平台限制，且会闪烁），
     * 而是让前台守护服务立刻巡检拉起。配合 LockGuardService 的
     * TYPE_APPLICATION_OVERLAY 覆盖层，锁机页被切走后 1 秒内
     * 覆盖层会盖住整个屏幕（含桌面/小窗），用户无法操作任何内容。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d(TAG, "检测到上滑/Home 手势，锁机页即将离开前台")
        // 尽力触发守护巡检（无需等待下一个 600ms tick）
        try {
            LockGuardService.ensureRunning(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "触发守护巡检失败：${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 失焦 = 有别的窗口抢了焦点（通知栏/侧边栏/返回动画/任何系统界面）。
        // 锁机中除答题页外不允许任何窗口存在：
        // 1. 收起通知栏
        // 2. 把自己置顶（盖住华为智慧多窗侧边栏等系统窗口）
        // 注意：本 Activity 仍在 resumed 状态（仅失焦），此时 startActivity 合法。
        if (!hasFocus && lockState.shouldBlockNow &&
            !UnlockChallengeActivity.active && !friendUnlockSession
        ) {
            // 屏幕已息屏（按电源键）：不折腾（置顶会把屏幕重新点亮）
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isInteractive) return

            com.focusguard.app.access.GuardAccessibilityService.instance
                ?.dismissNotificationShade()

            // 覆盖层已显示 → 屏幕已被盖住，无需置顶。
            // 跳过置顶可切断"失焦→置顶→覆盖层变化→再失焦"的高频循环。
            if (com.focusguard.app.enforce.LockOverlayManager.isShowing) return

            // 节流：覆盖层 addView/removeView、窗口动画会多次触发失焦，
            // 高频 startActivity 会让系统窗口动画堆积 → ANR/闪退。
            val now = System.currentTimeMillis()
            if (now - lastFocusReassertAt < 500L) return
            lastFocusReassertAt = now

            try {
                LockScreenActivity.show(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "失焦置顶失败：${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (friendUnlockSession && lockState.shouldBlockNow) {
            restoreOverlayAfterFriendUnlock()
        }
        friendUnlockSession = false
        clearFriendUnlockActive()
        if (instance === this) instance = null
        instanceCreatedAt = 0L
        foreground = false
        // 兜底退出 Lock Task（正常路径已在 unlock/暂停时退出；
        // 若因锁机到期 finish 等路径遗漏，这里保证系统不被锁死）
        com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
        Log.d(TAG, "锁机页已销毁（守护服务会在需要时重新拉起）")
        // 这里绝不自行 startActivity：
        // 用已销毁的 Activity 作为 Context 拉起自己会造成崩溃循环。
        // 恢复工作交由 LockGuardService 的巡检完成。
    }

    private fun restoreOverlayAfterFriendUnlock() {
        if (!LockOverlayManager.canShow(applicationContext) || LockOverlayManager.isShowing) return
        runCatching {
            LockOverlayManager.showNow(
                context = applicationContext,
                lockState = lockState,
                onStartChallenge = {
                    startChallengeFromOverlay(applicationContext, lockState)
                },
                onRequestPause = { showForPause(applicationContext) }
            )
        }.onFailure { Log.w(TAG, "朋友解锁退出后恢复悬浮窗失败：${it.message}") }
    }
}

// ── UI ────────────────────────────────────────────────────────────────

/** 强度 3 专用全屏会话：进入 Activity 后直接呈现密码输入，不显示普通锁机主页。 */
@Composable
private fun FriendUnlockOnlyScreen(
    lockState: LockState,
    onVerified: () -> Unit,
    onExpired: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = remember(context) {
        com.focusguard.app.ui.theme.FocusColors.paletteForLockScreen(
            com.focusguard.app.data.Settings(context).themeMode
        )
    }
    // 与 LockScreenContent 一致的到期收尾：锁自然到期时自动关闭本页，
    // 否则 singleTask 复用与返回路径的守卫会不一致。
    LaunchedEffect(Unit) {
        while (lockState.isLocked) {
            kotlinx.coroutines.delay(1000L)
        }
        onExpired()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UnlockCard(palette = palette) {
                FriendUnlockSection(
                    cipher = lockState.friendCipher,
                    shift = lockState.friendShift,
                    palette = palette,
                    focusRequestId = 1,
                    onVerified = onVerified
                )
            }
        }
    }
}

@Composable
private fun LockScreenContent(
    lockState: LockState,
    onStartChallenge: (Int) -> Unit,
    onUnlocked: () -> Unit
) {
    var remainingSeconds by remember { mutableIntStateOf(lockState.remainingSeconds) }
    var isWorkPhase by remember { mutableStateOf(lockState.pomodoroIsWorkPhase) }
    var phaseSeconds by remember { mutableIntStateOf(lockState.pomodoroRemainingSeconds) }
    var pauseSeconds by remember { mutableIntStateOf(lockState.pauseRemainingSeconds) }
    var isPausing by remember { mutableStateOf(lockState.isPaused) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pauseLeft by remember { mutableIntStateOf(lockState.pauseQuota - lockState.pauseUsed) }

    val isPomodoro = lockState.lockSource == "POMODORO"
    // 箴言：优先用用户自定义（每行一条，随机取），否则用内置库。
    // 注意：LocalContext.current 是 @Composable 属性，必须在 Composable
    // 作用域取值，不能放进 remember 的 lambda（非 @Composable 上下文）。
    val mottoContext = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(mottoContext) {
        com.focusguard.app.data.Settings(mottoContext)
    }
    // 设计令牌：锁机页跟随所选主题（浅色回退深色·墨），见 DESIGN.md §3.2
    val palette = remember(mottoContext) {
        com.focusguard.app.ui.theme.FocusColors.paletteForLockScreen(settings.themeMode)
    }
    val motto = remember(mottoContext) {
        val custom = runCatching { settings.customMottos }.getOrDefault("")
        val customList = custom.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (customList.isNotEmpty()) customList.random()
        else MotivationalQuotes.random()
    }

    // 进度环基准：首次进入时的剩余时间即为本段总时长
    var totalSeconds by remember { mutableIntStateOf(lockState.remainingSeconds.coerceAtLeast(1)) }

    // 在 Composable 作用域取 Activity 引用（LaunchedEffect 内不能调 LocalContext.current）
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity

    // 统一的每秒刷新：时钟、倒计时、番茄钟阶段、暂停状态
    LaunchedEffect(Unit) {
        var lastWorkPhase = lockState.pomodoroIsWorkPhase

        while (lockState.isLocked) {
            kotlinx.coroutines.delay(1000L)
            nowMillis = System.currentTimeMillis()
            remainingSeconds = lockState.remainingSeconds
            pauseSeconds = lockState.pauseRemainingSeconds
            isPausing = lockState.isPaused
            pauseLeft = lockState.pauseQuota - lockState.pauseUsed

            if (isPomodoro) {
                phaseSeconds = lockState.pomodoroRemainingSeconds
                isWorkPhase = lockState.pomodoroIsWorkPhase
                if (phaseSeconds <= 0) {
                    val finished = lockState.advancePomodoroPhase()
                    if (finished) break
                    isWorkPhase = lockState.pomodoroIsWorkPhase
                    phaseSeconds = lockState.pomodoroRemainingSeconds
                    // 新阶段开始：重置进度环基准
                    totalSeconds = phaseSeconds.coerceAtLeast(1)
                }
                // 阶段切换：休息→工作 重新进入 Lock Task；工作→休息 释放。
                // 进入走 Activity 的异步路径（Binder 配置在后台线程，
                // 避免番茄钟切阶段时主线程被阻塞造成界面卡住）。
                if (isWorkPhase != lastWorkPhase && activity != null) {
                    if (isWorkPhase) {
                        (activity as? LockScreenActivity)?.requestEnterLockTask()
                    } else {
                        com.focusguard.app.enhance.LockTaskEnhancer.exit(activity)
                    }
                    lastWorkPhase = isWorkPhase
                }
            }
        }
        onUnlocked()
    }

    // ── 配色：按状态切换主色调 ────────────────────────
    val isRelaxed = isPausing || (isPomodoro && !isWorkPhase)
    val accent = if (isRelaxed) palette.success else palette.accent

    // 呼吸光效：夜光表盘的光晕缓慢明暗（5s 周期，幅度克制）
    val glowAlpha by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.04f,
        targetValue = 0.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // ── 进入编排：标签 → 核心计时 → 操作区（受系统动画缩放约束，见 DESIGN.md §3.5） ──
    val animationsOn = remember(mottoContext) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                mottoContext.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }
    val stageTop = remember { androidx.compose.animation.core.Animatable(0f) }
    val stageCore = remember { androidx.compose.animation.core.Animatable(0f) }
    val stageBottom = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!animationsOn) {
            stageTop.snapTo(1f)
            stageCore.snapTo(1f)
            stageBottom.snapTo(1f)
        } else {
            stageTop.animateTo(1f, tween(160))
            stageCore.animateTo(
                1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 380f)
            )
            stageBottom.animateTo(1f, tween(200))
        }
    }

    val shownSeconds = when {
        isPausing -> pauseSeconds
        isPomodoro -> phaseSeconds
        else -> remainingSeconds
    }
    val progress = if (totalSeconds <= 0) 0f
        else (shownSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
    ) {
        // 中央夜光光晕（呼吸）——模拟夜光表盘反光，全应用唯一允许的渐变
        Box(
            modifier = Modifier
                .size(560.dp)
                .align(Alignment.Center)
                .background(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 顶部留出状态栏高度（窗口已 edge-to-edge 铺满，
                // 背景延伸到状态栏区域，内容不被刘海/挖孔压住）
                .padding(start = 24.dp, end = 24.dp, top = 52.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 顶栏：时钟 + 日期（编排第一段） ─────────────
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.graphicsLayer { alpha = stageTop.value }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val timeFormat = remember {
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    }
                    val dateFormat = remember {
                        java.text.SimpleDateFormat("M月d日 EEEE", java.util.Locale.getDefault())
                    }
                    Text(
                        text = timeFormat.format(java.util.Date(nowMillis)),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 3.sp,
                        color = palette.text
                    )
                    Text(
                        text = dateFormat.format(java.util.Date(nowMillis)),
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = palette.haze
                    )

                    Spacer(Modifier.height(20.dp))

                    // ── 状态胶囊 ─────────────────────────────────
                    StatusPill(
                        text = when {
                            isPausing -> "暂停中 · 可自由使用"
                            isPomodoro && isWorkPhase -> "番茄钟 · 专注阶段"
                            isPomodoro -> "番茄钟 · 休息阶段"
                            lockState.lockSource == "AI" -> "AI 检测到娱乐 · 已锁定"
                            else -> "专注锁定中"
                        },
                        accent = accent,
                        palette = palette,
                        locked = !isRelaxed
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            // ── 核心：环形进度 + 倒计时（编排第二段，scale 落下） ──
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.graphicsLayer {
                    alpha = stageCore.value
                    val s = 0.92f + 0.08f * stageCore.value
                    scaleX = s
                    scaleY = s
                }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CountdownRing(
                        progress = progress,
                        seconds = shownSeconds,
                        label = if (isPausing) "暂停剩余" else "锁定剩余",
                        accent = accent,
                        palette = palette
                    )

                    if (isPomodoro) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MiniStat("剩余轮次", "${lockState.pomodoroRoundsLeft}", accent, palette)
                            MiniStat("今日完成", "${lockState.pomodoroCompletedToday}", accent, palette)
                        }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))

            // ── 底部内容（编排第三段：上移淡入） ──────────────
            val bottomRise = (1f - stageBottom.value) *
                with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = stageBottom.value
                        translationY = bottomRise
                    }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // ── 励志语录卡 ────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.card)
                            .border(1.dp, palette.line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Column {
                            Text(
                                text = "今日箴言",
                                fontSize = 10.sp,
                                letterSpacing = 2.sp,
                                color = palette.haze,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = motto,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                color = palette.text.copy(alpha = 0.85f)
                            )
                        }
                    }

            Spacer(Modifier.height(16.dp))

            // ── 待办清单（锁机时也能看到自己该做什么） ──────
            LockMemoCard(accent = accent, palette = palette)

            Spacer(Modifier.height(20.dp))

            // ── 解锁区（暂停中隐藏，避免重复操作） ────────
            if (!isPausing) {
                UnlockCard(palette = palette) {
                    when (lockState.unlockStrength) {
                        4 -> LockedForeverHint(palette)
                        3 -> FriendUnlockSection(
                            cipher = lockState.friendCipher,
                            shift = lockState.friendShift,
                            palette = palette,
                            onVerified = onUnlocked
                        )
                        2 -> UnlockButtonWithHint(
                            hint = "需连续答对 5 道高难度题才能解锁",
                            buttonText = "开始挑战 · 5 题",
                            accent = accent,
                            palette = palette,
                            onClick = { onStartChallenge(5) }
                        )
                        else -> UnlockButtonWithHint(
                            hint = "答对 1 道计算题即可解锁",
                            buttonText = "答题解锁",
                            accent = accent,
                            palette = palette,
                            onClick = { onStartChallenge(1) }
                        )
                    }
                }

                // ── 暂停申请 ──────────────────────────────
                if (lockState.pauseEnabled) {
                    Spacer(Modifier.height(14.dp))
                    if (lockState.canPause) {
                        TextButton(
                            onClick = { onStartChallenge(-1) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "答题申请暂停（剩 $pauseLeft 次 · 每次 ${lockState.pauseMinutes} 分钟）",
                                color = palette.haze,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Text(
                            text = "暂停次数已用完（共 ${lockState.pauseQuota} 次）",
                            fontSize = 11.sp,
                            color = palette.faint
                        )
                    }
                }
            }
            }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 状态胶囊：小圆点 + 文字，锁定态用主色，放松态用成功色。 */
@Composable
private fun StatusPill(
    text: String,
    accent: Color,
    palette: com.focusguard.app.ui.theme.FocusColors.Palette,
    locked: Boolean
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, palette.line.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            color = accent
        )
    }
}

/**
 * 环形倒计时。
 *
 * 用 Canvas 画双层圆弧（底轨 + 进度弧），进度弧带渐变扫描色，
 * 中心叠加时间数字。这是整个锁机页的视觉焦点。
 */
@Composable
private fun CountdownRing(
    progress: Float,
    seconds: Int,
    label: String,
    accent: Color,
    palette: com.focusguard.app.ui.theme.FocusColors.Palette
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "ringProgress"
    )

    Box(
        modifier = Modifier.size(238.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(
                size.width - inset * 2,
                size.height - inset * 2
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)

            // 底轨
            drawArc(
                color = palette.line.copy(alpha = 0.55f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )

            // 进度弧（剩余时间比例，单一强调色）
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = palette.haze
            )
            Spacer(Modifier.height(6.dp))

            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            // 分钟位变化轻弹（1.0→1.04→1.0），钟表翻页质感；秒位不做动画避免每秒闪烁
            val minuteBounce = remember { androidx.compose.animation.core.Animatable(0f) }
            var firstMinute by remember { mutableStateOf(true) }
            LaunchedEffect(m) {
                if (firstMinute) {
                    firstMinute = false
                } else {
                    minuteBounce.snapTo(1f)
                    minuteBounce.animateTo(0f, tween(300))
                }
            }
            // 签名元素：衬线大数字（钟表刻度质感），见 DESIGN.md §3.3
            Text(
                text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s),
                fontSize = if (h > 0) 40.sp else 50.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = palette.text,
                modifier = Modifier.graphicsLayer {
                    val sc = 1f + 0.04f * minuteBounce.value
                    scaleX = sc
                    scaleY = sc
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (h > 0) "时 分 秒" else "分 秒",
                fontSize = 9.sp,
                letterSpacing = 3.sp,
                color = palette.faint
            )
        }
    }
}

/** 番茄钟小统计块。 */
/**
 * 锁机页的待办清单卡片。
 *
 * 锁机时用户最需要的信息不是"还剩多久"，而是"我现在该干什么"。
 * 这里直接展示未完成事项（紧急/逾期高亮），可勾选完成——
 * 让锁机从"惩罚"变成"引导"。
 */
@Composable
private fun LockMemoCard(accent: Color, palette: com.focusguard.app.ui.theme.FocusColors.Palette) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val memoStore = remember { com.focusguard.app.data.MemoStore(context) }
    var pending by remember { mutableStateOf(memoStore.getPending()) }

    // 无待办时不占位置，保持锁机页简洁
    if (pending.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .border(1.dp, palette.line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 15.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "待办清单",
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    color = palette.haze,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${pending.size} 项未完成",
                    fontSize = 10.sp,
                    color = palette.faint
                )
            }

            Spacer(Modifier.height(10.dp))

            pending.take(4).forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 勾选完成：锁机期间也能推进待办
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(palette.line.copy(alpha = 0.35f))
                            .clickable {
                                memoStore.markDone(item.id)
                                pending = memoStore.getPending()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "标记完成",
                            tint = accent.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = item.text,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = palette.text.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 紧急/逾期标记：让用户一眼看到该先做哪个
                    val tag = when {
                        item.overdue -> "逾期"
                        item.priority == 2 -> "紧急"
                        item.priority == 1 -> "重要"
                        else -> ""
                    }
                    if (tag.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tag,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (item.overdue || item.priority == 2) {
                                palette.error
                            } else {
                                palette.accent
                            }
                        )
                    }
                }
            }

            if (pending.size > 4) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "还有 ${pending.size - 4} 项…",
                    fontSize = 10.sp,
                    color = palette.faint
                )
            }
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    accent: Color,
    palette: com.focusguard.app.ui.theme.FocusColors.Palette
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .border(1.dp, palette.line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
        Text(label, fontSize = 10.sp, color = palette.haze)
    }
}

/** 解锁区容器卡片：细边框分栏，让解锁交互聚焦。 */
@Composable
private fun UnlockCard(
    palette: com.focusguard.app.ui.theme.FocusColors.Palette,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .border(1.dp, palette.line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "解锁方式",
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
                color = palette.haze
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 强度 4：不可提前解锁的提示。 */
@Composable
private fun LockedForeverHint(palette: com.focusguard.app.ui.theme.FocusColors.Palette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = palette.error,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "本次锁机不可提前解锁",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.error
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "请等待倒计时结束",
            fontSize = 12.sp,
            color = palette.haze,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun UnlockButtonWithHint(
    hint: String,
    buttonText: String,
    accent: Color,
    palette: com.focusguard.app.ui.theme.FocusColors.Palette,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = hint,
            fontSize = 12.sp,
            color = palette.haze,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = palette.bg
            )
        ) {
            Text(
                text = buttonText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 强度 3：朋友辅助解锁——显示密文与偏移量，输入解密后的密码。 */
@Composable
private fun FriendUnlockSection(
    cipher: String,
    shift: Int,
    palette: com.focusguard.app.ui.theme.FocusColors.Palette,
    focusRequestId: Int = 0,
    onVerified: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showHint by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(focusRequestId) {
        if (focusRequestId > 0) {
            kotlinx.coroutines.delay(250L)
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "朋友辅助解锁",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.accent
        )

        Surface(
            color = palette.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, palette.line.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("密文", fontSize = 11.sp, color = palette.haze)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = cipher.ifBlank { "——" },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 6.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("偏移量：$shift", fontSize = 14.sp, color = palette.accentDeep)
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it; errorMsg = null },
            label = { Text("朋友解密后的密码") },
            placeholder = { Text("输入明文密码") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            shape = RoundedCornerShape(10.dp),
            singleLine = true
        )

        errorMsg?.let { Text(it, color = palette.error, fontSize = 12.sp) }

        TextButton(onClick = { showHint = !showHint }) {
            Text(
                text = if (showHint) "收起解密说明" else "怎么解密？",
                color = palette.haze,
                fontSize = 12.sp
            )
        }

        if (showHint) {
            Text(
                text = "把密文交给朋友，让朋友用在线工具解密后把结果告给你。\n" +
                    "推荐工具：https://www.lddgo.net/encrypt/caesar-cipher\n" +
                    "解密方式选「凯撒密码解密」，偏移量填 $shift。\n" +
                    "也可手动推算：每个字母向前移动 $shift 位（偏移 3 时 D→A），数字不变。",
                fontSize = 12.sp,
                color = palette.haze,
                lineHeight = 18.sp
            )
        }

        Button(
            onClick = {
                if (LockState(context).verifyFriendPassword(input)) {
                    onVerified()
                } else {
                    errorMsg = "密码错误，请核对解密结果"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = palette.bg
            )
        ) {
            Text("输入密码解锁", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 励志语录库：锁机页随机展示一条。 */
private object MotivationalQuotes {

    private val quotes = listOf(
        "自律给我自由",
        "现在的努力，是为了以后更好的自己",
        "把专注当成习惯，优秀就会成为自然",
        "每一个不起舞的日子，都是对生命的辜负",
        "坚持一下，你比自己想象的更强大",
        "你的时间花在哪里，人生的花就开在哪里",
        "别让未来的你，讨厌现在放纵的自己",
        "努力是会上瘾的，尤其是尝到甜头之后",
        "优秀的人不是天生优秀，而是比常人更自律",
        "读书是为了遇见更好的自己",
        "熬过无人问津的日子，才有诗和远方",
        "自律的顶端是享受孤独",
        "你现在偷的懒，都会变成以后打脸的巴掌",
        "与其仰望别人，不如点亮自己",
        "脚踏实地，才能仰望星空",
        "奋斗的路上，每一步都算数",
        "把每一件简单的事做好，就是不简单",
        "专注当下，未来自然来",
        "坚持做难而正确的事",
        "时间不会辜负每一个认真生活的人"
    )

    fun random(): String = quotes[kotlin.random.Random.nextInt(quotes.size)]
}
