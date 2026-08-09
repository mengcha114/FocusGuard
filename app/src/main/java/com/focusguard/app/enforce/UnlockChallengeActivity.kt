package com.focusguard.app.enforce

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.focusguard.app.data.LockState
import com.focusguard.app.ui.screens.UnlockChallengeScreen

/**
 * 解锁挑战答题页（独立 Activity，与锁机页同 task）。
 *
 * ## 为什么独立成 Activity
 * 答题需要弹出输入法。若答题界面内嵌在锁机页里，输入法弹出会导致
 * 锁机页失焦 → 触发"失焦顶回" → 输入法被顶掉 → 循环 → 崩溃。
 *
 * ## 为什么必须与锁机页同 task
 * 锁机页是 singleTask + taskAffinity=""，若答题页用不同 affinity，
 * 会形成跨 task 启动，在 Android 10+ 上受"后台启动 Activity"限制，
 * 部分 ROM 直接静默拒绝 → 表现为"点击答题没反应 / 闪退"。
 * 两者共享 taskAffinity 后，答题页只是压在锁机页之上的普通跳转。
 *
 * ## active 标志的安全设计
 * 早期实现用 `var active: Boolean`，若 startActivity 失败（onCreate 永不执行），
 * active 会永久卡在 true，导致此后所有顶回被跳过 → 锁机彻底失效。
 * 现在改为**带时间戳的意图标记**：只在启动后的短暂窗口内有效，
 * Activity 真正创建后才转为长期有效，销毁时立即失效。
 */
class UnlockChallengeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "UnlockChallengeActivity"
        private const val EXTRA_REQUIRED_CORRECT = "required_correct"

        /** 启动意图的有效窗口：超过此时长仍未创建成功即认为启动失败。 */
        private const val LAUNCH_INTENT_WINDOW_MS = 4_000L

        /** 答题页真正在前台。 */
        @Volatile
        private var created: Boolean = false

        /** 答题页是否已创建（供守护服务区分"启动窗口期"与"已被切走"）。 */
        val isCreated: Boolean
            get() = created

        /** 最近一次发起启动的时间戳，用于覆盖"启动中"的空窗期。 */
        @Volatile
        private var launchRequestedAt: Long = 0L

        /** 当前答题页实例。 */
        @Volatile
        var instance: UnlockChallengeActivity? = null
            private set

        /**
         * 实例创建时间戳。
         *
         * guardTick 让位判断用：答题页已创建但尚未 onResume（foreground 未置 true）
         * 的窗口期（毫秒级），守护轮询恰好 tick 会拉起悬浮窗盖住答题页——
         * 表现为"点击答题后答题页直接退出"。创建后 1.5s 内一律让位。
         */
        @Volatile
        var instanceCreatedAt: Long = 0L
            private set

        /**
         * 答题流程是否处于活跃状态（锁机页据此暂停顶回）。
         *
         * 关键：启动窗口会自动过期，绝不会因启动失败而永久卡住。
         */
        val active: Boolean
            get() {
                if (created) return true
                val elapsed = System.currentTimeMillis() - launchRequestedAt
                return elapsed in 0..LAUNCH_INTENT_WINDOW_MS
            }

        /**
         * 答题页是否在前台可见。
         *
         * 与 [active] 的区别：active 表示"答题流程存在"（启动中/已创建），
         * foreground 表示"答题页正占据屏幕"。
         * 守护巡检据此决定悬浮窗：答题页在前台 → 悬浮窗让位（输入法可用）；
         * 答题页被切走 → 立即恢复悬浮窗锁住屏幕（堵住"切走答题页"漏洞）。
         */
        @Volatile
        var foreground: Boolean = false
            private set

        /**
         * 标记"即将启动"：让启动窗口从此刻立即生效。
         *
         * 悬浮窗按钮链路会在移除悬浮窗后延迟 150ms 才真正 startActivity
         * （规避华为 ROM 的 native 竞态）。这 150ms 内 guardTick 若看到
         * active=false 会把悬浮窗重新拉起盖住答题页——先打点即可让
         * [active] 立即为 true，guardTick 让位。
         */
        fun markLaunchPending() {
            launchRequestedAt = System.currentTimeMillis()
        }

        /**
         * 启动答题页。
         *
         * @param requiredCorrect 需要连续答对的题数
         * @return 是否成功发起启动
         */
        fun show(context: Context, requiredCorrect: Int = 1): Boolean {
            // 先打时间戳：覆盖 startActivity → onCreate 之间的空窗期，
            // 避免锁机页在这段时间把刚要出现的答题页顶掉。
            launchRequestedAt = System.currentTimeMillis()
            return try {
                val intent = Intent(context, UnlockChallengeActivity::class.java).apply {
                    putExtra(EXTRA_REQUIRED_CORRECT, requiredCorrect)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                context.startActivity(intent)
                Log.d(TAG, "已发起答题页启动，需答对 $requiredCorrect 题")
                true
            } catch (e: Exception) {
                // 启动失败立即清掉时间戳，让锁机页恢复防护
                launchRequestedAt = 0L
                Log.e(TAG, "启动答题页失败：${e.message}")
                false
            }
        }
    }

    private lateinit var lockState: LockState

    /** 切走频次监控：2 秒内切走 ≥2 次视为恶意破解，直接结束答题页。 */
    private var lastPauseAt = 0L
    private var pauseCountInWindow = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockState = LockState(this)
        instance = this
        instanceCreatedAt = System.currentTimeMillis()
        created = true



        val requiredCorrect = intent.getIntExtra(EXTRA_REQUIRED_CORRECT, 1)
        Log.d(TAG, "答题页已创建，需答对 $requiredCorrect 题")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "设置窗口标志失败：${e.message}")
        }

        // 注意：这里绝不能加 FLAG_SECURE。
        // FLAG_SECURE 在部分 ROM 上会阻止输入法窗口正常附着，
        // 是"打开输入法就闪退"的直接成因。

        // 隐藏系统栏但保留输入法可用（不使用 BEHAVIOR_SHOW_TRANSIENT 以免干扰 IME）
        hideSystemBars()

        // ── 通知栏防下拉（无无障碍也能生效） ──────────────
        // 沉浸模式下下拉通知栏 = 系统栏先滑出（SYSTEM_UI_FLAG_FULLSCREEN 被清除）。
        // 监听系统栏可见性变化：一旦状态栏被拉出，立即收回（通知栏动画被强制
        // 中断，无法完整展开），同时若有无障碍服务则一并收起通知栏。
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if ((visibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                Log.d(TAG, "检测到系统栏被拉出（下拉通知栏），立即收回")
                hideSystemBars()
                try {
                    com.focusguard.app.access.GuardAccessibilityService.instance
                        ?.dismissNotificationShade()
                } catch (e: Exception) {
                    Log.w(TAG, "收起通知栏失败：${e.message}")
                }
            }
        }

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212))
            ) {
                UnlockChallengeScreen(
                    requiredCorrect = requiredCorrect,
                    onUnlocked = { handleSuccess() }
                )
            }
        }
    }

    /**
     * 答对全部题目。
     *
     * 注意：这里**不直接调用 releaseLock**。
     * 是"解锁"还是"换取暂停"由锁机页的 pendingPause 决定，
     * 统一交给 [LockScreenActivity.onUnlockedExternally] 处理，
     * 避免暂停申请被误解为完全解锁（曾导致答题后锁机直接消失）。
     */
    private fun handleSuccess() {
        val lockScreen = LockScreenActivity.instance
        if (lockScreen != null) {
            lockScreen.onUnlockedExternally()
        } else {
            // 锁机页已被系统回收：按解锁处理，避免用户答对后仍被困住
            Log.w(TAG, "锁机页实例不存在，直接释放锁机")
            try {
                lockState.releaseLock()
            } catch (e: Exception) {
                Log.w(TAG, "释放锁机失败：${e.message}")
            }
        }
        finish()
    }

    /** 放弃答题，回到锁机页。 */
    private fun returnToLockScreen() {
        // ── 无缝接替（0 露桌） ─────────────────────────
        // 先同步挂载全屏悬浮窗（<10ms 盖住屏幕），再 finish 销毁答题页——
        // 答题页销毁瞬间系统暴露的是已被悬浮窗遮挡的界面，桌面 0 毫秒暴露。
        // 无悬浮窗权限才退回 Activity 兜底。
        val stillLocked = try {
            this::lockState.isInitialized && lockState.shouldBlockNow
        } catch (e: Exception) {
            false
        }
        if (stillLocked) {
            if (com.focusguard.app.enforce.LockOverlayManager.canShow(applicationContext)) {
                com.focusguard.app.enforce.LockOverlayManager.showNow(
                    context = applicationContext,
                    lockState = lockState,
                    onStartChallenge = {
                        com.focusguard.app.enforce.LockScreenActivity
                            .startChallengeFromOverlay(applicationContext, lockState)
                    },
                    onRequestPause = {
                        com.focusguard.app.enforce.LockScreenActivity
                            .showForPause(applicationContext)
                    }
                )
            } else {
                LockScreenActivity.show(applicationContext)
            }
        }
        finish()
    }

    @Deprecated("Back returns to lock screen, not unlock")
    override fun onBackPressed() {
        // 答题页不允许退出：无论按返回键还是侧滑返回，都回到锁机状态
        // （悬浮窗优先，见 LockScreenActivity.show 的统一逻辑），
        // 而不是回到用户之前的应用——否则"答题页轻松退出"就绕过了锁机。
        returnToLockScreen()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        // ── 无缝接替（0 露桌） ─────────────────────────
        // 点击答题时悬浮窗不先隐藏（防露桌），答题页在悬浮窗下方完成
        // 创建与首帧绘制；此处 onResume = 答题页已完全就绪并占据屏幕，
        // 此时才撤下悬浮窗——撤下瞬间露出的直接是答题页，桌面 0 毫秒暴露。
        try {
            com.focusguard.app.enforce.LockOverlayManager.hideNow()
        } catch (e: Exception) {
            Log.w(TAG, "撤下悬浮窗失败：${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        // 注意：切走惩罚不在这里——onPause 会被悬浮窗盖住、窗口切换、
        // 输入法弹起等被动场景触发，误判会直接把答题页 finish（"第二次
        // 点击答题后直接退出"的根因之一）。惩罚移到 onUserLeaveHint
        // （只有用户明确 Home/上滑切走才触发）。
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 用户明确切走（Home/上滑手势）才走到这里。
        // ── 切走惩罚：2 秒内切走 ≥2 次 = 反复刷窗口期尝试破解 → 直接结束答题
        val now = System.currentTimeMillis()
        if (now - lastPauseAt < 2_000L) {
            pauseCountInWindow++
        } else {
            pauseCountInWindow = 1
        }
        lastPauseAt = now
        if (pauseCountInWindow >= 2) {
            Log.w(TAG, "检测到连续切走答题页（${pauseCountInWindow} 次），执行切走惩罚")
            finish()
            return
        }
        // 同步挂载全屏悬浮窗锁定
        Log.d(TAG, "用户按 Home 或上滑切走答题页，挂载全屏悬浮窗锁定")
        val stillLocked = try {
            this::lockState.isInitialized && lockState.shouldBlockNow
        } catch (e: Exception) {
            false
        }
        if (stillLocked) {
            if (com.focusguard.app.enforce.LockOverlayManager.canShow(applicationContext)) {
                com.focusguard.app.enforce.LockOverlayManager.showNow(
                    context = applicationContext,
                    lockState = lockState,
                    onStartChallenge = {
                        com.focusguard.app.enforce.LockScreenActivity
                            .startChallengeFromOverlay(applicationContext, lockState)
                    },
                    onRequestPause = {
                        com.focusguard.app.enforce.LockScreenActivity
                            .showForPause(applicationContext)
                    }
                )
            } else {
                LockScreenActivity.show(applicationContext)
            }
        }
    }

    /** 隐藏状态栏（输入法兼容：不隐藏导航栏、不用 BEHAVIOR_SHOW_TRANSIENT）。 */
    private fun hideSystemBars() {
        try {
            val controller = androidx.core.view.WindowInsetsControllerCompat(
                window, window.decorView
            )
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } catch (e: Exception) {
            Log.w(TAG, "隐藏状态栏失败：${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        instanceCreatedAt = 0L
        foreground = false
        created = false
        launchRequestedAt = 0L
        Log.d(TAG, "答题页已关闭，防护恢复")

        // 答题页关闭但锁机仍在 → 确保锁机页回到前台
        val stillLocked = try {
            this::lockState.isInitialized && lockState.shouldBlockNow
        } catch (e: Exception) {
            false
        }
        if (stillLocked && LockScreenActivity.instance == null) {
            LockScreenActivity.show(applicationContext)
        }
    }
}
