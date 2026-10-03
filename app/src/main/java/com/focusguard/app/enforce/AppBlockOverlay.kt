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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
 * 2. **停掉**：显示封锁的同时挂起被锁应用（[com.focusguard.app.enhance.LockPolicies.suspendBlock]）
 *    —— 小窗/画中画/分屏/后台声音一次全停。挂起后桌面会到前台，但**悬浮窗仍在最上层**，
 *    用户看到的是封锁页而不是桌面，所以不会再有「强制退出」的观感；
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
    private var appContext: Context? = null
    private var currentPkg: String? = null
    // 界面读取的值用 Compose 状态包装：数值变化时悬浮窗内会自动重绘
    private val labelState = androidx.compose.runtime.mutableStateOf("")
    private val untilState = androidx.compose.runtime.mutableStateOf(0L)
    private val usedState = androidx.compose.runtime.mutableIntStateOf(0)
    private val limitState = androidx.compose.runtime.mutableIntStateOf(0)
    private var checkRunnable: Runnable? = null

    /** 悬浮窗里 Compose 的 owner（移除窗口时销毁，避免泄漏）。 */
    private var composeOwner: OverlayComposeOwner? = null

    /** [pkg] 是否是桌面（HOME）应用。 */
    private fun isHomePackage(context: Context, pkg: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName == pkg
    }.getOrDefault(false)

    /** 本次封锁是否已把应用真正停掉（停掉后不走「离开就撤下」逻辑）。 */
    @Volatile
    private var appSuspended = false

    /** 本次封锁开始显示的时刻（用于区分「冻结导致桌面露出」与「用户主动回桌面」）。 */
    @Volatile
    private var shownAt = 0L

    /** 连续判定「用户已离开应用」的次数（防抖：单次采样不算）。 */
    private var leftStrikes = 0

    /** 最近一次失败原因（设置页/日志排查用）。 */
    @Volatile
    var lastError: String = ""
        private set

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
        if (!android.provider.Settings.canDrawOverlays(app)) {
            lastError = "没有悬浮窗权限"
            return false
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 主线程：直接给出真实结果，调用方据此决定是否退回 Activity
            return showOnMain(app, pkg, label, usedMinutes, limitMinutes, blockUntil)
        }
        uiHandler.post {
            if (!showOnMain(app, pkg, label, usedMinutes, limitMinutes, blockUntil)) {
                // 后台线程无法把失败传回去：这里自己拉起封锁页兜底，绝不静默什么都不做
                Log.w(TAG, "悬浮窗遮盖失败（${lastError}），改用封锁页兜底")
                runCatching {
                    AppBlockActivity.show(
                        context = app,
                        packageName = pkg,
                        appLabel = label,
                        usedMinutes = usedMinutes,
                        limitMinutes = limitMinutes,
                        blockUntil = blockUntil
                    )
                }
            }
        }
        return true
    }

    /** 撤下悬浮窗（答题解封 / 封锁到期 / 锁机接管时调用，会解除应用的挂起）。 */
    fun hide() {
        uiHandler.post { hideOnMain(stopApp = false) }
    }

    private fun showOnMain(
        context: Context,
        pkg: String,
        label: String,
        usedMinutes: Int,
        limitMinutes: Int,
        blockUntil: Long
    ): Boolean {
        appContext = context
        currentPkg = pkg
        labelState.value = label
        untilState.value = blockUntil
        usedState.value = usedMinutes
        limitState.value = limitMinutes
        // 冻结 + 覆盖**同时做**（用户要求的组合）：
        // 挂起会让系统把前台应用停掉、桌面到前台，但我们的悬浮窗始终在最上层，
        // 用户看到的是封锁页而不是桌面 —— 既不会有「强制退出」的感觉，
        // 又一次性结束小窗/画中画/分屏/后台声音。
        Thread {
            val suspended = runCatching {
                com.focusguard.app.enhance.LockPolicies.suspendBlock(context, pkg)
            }.getOrDefault(false)
            appSuspended = suspended
            if (!suspended && com.focusguard.app.data.Settings(context).forceStopUnlocked) {
                // 没有 Shizuku/Dhizuku（非技术用户）：用系统「强行停止」把应用真停掉，
                // 同样是在封锁悬浮窗后面完成，用户只看到封锁页
                runCatching { com.focusguard.app.enforce.ForceStopHelper.requestStop(context, pkg) }
            }
        }.start()

        val existing = root
        if (existing != null && showing == pkg) {
            startSelfCheck()
            return true
        }
        removeViewInternal()
        val view = try {
            buildContent(context, pkg)
        } catch (e: Exception) {
            lastError = "构建失败：${e.message}"
            Log.e(TAG, lastError, e)
            return false
        }
        try {
            windowManager(context).addView(view, buildLayoutParams(context))
        } catch (e: Exception) {
            lastError = "挂载失败：${e.message}"
            Log.w(TAG, lastError)
            return false
        }
        root = view
        showing = pkg
        shownAt = System.currentTimeMillis()
        leftStrikes = 0
        lastError = ""
        startSelfCheck()
        Log.d(TAG, "已用悬浮窗遮盖 $pkg（$label）")
        return true
    }

    private fun hideOnMain(stopApp: Boolean = false) {
        val pkg = currentPkg
        removeViewInternal()
        stopSelfCheck()
        val app = appContext
        if (pkg != null && app != null) {
            Thread {
                runCatching {
                    if (stopApp) {
                        // 用户离开被锁应用：真正停掉它（后台声音、画中画、分屏随之结束）
                        com.focusguard.app.enhance.LockPolicies.suspendBlock(app, pkg)
                    } else {
                        com.focusguard.app.enhance.LockPolicies.releaseBlock(app, pkg)
                    }
                }
            }.start()
        }
        Log.d(TAG, "封锁悬浮窗已撤下（保留挂起=$stopApp）")
    }

    private fun removeViewInternal() {
        val view = root ?: return
        try {
            windowManager(view.context).removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "移除封锁悬浮窗失败：${e.message}")
        }
        root = null
        showing = null
        appSuspended = false
        currentPkg = null
        composeOwner?.destroy()
        composeOwner = null
    }

    // ── 自查：每秒核对封锁条件与用户位置 ─────────────────────

    private fun startSelfCheck() {
        if (checkRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                val app = appContext
                val pkg = currentPkg
                if (app == null || pkg == null) {
                    hideOnMain(stopApp = false)
                    return
                }
                val lockActive = runCatching {
                    val state = com.focusguard.app.data.LockState(app)
                    state.isLocked && state.shouldBlockNow
                }.getOrDefault(false)
                if (lockActive || !isStillBlocked(app, pkg)) {
                    // 锁机接管 / 封锁到期 / 已答题解封：撤下并解除挂起
                    Log.d(TAG, "封锁条件变化，撤下：$pkg（锁机中=$lockActive）")
                    hideOnMain(stopApp = false)
                    return
                }
                // 小窗/分屏/画中画会压在我们的悬浮窗之上：这种情况下直接停掉该应用
                // （无障碍事件不一定会再来，所以每秒自查里也要看一次）
                val small = runCatching {
                    com.focusguard.app.access.GuardAccessibilityService.instance
                        ?.isSmallWindowForBlocked(pkg)
                }.getOrNull() ?: false
                if (small) {
                    Log.d(TAG, "被锁应用 $pkg 处于小窗/分屏/画中画，停掉它")
                    Thread {
                        runCatching {
                            com.focusguard.app.enhance.LockPolicies.suspendBlock(app, pkg)
                        }
                    }.start()
                }
                // 用户离开的处理：
                // · 应用**没被**停掉（无 Shizuku/Dhizuku）→ 立刻撤下并顺手停掉它；
                // · 应用**已被**停掉 → 前 3 秒内的「前台变成桌面」是冻结造成的（应用被
                //   系统掐掉、桌面露出来），**不能**据此撤下（否则又变成「打开就被踢出」）；
                //   3 秒之后仍判定离开，说明是用户主动回桌面（系统手势）→ 撤下并解冻。
                val left = hasLeftApp(app, pkg)
                val settled = System.currentTimeMillis() - shownAt > 3_000L
                if (left && (!appSuspended || settled)) {
                    Log.d(TAG, "用户已离开 $pkg（已停掉=$appSuspended），撤下并解冻")
                    hideOnMain(stopApp = false)
                    return
                }
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

    /**
     * 用户是否已经离开被锁应用（悬浮窗不产生 Activity 事件，因此这个判据很干净）。
     *
     * 注意两个坑：
     * ① 刚点开图标那一刻，事件流可能还是**启动器/系统界面**（SYSTEM 类）——
     *    那种「离开」是假象，会把刚显示的悬浮窗立刻撤掉（用户反馈的「点了却没出现封锁页」）；
     * ② 单次采样可能抖动，要求**连续两次**都判定离开才撤。
     */
    private fun hasLeftApp(context: Context, pkg: String): Boolean {
        // 实时活动窗口优先：悬浮窗盖着的时候它就是我们的窗口（说明还在被锁应用之上），
        // 事件流（UsageStats）有 1~2 秒延迟，只看它会把「刚打开」误判成「已回桌面」。
        val live = com.focusguard.app.access.GuardAccessibilityService.instance
            ?.liveWindowPackage()
        if (live == pkg || live == context.packageName) {
            leftStrikes = 0
            return false
        }
        val fg = com.focusguard.app.service.ForegroundAppDetector.current(context) ?: return false
        if (fg == pkg || fg == context.packageName) {
            leftStrikes = 0
            return false
        }
        // 两个来源都指向桌面：明确「已离开」，立即撤下（否则悬浮窗会盖在桌面上）
        if (isHomePackage(context, live ?: fg)) return true
        val category = runCatching {
            com.focusguard.app.detection.AppClassifier.classifyPackage(
                context, fg, com.focusguard.app.detection.AppCategoryStore.shared(context)
            )?.category
        }.getOrNull()
        if (category == null || category == com.focusguard.app.detection.AppCategory.SYSTEM) {
            // 认不出 / 系统界面（启动器、系统设置外壳）→ 不作为「已离开」的证据
            leftStrikes = 0
            return false
        }
        leftStrikes++
        return leftStrikes >= 2
    }

    // ── 界面 ─────────────────────────────────────────────

    private fun buildContent(context: Context, pkg: String): View {
        // 复用与封锁 Activity 完全相同的 Compose 界面（含内嵌答题）：
        // 1) 视觉与锁机页一致，不再是手写的简陋 View；
        // 2) 点「答题解封」不用再跳 Activity，全程留在悬浮窗里。
        val view = androidx.compose.ui.platform.ComposeView(context)
        attachOwners(view)
        view.setContent {
            com.focusguard.app.ui.theme.FocusGuardTheme(
                themeMode = com.focusguard.app.ui.theme.ThemeState.mode,
                accentOverride = com.focusguard.app.ui.theme.ThemeState.accent
            ) {
                AppBlockContent(
                    appLabel = labelState.value,
                    usedMinutes = usedState.value,
                    limitMinutes = limitState.value,
                    blockUntil = untilState.value,
                    onUnlocked = {
                        val app = appContext ?: return@AppBlockContent
                        Thread {
                            runCatching {
                                AppLockToolExecutor.grantExtraTime(
                                    app, pkg, com.focusguard.app.data.Settings(app).appBlockMinutes
                                )
                            }
                        }.start()
                        hide()
                    },
                    onGoHome = {
                        val app = appContext ?: return@AppBlockContent
                        // 必须先撤下悬浮窗：它是最上层窗口，光把桌面拉到前台是看不见的
                        // （用户反馈「点返回桌面没反应、一直卡在封锁页」）
                        hide()
                        val home = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        runCatching { app.startActivity(home) }
                    }
                )
            }
        }
        return view
    }

    /** 悬浮窗里的 ComposeView 不在 Activity 里，必须自己挂上三个 owner 才能渲染。 */
    private fun attachOwners(view: androidx.compose.ui.platform.ComposeView) {
        val owner = OverlayComposeOwner()
        owner.start()
        composeOwner = owner
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
    }

    /** Compose 在悬浮窗里需要的 Lifecycle / SavedState / ViewModelStore 三件套。 */
    private class OverlayComposeOwner : androidx.lifecycle.LifecycleOwner,
        androidx.savedstate.SavedStateRegistryOwner,
        androidx.lifecycle.ViewModelStoreOwner {

        private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
        private val savedStateController = androidx.savedstate.SavedStateRegistryController.create(this)

        override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
            get() = savedStateController.savedStateRegistry
        override val viewModelStore = androidx.lifecycle.ViewModelStore()

        fun start() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }

        fun destroy() {
            lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
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
