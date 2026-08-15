package com.focusguard.app.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.focusguard.app.data.LockState
import com.focusguard.app.enforce.LockScreenActivity
import com.focusguard.app.enforce.UnlockChallengeActivity
import com.focusguard.app.service.LockGuardService

/**
 * 无障碍服务。
 *
 * 定位调整（重要）：无障碍**不再是锁机的唯一防线**。
 * 各家 ROM 会随时回收无障碍服务，因此锁机的主防线已移到
 * [LockGuardService]（前台服务 + 使用情况权限轮询）。
 * 本服务现在只是"加速器"：存在时反应更快（事件驱动 vs 700ms 轮询），
 * 不存在时锁机依然有效。
 *
 * 职责：
 * 1. 锁机拦截（事件驱动，比轮询快）
 * 2. 屏蔽通知栏 / 最近任务 / 语音助手 / 分屏小窗
 * 3. 强制退出（执法模式 EXIT）
 * 4. 服务连接时确保 [LockGuardService] 在跑（互为保活）
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GuardAccessibility"

        @Volatile
        var instance: GuardAccessibilityService? = null
            private set

        /**
         * 锁机时需要拦截的系统界面包名。
         * 各厂商不一致，能匹配的就匹配；匹配不到时还有"非自身窗口即顶回"的兜底。
         */
        private val blockedSystemPackages = listOf(
            "com.android.systemui",                      // 通知栏 / 最近任务 / 状态栏
            "com.google.android.googlequicksearchbox",   // Google 语音助手
            "com.google.android.apps.googlequicksearchbox",
            "com.xiaomi.voiceassist",                    // 小米语音助手
            "com.miui.voiceassist",
            "com.miui.systemui",
            "com.oplus.screenrecorder",                  // OPPO 系
            "com.coloros.assistantscreen",
            "com.vivo.assistant",                        // vivo 语音助手
            "com.huawei.vassistant",                     // 华为语音助手
            "com.huawei.systemmanager",
            "com.hihonor.assistant",                     // 荣耀
            // ── 侧滑破解：华为/荣耀智慧多窗侧边栏（屏幕边缘滑出的应用栏） ──
            "com.huawei.smartwindow",                    // 华为智慧多窗
            "com.hihonor.smartwindow",                   // 荣耀智慧多窗
            "com.huawei.android.launcher.smartwindow",
            "com.hihonor.systemmanager"                  // 荣耀系统管家（侧边栏宿主）
        )

        /** 语音助手包名（锁机中常驻球/助手 UI 一律顶回，即使挂在 systemui 进程下）。 */
        private val voiceAssistantPackages = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googlequicksearchbox",
            "com.xiaomi.voiceassist",
            "com.miui.voiceassist",
            "com.vivo.assistant",
            "com.huawei.vassistant",
            "com.hihonor.assistant"
        )

        /** 命中即"侧边栏/悬浮类"系统界面：不止收起，还要顶回锁机页。 */
        private fun isSideBarPackage(pkg: String): Boolean =
            pkg.contains("smartwindow", ignoreCase = true) ||
                pkg.contains("side", ignoreCase = true) ||
                pkg == "com.huawei.systemmanager" ||
                pkg == "com.hihonor.systemmanager"
    }

    private var lockState: LockState? = null

    /** 窗口状态事件顶回节流（独立变量，避免与高频窗口列表事件互相吞）。 */
    private var lastStateReassertAt = 0L

    /** 窗口列表事件顶回节流。 */
    private var lastWindowsReassertAt = 0L

    /** 让位期（答题/朋友验证）语音助手顶回节流。 */
    private var lastYieldReassertAt = 0L

    /** 收起通知栏节流：SystemUI 对高频全局动作限流，250ms 一次最稳。 */
    private var lastDismissAt = 0L

    /** 状态栏物理拦截条（TYPE_ACCESSIBILITY_OVERLAY）：吃掉下拉起始手势。 */
    private var statusBarBlock: android.view.View? = null

    /** 动态手势阻断窗：触摸开始时弹出，打断进行中的下拉手势（第三方锁机同机制）。 */
    private var gestureBlocker: android.view.View? = null
    private var gestureBlockerTeardown: java.lang.Runnable? = null
    private var lastBlockerAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lockState = LockState(this)

        // 需要窗口内容信息才能识别分屏/小窗窗口
        try {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        } catch (e: Exception) {
            Log.w(TAG, "设置无障碍 flags 失败：${e.message}")
        }

        // 无障碍与前台守护互为保活：任一存活就把对方拉起来
        try {
            if (!LockGuardService.isRunning) {
                LockGuardService.start(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "拉起锁机守护失败：${e.message}")
        }

        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val state = lockState ?: return
        if (!state.isLocked) return

        // 番茄钟休息阶段 / 暂停中 放行
        if (!state.shouldBlockNow) return

        // 状态栏拦截条随锁机状态挂载/移除（拦截下拉起始手势的物理屏障）
        ensureStatusBarBlock()

        // 无论何种事件、无论是否在答题界面，只要下拉了系统通知栏/控制中心，一律强制收起
        val pkgName = event.packageName?.toString() ?: ""
        if (pkgName == "com.android.systemui" || pkgName in blockedSystemPackages) {
            dismissNotificationShade()
        }

        // ── 答题/验证让位期间的唯一例外：语音助手/侧边栏仍强制拦截 ──
        // （用户报告：答题页面语音助手拦不下）。HOME 不影响悬浮窗答题 UI；
        // Activity 答题页被压后台后由守护拉回锁机界面，拦截优先于答题进度。
        if (pkgName in voiceAssistantPackages || isSideBarPackage(pkgName)) {
            val now = System.currentTimeMillis()
            if (now - lastYieldReassertAt >= 300L) {
                lastYieldReassertAt = now
                Log.d(TAG, "让位期间检测到语音助手/侧边栏 $pkgName，仍执行顶回")
                try {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    LockScreenActivity.reassert(this)
                } catch (e: Exception) {
                    Log.w(TAG, "让位期顶回失败：${e.message}")
                }
            }
            return
        }

        // ── 答题页前台：精准放行（防破解加固） ──────────
        // 答题页正常交互（输入框/输入法）必须放行——悬浮窗在此阶段让位，
        // 只放行答题交互本身，通知栏/侧边栏/分屏依然强制拦截。
        if (UnlockChallengeActivity.active) {
            // 页面切换时窗口列表会短暂同时包含锁机页与答题页。通用窗口检查
            // 会将两个应用窗口误判成分屏，并 HOME + reassert(singleTask 锁机页)，
            // 直接清除答题页。答题 active 期间只保留上方通知栏收起，不做顶回。
            return
        }

        // 朋友密码会话（强度 3）期间锁机页承载输入，同样不做顶回，
        // 否则输入法窗口会被误判成分屏并打断输入。
        if (LockScreenActivity.friendUnlockActive) return

        // 悬浮窗朋友验证模式同样让位：外来系统窗口触发 HOME + reassert
        // 会盖住验证界面、打断输入。
        if (com.focusguard.app.enforce.LockOverlayManager.isFriendUnlockMode) return

        // 悬浮窗内答题模式（v3.0.0）：触摸事件在此大量触发，
        // 弹阻断窗会打断自绘键盘输入——答题期间禁止弹窗。
        if (com.focusguard.app.enforce.LockOverlayManager.isChallengeMode) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowsChanged()
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                // 手势结束：立即撤阻断窗（比自撤更快，减少视觉残留）
                removeGestureBlocker()
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // 手指接触屏幕（锁机主界面）→ 弹阻断窗打断下拉手势起点
                if (gestureBlocker != null) {
                    scheduleGestureBlockerTeardown()
                } else {
                    popGestureBlocker()
                }
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // 自身界面（锁屏页/答题页/应用主界面）不拦截
        if (pkg == packageName) return

        // 语音助手窗口：跳过节流，检测即顶（多次触发防不住的直接原因）
        val isAssistant = pkg in voiceAssistantPackages
        val now = System.currentTimeMillis()
        if (!isAssistant && now - lastStateReassertAt < 300L) return

        // 系统界面：收起通知栏；侧边栏类（智慧多窗等）额外顶回
        if (pkg in blockedSystemPackages) {
            Log.d(TAG, "锁机中检测到系统界面 $pkg，执行防破解")
            dismissNotificationShade()
            if (isSideBarPackage(pkg)) {
                Log.d(TAG, "检测到侧边栏类界面 $pkg，立即顶回锁屏页")
                try {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    LockScreenActivity.reassert(this)
                } catch (e: Exception) {
                    Log.w(TAG, "顶回锁屏页失败：${e.message}")
                }
                lastStateReassertAt = now
                return
            }
        }

        Log.d(TAG, "锁机中检测到前台切换至 $pkg，立即顶回锁屏页")
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            LockScreenActivity.reassert(this)
        } catch (e: Exception) {
            Log.w(TAG, "顶回锁屏页失败：${e.message}")
        }
        lastStateReassertAt = now
    }

    private fun handleWindowsChanged() {
        // shade 焦点检测独立于节流：立即收起，绝不被 300ms 节流吞掉
        val hasShadeFocused = try {
            windows?.any { w ->
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                    w.isFocused &&
                    w.root?.packageName?.toString() == "com.android.systemui"
            } ?: false
        } catch (e: Exception) {
            false
        }
        if (hasShadeFocused) {
            Log.d(TAG, "锁机中检测到通知栏/QS 面板获得焦点，立即收起")
            dismissNotificationShadeImmediate()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastWindowsReassertAt < 300L) return

        // 记录当前窗口清单（诊断用：定位未知侧滑破解窗口的包名/类型）
        try {
            val winList = windows
                ?.mapNotNull { w ->
                    val t = when (w.type) {
                        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
                        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIV"
                        else -> "T${w.type}"
                    }
                    val pkg = runCatching {
                        w.root?.packageName?.toString()
                    }.getOrNull() ?: "?"
                    "$t:$pkg"
                }
                ?.joinToString(",")
            if (!winList.isNullOrBlank()) {
                Log.d(TAG, "锁机中窗口清单：$winList")
            }
        } catch (e: Exception) {
            // 诊断日志失败不影响拦截
        }

        // 检测分屏/小窗：多于一个应用窗口同时可见 → 顶回锁机页。
        // 收紧：排除 launcher 与自身过渡窗口，且要求两个窗口都 isActive
        // （应用重开/锁机页创建过渡期 launcher 与锁机页短暂同屏，不能误判）。
        val hasSplit = try {
            val appWindows = windows?.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.isActive
            } ?: emptyList()
            val ownOrLauncher = appWindows.all { w ->
                val pkg = w.root?.packageName?.toString() ?: ""
                pkg == packageName ||
                    pkg.contains("launcher", ignoreCase = true) ||
                    pkg.contains("systemui", ignoreCase = true)
            }
            appWindows.size >= 2 && !ownOrLauncher
        } catch (e: Exception) {
            false
        }

        // 检测"不抢焦点的系统悬浮窗"（华为智慧多窗侧边栏、助手常驻球等）：
        // 规则：SYS 类型 + 非自身 + 非 launcher；仅当确认是常驻状态栏
        // （systemui、不抢焦点、且无标题的纯系统栏）才排除。
        // 助手常驻球/面板由 SystemUI 进程承载时通常带标题（如"小爱同学"），必须顶回。
        val hasForeignSysWindow = try {
            windows?.any { w ->
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                    w.root?.packageName?.toString()?.let { pkg ->
                        pkg != packageName &&
                            !pkg.contains("launcher", ignoreCase = true) &&
                            !(pkg == "com.android.systemui" &&
                                !w.isFocused &&
                                w.title?.toString().isNullOrBlank())
                    } == true
            } ?: false
        } catch (e: Exception) {
            false
        }

        if (hasSplit || hasForeignSysWindow) {
            lastWindowsReassertAt = now
            Log.d(
                TAG,
                if (hasSplit) "锁机中检测到分屏/小窗，立即顶回"
                else "锁机中检测到系统悬浮窗（疑似侧边栏/助手常驻球），立即顶回"
            )
            try {
                if (hasSplit) {
                    // 分屏：先回桌面再拉起锁机页
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    LockScreenActivity.reassert(this)
                } else {
                    // 悬浮窗：只把锁机页置顶，不按 Home——
                    // 华为智慧多窗悬浮球等可能常驻，反复 Home 会造成
                    // "锁机页消失→拉起"闪烁甚至 ANR
                    LockScreenActivity.reassert(this)
                }
            } catch (e: Exception) {
                Log.w(TAG, "顶回失败：${e.message}")
            }
        }
    }

    /**
     * 动态手势阻断窗 v2（模仿第三方锁机软件机制）：
     * 触摸开始时在顶部弹出（状态栏+120dp 热区），窗口出现在下拉手势路径上
     * → 系统对进行中的手势发送 CANCEL，shade 无法展开；300ms 后自撤。
     * 800ms 节流防高频窗口增删；答题/朋友验证期间由让位保护禁止弹窗。
     */
    fun popGestureBlocker() {
        try {
            val state = lockState ?: return
            if (!state.isLocked || !state.shouldBlockNow) return
            if (gestureBlocker != null) {
                scheduleGestureBlockerTeardown()
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastBlockerAt < 800L) return
            lastBlockerAt = now
            val density = resources.displayMetrics.density
            val statusBar = runCatching {
                val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (rid > 0) resources.getDimensionPixelSize(rid) else (32 * density).toInt()
            }.getOrDefault((32 * density).toInt())
            val height = statusBar + (120 * density).toInt()
            val blocker = object : android.view.View(this) {
                override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                    Log.d(TAG, "手势阻断窗吞掉触摸 ${event.actionMasked}")
                    dismissNotificationShadeImmediate()
                    return true
                }
            }.apply {
                // 淡红半透明：与第三方「红框」机制对齐，同时确认窗口真实弹出
                setBackgroundColor(0x22FF3B30)
            }
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                height,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            getSystemService(android.view.WindowManager::class.java).addView(blocker, lp)
            gestureBlocker = blocker
            Log.d(TAG, "手势阻断窗弹出（高 ${height}px）")
            scheduleGestureBlockerTeardown()
        } catch (e: Exception) {
            Log.w(TAG, "手势阻断窗弹出失败：${e.message}")
        }
    }

    private fun scheduleGestureBlockerTeardown() {
        gestureBlockerTeardown?.let {
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it)
            }
        }
        val r = java.lang.Runnable { removeGestureBlocker() }
        gestureBlockerTeardown = r
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, 300L)
    }

    private fun removeGestureBlocker() {
        try {
            gestureBlocker?.let {
                getSystemService(android.view.WindowManager::class.java).removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "移除手势阻断窗失败：${e.message}")
        }
        gestureBlocker = null
    }

    /** 收起通知栏（下拉状态栏也会被顶回）。250ms 节流防 SystemUI 限流。 */
    fun dismissNotificationShade() {
        val now = System.currentTimeMillis()
        if (now - lastDismissAt < 250L) return
        lastDismissAt = now
        try {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } catch (e: Exception) {
            Log.w(TAG, "收起通知栏失败：${e.message}")
        }
    }

    /** 精准信号直通（shade 获得焦点/拦截条命中触摸）：不经节流立即收起。 */
    fun dismissNotificationShadeImmediate() {
        try {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } catch (e: Exception) {
            Log.w(TAG, "立即收起通知栏失败：${e.message}")
        }
    }

    /**
     * 状态栏物理拦截条：锁机期间在状态栏高度区域挂一条
     * TYPE_ACCESSIBILITY_OVERLAY 全宽细条，直接吞掉下拉手势的起点——
     * 通知栏根本展开不了，不依赖事件后的"收起"。
     * 无障碍被回收时系统会自动移除本窗口；服务重连后由
     * [onAccessibilityEvent]/守护巡检重新挂载。
     */
    fun ensureStatusBarBlock() {
        try {
            val state = lockState ?: return
            val needed = state.isLocked && state.shouldBlockNow
            if (needed && statusBarBlock == null) {
                // 拦截条高度 = 状态栏 + 下拉手势热区。
                // status_bar_height 读取失败时兜底 32dp；再加 24dp 热区，
                // 保证手指从状态栏下缘附近开始下拉也会先落到拦截条上。
                val density = resources.displayMetrics.density
                val statusBar = runCatching {
                    val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
                    if (rid > 0) resources.getDimensionPixelSize(rid) else (32 * density).toInt()
                }.getOrDefault((32 * density).toInt())
                val barHeight = statusBar + (24 * density).toInt()
                val block = object : android.view.View(this) {
                    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                        Log.d(TAG, "状态栏拦截条命中触摸 ${event.actionMasked}")
                        // 立即收起（用户手指碰到通知栏区域 = 有下拉意图）
                        dismissNotificationShadeImmediate()
                        return true
                    }
                }
                val lp = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    barHeight,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.TOP
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                getSystemService(android.view.WindowManager::class.java).addView(block, lp)
                statusBarBlock = block
                Log.d(TAG, "状态栏拦截条已挂载，高度 ${barHeight}px（状态栏 ${statusBar}px + 热区）")
            } else if (!needed && statusBarBlock != null) {
                removeStatusBarBlock()
            }
        } catch (e: Exception) {
            Log.w(TAG, "状态栏拦截条挂载失败（忽略，回退事件收起）：${e.message}")
        }
    }

    private fun removeStatusBarBlock() {
        try {
            statusBarBlock?.let {
                getSystemService(android.view.WindowManager::class.java).removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "移除状态栏拦截条失败：${e.message}")
        }
        statusBarBlock = null
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        removeStatusBarBlock()
        removeGestureBlocker()
        instance = null
        Log.d(TAG, "无障碍服务已销毁")

        // 无障碍掉了不等于锁机失效：确保前台守护在跑，它不依赖无障碍
        try {
            if (!LockGuardService.isRunning) {
                LockGuardService.start(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "无障碍销毁时拉起守护失败：${e.message}")
        }

        notifyAccessibilityLost()
    }

    /** 无障碍服务断开时发通知引导重新开启。 */
    private fun notifyAccessibilityLost() {
        try {
            val state = lockState ?: return
            // 只有锁机期间断开才值得提醒（平时断开不影响）
            if (!state.isLocked) return
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                2,
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = android.app.Notification.Builder(
                this, com.focusguard.app.FocusGuardApp.CHANNEL_ID
            )
                .setSmallIcon(com.focusguard.app.R.drawable.ic_shield)
                .setContentTitle("无障碍已断开（锁机仍生效）")
                .setContentText("锁机由前台守护继续维持，但拦截速度变慢，建议重新开启无障碍")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.notify(1002, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发送无障碍断开提醒失败：${e.message}")
        }
    }

    fun performHome(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_HOME)
    } catch (e: Exception) {
        Log.e(TAG, "执行 Home 失败", e)
        false
    }

    fun performBack(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_BACK)
    } catch (e: Exception) {
        Log.e(TAG, "执行返回失败", e)
        false
    }

    /** 强制退出当前应用并回到桌面。 */
    fun exitCurrentApp(): Boolean {
        val ok = performHome()
        if (!ok) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return true
            } catch (e: Exception) {
                Log.w(TAG, "回桌面失败：${e.message}")
            }
        }
        return ok
    }
}
