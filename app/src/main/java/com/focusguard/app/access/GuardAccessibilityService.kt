package com.focusguard.app.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
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

        /**
         * 锁机期间禁止的「卸载 / 关闭权限」入口。
         *
         * 安全模式下第三方应用全部被禁用，本应用无法运行，用户可能借机卸载应用来破解；
         * 有 Dhizuku 时靠 setUninstallBlocked（设备策略，安全模式下同样生效）拦截，
         * 没有 Dhizuku 时只能拦界面入口：系统设置（应用信息/卸载）、安装器、各应用商店。
         */
        /** 最近一次窗口状态变化的包名（前台识别的兜底来源）。 */
        @Volatile
        private var lastWindowPackage: String? = null

        /** 电源菜单（关机/重启）拦截节流。 */
        @Volatile
        private var lastPowerMenuBlockAt = 0L

        /** 「退出屏幕固定」确认框拦截节流。 */
        @Volatile
        private var lastUnpinBlockAt = 0L

        private val uninstallEntryPackages = listOf(
            "com.android.settings",                  // 应用信息 → 卸载 / 强行停止 / 关无障碍
            "com.android.packageinstaller",          // 卸载确认界面
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",      // 应用权限页
            "com.miui.packageinstaller",
            "com.miui.securitycenter",               // 小米安全中心（应用管理）
            "com.coloros.safecenter",                // OPPO / 一加
            "com.oplus.safecenter",
            "com.vivo.permissionmanager",            // vivo
            "com.iqoo.secure",
            "com.huawei.systemmanager",              // 华为
            "com.hihonor.systemmanager",             // 荣耀
            "com.samsung.android.lool",              // 三星
            "com.android.vending",                   // Google Play（可卸载）
            "com.xiaomi.market",                     // 小米应用商店
            "com.heytap.market",                     // OPPO 应用商店
            "com.bbk.appstore",                      // vivo 应用商店
            "com.huawei.appmarket"                   // 华为应用市场
        )

        /** 命中即"侧边栏/悬浮类"系统界面：不止收起，还要顶回锁机页。 */
        private fun isSideBarPackage(pkg: String): Boolean =
            pkg.contains("smartwindow", ignoreCase = true) ||
                pkg.contains("side", ignoreCase = true) ||
                pkg == "com.huawei.systemmanager" ||
                pkg == "com.hihonor.systemmanager"
    }

    private var lockState: LockState? = null

    /** 设置项懒加载（事件回调是高频路径，不能每次 new）。 */
    private val appSettingsRef: com.focusguard.app.data.Settings by lazy {
        com.focusguard.app.data.Settings(this)
    }

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

    /**
     * 截屏失败码。常量值取自 [AccessibilityService]：
     * 1 = 无无障碍访问、2 = 显示器无效、3 = 调用过于频繁（Android 14 起有 ~333ms 限流）、
     * 4 = 内部错误、5 = 安全窗口（FLAG_SECURE）。这里用字面量是为了避开
     * 「API 34 才有的常量在低版本类里找不到」的坑。
     */
    private val ERROR_NO_ACCESSIBILITY_ACCESS = 1
    private val ERROR_INTERNAL = 4

    /**
     * 用无障碍能力截一张图（API 30+）：**不建立 MediaProjection 会话**，
     * 所以不会出现「正在共享屏幕」提示，也不会让系统把通知正文隐藏。
     * 失败（无权限 / 被系统限流 / 低版本）一律回调 null，调用方自行回退。
     */
    fun captureScreenshot(onDone: (android.graphics.Bitmap?, Int) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDone(null, ERROR_NO_ACCESSIBILITY_ACCESS); return
        }
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        var copied: android.graphics.Bitmap? = null
                        try {
                            copied = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        } catch (t: Throwable) {
                            copied = null
                        } finally {
                            runCatching { result.hardwareBuffer.close() }
                        }
                        onDone(copied, 0)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w("GuardA11y", "无障碍截图失败：$errorCode")
                        onDone(null, errorCode)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w("GuardA11y", "无障碍截图异常：${t.message}")
            onDone(null, ERROR_INTERNAL)
        }
    }

    override fun onServiceConnected() {
        // 开机/重启后 ROM 常把悬浮窗权限清掉：无障碍一连上就补回，
        // 避免等到执法瞬间才发现锁机页显示不出来
        runCatching {
            com.focusguard.app.enhance.ShizukuEnhancer.ensureOverlayPermission(this)
        }
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
        // 先把事件自带的包名记进缓存（纯内存操作，绝不触发 Binder 往返）
        try {
            if (event != null && event.windowId != 0) {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    windowPkgCache[event.windowId] = pkg
                    if (pkg != packageName &&
                        !pkg.contains("systemui") &&
                        !pkg.contains("launcher") &&
                        !pkg.contains("keyguard")
                    ) {
                        lastForeignPkg = pkg
                        lastForeignAt = System.currentTimeMillis()
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (event == null) return

        // 应用封锁与「实时窗口包名」跟锁机状态无关，必须在锁机早退之前处理：
        // 「仅锁该软件」与「每日时长上限」两类封锁都只发生在非锁机态，
        // 之前的早退让 interceptIfBlocked 成了死代码（打开被封应用要等守护巡检兜底）。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val winPkg = event.packageName?.toString().orEmpty()
            if (winPkg.isNotBlank()) {
                lastWindowPackage = winPkg
                lastWindowPackageAt = System.currentTimeMillis()
                if (winPkg != packageName && !isLockActive()) {
                    interceptIfBlocked(winPkg, android.os.SystemClock.elapsedRealtime())
                }
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // 部分 ROM 会把 WINDOW_STATE_CHANGED 合并/延迟（用户报告「锁住应用后仍要等几秒」），
            // 窗口列表变化时再给一次机会：只认**实时**活动窗口，避免陈旧包名误弹封锁页。
            val winPkg = liveWindowPackage().orEmpty()
            if (winPkg.isNotBlank() && winPkg != packageName && !isLockActive()) {
                interceptIfBlocked(winPkg, android.os.SystemClock.elapsedRealtime())
            }
        }

        // 封锁悬浮窗显示期间的两条旁路：
        // ① 系统界面（通知栏/最近任务）一出现就收起 —— 否则能从通知内容偷看被锁应用；
        // ② 被锁应用出现多个窗口（画中画/分屏）→ 直接顶回桌面（全屏应用只有一个活动窗口）。
        // 无授权时的「停止应用」：已布置就找应用信息页里的「强行停止」代点
        if (com.focusguard.app.enforce.ForceStopHelper.isArmed()) {
            val host = event.packageName?.toString().orEmpty()
            if (com.focusguard.app.enforce.ForceStopHelper.isAppDetailHost(host)) {
                val target = com.focusguard.app.enforce.ForceStopHelper.armedPackage()
                val clicked = runCatching {
                    windows.firstNotNullOfOrNull { w ->
                        val owner = windowPkgCache[w.id] ?: return@firstNotNullOfOrNull null
                        if (com.focusguard.app.enforce.ForceStopHelper.isAppDetailHost(owner)) {
                            if (com.focusguard.app.enforce.ForceStopHelper.clickForceStop(w.root)) true else null
                        } else {
                            null
                        }
                    } ?: false
                }.getOrDefault(false)
                if (clicked) {
                    Log.d(TAG, "已代点强行停止：$target")
                    // 把「应用信息」页收掉：否则用户回到桌面时会看到它（封锁页仍盖在最上层）
                    runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                }
            }
        }

        // 屏幕录制授权弹窗：被 arm 过（我们刚主动拉起）时替他点「立即开始」
        if (com.focusguard.app.enforce.ProjectionConsent.isArmed()) {
            val consentPkg = event.packageName?.toString().orEmpty()
            if (com.focusguard.app.enforce.ProjectionConsent.isConsentHost(consentPkg)) {
                val clicked = runCatching {
                    com.focusguard.app.enforce.ProjectionConsent
                        .clickPositive(rootInActiveWindow)
                }.getOrDefault(false)
                if (clicked) Log.d(TAG, "已自动点击屏幕录制授权弹窗")
            }
        }

        if (com.focusguard.app.enforce.AppBlockOverlay.isShowing()) {
            val uiPkg = event.packageName?.toString().orEmpty()
            if (uiPkg == "com.android.systemui" || uiPkg in blockedSystemPackages) {
                dismissNotificationShade()
            }
            val blocked = com.focusguard.app.enforce.AppBlockOverlay.showingPackage()
            if (blocked != null && isSmallWindowForBlocked(blocked)) {
                // 小窗/分屏/画中画的 z-order 可能压在我们的封锁悬浮窗之上（HOME 也顶不掉
                // ROM 小窗）⇒ 这种情况下**直接停掉该应用**：小窗、后台声音一起结束。
                // 正常全屏使用时不会走到这里，所以不会出现「打开就被强制退出」。
                Log.d(TAG, "被锁应用 $blocked 处于小窗/分屏/画中画，停掉它")
                Thread {
                    val ok = runCatching {
                        com.focusguard.app.enhance.LockPolicies
                            .suspendBlock(this@GuardAccessibilityService, blocked)
                    }.getOrDefault(false)
                    if (!ok && com.focusguard.app.data.Settings(this@GuardAccessibilityService).forceStopUnlocked) {
                        // 未授权（不能冻结）：用系统「强行停止」把它真停掉
                        runCatching {
                            com.focusguard.app.enforce.ForceStopHelper
                                .requestStop(this@GuardAccessibilityService, blocked)
                        }
                    }
                }.start()
            }
        }

        val state = lockState ?: return
        if (!state.isLocked) return

        // 番茄钟休息阶段 / 暂停中 放行
        if (!state.shouldBlockNow) return

        // 状态栏拦截条随锁机状态挂载/移除（拦截下拉起始手势的物理屏障）
        ensureStatusBarBlock()


        // ── 非极客加固：电源菜单（长按电源键的关机/重启）收起 ──
        // 不能关机/重启，就少了一条"重开机绕过锁机"的路；系统菜单属于 systemui/android，
        // 只能靠无障碍把它收起来（不依赖 Shizuku/Dhizuku）。
        runCatching {
            if (appSettingsRef.blockPowerMenu) {
                val cls = event.className?.toString().orEmpty()
                if (cls.contains("globalactions", ignoreCase = true)) {
                    val nowPm = System.currentTimeMillis()
                    if (nowPm - lastPowerMenuBlockAt >= 500L) {
                        lastPowerMenuBlockAt = nowPm
                        Log.d(TAG, "锁机期间拦截电源菜单（${event.packageName}/$cls）")
                        com.focusguard.app.util.StartupTrace.mark(this, "lock.powerMenuBlocked")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        sendBroadcast(
                            android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                        )
                    }
                }
            }
        }

        // ── 可选（默认关）：拦住系统「要退出固定模式吗？」确认框 ──
        // 屏幕固定的官方退出手势是"按住返回+最近任务"，系统会弹确认框。
        // 默认放行（免得答不出题又记不住密码的用户被彻底困死）；打开开关后才收起它。
        runCatching {
            if (appSettingsRef.blockPinningEscape) {
                val sb = StringBuilder()
                for (i in 0 until event.text.size) sb.append(event.text[i]).append(' ')
                val t = sb.toString()
                val looksLikeUnpin = t.contains("退出固定") || t.contains("取消固定") ||
                    t.contains("固定模式") || t.lowercase().contains("unpin")
                if (looksLikeUnpin) {
                    val nowUnpin = System.currentTimeMillis()
                    if (nowUnpin - lastUnpinBlockAt >= 500L) {
                        lastUnpinBlockAt = nowUnpin
                        Log.d(TAG, "按设置收起「退出屏幕固定」确认框")
                        com.focusguard.app.util.StartupTrace.mark(this, "lock.unpinBlocked")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
        }

        // 陌生人守卫：事件来源不是我们自己（有人盖在锁机页上方，如语音助手）→
        // 交给 LockTopGuard 计数与升级处理（白名单/冷却/熔断都在里面）；
        // 普通的顶回仍由下面的既有逻辑负责。
        runCatching {
            // 只读事件自带的包名（纯本地字段）：rootInActiveWindow 与 window.root
            // 同属跨进程阻塞调用，无障碍回调在主线程，绝不能在这里碰。
            val above = event.packageName?.toString().orEmpty()
            // 应用切前台 ⇒ 通知监控服务「过 5.5 秒检测一次」（等用户真正打开内容）
            if (above.isNotBlank()) {
                com.focusguard.app.service.MonitorService.notifyForeground(this, above)
            }
            if (above.isNotBlank() && above != packageName) {
                com.focusguard.app.enforce.LockTopGuard.onForeignAboveLock(this, above)
            }
        }

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

    /**
     * 当前活动窗口的包名 —— 尽量实时，但**主线程上绝不阻塞**。
     *
     * 显示封锁页这种会打断用户的操作优先用这个：[currentWindowPackage] 允许用上次
     * 窗口事件兜底，用户退回桌面后的几秒内它仍报「在封锁应用里」，表现为偶发弹一下
     * 封锁页（用户报告的缺陷）。隐私判定方向相反（多判一次敏感是安全的），那边继续用它。
     *
     * 注意：主线程上只能用「焦点窗口 id + 事件缓存」，拿不到时返回 null（不猜），
     * 真正的 rootInActiveWindow 只在工作线程调用；否则其它应用卡死时主线程会被
     * 同步卡住（实测 254 秒 ANR）。
     */
    fun liveWindowPackage(): String? = liveWindowPackageNoBlock()

    fun currentWindowPackage(): String? =
        liveWindowPackageNoBlock() ?: lastWindowPackage

    /**
     * 「当前活动窗口包名」的非阻塞实现。
     *
     * 这条路径会被 [onAccessibilityEvent] 在**主线程**调用，因此绝不能直接读
     * `rootInActiveWindow`（跨进程阻塞，其它应用卡死时会同步卡住主线程 → ANR）。
     * 主线程改为：实时窗口列表里取焦点窗口 id → 用事件早已缓存好的
     * windowId→包名 映射；工作线程才允许走 rootInActiveWindow。
     */
    private fun liveWindowPackageNoBlock(): String? = try {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
        } else {
            val focusedId = windows?.firstOrNull { it.isFocused }?.id
            val cached = focusedId?.let { windowPkgCache[it] }
            cached?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 用户是否仍停留在 [pkg]（主线程安全，不碰 window.root / rootInActiveWindow）。
     *
     * 用于封锁页「补发」前的自检：退回桌面后不要再把封锁页盖上来。
     * 优先用焦点窗口 id 的事件缓存；拿不到时退回最近一次窗口状态事件
     * （3 秒内的才算数，避免用陈旧包名误判成"还在应用里"）。
     */
    private fun isStillOnPackage(pkg: String): Boolean {
        val live = liveWindowPackageNoBlock()
        if (live != null) return live == pkg
        return lastWindowPackage == pkg &&
            System.currentTimeMillis() - lastWindowPackageAt < 3_000L
    }

    /** 最近一次窗口事件时刻（判断窗口包名是否还新鲜）。 */
    @Volatile private var lastWindowPackageAt = 0L

    private var lastBlockInterceptAt = 0L
    private var burstToken = 0

    /** 共享的规则仓库：构造实例要读磁盘文件，窗口事件里不能每次都新建。 */
    private val ruleStore: com.focusguard.app.usage.UsageRuleStore
        get() = com.focusguard.app.usage.UsageRuleStore.shared(this)

    /**
     * 应用级封锁的即时拦截。
     *
     * 原先只靠守护巡检（300ms 一次 + 1 秒节流），用户打开被封应用最多要等 1.3 秒
     * 才看到封锁页，体验上像「没生效」。这里在窗口切换到该应用的瞬间就拉起封锁页。
     *
     * 两个细节决定「打开即挡」是否真的成立：
     * 1. 判定只读内存数据（共享的规则仓库 + 封锁表），不做文件 IO、不查 UsageStats；
     * 2. 刚切过去的那一次启动请求常被系统丢下（目标应用正在启动），
     *    因此随后再补发几次，直到封锁页确实压在最上面。
     */
    private fun interceptIfBlocked(
        pkg: String,
        eventAt: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        if (!runCatching { isBlockedNow(pkg) }.getOrDefault(false)) return
        val now = System.currentTimeMillis()
        if (now - lastBlockInterceptAt < 200L) return
        lastBlockInterceptAt = now
        showAppBlock(pkg, eventAt)

        // 补发：只发一次时系统可能把启动请求丢掉，实测要等下一次巡检才出现
        val token = ++burstToken
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // 补发要快：第一次启动请求被系统丢掉时，靠这几次尽快把封锁页压上去
        for (delayMs in longArrayOf(150L, 400L, 900L, 1800L)) {
            handler.postDelayed({
                // 用户可能已经退回桌面：只有在仍处于该应用时才补发，
                // 否则封锁页会盖在桌面上（比"晚一点才挡"更烦人）
                if (token != burstToken) return@postDelayed
                if (!isStillOnPackage(pkg)) return@postDelayed
                if (runCatching { isBlockedNow(pkg) }.getOrDefault(false)) showAppBlock(pkg, eventAt)
            }, delayMs)
        }
    }

    /**
     * 拉起全屏封锁页。
     *
     * 没有悬浮窗权限时，全屏页的启动请求很可能被系统的后台启动限制丢弃，
     * 那时先把用户顶回桌面——「打开就被打断」这件事本身不依赖任何界面。
     */
    private fun showAppBlock(
        pkg: String,
        eventAt: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        runCatching {
            val until = com.focusguard.app.data.AppBlockStore(this).blockedUntil(pkg)
            val label = runCatching {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val rule = ruleStore.getRule(pkg)
            val used = (ruleStore.getTodaySeconds(pkg) / 60).toInt()
            // 优先用悬浮窗遮盖：毫秒级、不可被手势销毁，而且**不用把人踢出应用**
            // （用户反馈：以前会「直接强制退出」）。工具可用时同时真正挂起该应用。
            val covered = com.focusguard.app.enforce.AppBlockOverlay.show(
                context = this,
                pkg = pkg,
                label = label,
                usedMinutes = used,
                limitMinutes = rule?.hardBlockMinutes ?: 0,
                blockUntil = until
            )
            Log.d(
                TAG,
                "$pkg 拦截：事件→${if (covered) "悬浮窗" else "兜底"} " +
                    "${android.os.SystemClock.elapsedRealtime() - eventAt}ms"
            )
            if (covered) return@runCatching
            // 兜底（没有悬浮窗权限）：直接拉封锁页把界面盖住。
            // 既不要 HOME 踢人、也不要挂起应用 —— 两者都会让用户感觉「被强制退出」
            // （挂起会让系统直接停掉前台应用）。挂起只留给「用户离开应用」的时机。
            com.focusguard.app.enforce.AppBlockActivity.show(
                context = this,
                packageName = pkg,
                appLabel = label,
                usedMinutes = used,
                limitMinutes = rule?.hardBlockMinutes ?: 0,
                blockUntil = until
            )
        }.onFailure { Log.w(TAG, "拉起封锁页失败：${it.message}") }
    }

    /** 该应用此刻是否应当被封锁（临时封锁期内，或今日使用已超上限）。 */
    private fun isBlockedNow(pkg: String): Boolean {
        if (pkg == packageName) return false
        if (com.focusguard.app.data.AppBlockStore(this).blockedUntil(pkg) > 0L) return true
        val limit = ruleStore.getRule(pkg)?.hardBlockMinutes ?: return false
        return (ruleStore.getTodaySeconds(pkg) / 60).toInt() >= limit
    }

    private var lastSmallWindowCheckAt = 0L

    /**
     * [pkg] 是否处于小窗 / 分屏 / 画中画：它拥有一个**明显小于屏幕**的窗口。
     *
     * 判据用尺寸而不是窗口个数（ROM 小窗常常只有一个窗口，个数判不出来）；
     * 阈值 0.85 足以区分分屏（约 0.5）与画中画（约 0.3），又不会把
     * 正常全屏（扣掉状态栏约 0.93~0.98）误判进来。2 秒节流，避免频繁遍历。
     */
    fun isSmallWindowForBlocked(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSmallWindowCheckAt < 2_000L) return false
        lastSmallWindowCheckAt = now
        return try {
            val dm = resources.displayMetrics
            val fullW = dm.widthPixels
            val fullH = dm.heightPixels
            windows.any { window ->
                val owner = runCatching { windowPkgCache[window.id] }.getOrNull()
                if (owner != pkg) return@any false
                val bounds = android.graphics.Rect()
                window.getBoundsInScreen(bounds)
                bounds.width() < fullW * 0.85f || bounds.height() < fullH * 0.85f
            }
        } catch (e: Exception) {
            false
        }
    }

    /** [pkg] 当前拥有的窗口数：>1 说明它处于画中画/分屏（全屏应用只有一个活动窗口）。 */
    private fun windowCountOf(pkg: String): Int = try {
        windows.count { window ->
            runCatching { windowPkgCache[window.id] }.getOrNull() == pkg
        }
    } catch (e: Exception) {
        0
    }

    /** 当前是否处于「锁机中且应当拦截」的状态。 */
    private fun isLockActive(): Boolean = runCatching {
        com.focusguard.app.data.LockState(this).shouldBlockNow
    }.getOrDefault(false)

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // 自身界面（锁屏页/答题页/应用主界面）不拦截
        if (pkg == packageName) return

        // 语音助手窗口：跳过节流，检测即顶（多次触发防不住的直接原因）
        val isAssistant = pkg in voiceAssistantPackages
        val now = System.currentTimeMillis()
        if (!isAssistant && now - lastStateReassertAt < 300L) return

        // 卸载 / 关权限入口：锁机期间一律顶回锁机页（没有 Dhizuku 时唯一手段）
        if (pkg in uninstallEntryPackages && isLockActive()) {
            Log.d(TAG, "锁机中检测到卸载/权限入口 $pkg，立即顶回")
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.w(TAG, "顶回失败：${e.message}")
            }
            return
        }

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
                    windowPkgCache[w.id]?.contains("systemui") == true
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
                    // 不再访问 w.root（跨进程阻塞源），只用缓存
                    val pkg = windowPkgCache[w.id] ?: "?"
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
                val pkg = windowPkgCache[w.id] ?: ""
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
                    windowPkgCache[w.id]?.let { pkg ->
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

    // ── 高层全屏覆盖（z-order 高于其它应用的 TYPE_APPLICATION_OVERLAY） ──
    @Volatile private var topOverlayView: android.view.View? = null

    /**
     * windowId → 包名 缓存。
     *
     * `AccessibilityWindowInfo.root` 是**跨进程取窗口根节点**，其它应用卡住时
     * 会同步阻塞调用线程；在无障碍回调（主线程）里对每个窗口都取一次，
     * 就是「主线程卡顿 254 秒 → 被系统判无响应」的根因。
     * 这里改为只用事件里本就带有的 `event.packageName`（纯本地字段，零阻塞）。
     */
    private val windowPkgCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * 窗口扫描线程。
     *
     * `AccessibilityWindowInfo.root` 是跨进程调用，**只有它**会阻塞；
     * 在无障碍回调（主线程）里调用过一次就会把主线程拖死（实测 254s ANR）。
     * 但它又确实是唯一能拿到「别的应用窗口属于哪个包」的途径。
     * 折中：把扫描整个丢到这条独立线程上执行，主线程只读扫描结果。
     */
    private val windowScanExecutor = java.util.concurrent.Executors
        .newSingleThreadExecutor { r ->
            Thread(r, "a11y-window-scan").apply { isDaemon = true }
        }
    @Volatile private var scanInFlight = false
    @Volatile private var scannedForeign: List<String> = emptyList()
    /** 外来覆盖层的屏幕矩形（同一次扫描里取，供遮盖补丁使用）。 */
    @Volatile private var scannedRects: List<android.graphics.Rect> = emptyList()
    /** 遮盖补丁窗口：bounds-key -> View */
    private val patchViews = java.util.HashMap<String, android.view.View>()
    @Volatile private var scannedFocusedPkg: String? = null
    @Volatile private var scannedAt = 0L

    /** 最近一次「不属于我们也不是系统底座」的窗口包名（用于识别外来悬浮窗）。 */
    @Volatile private var lastForeignPkg: String? = null
    @Volatile private var lastForeignAt: Long = 0L

    /**
     * 把锁机全屏窗口挂到无障碍覆盖层。
     *
     * 普通悬浮窗是 TYPE_APPLICATION_OVERLAY，**同类型后加的窗口会压在我们上面**
     * （语音助手、加速球、录屏/悬浮工具等「顽固悬浮窗」就是这么盖住的）。
     * TYPE_ACCESSIBILITY_OVERLAY 的窗口层级高于所有普通应用悬浮窗，
     * 锁机主体挂到这里之后，其它应用再怎么加悬浮窗也压不到它上面。
     *
     * 失败（服务被中断、系统拒绝）时返回 false，调用方自动回退普通悬浮窗。
     */
    fun attachTopOverlay(
        view: android.view.View,
        params: android.view.WindowManager.LayoutParams
    ): Boolean = try {
        detachTopOverlay()
        // 复制一份再改 type：调用方挂载失败后还要拿原参数回退普通悬浮窗，
        // 不能让它手里的 LayoutParams 被这里改成无障碍类型。
        val overlayParams = android.view.WindowManager.LayoutParams().apply {
            copyFrom(params)
        }
        overlayParams.type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        getSystemService(android.view.WindowManager::class.java).addView(view, overlayParams)
        topOverlayView = view
        Log.d(
            TAG,
            "锁机主体已挂到无障碍覆盖层（type=TYPE_ACCESSIBILITY_OVERLAY，" +
                "层级 31 > TYPE_APPLICATION_OVERLAY 的 12）"
        )
        true
    } catch (e: Exception) {
        Log.w(
            TAG,
            "无障碍覆盖层挂载失败，回退普通悬浮窗：" +
                "${e.javaClass.simpleName}: ${e.message}"
        )
        topOverlayView = null
        false
    }

    /** 撤下无障碍覆盖层（锁机结束 / 窗口重建时调用）。 */
    fun detachTopOverlay() {
        try {
            topOverlayView?.let {
                getSystemService(android.view.WindowManager::class.java).removeViewImmediate(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "移除无障碍覆盖层失败：${e.message}")
        }
        topOverlayView = null
    }

    /**
     * 列出「可能盖在我们上面」的外来悬浮窗所属包名。
     *
     * 无障碍把普通应用的 TYPE_APPLICATION_OVERLAY 统一上报成 TYPE_SYSTEM，
     * 因此这里把系统类窗口里不属于我们、也不属于系统底座（SystemUI/桌面/输入法/
     * 键盘等）的包名挑出来，交给上层做「重新置顶 + 顶置守卫」处理。
     */
    /**
     * 后台扫描一次窗口清单，挑出「可能盖在我们上面」的外来包名。
     *
     * 只在扫描线程里访问 `window.root`（阻塞源），主线程永不触碰。
     * 扫描在进行中时直接跳过，避免堆积。
     */
    private fun requestWindowScan() {
        if (scanInFlight) return
        scanInFlight = true
        try {
            windowScanExecutor.execute {
                try {
                    val self = packageName
                    val found = ArrayList<String>(4)
                    val rects = ArrayList<android.graphics.Rect>(4)
                    // 焦点窗口也在这条线程上取：它同样要读 window.root（阻塞源）
                    var focusedPkg: String? = null
                    windows.orEmpty().forEach { w ->
                        try {
                            if (w.isFocused) {
                                val fp = w.root?.packageName?.toString()
                                if (!fp.isNullOrBlank()) focusedPkg = fp
                            }
                            val t = w.type
                            if (t != AccessibilityWindowInfo.TYPE_SYSTEM &&
                                t != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                            ) return@forEach
                            val pkg = w.root?.packageName?.toString() ?: return@forEach
                            if (pkg.isBlank() || pkg == self) return@forEach
                            if (pkg.contains("systemui") || pkg.contains("launcher") ||
                                pkg.contains("keyguard")
                            ) return@forEach
                            found.add(pkg)
                            // 顺便记下它的屏幕矩形（getBoundsInScreen 只是本地字段，不阻塞）
                            val rc = android.graphics.Rect()
                            w.getBoundsInScreen(rc)
                            // 补丁矩形还要更严：状态栏/导航栏/输入法这类系统条一旦被盖上，
                            // 就会在屏幕边缘出现白边（用户截图里的左右两条白边正是它）。
                            val dm = resources.displayMetrics
                            val sw = dm.widthPixels
                            val sh = dm.heightPixels
                            val wRatio = rc.width().toFloat() / sw
                            val hRatio = rc.height().toFloat() / sh
                            val thinBar = hRatio < 0.09f || wRatio < 0.16f   // 状态栏/导航条/侧条
                            val almostFull = wRatio > 0.92f && hRatio > 0.92f // 全屏系统窗口
                            val isIme = pkg.contains("inputmethod", true) || pkg.contains("ime", true)
                            if (!thinBar && !almostFull && !isIme &&
                                rc.width() > 8 && rc.height() > 8
                            ) rects.add(rc)
                        } catch (_: Throwable) {
                        }
                    }
                    scannedFocusedPkg = focusedPkg
                    scannedForeign = found.distinct()
                    scannedRects = rects
                    scannedAt = System.currentTimeMillis()
                } catch (_: Throwable) {
                } finally {
                    scanInFlight = false
                }
            }
        } catch (_: Throwable) {
            scanInFlight = false
        }
    }

    /**
     * 外来覆盖层包名（供锁机看门狗判断是否需要重新置顶）。
     *
     * 该函数本身**不做任何阻塞调用**：只读扫描线程的结果，
     * 结果超过 3 秒就顺手触发一次后台重扫。
     */
    fun foreignOverlayPackages(): List<String> {
        val age = System.currentTimeMillis() - scannedAt
        if (age > 3_000L) requestWindowScan()
        return if (age <= 6_000L) scannedForeign else emptyList()
    }

    /**
     * 焦点窗口包名（**非阻塞**版）。
     *
     * 以前这里是 `windows.firstOrNull { it.isFocused }.root?.packageName`：
     * `AccessibilityWindowInfo.root` 是跨进程调用，其它应用卡住时会把调用线程
     * 同步卡死（实测主线程 254 秒 ANR）。现在只读 [windowScanExecutor] 的扫描
     * 结果，最多旧 3 秒——对 300ms 一次的守护巡检完全够用。
     */
    fun focusedWindowPackageCached(): String? {
        if (System.currentTimeMillis() - scannedAt > 1_000L) requestWindowScan()
        return scannedFocusedPkg
    }

    /**
     * 遮盖补丁：在外来悬浮窗所在的矩形上盖一块 31 层的窗口。
     *
     * 为什么必须这么做：「重新置顶」只是抢一时的 z-order，对方一 re-add 又会上来，
     * 所以用户会看到「有时有效、有时又被顶上来」。遮盖补丁位于
     * TYPE_ACCESSIBILITY_OVERLAY(31) 层，而外来悬浮窗是(12)层，层级固定更高，
     * 它再 re-add 也翻不到上面去；同时补丁本身可触摸，把那一块的点击也吃掉，
     * 做到「看不见也点不到」。
     *
     * 必须在主线程调用（增删窗口）。
     */
    fun syncForeignPatches(active: Boolean) {
        val wm = getSystemService(android.view.WindowManager::class.java) ?: return
        if (!active) {
            patchViews.values.forEach { v -> runCatching { wm.removeViewImmediate(v) } }
            patchViews.clear()
            return
        }
        val want = scannedRects.filter { it.width() > 8 && it.height() > 8 }
        val wantKeys = want.map { "${it.left},${it.top},${it.right},${it.bottom}" }.toHashSet()
        // 移除过期补丁
        val iter = patchViews.keys.iterator()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k !in wantKeys) {
                runCatching { patchViews[k]?.let { wm.removeViewImmediate(it) } }
                iter.remove()
            }
        }
        if (want.isEmpty()) return
        // 补丁底色取锁机页背景色，视觉上像那块区域"被抹掉了"
        val bg = runCatching {
            com.focusguard.app.ui.theme.FocusColors.paletteForLockScreen(
                com.focusguard.app.data.Settings(this).themeMode, this
            ).bg
        }.getOrNull()
        // 底色不用主题 bg（浅色主题下接近纯白，边缘看起来就是"白边"），
        // 改用锁机页卡片底色并留一点透明，和卡片融为一体
        val color = if (bg != null) {
            android.graphics.Color.argb(
                236,
                ((bg.red + 0.03f).coerceIn(0f, 1f) * 255).toInt(),
                ((bg.green + 0.03f).coerceIn(0f, 1f) * 255).toInt(),
                ((bg.blue + 0.03f).coerceIn(0f, 1f) * 255).toInt()
            )
        } else 0xF20E1217.toInt()
        for (r in want) {
            val k = "${r.left},${r.top},${r.right},${r.bottom}"
            if (patchViews.containsKey(k)) continue
            val v = object : android.view.View(this) {
                override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true
            }
            v.setBackgroundColor(color)
            val lp = android.view.WindowManager.LayoutParams(
                r.width(), r.height(),
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = r.left
                y = r.top
            }
            runCatching { wm.addView(v, lp) }.onSuccess {
                patchViews[k] = v
                Log.d(TAG, "遮盖补丁已加：$k")
            }
        }
    }

    /** 当前是否已用无障碍覆盖层承载锁机主体。 */
    val hasTopOverlay: Boolean get() = topOverlayView != null

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        detachTopOverlay()
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
                .setTimeoutAfter(120_000L)
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
