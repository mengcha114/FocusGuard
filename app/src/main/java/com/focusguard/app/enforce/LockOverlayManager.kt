package com.focusguard.app.enforce

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.focusguard.app.data.LockState

/**
 * TYPE_APPLICATION_OVERLAY 全屏覆盖层管理器（纯传统 View 实现）。
 *
 * ## 为什么需要这个
 * Activity 的锁机页存在一个无法回避的软肋：小窗（Picture-in-Picture / 浮动窗口）
 * 的 z-order 天然高于普通 Activity。TYPE_APPLICATION_OVERLAY 窗口是 App 能申请的
 * 最高 z-order，盖住小窗/分屏，且不受"清后台"影响。
 *
 * ## 线程模型（重要）
 * WindowManager 的 addView / removeViewImmediate **必须在主线程**执行。
 * 本管理器所有窗口操作统一 post 到主线程 Handler 串行执行；
 * show/hide 可被任意线程调用（守护协程、Activity 回调），内部保证线程安全。
 *
 * ## 为什么不用 Compose（教训）
 * 早期用 ComposeView 渲染覆盖层，在 WindowManager 直挂的 View 上崩溃：
 * `ViewTreeLifecycleOwner not found`（Compose WindowRecomposer 需要 Activity
 * 窗口上下文）。覆盖层 UI 极简，传统 View 零框架依赖。
 *
 * ## 防竞态
 * [HIDE_COOLDOWN_MS]：hide 后短暂拒绝重新 show——否则"按钮→hide→guardTick
 * 立即重 show"会把锁机页/答题页盖住，表现为"点了没反应"。
 */
object LockOverlayManager {

    private const val TAG = "LockOverlayManager"

    /** hide 后拒绝重新 show 的冷却时长：防"按钮→hide→guardTick 立即重 show"竞态。 */
    private const val HIDE_COOLDOWN_MS = 1200L

    /** 覆盖层是否当前可见（主线程维护，volatile 供任意线程读取）。 */
    @Volatile
    var isShowing: Boolean = false
        private set

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var timeText: TextView? = null
    private var currentLockState: LockState? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var clockRunnable: Runnable? = null
    private var lastHideAt = 0L

    /**
     * 显示覆盖层（幂等）。任意线程可调用；窗口操作在内部切到主线程执行。
     *
     * @param context 任意 Context
     * @param lockState 当前锁机状态，覆盖层每秒刷新剩余时间；按钮按解锁强度显示
     * @param onStartChallenge 点击解锁按钮时的回调（主线程回调）
     */
    fun show(
        context: Context,
        lockState: LockState,
        onStartChallenge: () -> Unit
    ) {
        if (isShowing) return
        val now = System.currentTimeMillis()
        if (now - lastHideAt < HIDE_COOLDOWN_MS) {
            Log.d(TAG, "覆盖层冷却中，跳过本次显示")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，覆盖层不启动（Activity 防线仍有效）")
            return
        }
        uiHandler.post {
            if (isShowing) return@post
            try {
                val appContext = context.applicationContext
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val root = FrameLayout(appContext).apply {
                    setBackgroundColor(0xE8000000.toInt())
                }
                root.addView(buildContent(appContext, lockState, onStartChallenge))

                wm.addView(root, buildLayoutParams())

                windowManager = wm
                overlayRoot = root
                currentLockState = lockState
                isShowing = true
                startClock()
                Log.d(TAG, "覆盖层已显示")
            } catch (e: Exception) {
                Log.e(TAG, "显示覆盖层失败：${e.message}")
                cleanupOnMain()
            }
        }
    }

    /** 隐藏并销毁覆盖层。任意线程可调用。 */
    fun hide() {
        uiHandler.post {
            if (!isShowing) return@post
            lastHideAt = System.currentTimeMillis()
            cleanupOnMain()
            Log.d(TAG, "覆盖层已隐藏")
        }
    }

    // ── 内容构建（纯传统 View） ──────────────────────────────

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun buildContent(
        context: Context,
        lockState: LockState,
        onStartChallenge: () -> Unit
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 32), dp(context, 32), dp(context, 32), dp(context, 32))
        }

        // 锁图标（Unicode 锁形，避免依赖图标库）
        val icon = TextView(context).apply {
            text = "\uD83D\uDD12"
            textSize = 46f
            gravity = Gravity.CENTER
        }
        container.addView(icon, matchWrap())

        // 标题
        val title = TextView(context).apply {
            text = "专注卫士"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 14), 0, 0)
        }
        container.addView(title, matchWrap())

        // 剩余时间
        val time = TextView(context).apply {
            text = "--:--"
            textSize = 44f
            setTextColor(Color.parseColor("#FF6B6B"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 18), 0, 0)
        }
        timeText = time
        container.addView(time, matchWrap())

        // 说明
        val hint = TextView(context).apply {
            text = "屏幕已锁定，请专心工作学习"
            textSize = 13f
            setTextColor(Color.parseColor("#8CFFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 12), 0, 0)
        }
        container.addView(hint, matchWrap())

        // 解锁按钮：按强度显示（强度 4 不可提前解锁，不显示按钮）
        if (lockState.unlockStrength < 4) {
            val buttonText = when (lockState.unlockStrength) {
                3 -> "去解锁"
                else -> "答题解锁"
            }
            val button = Button(context).apply {
                text = buttonText
                textSize = 15f
                setTextColor(Color.parseColor("#D0BCFF"))
                setBackgroundColor(Color.TRANSPARENT)
                isAllCaps = false
                setOnClickListener {
                    onStartChallenge()
                }
            }
            container.addView(button, matchWrap())
        } else {
            val noUnlock = TextView(context).apply {
                text = "本次锁机不可提前解锁，请等待时间结束"
                textSize = 12f
                setTextColor(Color.parseColor("#C6786F"))
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 12), 0, 0)
            }
            container.addView(noUnlock, matchWrap())
        }

        return container
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // NOT_FOCUSABLE：不抢焦点，不挡输入法
            // LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS：覆盖状态栏/导航栏区域
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    // ── 倒计时刷新（主线程 Handler，每秒） ────────────────────

    private fun startClock() {
        val runnable = object : Runnable {
            override fun run() {
                val ls = currentLockState ?: return
                val t = timeText ?: return
                val secs = ls.remainingSeconds
                val h = secs / 3600
                val m = (secs % 3600) / 60
                val s = secs % 60
                t.text = if (h > 0) "%02d:%02d:%02d".format(h, m, s)
                else "%02d:%02d".format(m, s)
                uiHandler.postDelayed(this, 1000L)
            }
        }
        clockRunnable = runnable
        uiHandler.post(runnable)
    }

    /** 必须在主线程调用。 */
    private fun cleanupOnMain() {
        clockRunnable?.let { uiHandler.removeCallbacks(it) }
        clockRunnable = null
        currentLockState = null
        try {
            val root = overlayRoot
            val wm = windowManager
            if (root != null && wm != null) {
                wm.removeViewImmediate(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "移除覆盖层视图失败：${e.message}")
        }
        overlayRoot = null
        windowManager = null
        timeText = null
        isShowing = false
    }
}
