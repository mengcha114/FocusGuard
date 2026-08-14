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

    // ── 悬浮窗内答题模式（防手势退出的核心） ────────────
    // 答题 UI 直接画在同一个 TYPE_APPLICATION_OVERLAY 窗口里：
    // 悬浮窗不属于任何 Task，系统手势（底部上滑/侧滑返回/最近任务）
    // 物理上无法作用于它——这是 Activity 方案永远做不到的。
    // 切换只是 root 的子 View 替换，没有任何窗口生命周期，零露桌。

    /** 当前是否处于答题模式（守护巡检据此完全放行，不打断答题）。 */
    @Volatile
    var isChallengeMode: Boolean = false
        private set

    /**
     * 当前是否处于朋友密码验证模式（强度 3）。
     * 与 [isChallengeMode] 同时置位：守护巡检/窗口重建让位自动覆盖。
     */
    @Volatile
    var isFriendUnlockMode: Boolean = false
        private set

    /** 答题会话状态（object 级字段：窗口被 ROM 回收重建后进度不丢）。 */
    private var challengeSession: ChallengeSession? = null

    /** 答题通过回调：参数为 forPause（true=换取暂停，false=解锁）。 */
    private var challengePassedCallback: ((Boolean) -> Unit)? = null

    private val challengeGenerator by lazy {
        com.focusguard.app.challenge.ChallengeGenerator()
    }

    // 锁机界面的时钟视图（每秒刷新）
    private var wallClockText: TextView? = null
    private var wallDateText: TextView? = null

    // 答题界面的可变视图引用（按键只更新文本，不整屏重建）
    private var challengeAnswerText: TextView? = null
    private var challengeFeedbackText: TextView? = null
    private var challengeProgressText: TextView? = null
    private var challengeQuestionText: TextView? = null
    private var challengeRefreshButton: Button? = null
    private var challengeKeyboardBox: LinearLayout? = null

    /** 键盘是否显示字母页（默认数字页）。 */
    private var challengeLetterPage = false

    // ── 朋友密码验证模式（强度 3；与答题模式互斥，共用视图引用） ──
    private var friendUnlockInput = ""
    private var friendUnlockFeedback = ""
    private var friendUnlockFeedbackIsError = false
    /** 朋友密码明文多为字母，默认字母页。 */
    private var friendUnlockLetterPage = true
    private var friendUnlockPassedCallback: (() -> Unit)? = null

    /** 悬浮窗内答题的会话状态。 */
    private data class ChallengeSession(
        var question: com.focusguard.app.challenge.ChallengeQuestion,
        var input: String = "",
        var correctCount: Int = 0,
        val requiredCorrect: Int,
        val forPause: Boolean,
        var feedback: String = "",
        var feedbackIsError: Boolean = false,
        /** 反馈展示中（正在等待自动换题），此期间禁用输入。 */
        var switching: Boolean = false
    )

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

                /**
                 * 捕获窗口外触摸（配合 FLAG_WATCH_OUTSIDE_TOUCH）。
                 * 任何窗口外/边缘触摸都触发系统栏重新隐藏与通知栏收起。
                 */
                override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                    if (event.action == android.view.MotionEvent.ACTION_OUTSIDE ||
                        event.action == android.view.MotionEvent.ACTION_DOWN
                    ) {
                        hideSystemBars(this)
                        notifyShadeDismiss()
                    }
                    return super.onTouchEvent(event)
                }

                /**
                 * 全屏按压/滑动事件预拦截：
                 * 任何在顶部 35% 区域内的触摸（DOWN/MOVE），全部阻断并收起通知栏。
                 */
                override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                    if (event.rawY < height * 0.35f) {
                        hideSystemBars(this)
                        notifyShadeDismiss()
                        if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                            event.action == android.view.MotionEvent.ACTION_MOVE
                        ) {
                            return true
                        }
                    }
                    return super.dispatchTouchEvent(event)
                }
            }.apply {
                background = buildBackground()
                isFocusableInTouchMode = true
                isFocusable = true
            }
            // 窗口重建时若处于答题/朋友验证模式 → 恢复对应界面（进度保留）
            val session = challengeSession
            if (isChallengeMode && isFriendUnlockMode) {
                root.addView(buildFriendUnlockContent(appContext, lockState))
            } else if (isChallengeMode && session != null) {
                root.addView(buildChallengeContent(appContext, lockState, session))
            } else {
                root.addView(buildContent(appContext, lockState, onStartChallenge, onRequestPause))
            }

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
            gravity = Gravity.CENTER_HORIZONTAL
            // 高度撑满：底部弹性占位才能把操作区推到屏幕下部
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 顶部留出状态栏高度，内容不被刘海/挖孔压住（窗口本身已铺满全屏）
            setPadding(
                dp(context, 26),
                dp(context, 48),
                dp(context, 26),
                dp(context, 34)
            )
        }

        // ── 顶部：真实时钟 + 日期（像系统锁屏一样） ──────────
        val wallClock = TextView(context).apply {
            text = "--:--"
            textSize = 58f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
        }
        wallClockText = wallClock
        container.addView(wallClock, matchWrap())

        val wallDate = TextView(context).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#8FFFFFFF"))
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            setPadding(0, dp(context, 2), 0, 0)
        }
        wallDateText = wallDate
        container.addView(wallDate, matchWrap())

        // ── 中部：锁定状态卡（圆角卡片 + 锁标 + 倒计时） ──────
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 24).toFloat()
                setColor(0x14FFFFFF)
                setStroke(dp(context, 1), 0x26FFFFFF)
            }
            setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 20))
        }

        // 锁标 + 应用名（一行）
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(
                    TextView(context).apply {
                        text = "\uD83D\uDD12"
                        textSize = 17f
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    TextView(context).apply {
                        text = "专注卫士"
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.1f
                        setPadding(dp(context, 8), 0, 0, 0)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            matchWrap()
        )

        // 状态行（锁定中 / 番茄钟专注 / 暂停中，每秒刷新）
        val status = TextView(context).apply {
            text = "设备已锁定"
            textSize = 11f
            setTextColor(Color.parseColor("#9C8BC9"))
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(context, 10), 0, 0)
        }
        statusText = status
        card.addView(status, matchWrap())

        // 大号剩余时长
        val time = TextView(context).apply {
            text = "--:--"
            textSize = 46f
            setTextColor(Color.parseColor("#B4A5FF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 4), 0, 0)
        }
        timeText = time
        card.addView(time, matchWrap())

        card.addView(
            TextView(context).apply {
                text = "剩余锁定时间"
                textSize = 10f
                setTextColor(Color.parseColor("#60FFFFFF"))
                gravity = Gravity.CENTER
                letterSpacing = 0.14f
            },
            matchWrap()
        )

        container.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 22) }
        )

        // ── 待办清单卡片（带边框，左对齐，逾期/紧急高亮） ──────
        val pending = runCatching { MemoStore(context).getPending() }.getOrDefault(emptyList())
        if (pending.isNotEmpty()) {
            val memoCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 18).toFloat()
                    setColor(0x0FFFFFFF)
                    setStroke(dp(context, 1), 0x1FFFFFFF)
                }
                setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
            }
            // 卡片标题行：小圆点 + 标题 + 数量
            memoCard.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = "待办清单"
                            textSize = 11f
                            letterSpacing = 0.16f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.parseColor("#B4A5FF"))
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        TextView(context).apply {
                            text = "${pending.size} 项"
                            textSize = 10f
                            setTextColor(Color.parseColor("#60FFFFFF"))
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                },
                matchWrap()
            )
            pending.take(4).forEach { item ->
                val tagText = when {
                    item.overdue -> "逾期"
                    item.priority == 2 -> "紧急"
                    item.priority == 1 -> "重要"
                    else -> ""
                }
                val tagColor = when {
                    item.overdue || item.priority == 2 -> "#EF9A9A"
                    item.priority == 1 -> "#FFCC80"
                    else -> "#60FFFFFF"
                }
                memoCard.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(context, 5), 0, dp(context, 5))
                        // 左侧竖条：按优先级着色
                        addView(
                            View(context).apply {
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(context, 2).toFloat()
                                    setColor(Color.parseColor(tagColor))
                                }
                            },
                            LinearLayout.LayoutParams(dp(context, 3), dp(context, 14)).apply {
                                marginEnd = dp(context, 10)
                            }
                        )
                        addView(
                            TextView(context).apply {
                                text = item.text
                                textSize = 12f
                                setTextColor(Color.parseColor("#D0FFFFFF"))
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        if (tagText.isNotEmpty()) {
                            addView(
                                TextView(context).apply {
                                    text = tagText
                                    textSize = 9f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setTextColor(Color.parseColor(tagColor))
                                    background = GradientDrawable().apply {
                                        cornerRadius = dp(context, 6).toFloat()
                                        setColor(0x14FFFFFF)
                                    }
                                    setPadding(
                                        dp(context, 6), dp(context, 2),
                                        dp(context, 6), dp(context, 2)
                                    )
                                },
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { marginStart = dp(context, 8) }
                            )
                        }
                    },
                    matchWrap()
                )
            }
            if (pending.size > 4) {
                memoCard.addView(
                    TextView(context).apply {
                        text = "还有 ${pending.size - 4} 项…"
                        textSize = 10f
                        setTextColor(Color.parseColor("#50FFFFFF"))
                        setPadding(dp(context, 13), dp(context, 3), 0, 0)
                    },
                    matchWrap()
                )
            }
            container.addView(
                memoCard,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 16) }
            )
        }

        // ── 箴言卡片（带左侧竖线的引用样式） ──────────────────
        container.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 16).toFloat()
                    setColor(0x0AFFFFFF)
                    setStroke(dp(context, 1), 0x18FFFFFF)
                }
                setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
                addView(
                    View(context).apply {
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 2).toFloat()
                            setColor(Color.parseColor("#8B7CF6"))
                        }
                    },
                    LinearLayout.LayoutParams(dp(context, 3), LinearLayout.LayoutParams.MATCH_PARENT)
                        .apply { marginEnd = dp(context, 12) }
                )
                addView(
                    TextView(context).apply {
                        text = pickMotto(context)
                        textSize = 13f
                        setLineSpacing(dp(context, 3).toFloat(), 1f)
                        setTextColor(Color.parseColor("#C0B4FF"))
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 14) }
        )

        // 弹性占位：把按钮压到屏幕下部，消除底部大片空白
        container.addView(
            View(context),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        // ── 底部操作区：全宽按钮（贴近拇指区域，视觉更稳） ──────
        if (lockState.unlockStrength < 4) {
            container.addView(
                Button(context).apply {
                    text = if (lockState.unlockStrength == 3) "去解锁" else "答题解锁"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    isAllCaps = false
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(0xFF7C4DFF.toInt(), 0xFF5E35B1.toInt())
                    ).apply { cornerRadius = dp(context, 16).toFloat() }
                    setOnClickListener {
                        try {
                            onStartChallenge()
                        } catch (e: Exception) {
                            Log.e(TAG, "解锁按钮回调异常：${e.message}")
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 52)
                ).apply { topMargin = dp(context, 8) }
            )
            // 暂停按钮：设置了"允许中途暂停"且有剩余配额时显示
            if (lockState.canPause && onRequestPause != null) {
                container.addView(
                    Button(context).apply {
                        text = "暂停（答题换取 ${lockState.pauseMinutes} 分钟）"
                        textSize = 13f
                        setTextColor(Color.parseColor("#C0B4FF"))
                        isAllCaps = false
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 14).toFloat()
                            setColor(0x1AFFFFFF)
                            setStroke(dp(context, 1), 0x334F378B)
                        }
                        setOnClickListener {
                            try {
                                onRequestPause()
                            } catch (e: Exception) {
                                Log.e(TAG, "暂停按钮回调异常：${e.message}")
                            }
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(context, 44)
                    ).apply { topMargin = dp(context, 10) }
                )
            }
        } else {
            container.addView(
                TextView(context).apply {
                    text = "本次锁机不可提前解锁，请等待时间结束"
                    textSize = 12f
                    setTextColor(Color.parseColor("#EF9A9A"))
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(context, 14).toFloat()
                        setColor(0x14EF5350)
                    }
                    setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 8) }
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
            //   （去掉 FLAG_FULLSCREEN——它会让窗口避开状态栏区域，
            //    表现为顶部缺一条；改用 NO_LIMITS + 全屏 insets 隐藏，
            //    窗口真正铺到屏幕最顶端，视觉上无缺口）
            // FLAG_WATCH_OUTSIDE_TOUCH：捕获窗口外触摸（下拉通知栏的起手动作）
            // 不加 TURN_SCREEN_ON / KEEP_SCREEN_ON——否则息屏会被强行点亮
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 铺到刘海/挖孔区域，顶部彻底无缺口
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * 隐藏系统栏（沉浸式）+ 持续阻止下拉通知栏。
     *
     * 关键点：
     * 1. `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 会允许"滑动临时显示"——
     *    用户借这一下就能把通知栏拉出来。改用 `BEHAVIOR_DEFAULT`
     *    （不允许滑动唤出），配合下面的 insets 监听持续压制。
     * 2. 部分 ROM 会在切换/焦点变化后重置系统栏状态 → 注册
     *    `OnApplyWindowInsetsListener`，一旦发现状态栏可见立即再隐藏，
     *    通知栏动画被反复中断，无法完整展开。
     * 3. 布局层面不再用 FLAG_FULLSCREEN（会让窗口避开状态栏留下缺口），
     *    改为 NO_LIMITS + cutout SHORT_EDGES，窗口铺满到屏幕最顶端。
     */
    private fun hideSystemBars(root: View) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                root.windowInsetsController?.let { controller ->
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                    // 不允许"滑动临时显示系统栏"——否则用户能借此拉出通知栏
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_DEFAULT
                }
                // 持续压制：系统栏一旦重新可见（下拉动作），立即再隐藏
                root.setOnApplyWindowInsetsListener { v, insets ->
                    try {
                        val statusVisible =
                            insets.isVisible(android.view.WindowInsets.Type.statusBars())
                        if (statusVisible) {
                            v.windowInsetsController?.hide(
                                android.view.WindowInsets.Type.statusBars() or
                                    android.view.WindowInsets.Type.navigationBars()
                            )
                            notifyShadeDismiss()
                        }
                    } catch (e: Exception) {
                        // 忽略，不影响窗口
                    }
                    insets
                }
            } else {
                @Suppress("DEPRECATION")
                root.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                @Suppress("DEPRECATION")
                root.setOnSystemUiVisibilityChangeListener { visibility ->
                    @Suppress("DEPRECATION")
                    if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        @Suppress("DEPRECATION")
                        root.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        notifyShadeDismiss()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "隐藏系统栏失败：${e.message}")
        }
    }

    /** 借无障碍服务收起已展开的通知栏（有权限时的补充防线）。 */
    private fun notifyShadeDismiss() {
        try {
            com.focusguard.app.access.GuardAccessibilityService.instance
                ?.dismissNotificationShade()
        } catch (e: Exception) {
            // 无障碍未开启时忽略
        }
    }

    // ── 倒计时刷新（主线程 Handler，每秒） ────────────────────

    private fun startClock() {
        val runnable = object : Runnable {
            override fun run() {
                // ── 真实时钟与日期（像系统锁屏） ──────────────
                try {
                    val now = java.util.Date()
                    wallClockText?.text = java.text.SimpleDateFormat(
                        "HH:mm", java.util.Locale.getDefault()
                    ).format(now)
                    wallDateText?.text = java.text.SimpleDateFormat(
                        "M月d日 EEEE", java.util.Locale.CHINA
                    ).format(now)
                } catch (e: Exception) {
                    // 时间格式化失败不影响其他刷新
                }

                val ls = currentLockState ?: run {
                    // 答题模式下 currentLockState 可能为空，但时钟仍需继续走
                    uiHandler.postDelayed(this, 1000L)
                    return
                }
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

    // ══════════════════════════════════════════════════════
    // 悬浮窗内答题模式
    // ══════════════════════════════════════════════════════

    /**
     * 进入悬浮窗内答题模式（必须主线程；非主线程自动 post）。
     *
     * 与 Activity 方案的本质区别：**不启动任何 Activity**，
     * 答题 UI 就画在当前悬浮窗里。悬浮窗不属于任何 Task →
     * 底部上滑、侧滑返回、最近任务都无法把它移走，
     * 手势退出在物理上不可能发生。
     *
     * @param requiredCorrect 需连续答对的题数（强度 1 → 1，强度 2 → 5）
     * @param forPause true=答对后换取暂停；false=答对后解锁
     * @param onPassed 答题通过回调（在主线程调用）
     */
    fun enterChallengeMode(
        context: Context,
        lockState: LockState,
        requiredCorrect: Int,
        forPause: Boolean,
        onPassed: (Boolean) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post { enterChallengeMode(context, lockState, requiredCorrect, forPause, onPassed) }
            return
        }
        // 窗口未挂载 → 先挂载（force：绕过 hide 冷却）
        if (!isShowing) {
            showNow(
                context = context,
                lockState = lockState,
                onStartChallenge = {},
                onRequestPause = null
            )
        }
        val root = overlayRoot
        if (root == null) {
            Log.w(TAG, "悬浮窗不可用，无法进入答题模式")
            return
        }

        challengePassedCallback = onPassed
        challengeLetterPage = false
        // 互斥：进入答题模式即复位朋友验证标志。
        isFriendUnlockMode = false
        // 已有未完成的答题会话（用户点「返回锁机界面」再进）→ 恢复同一题，
        // 防止用"退出重进"绕过「换一题」5 次限制反复刷题。
        if (challengeSession == null) {
            challengeSession = ChallengeSession(
                // numericOnly：自绘键盘无法输入中文，排除星期推算题
                question = challengeGenerator.generate(
                    difficulty = if (requiredCorrect >= 3) 3 else 2,
                    numericOnly = true
                ),
                requiredCorrect = requiredCorrect.coerceAtLeast(1),
                forPause = forPause
            )
        }
        // 锁机时钟停掉（答题界面没有倒计时视图）
        clockRunnable?.let { uiHandler.removeCallbacks(it) }
        clockRunnable = null
        timeText = null
        statusText = null
        wallClockText = null
        wallDateText = null

        isChallengeMode = true
        val session = challengeSession ?: return
        root.removeAllViews()
        root.addView(buildChallengeContent(context.applicationContext, lockState, session))
        root.requestFocus()
        hideSystemBars(root)
        Log.d(TAG, "已进入悬浮窗内答题模式（需答对 ${session.requiredCorrect} 题）")
    }

    /** 退出答题模式，恢复锁机主界面（必须主线程；非主线程自动 post）。 */
    fun exitChallengeMode(
        context: Context,
        lockState: LockState,
        onStartChallenge: () -> Unit,
        onRequestPause: (() -> Unit)?
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post {
                exitChallengeMode(context, lockState, onStartChallenge, onRequestPause)
            }
            return
        }
        isChallengeMode = false
        isFriendUnlockMode = false
        // 注意：challengeSession 故意**不清空**——「返回锁机界面」只是暂停答题，
        // 再进时恢复同一题（enterChallengeMode 会复用），防止退出重进换题绕过限制。
        // 会话只在答题通过（onSubmitAnswer → challengeSession = null）或
        // 锁机结束（cleanupOnMain）时清空。
        challengePassedCallback = null
        clearChallengeViewRefs()

        val root = overlayRoot ?: return
        lastChallengeCallback = onStartChallenge
        lastPauseCallback = onRequestPause
        currentLockState = lockState
        root.removeAllViews()
        root.addView(
            buildContent(context.applicationContext, lockState, onStartChallenge, onRequestPause)
        )
        root.requestFocus()
        hideSystemBars(root)
        startClock()
        Log.d(TAG, "已退出答题模式，恢复锁机界面")
    }

    /**
     * 进入朋友密码验证模式（强度 3，悬浮窗形态）。
     *
     * 与答题模式同构：验证 UI 直接画在悬浮窗里，不启动 Activity。
     * 悬浮窗不属于任何 Task → 手势退出物理上不可能；根视图自带的
     * 吞键/下拉拦截/焦点抢占全部生效。
     */
    fun enterFriendUnlockMode(
        context: Context,
        lockState: LockState,
        onPassed: () -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post { enterFriendUnlockMode(context, lockState, onPassed) }
            return
        }
        if (!isShowing) {
            showNow(
                context = context,
                lockState = lockState,
                onStartChallenge = {},
                onRequestPause = null
            )
        }
        val root = overlayRoot
        if (root == null) {
            Log.w(TAG, "悬浮窗不可用，无法进入朋友密码验证")
            return
        }

        friendUnlockPassedCallback = onPassed
        friendUnlockInput = ""
        friendUnlockFeedback = ""
        friendUnlockFeedbackIsError = false
        friendUnlockLetterPage = true
        // 互斥：进入朋友验证即放弃未完成的答题会话（两种入口不同时存在于
        // 同一锁机实例），避免回收重建时错渲染成暂停答题界面。
        challengeSession = null
        // 锁机时钟停掉（验证界面没有倒计时视图）
        clockRunnable?.let { uiHandler.removeCallbacks(it) }
        clockRunnable = null
        timeText = null
        statusText = null
        wallClockText = null
        wallDateText = null

        isFriendUnlockMode = true
        isChallengeMode = true
        root.removeAllViews()
        root.addView(buildFriendUnlockContent(context.applicationContext, lockState))
        root.requestFocus()
        hideSystemBars(root)
        Log.d(TAG, "已进入悬浮窗内朋友密码验证模式")
    }

    /** 退出朋友密码验证模式，恢复锁机主界面（必须主线程；非主线程自动 post）。 */
    fun exitFriendUnlockMode(
        context: Context,
        lockState: LockState,
        onStartChallenge: () -> Unit,
        onRequestPause: (() -> Unit)?
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post {
                exitFriendUnlockMode(context, lockState, onStartChallenge, onRequestPause)
            }
            return
        }
        isFriendUnlockMode = false
        isChallengeMode = false
        friendUnlockPassedCallback = null
        friendUnlockInput = ""
        friendUnlockFeedback = ""
        clearChallengeViewRefs()

        val root = overlayRoot ?: return
        lastChallengeCallback = onStartChallenge
        lastPauseCallback = onRequestPause
        currentLockState = lockState
        root.removeAllViews()
        root.addView(
            buildContent(context.applicationContext, lockState, onStartChallenge, onRequestPause)
        )
        root.requestFocus()
        hideSystemBars(root)
        startClock()
        Log.d(TAG, "已退出朋友密码验证模式，恢复锁机界面")
    }

    private fun clearChallengeViewRefs() {
        challengeAnswerText = null
        challengeFeedbackText = null
        challengeProgressText = null
        challengeQuestionText = null
        challengeRefreshButton = null
        challengeKeyboardBox = null
    }

    /** 构建答题界面（纯传统 View；禁止 Compose——ComposeView 挂 WM 会崩）。 */
    private fun buildChallengeContent(
        context: Context,
        lockState: LockState,
        session: ChallengeSession
    ): View {
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 顶部留状态栏高度：窗口铺满全屏（无缺口），内容不被刘海压住
            setPadding(dp(context, 20), dp(context, 52), dp(context, 20), dp(context, 20))
        }

        // ── 标题 + 进度 ──────────────────────────────
        container.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        text = "解锁挑战"
                        textSize = 19f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    TextView(context).apply {
                        text = "${session.correctCount} / ${session.requiredCorrect}"
                        textSize = 14f
                        setTextColor(Color.parseColor("#66BB6A"))
                        typeface = Typeface.DEFAULT_BOLD
                        challengeProgressText = this
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            matchWrap()
        )

        container.addView(
            TextView(context).apply {
                text = if (session.forPause) {
                    "答对后可换取一次暂停"
                } else {
                    "答对 ${session.requiredCorrect} 题即可解锁；答错会给出解析并换题"
                }
                textSize = 11f
                setTextColor(Color.parseColor("#70FFFFFF"))
                setPadding(0, dp(context, 4), 0, 0)
            },
            matchWrap()
        )

        // ── 题目卡 ──────────────────────────────────
        container.addView(
            TextView(context).apply {
                text = session.question.question
                textSize = 16f
                setTextColor(Color.WHITE)
                setLineSpacing(dp(context, 5).toFloat(), 1f)
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 16).toFloat()
                    setColor(0x14FFFFFF)
                    setStroke(dp(context, 1), 0x26FFFFFF)
                }
                setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
                challengeQuestionText = this
            },
            matchWrap().apply { topMargin = dp(context, 14) }
        )

        // ── 答案显示区 ──────────────────────────────
        container.addView(
            TextView(context).apply {
                text = session.input.ifEmpty { "请输入答案" }
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(
                    if (session.input.isEmpty()) Color.parseColor("#50FFFFFF") else Color.WHITE
                )
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(0xFF1A1622.toInt())
                    setStroke(dp(context, 1), 0xFF4F378B.toInt())
                }
                setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                challengeAnswerText = this
            },
            matchWrap().apply { topMargin = dp(context, 12) }
        )

        // ── 反馈区 ──────────────────────────────────
        container.addView(
            TextView(context).apply {
                text = session.feedback
                textSize = 12f
                setLineSpacing(dp(context, 4).toFloat(), 1f)
                setTextColor(
                    if (session.feedbackIsError) Color.parseColor("#EF9A9A")
                    else Color.parseColor("#A5D6A7")
                )
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(if (session.feedbackIsError) 0x18EF5350 else 0x1866BB6A)
                }
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                visibility = if (session.feedback.isBlank()) View.GONE else View.VISIBLE
                challengeFeedbackText = this
            },
            matchWrap().apply { topMargin = dp(context, 10) }
        )

        // ── 自绘键盘 ────────────────────────────────
        val keyboardBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        challengeKeyboardBox = keyboardBox
        container.addView(keyboardBox, matchWrap().apply { topMargin = dp(context, 12) })
        renderKeyboard(context, lockState, session)

        // ── 返回锁机 ────────────────────────────────
        container.addView(
            Button(context).apply {
                text = "返回锁机界面"
                textSize = 13f
                setTextColor(Color.parseColor("#C0B4FF"))
                isAllCaps = false
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(0x334F378B.toInt())
                }
                setPadding(dp(context, 20), dp(context, 9), dp(context, 20), dp(context, 9))
                setOnClickListener {
                    val ctx = lastContext ?: context
                    exitChallengeMode(
                        ctx, lockState,
                        onStartChallenge = lastChallengeCallback ?: {},
                        onRequestPause = lastPauseCallback
                    )
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = dp(context, 14)
            }
        )

        scroll.addView(
            container,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    /** 构建朋友密码验证界面（纯传统 View，与答题界面同构）。 */
    private fun buildFriendUnlockContent(
        context: Context,
        lockState: LockState
    ): View {
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 52), dp(context, 20), dp(context, 20))
        }

        // ── 标题 ──────────────────────────────────────
        container.addView(
            TextView(context).apply {
                text = "朋友辅助解锁"
                textSize = 19f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            },
            matchWrap()
        )

        container.addView(
            TextView(context).apply {
                text = "输入朋友提供的密码即可解锁"
                textSize = 11f
                setTextColor(Color.parseColor("#70FFFFFF"))
                setPadding(0, dp(context, 4), 0, 0)
            },
            matchWrap()
        )

        // ── 密文卡 ──────────────────────────────────
        container.addView(
            TextView(context).apply {
                text = "密文：${lockState.friendCipher}\n偏移量：${lockState.friendShift}"
                textSize = 17f
                setTextColor(Color.parseColor("#D0BCFF"))
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(dp(context, 6).toFloat(), 1f)
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 16).toFloat()
                    setColor(0x144F378B.toInt())
                    setStroke(dp(context, 1), 0x334F378B.toInt())
                }
                setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            },
            matchWrap().apply { topMargin = dp(context, 14) }
        )

        // ── 输入显示区 ──────────────────────────────
        container.addView(
            TextView(context).apply {
                text = if (friendUnlockInput.isEmpty()) "请输入密码" else friendUnlockInput
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(
                    if (friendUnlockInput.isEmpty()) Color.parseColor("#50FFFFFF") else Color.WHITE
                )
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(0xFF1A1622.toInt())
                    setStroke(dp(context, 1), 0xFF4F378B.toInt())
                }
                setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                challengeAnswerText = this
            },
            matchWrap().apply { topMargin = dp(context, 12) }
        )

        // ── 反馈区 ──────────────────────────────────
        container.addView(
            TextView(context).apply {
                text = friendUnlockFeedback
                textSize = 12f
                setTextColor(
                    if (friendUnlockFeedbackIsError) Color.parseColor("#EF9A9A")
                    else Color.parseColor("#A5D6A7")
                )
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(if (friendUnlockFeedbackIsError) 0x18EF5350 else 0x1866BB6A)
                }
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                visibility = if (friendUnlockFeedback.isBlank()) View.GONE else View.VISIBLE
                challengeFeedbackText = this
            },
            matchWrap().apply { topMargin = dp(context, 10) }
        )

        // ── 自绘键盘 ────────────────────────────────
        val keyboardBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        challengeKeyboardBox = keyboardBox
        container.addView(keyboardBox, matchWrap().apply { topMargin = dp(context, 12) })
        renderFriendKeyboard(context, lockState)

        // ── 返回锁机 ────────────────────────────────
        container.addView(
            Button(context).apply {
                text = "返回锁机界面"
                textSize = 13f
                setTextColor(Color.parseColor("#C0B4FF"))
                isAllCaps = false
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(0x334F378B.toInt())
                }
                setPadding(dp(context, 20), dp(context, 9), dp(context, 20), dp(context, 9))
                setOnClickListener {
                    val ctx = lastContext ?: context
                    exitFriendUnlockMode(
                        ctx, lockState,
                        onStartChallenge = lastChallengeCallback ?: {},
                        onRequestPause = lastPauseCallback
                    )
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = dp(context, 14)
            }
        )

        scroll.addView(
            container,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    /** 渲染朋友密码验证的自绘键盘（字母页 / 数字页可切换，默认字母页）。 */
    private fun renderFriendKeyboard(
        context: Context,
        lockState: LockState
    ) {
        val box = challengeKeyboardBox ?: return
        box.removeAllViews()

        val rows: List<List<String>> = if (friendUnlockLetterPage) {
            listOf(
                listOf("A", "B", "C", "D", "E", "F", "G"),
                listOf("H", "I", "J", "K", "L", "M", "N"),
                listOf("O", "P", "Q", "R", "S", "T", "U"),
                listOf("V", "W", "X", "Y", "Z", "⌫")
            )
        } else {
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("-", "0", ".")
            )
        }

        rows.forEach { row ->
            box.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { key ->
                        addView(
                            buildKeyButton(context, key) {
                                when (key) {
                                    "⌫" -> onFriendKeyBackspace()
                                    else -> onFriendKeyInput(key)
                                }
                            },
                            LinearLayout.LayoutParams(
                                0, dp(context, 46), 1f
                            ).apply {
                                marginStart = dp(context, 3)
                                marginEnd = dp(context, 3)
                                topMargin = dp(context, 3)
                                bottomMargin = dp(context, 3)
                            }
                        )
                    }
                },
                matchWrap()
            )
        }

        // 功能键行：ABC/123 切换、清空、退格（数字页）
        box.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    buildKeyButton(
                        context,
                        if (friendUnlockLetterPage) "123" else "ABC"
                    ) {
                        friendUnlockLetterPage = !friendUnlockLetterPage
                        renderFriendKeyboard(context, lockState)
                    },
                    LinearLayout.LayoutParams(0, dp(context, 46), 1f).apply {
                        marginStart = dp(context, 3); marginEnd = dp(context, 3)
                    }
                )
                if (!friendUnlockLetterPage) {
                    addView(
                        buildKeyButton(context, "⌫") { onFriendKeyBackspace() },
                        LinearLayout.LayoutParams(0, dp(context, 46), 1f).apply {
                            marginStart = dp(context, 3); marginEnd = dp(context, 3)
                        }
                    )
                }
                addView(
                    buildKeyButton(context, "清空") {
                        friendUnlockInput = ""
                        refreshFriendAnswerText()
                    },
                    LinearLayout.LayoutParams(0, dp(context, 46), 1f).apply {
                        marginStart = dp(context, 3); marginEnd = dp(context, 3)
                    }
                )
            },
            matchWrap()
        )

        // 提交
        box.addView(
            Button(context).apply {
                text = "验证密码"
                textSize = 15f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(0xFF7C4DFF.toInt())
                }
                setOnClickListener { onSubmitFriendUnlock(context, lockState) }
            },
            LinearLayout.LayoutParams(0, dp(context, 50), 2f).apply {
                marginStart = dp(context, 3)
                marginEnd = dp(context, 3)
                topMargin = dp(context, 8)
            }
        )
    }

    private fun onFriendKeyInput(key: String) {
        if (friendUnlockInput.length >= 24) return
        friendUnlockInput += key
        refreshFriendAnswerText()
    }

    private fun onFriendKeyBackspace() {
        if (friendUnlockInput.isNotEmpty()) {
            friendUnlockInput = friendUnlockInput.dropLast(1)
            refreshFriendAnswerText()
        }
    }

    private fun refreshFriendAnswerText() {
        challengeAnswerText?.let { tv ->
            tv.text = if (friendUnlockInput.isEmpty()) "请输入密码" else friendUnlockInput
            tv.setTextColor(
                if (friendUnlockInput.isEmpty()) Color.parseColor("#50FFFFFF") else Color.WHITE
            )
        }
    }

    /** 提交朋友密码：验证通过 → 回调解锁；失败 → 提示并清空输入。 */
    private fun onSubmitFriendUnlock(
        context: Context,
        lockState: LockState
    ) {
        if (friendUnlockInput.isBlank()) return
        if (lockState.verifyFriendPassword(friendUnlockInput)) {
            Log.d(TAG, "悬浮窗朋友密码验证通过")
            val cb = friendUnlockPassedCallback
            isFriendUnlockMode = false
            isChallengeMode = false
            friendUnlockPassedCallback = null
            friendUnlockInput = ""
            friendUnlockFeedback = ""
            clearChallengeViewRefs()
            cb?.invoke()
        } else {
            friendUnlockFeedback = "密码不正确，请重新输入"
            friendUnlockFeedbackIsError = true
            friendUnlockInput = ""
            challengeFeedbackText?.let { tv ->
                tv.text = friendUnlockFeedback
                tv.visibility = View.VISIBLE
                tv.setTextColor(Color.parseColor("#EF9A9A"))
            }
            refreshFriendAnswerText()
        }
    }

    /**
     * 渲染自绘键盘（数字页 / 字母页可切换）。
     *
     * 为什么自绘而不用系统输入法：非 Activity 窗口挂 IME 在部分 ROM
     * （尤其华为）上不稳定，历史上导致过"打开输入法就闪退"。自绘键盘
     * 零 IME 依赖，彻底规避该类问题。
     */
    private fun renderKeyboard(
        context: Context,
        lockState: LockState,
        session: ChallengeSession
    ) {
        val box = challengeKeyboardBox ?: return
        box.removeAllViews()

        val rows: List<List<String>> = if (challengeLetterPage) {
            listOf(
                listOf("A", "B", "C", "D", "E", "F", "G"),
                listOf("H", "I", "J", "K", "L", "M", "N"),
                listOf("O", "P", "Q", "R", "S", "T", "U"),
                listOf("V", "W", "X", "Y", "Z", "⌫")
            )
        } else {
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("-", "0", ".")
            )
        }

        rows.forEach { row ->
            box.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { key ->
                        addView(
                            buildKeyButton(context, key) {
                                when (key) {
                                    "⌫" -> onKeyBackspace(session)
                                    else -> onKeyInput(session, key)
                                }
                            },
                            LinearLayout.LayoutParams(
                                0, dp(context, 46), 1f
                            ).apply {
                                marginStart = dp(context, 3)
                                marginEnd = dp(context, 3)
                                topMargin = dp(context, 3)
                                bottomMargin = dp(context, 3)
                            }
                        )
                    }
                },
                matchWrap()
            )
        }

        // 功能键行：ABC/123 切换、清空、退格（数字页）、提交
        box.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    buildKeyButton(
                        context,
                        if (challengeLetterPage) "123" else "ABC"
                    ) {
                        challengeLetterPage = !challengeLetterPage
                        renderKeyboard(context, lockState, session)
                    },
                    LinearLayout.LayoutParams(0, dp(context, 46), 1f).apply {
                        marginStart = dp(context, 3); marginEnd = dp(context, 3)
                    }
                )
                if (!challengeLetterPage) {
                    addView(
                        buildKeyButton(context, "⌫") { onKeyBackspace(session) },
                        LinearLayout.LayoutParams(0, dp(context, 46), 1f).apply {
                            marginStart = dp(context, 3); marginEnd = dp(context, 3)
                        }
                    )
                }
                addView(
                    buildKeyButton(context, "清空") {
                        if (!session.switching) {
                            session.input = ""
                            refreshAnswerText(session)
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(context, 46), 1f).apply {
                        marginStart = dp(context, 3); marginEnd = dp(context, 3)
                    }
                )
            },
            matchWrap()
        )

        // 提交 + 换一题
        box.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    Button(context).apply {
                        text = "提交答案"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        isAllCaps = false
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 12).toFloat()
                            setColor(0xFF7C4DFF.toInt())
                        }
                        setOnClickListener { onSubmitAnswer(context, lockState, session) }
                    },
                    LinearLayout.LayoutParams(0, dp(context, 50), 1.6f).apply {
                        marginStart = dp(context, 3)
                        marginEnd = dp(context, 3)
                        topMargin = dp(context, 8)
                    }
                )
                addView(
                    Button(context).apply {
                        val used = lockState.challengeRefreshCount
                        val exhausted = used >= 5
                        text = if (exhausted) "换题已满(5/5)" else "换一题($used/5)"
                        textSize = 12f
                        isAllCaps = false
                        isEnabled = !exhausted
                        setTextColor(
                            if (exhausted) Color.parseColor("#50FFFFFF")
                            else Color.parseColor("#C0B4FF")
                        )
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 12).toFloat()
                            setColor(if (exhausted) 0x22FFFFFF else 0x334F378B.toInt())
                        }
                        challengeRefreshButton = this
                        setOnClickListener {
                            if (session.switching) return@setOnClickListener
                            if (lockState.challengeRefreshCount >= 5) return@setOnClickListener
                            lockState.recordChallengeRefresh()
                            nextQuestion(context, lockState, session)
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(context, 50), 1f).apply {
                        marginStart = dp(context, 3)
                        marginEnd = dp(context, 3)
                        topMargin = dp(context, 8)
                    }
                )
            },
            matchWrap()
        )
    }

    /** 构建一个键盘按键。 */
    private fun buildKeyButton(
        context: Context,
        label: String,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        textSize = if (label.length > 1) 13f else 17f
        setTextColor(Color.WHITE)
        isAllCaps = false
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 10).toFloat()
            setColor(0xFF2E2740.toInt())
        }
        setOnClickListener {
            try {
                onClick()
            } catch (e: Exception) {
                Log.w(TAG, "键盘按键异常：${e.message}")
            }
        }
    }

    private fun onKeyInput(session: ChallengeSession, key: String) {
        if (session.switching) return
        if (session.input.length >= 24) return
        session.input += key
        refreshAnswerText(session)
    }

    private fun onKeyBackspace(session: ChallengeSession) {
        if (session.switching) return
        if (session.input.isNotEmpty()) {
            session.input = session.input.dropLast(1)
            refreshAnswerText(session)
        }
    }

    private fun refreshAnswerText(session: ChallengeSession) {
        challengeAnswerText?.let { tv ->
            tv.text = session.input.ifEmpty { "请输入答案" }
            tv.setTextColor(
                if (session.input.isEmpty()) Color.parseColor("#50FFFFFF") else Color.WHITE
            )
        }
    }

    /** 提交答案：判分 → 通过则回调，否则显示解析并自动换题。 */
    private fun onSubmitAnswer(
        context: Context,
        lockState: LockState,
        session: ChallengeSession
    ) {
        if (session.switching) return
        if (session.input.isBlank()) return

        val correct = challengeGenerator.isAnswerCorrect(session.input, session.question.answer)
        if (correct) {
            session.correctCount += 1
            challengeProgressText?.text = "${session.correctCount} / ${session.requiredCorrect}"
            if (session.correctCount >= session.requiredCorrect) {
                Log.d(TAG, "悬浮窗答题通过（forPause=${session.forPause}）")
                val cb = challengePassedCallback
                val forPause = session.forPause
                isChallengeMode = false
                challengeSession = null
                challengePassedCallback = null
                clearChallengeViewRefs()
                cb?.invoke(forPause)
                return
            }
            showFeedback(
                session,
                "回答正确！还需 ${session.requiredCorrect - session.correctCount} 题",
                isError = false
            )
            session.switching = true
            uiHandler.postDelayed({
                session.switching = false
                nextQuestion(context, lockState, session)
            }, 900L)
        } else {
            val msg = buildString {
                append("回答错误。正确答案：${session.question.answer}")
                if (session.question.explanation.isNotBlank()) {
                    append("\n解析：${session.question.explanation}")
                }
            }
            showFeedback(session, msg, isError = true)
            session.switching = true
            uiHandler.postDelayed({
                session.switching = false
                nextQuestion(context, lockState, session)
            }, 2800L)
        }
    }

    private fun showFeedback(session: ChallengeSession, msg: String, isError: Boolean) {
        session.feedback = msg
        session.feedbackIsError = isError
        challengeFeedbackText?.let { tv ->
            tv.text = msg
            tv.visibility = View.VISIBLE
            tv.setTextColor(
                if (isError) Color.parseColor("#EF9A9A") else Color.parseColor("#A5D6A7")
            )
        }
    }

    /** 换下一题（不清空进度）。 */
    private fun nextQuestion(
        context: Context,
        lockState: LockState,
        session: ChallengeSession
    ) {
        session.question = challengeGenerator.generate(
            difficulty = if (session.requiredCorrect >= 3) 3 else 2,
            numericOnly = true
        )
        session.input = ""
        session.feedback = ""
        session.feedbackIsError = false
        challengeQuestionText?.text = session.question.question
        challengeFeedbackText?.visibility = View.GONE
        refreshAnswerText(session)
        // 刷新换题按钮状态（次数可能已达上限）
        challengeRefreshButton?.let { btn ->
            val used = lockState.challengeRefreshCount
            val exhausted = used >= 5
            btn.text = if (exhausted) "换题已满(5/5)" else "换一题($used/5)"
            btn.isEnabled = !exhausted
            btn.setTextColor(
                if (exhausted) Color.parseColor("#50FFFFFF") else Color.parseColor("#C0B4FF")
            )
        }
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
        wallClockText = null
        wallDateText = null
        isShowing = false
        // 注意：isFriendUnlockMode / friendUnlock* 故意**不清空**——窗口被 ROM
        // 回收后 verifyAttached → show → attachWindow 靠它们恢复朋友验证界面
        // 与输入进度（与 challengeSession 的生存语义一致）；正常退出路径
        // （exitFriendUnlockMode / 验证通过）已在复位标志后才进入这里。
        clearChallengeViewRefs()
    }
}
