package com.focusguard.app.enforce

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.focusguard.app.data.LockState
import com.focusguard.app.data.MemoStore

/**
 * 锁机主体：TYPE_APPLICATION_OVERLAY 全屏悬浮窗（纯传统 View）。
 *
 * ## 为什么悬浮窗是主体，而不是 Activity
 * Activity 有一堆无法回避的软肋：会被上滑手势销毁、被最近任务划掉、被系统
 * 内存回收、被 ROM 清理、被小窗/分屏压在下面。任何一次"掉出前台"到"被守护
 * 拉回"之间都是破解窗口（哪怕只有 300ms）。
 *
 * TYPE_APPLICATION_OVERLAY 是应用能申请的最高 z-order 窗口：
 * - 不属于任何 Task → 上滑手势、最近任务、清后台都动不了它
 * - z-order 高于普通 Activity、小窗、分屏
 * - 只要不主动 removeView 就一直挂在屏幕上，没有"窗口期"
 *
 * 所以现在的分工是：**悬浮窗常驻显示锁机界面**，Activity 只在答题时出场。
 * 无悬浮窗权限时才退回 Activity 方案（见 [LockGuardService]）。
 *
 * ## 按键拦截（关键加固）
 * 早期版本带 `FLAG_NOT_FOCUSABLE`，窗口完全不接收按键 → 返回键/侧滑手势直接
 * 穿透到下层应用，锁机形同虚设。现在窗口是 focusable 的，并在 root 上装
 * [KeyEvent] 监听吞掉返回/菜单等键；音量键放行（不影响用户调音量）。
 *
 * ## 自愈
 * [verifyAttached] 由守护巡检每 300ms 调用：若窗口被系统回收（进程被杀后
 * 重建、ROM 清理悬浮窗），立即重建。这是"锁死"的最后一道保险。
 *
 * ## 线程模型
 * WindowManager 的 addView / removeViewImmediate **必须在主线程**。
 * 所有窗口操作统一 post 到主线程 Handler 串行执行；show/hide/verifyAttached
 * 可被任意线程调用（守护协程、Activity 回调）。
 *
 * ## 为什么不用 Compose（教训）
 * ComposeView 挂在 WindowManager 上会崩：`ViewTreeLifecycleOwner not found`
 * （Compose 的 WindowRecomposer 需要 Activity 窗口上下文）。传统 View 零依赖。
 */
object LockOverlayManager {

    private const val TAG = "LockOverlayManager"

    /**
     * hide 后拒绝重新 show 的冷却时长。
     *
     * 仅用于答题流程：hide → 答题页启动之间若被 guardTick 立即重 show，
     * 会把答题页盖住（表现为"点了没反应"）。锁机主体显示走 [show] 的
     * force 路径，不受冷却影响。
     */
    private const val HIDE_COOLDOWN_MS = 1200L

    /** 覆盖层是否当前可见（主线程维护，volatile 供任意线程读取）。 */
    @Volatile
    var isShowing: Boolean = false
        private set

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var timeText: TextView? = null
    private var statusText: TextView? = null
    private var currentLockState: LockState? = null
    private var lastChallengeCallback: (() -> Unit)? = null
    private var lastPauseCallback: (() -> Unit)? = null
    private var lastContext: Context? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var clockRunnable: Runnable? = null
    private var lastHideAt = 0L

    /** 是否具备悬浮窗权限——决定锁机走"悬浮窗主体"还是"Activity 兜底"。 */
    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 显示覆盖层（幂等）。任意线程可调用；窗口操作在内部切到主线程执行。
     *
     * @param force true 时忽略 hide 冷却（锁机主体显示用）。
     *              答题流程结束后的补位显示应传 false，避免盖住答题页。
     */
    fun show(
        context: Context,
        lockState: LockState,
        force: Boolean = false,
        onStartChallenge: () -> Unit,
        onRequestPause: (() -> Unit)? = null
    ) {
        if (isShowing) return
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastHideAt < HIDE_COOLDOWN_MS) {
                Log.d(TAG, "覆盖层冷却中，跳过本次显示")
                return
            }
        }
        if (!canShow(context)) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，覆盖层不可用（走 Activity 兜底）")
            return
        }
        // 记住参数：窗口被系统回收后 verifyAttached 用它原样重建
        lastContext = context.applicationContext
        lastChallengeCallback = onStartChallenge
        lastPauseCallback = onRequestPause

        uiHandler.post {
            if (isShowing) return@post
            attachWindow(context, lockState, onStartChallenge, onRequestPause)
        }
    }

    /**
     * 同步显示覆盖层（仅主线程）。
     *
     * 用途：答题页被切走的瞬间（onPause 在主线程执行）**同步**挂载全屏
     * 覆盖层——不走 Handler 异步，画面刚切走就已被覆盖（<10ms），
     * 不给"切走 → 覆盖层出现"之间的空窗期做任何操作（防破解加固）。
     *
     * addView 无 InputDispatcher 竞态（竞态只存在于"removeView 后立即
     * startActivity"的组合），在生命周期回调中同步 addView 是安全的。
     */
    fun showNow(
        context: Context,
        lockState: LockState,
        onStartChallenge: () -> Unit,
        onRequestPause: (() -> Unit)? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "showNow 必须在主线程调用，已退回异步 show")
            show(
                context, lockState,
                force = true,
                onStartChallenge = onStartChallenge,
                onRequestPause = onRequestPause
            )
            return
        }
        if (isShowing) return
        if (!canShow(context)) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，覆盖层不可用（走 Activity 兜底）")
            return
        }
        lastContext = context.applicationContext
        lastChallengeCallback = onStartChallenge
        lastPauseCallback = onRequestPause
        attachWindow(context, lockState, onStartChallenge, onRequestPause)
    }

    /**
     * 窗口挂载主逻辑（必须在主线程调用）。
     * 由异步 [show] 与同步 [showNow] 共用，避免两份窗口构建代码漂移。
     */
    private fun attachWindow(
        context: Context,
        lockState: LockState,
        onStartChallenge: () -> Unit,
        onRequestPause: (() -> Unit)?
    ) {
        if (isShowing) return
        if (UnlockChallengeActivity.active) {
            Log.d(TAG, "答题流程进行中，拒绝挂载悬浮窗")
            return
        }
        try {
            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val root = object : FrameLayout(appContext) {
                /**
                 * 吞掉返回键：这是侧滑返回手势最终落到的按键事件。
                 * dispatchKeyEvent 比 OnKeyListener 更靠前，ROM 手势也走这里。
                 */
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    return when (event.keyCode) {
                        // 音量键放行：锁机不该妨碍用户调音量
                        KeyEvent.KEYCODE_VOLUME_UP,
                        KeyEvent.KEYCODE_VOLUME_DOWN,
                        KeyEvent.KEYCODE_VOLUME_MUTE -> super.dispatchKeyEvent(event)
                        // 其余全部吞掉（返回、菜单、搜索、多任务…）
                        else -> true
                    }
                }
            }.apply {
                background = buildBackground()
                isFocusableInTouchMode = true
                isFocusable = true
            }
            root.addView(buildContent(appContext, lockState, onStartChallenge, onRequestPause))

            wm.addView(root, buildLayoutParams())
            root.requestFocus()
            hideSystemBars(root)

            windowManager = wm
            overlayRoot = root
            currentLockState = lockState
            isShowing = true
            startClock()
            Log.d(TAG, "锁机覆盖层已显示（悬浮窗主体）")
        } catch (e: Exception) {
            Log.e(TAG, "显示覆盖层失败：${e.message}")
            cleanupOnMain()
        }
    }

    /**
     * 校验窗口仍挂在屏幕上；被系统回收则原样重建。
     *
     * 守护巡检每 tick 调用。这是"进程被杀后重建 / ROM 清理悬浮窗"
     * 之后能自动恢复锁机的关键。
     */
    /**
     * 校验窗口仍挂在屏幕上；被系统回收则原样重建。
     * 由守护巡检每 tick 调用。
     */
    fun verifyAttached(
        context: Context? = lastContext,
        lockState: LockState? = currentLockState
    ) {
        if (!isShowing) return
        uiHandler.post {
            val root = overlayRoot ?: return@post
            if (root.isAttachedToWindow) {
                // 顺手把系统栏重新隐藏：部分 ROM 会在切换应用后重置
                hideSystemBars(root)
                return@post
            }
            Log.w(TAG, "覆盖层已被系统回收，立即重建")
            val ctx = lastContext
            val ls = currentLockState
            val cb = lastChallengeCallback
            val cbPause = lastPauseCallback
            cleanupOnMain()
            if (ctx != null && ls != null && cb != null) {
                show(ctx, ls, force = true, onStartChallenge = cb, onRequestPause = cbPause)
            } else if (context != null && lockState != null) {
                val savedCb = cb ?: {}
                show(context, lockState, force = true, onStartChallenge = savedCb, onRequestPause = cbPause)
            }
        }
    }

    /**
     * 同步隐藏：仅允许主线程调用。
     *
     * 悬浮窗按钮点击后要立刻启动答题页——如果走异步 [hide]，
     * 答题页 onCreate 时悬浮窗可能还挂着，把答题页盖住
     * （用户看到的"点了没反应/闪一下"）。
     */
    fun hideNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "hideNow 必须在主线程调用，已退回异步 hide")
            hide()
            return
        }
        if (!isShowing) return
        lastHideAt = System.currentTimeMillis()
        cleanupOnMain()
        Log.d(TAG, "覆盖层已同步隐藏")
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

    /** 深色渐变背景：比纯黑更有质感，也和锁机页视觉统一。 */
    private fun buildBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF0A0A0F.toInt(), 0xFF151221.toInt(), 0xFF0D0B14.toInt())
    )

    private fun buildContent(
        context: Context,
        lockState: LockState,
        onStartChallenge: () -> Unit,
        onRequestPause: (() -> Unit)?
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 34), dp(context, 40), dp(context, 34), dp(context, 40))
        }

        // 锁图标（Unicode 锁形，避免依赖图标资源）
        container.addView(
            TextView(context).apply {
                text = "\uD83D\uDD12"
                textSize = 44f
                gravity = Gravity.CENTER
            },
            matchWrap()
        )

        container.addView(
            TextView(context).apply {
                text = "专注卫士"
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
                setPadding(0, dp(context, 12), 0, 0)
            },
            matchWrap()
        )

        // 状态行（锁定中 / 番茄钟专注 / 暂停中，每秒刷新）
        val status = TextView(context).apply {
            text = "设备已锁定"
            textSize = 12f
            setTextColor(Color.parseColor("#9C8BC9"))
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 6), 0, 0)
        }
        statusText = status
        container.addView(status, matchWrap())

        // 大号倒计时
        val time = TextView(context).apply {
            text = "--:--"
            textSize = 52f
            setTextColor(Color.parseColor("#B4A5FF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 20), 0, 0)
        }
        timeText = time
        container.addView(time, matchWrap())

        container.addView(
            TextView(context).apply {
                text = "锁定期间无法使用其他应用"
                textSize = 12f
                setTextColor(Color.parseColor("#70FFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 4), 0, 0)
            },
            matchWrap()
        )

        // 待办清单：锁机时看到自己该做什么，比单纯拦截更有意义
        val pending = runCatching { MemoStore(context).getPending() }.getOrDefault(emptyList())
        if (pending.isNotEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = "待办清单"
                    textSize = 10f
                    letterSpacing = 0.2f
                    setTextColor(Color.parseColor("#9C8BC9"))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(context, 24), 0, dp(context, 8))
                },
                matchWrap()
            )
            pending.take(3).forEach { item ->
                val tag = when {
                    item.overdue -> " · 逾期"
                    item.priority == 2 -> " · 紧急"
                    item.priority == 1 -> " · 重要"
                    else -> ""
                }
                container.addView(
                    TextView(context).apply {
                        text = "· ${item.text}$tag"
                        textSize = 12f
                        setTextColor(Color.parseColor("#B0FFFFFF"))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(context, 2), 0, dp(context, 2))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    matchWrap()
                )
            }
        }

        // 箴言（与旧版锁机页一致：优先用户自定义，随机一条）
        container.addView(
            TextView(context).apply {
                text = pickMotto(context)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9C8BC9"))
                setPadding(dp(context, 20), dp(context, 22), dp(context, 20), 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            matchWrap()
        )

        // 解锁/暂停按钮：按强度显示（强度 4 不可提前解锁 → 不给按钮）
        if (lockState.unlockStrength < 4) {
            container.addView(
                Button(context).apply {
                    text = if (lockState.unlockStrength == 3) "去解锁" else "答题解锁"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    isAllCaps = false
                    background = GradientDrawable().apply {
                        cornerRadius = dp(context, 14).toFloat()
                        setColor(0xFF4F378B.toInt())
                    }
                    setPadding(dp(context, 28), dp(context, 12), dp(context, 28), dp(context, 12))
                    setOnClickListener {
                        try {
                            onStartChallenge()
                        } catch (e: Exception) {
                            Log.e(TAG, "解锁按钮回调异常：${e.message}")
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    topMargin = dp(context, 26)
                }
            )
            // 暂停按钮：设置了"允许中途暂停"且有剩余配额时显示
            // （用户反馈"设置了允许中途暂停但锁机页面没显示暂停入口"）
            if (lockState.canPause && onRequestPause != null) {
                container.addView(
                    Button(context).apply {
                        text = "暂停（答题）"
                        textSize = 13f
                        setTextColor(Color.parseColor("#C0B4FF"))
                        isAllCaps = false
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 12).toFloat()
                            setColor(0x334F378B.toInt()) // 半透明紫底，次级按钮观感
                        }
                        setPadding(dp(context, 24), dp(context, 9), dp(context, 24), dp(context, 9))
                        setOnClickListener {
                            try {
                                onRequestPause()
                            } catch (e: Exception) {
                                Log.e(TAG, "暂停按钮回调异常：${e.message}")
                            }
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER
                        topMargin = dp(context, 12)
                    }
                )
            }
        } else {
            container.addView(
                TextView(context).apply {
                    text = "本次锁机不可提前解锁，请等待时间结束"
                    textSize = 12f
                    setTextColor(Color.parseColor("#EF9A9A"))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(context, 26), 0, 0)
                },
                matchWrap()
            )
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
            // 注意这里**故意不加** FLAG_NOT_FOCUSABLE：
            // 窗口必须能接收按键，才能吞掉返回键/侧滑手势。
            // 这正是此前"侧滑轻易破解"的根因。
            //
            // LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS：铺满状态栏/导航栏区域
            // FLAG_FULLSCREEN：隐藏状态栏，减少下拉通知栏的入口
            // 不加 TURN_SCREEN_ON / KEEP_SCREEN_ON——否则息屏会被强行点亮
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    /** 隐藏系统栏（沉浸式）：减少下拉通知栏与导航手势的入口。 */
    private fun hideSystemBars(root: View) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                root.windowInsetsController?.let { controller ->
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior = android.view.WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                root.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        } catch (e: Exception) {
            Log.w(TAG, "隐藏系统栏失败：${e.message}")
        }
    }

    // ── 倒计时刷新（主线程 Handler，每秒） ────────────────────

    private fun startClock() {
        val runnable = object : Runnable {
            override fun run() {
                val ls = currentLockState ?: return
                val secs = when {
                    ls.isPaused -> ls.pauseRemainingSeconds
                    ls.lockSource == "POMODORO" -> ls.pomodoroRemainingSeconds
                    else -> ls.remainingSeconds
                }
                val h = secs / 3600
                val m = (secs % 3600) / 60
                val s = secs % 60
                timeText?.text = if (h > 0) "%02d:%02d:%02d".format(h, m, s)
                else "%02d:%02d".format(m, s)

                statusText?.text = when {
                    ls.isPaused -> "暂停中 · 可自由使用"
                    ls.lockSource == "POMODORO" && ls.pomodoroIsWorkPhase -> "番茄钟专注阶段"
                    ls.lockSource == "POMODORO" -> "番茄钟休息阶段"
                    else -> "设备已锁定"
                }
                // 配色随阶段切换：专注=紫（严肃），暂停/休息=绿（放松）——
                // 与旧版 Compose 锁机页的视觉语言一致
                val relaxed = ls.isPaused ||
                    (ls.lockSource == "POMODORO" && !ls.pomodoroIsWorkPhase)
                val accentColor = if (relaxed) {
                    android.graphics.Color.parseColor("#34D399")
                } else {
                    android.graphics.Color.parseColor("#B4A5FF")
                }
                timeText?.setTextColor(accentColor)
                statusText?.setTextColor(
                    if (relaxed) android.graphics.Color.parseColor("#6EE7B7")
                    else android.graphics.Color.parseColor("#9C8BC9")
                )
                uiHandler.postDelayed(this, 1000L)
            }
        }
        clockRunnable = runnable
        uiHandler.post(runnable)
    }

    /** 取一条箴言：优先用户自定义（每行一条随机），否则内置库。 */
    private fun pickMotto(context: Context): String {
        val custom = runCatching {
            com.focusguard.app.data.Settings(context).customMottos
        }.getOrDefault("")
        val customList = custom.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (customList.isNotEmpty()) {
            customList.random()
        } else {
            BUILTIN_MOTTOS.random()
        }
    }

    private val BUILTIN_MOTTOS = listOf(
        "把手机放下，把时间还给自己。",
        "专注不是天赋，是每天的选择。",
        "现在做的事，正在塑造未来的你。",
        "一次只做一件事，做完再做下一件。",
        "自律给我自由。",
        "别让短视频偷走你的梦想。",
        "深度工作，才是真正的生产力。",
        "坚持一下，你会感谢现在的自己。"
    )

    /** 必须在主线程调用。 */
    private fun cleanupOnMain() {
        clockRunnable?.let { uiHandler.removeCallbacks(it) }
        clockRunnable = null
        currentLockState = null
        lastPauseCallback = null
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
        statusText = null
        isShowing = false
    }
}
