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

        /** 命中即"侧边栏/悬浮类"系统界面：不止收起，还要顶回锁机页。 */
        private fun isSideBarPackage(pkg: String): Boolean =
            pkg.contains("smartwindow", ignoreCase = true) ||
                pkg.contains("side", ignoreCase = true) ||
                pkg == "com.huawei.systemmanager" ||
                pkg == "com.hihonor.systemmanager"
    }

    private var lockState: LockState? = null

    /** 上次重新拉起锁屏页的时间，避免高频事件导致刷屏。 */
    private var lastReassertAt = 0L

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

        // 答题页在前台时绝不干预（输入法会被顶掉导致闪退）
        if (UnlockChallengeActivity.active) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowsChanged()
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // 自身界面（锁屏页/答题页/应用主界面）不拦截
        if (pkg == packageName) return

        val now = System.currentTimeMillis()
        if (now - lastReassertAt < 300L) return
        lastReassertAt = now

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
    }

    private fun handleWindowsChanged() {
        val now = System.currentTimeMillis()
        if (now - lastReassertAt < 300L) return

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

        // 检测分屏/小窗：多于一个应用窗口同时可见 → 顶回锁机页
        val hasSplit = try {
            val appWindows = windows?.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            } ?: emptyList()
            appWindows.size >= 2
        } catch (e: Exception) {
            false
        }

        // 检测"不抢焦点的系统悬浮窗"（华为智慧多窗侧边栏等）：
        // 这类窗口不触发 state_changed 也不抢焦点，只能在这里抓到。
        // 规则：SYS 类型 + 有包名 + 非自身 + 非通知栏/状态栏（systemui）→ 顶回
        val hasForeignSysWindow = try {
            windows?.any { w ->
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                    w.root?.packageName?.toString()?.let { pkg ->
                        pkg != packageName &&
                            pkg != "com.android.systemui" &&
                            !pkg.contains("launcher", ignoreCase = true)
                    } == true
            } ?: false
        } catch (e: Exception) {
            false
        }

        if (hasSplit || hasForeignSysWindow) {
            lastReassertAt = now
            Log.d(
                TAG,
                if (hasSplit) "锁机中检测到分屏/小窗，立即顶回"
                else "锁机中检测到系统悬浮窗（疑似侧边栏），立即顶回"
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

    /** 收起通知栏（下拉状态栏也会被顶回）。 */
    fun dismissNotificationShade() {
        try {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } catch (e: Exception) {
            Log.w(TAG, "收起通知栏失败：${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
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
