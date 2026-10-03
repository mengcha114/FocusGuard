package com.focusguard.app.enforce

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 应用封锁的**悬浮窗**遮盖（传统 View，毫秒级）。
 *
 * 为什么不继续用 Activity：Activity 会被上滑手势销毁、被最近任务划掉、被小窗压住，
 * 而且冷启动要几百毫秒到几秒 —— 用户看到的是「被强制退出，过一会儿才出来一个页面」。
 * TYPE_APPLICATION_OVERLAY 不属于任何 Task，手势动不了它，addView 立刻可见。
 *
 * ## 三重保险（用户要求：计时结束前没有任何使用/查看/操作的机会）
 * 1. **盖住**：全屏悬浮窗（含状态栏区域），focusable 吞掉返回键；
 * 2. **停掉**：通知 [com.focusguard.app.enhance.LockPolicies.suspendBlock] 真正挂起被锁应用
 *    （后台播放/画中画/分屏随之中止）；工具不可用时降级为只盖住；
 * 3. **堵旁路**：自己每秒自查——封锁条件不再成立、用户已离开该应用、或锁机开始，都会自动撤下；
 *    通知栏与画中画由守护/无障碍侧配合处理（见 GuardAccessibilityService）。
 *
 * ## 关键判据
 * 悬浮窗不是 Activity，**不会产生 ACTIVITY_RESUMED 事件**，所以
 * [com.focusguard.app.service.ForegroundAppDetector.current] 会持续报告底下那个被锁应用；
 * 用户一旦按 Home 或切走，事件流变成桌面/别的应用 → 自查据此撤下悬浮窗
 * （这也是「悬浮窗盖到桌面上」这个历史毛病不会再出现的原因）。
 */
object AppBlockOverlay {

    private const val TAG = "AppBlockOverlay"
    private const val CHECK_INTERVAL_MS = 1_000L

    private val uiHandler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var infoText: TextView? = null
    private var countdownText: TextView? = null
    private var appContext: Context? = null
    private var currentPkg: String? = null
    private var currentLabel: String = ""
    private var currentUntil: Long = 0L
    private var currentUsed: Int = 0
    private var currentLimit: Int = 0
    private var checkRunnable: Runnable? = null

    @Volatile
    private var showing: String? = null

    fun isShowing(): Boolean = showing != null

    /** 当前正在被悬浮窗遮盖的包名（没有则为 null）。 */
    fun showingPackage(): String? = showing

    /**
     * 用悬浮窗遮盖被锁应用。任意线程可调用。
     *
     * @return false 表示没有悬浮窗权限或构建失败，调用方应退回 Activity 方案（HOME + 封锁页）
     */
    fun show(
        context: Context,
        pkg: String,
        label: String,
        usedMinutes: Int,
        limitMinutes: Int,
        blockUntil: Long
    ): Boolean {
        val app = context.applicationContext
        if (pkg.isBlank()) return false
        if (!android.provider.Settings.canDrawOverlays(app)) return false
        uiHandler.post {
            showOnMain(app, pkg, label, usedMinutes, limitMinutes, blockUntil)
        }
        return true
    }

    /** 撤下悬浮窗（封锁解除时调用）。 */
    fun hide() {
        uiHandler.post { hideOnMain() }
    }

    private fun showOnMain(
        context: Context,
        pkg: String,
        label: String,
        usedMinutes: Int,
        limitMinutes: Int,
        blockUntil: Long
    ) {
        appContext = context
        currentPkg = pkg
        currentLabel = label
        currentUntil = blockUntil
        currentUsed = usedMinutes
        currentLimit = limitMinutes
        // 真正停掉被锁应用（后台声音/画中画/分屏随之结束）；工具不可用时只是盖住
        Thread { runCatching { com.focusguard.app.enhance.LockPolicies.suspendBlock(context, pkg) } }.start()

        val existing = root
        if (existing != null && showing == pkg) {
            refreshTexts()
            startSelfCheck()
            return
        }
        removeViewInternal()
        val view = try {
            buildContent(context)
        } catch (e: Exception) {
            Log.e(TAG, "构建封锁悬浮窗失败：${e.message}", e)
            return
        }
        try {
            windowManager(context).addView(view, buildLayoutParams(context))
        } catch (e: Exception) {
            Log.w(TAG, "挂载封锁悬浮窗失败：${e.message}")
            return
        }
        root = view
        showing = pkg
        refreshTexts()
        startSelfCheck()
        Log.d(TAG, "已用悬浮窗遮盖 $pkg（$label）")
    }

    private fun hideOnMain() {
        val pkg = currentPkg
        removeViewInternal()
        stopSelfCheck()
        if (pkg != null) {
            val app = appContext
            if (app != null) {
                Thread { runCatching { com.focusguard.app.enhance.LockPolicies.releaseBlock(app, pkg) } }.start()
            }
        }
        Log.d(TAG, "封锁悬浮窗已撤下")
    }

    private fun removeViewInternal() {
        val view = root ?: return
        try {
            windowManager(view.context).removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "移除封锁悬浮窗失败：${e.message}")
        }
        root = null
        infoText = null
        countdownText = null
        showing = null
        currentPkg = null
    }

    // ── 自查：每秒核对封锁条件与用户位置 ─────────────────────

    private fun startSelfCheck() {
        if (checkRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                val app = appContext
                val pkg = currentPkg
                if (app == null || pkg == null) {
                    hideOnMain()
                    return
                }
                val lockActive = runCatching {
                    val state = com.focusguard.app.data.LockState(app)
                    state.isLocked && state.shouldBlockNow
                }.getOrDefault(false)
                if (lockActive || !isStillBlocked(app, pkg) || hasLeftApp(app, pkg)) {
                    Log.d(TAG, "封锁条件变化，撤下：$pkg（锁机中=$lockActive）")
                    hideOnMain()
                    return
                }
                refreshTexts()
                uiHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        checkRunnable = runnable
        uiHandler.postDelayed(runnable, CHECK_INTERVAL_MS)
    }

    private fun stopSelfCheck() {
        checkRunnable?.let { uiHandler.removeCallbacks(it) }
        checkRunnable = null
    }

    /** 封锁是否仍然成立（临时封锁未到期 / 今日用量仍超上限）。 */
    private fun isStillBlocked(context: Context, pkg: String): Boolean {
        if (com.focusguard.app.data.AppBlockStore(context).blockedUntil(pkg) > 0L) return true
        val store = com.focusguard.app.usage.UsageRuleStore.shared(context)
        val limit = store.getRule(pkg)?.hardBlockMinutes ?: return false
        return (store.getTodaySeconds(pkg) / 60).toInt() >= limit
    }

    /** 用户是否已经离开被锁应用（悬浮窗不产生 Activity 事件，因此这个判据很干净）。 */
    private fun hasLeftApp(context: Context, pkg: String): Boolean {
        val fg = com.focusguard.app.service.ForegroundAppDetector.current(context) ?: return false
        return fg != pkg && fg != context.packageName
    }

    // ── 界面 ─────────────────────────────────────────────

    private fun refreshTexts() {
        val until = currentUntil
        if (until > 0L) {
            val left = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            countdownText?.text = "还剩 %d:%02d".format(left / 60, left % 60)
            infoText?.text = "「$currentLabel」已被锁定，时间到自动解除"
        } else {
            countdownText?.text = "今日已用 $currentUsed 分钟 / 上限 $currentLimit 分钟"
            infoText?.text = "「$currentLabel」今日已达使用上限，明天 0 点自动重置"
        }
    }

    private fun buildContent(context: Context): View {
        val palette = com.focusguard.app.ui.theme.FocusColors.paletteForLockScreen(
            com.focusguard.app.data.Settings(context).themeMode,
            context
        )
        // palette 里的字段是 Compose Color，FocusColors.hex(...) 转成 #RRGGBB 字符串，
        // 再交给 android.graphics.Color.parseColor 得到传统 View 用的颜色
        fun color(value: androidx.compose.ui.graphics.Color) =
            Color.parseColor(com.focusguard.app.ui.theme.FocusColors.hex(value))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 28), dp(context, 28), dp(context, 28), dp(context, 28))
            setBackgroundColor(color(palette.bg))
            isFocusableInTouchMode = true
            isFocusable = true
            // 吞掉返回键：悬浮窗不属于任何 Task，返回键到这里就结束
            setOnKeyListener { _, keyCode, event ->
                event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE ||
                        keyCode == KeyEvent.KEYCODE_MENU)
            }
        }

        column.addView(
            TextView(context).apply {
                text = if (currentUntil > 0L) "🔒 应用已锁定" else "已达使用上限"
                textSize = 24f
                setTextColor(color(palette.text))
                gravity = Gravity.CENTER
            },
            wrap()
        )
        column.addView(
            TextView(context).apply {
                text = currentLabel
                textSize = 17f
                setTextColor(color(palette.haze))
                gravity = Gravity.CENTER
            },
            wrap().apply { topMargin = dp(context, 10) }
        )
        column.addView(
            TextView(context).apply {
                textSize = 30f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(color(palette.accent))
                gravity = Gravity.CENTER
                countdownText = this
            },
            wrap().apply { topMargin = dp(context, 18) }
        )
        column.addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = if (currentLimit > 0) {
                    (currentUsed * 100 / currentLimit).coerceIn(0, 100)
                } else {
                    100
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 6)
            ).apply { topMargin = dp(context, 14) }
        )
        column.addView(
            TextView(context).apply {
                textSize = 12f
                setTextColor(color(palette.haze))
                gravity = Gravity.CENTER
                infoText = this
            },
            wrap().apply { topMargin = dp(context, 12) }
        )

        column.addView(
            Button(context).apply {
                text = "答题解封（答对可再用一会）"
                textSize = 15f
                setBackgroundColor(color(palette.accent))
                setTextColor(color(palette.bg))
                setOnClickListener {
                    val app = appContext ?: return@setOnClickListener
                    val pkg = currentPkg ?: return@setOnClickListener
                    // 先撤下悬浮窗，再打开答题页（那里有现成的本地题库与判分）
                    val label = currentLabel
                    hideOnMain()
                    AppBlockActivity.show(
                        context = app,
                        packageName = pkg,
                        appLabel = label,
                        usedMinutes = currentUsed,
                        limitMinutes = currentLimit,
                        blockUntil = currentUntil
                    )
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 52)
            ).apply { topMargin = dp(context, 26) }
        )
        column.addView(
            Button(context).apply {
                text = "返回桌面"
                textSize = 15f
                setOnClickListener {
                    val app = appContext ?: return@setOnClickListener
                    val home = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    runCatching { app.startActivity(home) }
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 48)
            ).apply { topMargin = dp(context, 10) }
        )
        return column
    }

    private fun buildLayoutParams(context: Context): WindowManager.LayoutParams {
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
            // 与锁机悬浮窗同样的取向：focusable 才能吞返回键；铺满状态栏/刘海区域；
            // WATCH_OUTSIDE_TOUCH 捕获窗口外触摸（下拉通知栏的起手动作）
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun windowManager(context: Context): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun wrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
