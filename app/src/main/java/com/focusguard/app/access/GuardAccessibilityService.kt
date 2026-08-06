package com.focusguard.app.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.focusguard.app.data.LockState
import com.focusguard.app.enforce.LockScreenActivity

/**
 * 无障碍服务。
 *
 * 两个职责：
 * 1. 锁机拦截（防破解）——锁机期间：
 *    - 任何切换到其他应用的尝试都被顶回锁屏页
 *    - 下拉通知栏 / 最近任务 / 语音助手 / 分屏小窗 出现时立即收起或顶回
 * 2. 强制退出——执法模式 EXIT 时回桌面
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GuardAccessibility"
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
            "com.bbk.launcher2",
            "com.huawei.vassistant",                     // 华为语音助手
            "com.huawei.systemmanager"
        )
    }

    private var lockState: LockState? = null

    /** 上次重新拉起锁屏页的时间，避免高频事件导致刷屏。 */
    private var lastReassertAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lockState = LockState(this)
        // 需要窗口内容信息才能识别分屏/小窗窗口类型
        try {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        } catch (e: Exception) {
            Log.w(TAG, "设置无障碍 flags 失败：${e.message}")
        }
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val state = lockState ?: return
        if (!state.isLocked) return

        // 番茄钟休息阶段放行
        if (!state.shouldBlockNow) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // 分屏 / 画中画小窗出现时立即顶回
                handleWindowsChanged()
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // 自身界面（锁屏页/应用主界面）不拦截
        if (pkg == packageName) return

        val now = System.currentTimeMillis()
        if (now - lastReassertAt < 300L) return
        lastReassertAt = now

        // 下拉通知栏 / 最近任务 / 语音助手等系统界面：先收起，再顶回
        if (pkg in blockedSystemPackages) {
            Log.d(TAG, "锁机中检测到系统界面 $pkg，执行防破解")
            dismissNotificationShade()
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

        // 检测分屏/小窗：多于一个应用窗口同时可见 → 顶回锁机页
        val hasSplit = try {
            val appWindows = windows?.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            } ?: emptyList()
            appWindows.size >= 2
        } catch (e: Exception) {
            false
        }

        if (hasSplit) {
            lastReassertAt = now
            Log.d(TAG, "锁机中检测到分屏/小窗，立即顶回")
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
                LockScreenActivity.reassert(this)
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
        // 服务被系统停掉（部分 ROM 会定时清理无障碍）时提醒用户重新开启，
        // 避免锁机保护在用户不知情的情况下失效
        notifyAccessibilityLost()
    }

    /** 无障碍服务断开时发通知引导重新开启。 */
    private fun notifyAccessibilityLost() {
        try {
            val lockState = lockState ?: return
            // 只有锁机期间断开才值得提醒（平时断开不影响）
            if (!lockState.isLocked) return
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                2,
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = android.app.Notification.Builder(this, com.focusguard.app.FocusGuardApp.CHANNEL_ID)
                .setSmallIcon(com.focusguard.app.R.drawable.ic_shield)
                .setContentTitle("锁机保护部分失效")
                .setContentText("无障碍服务已断开，无法拦截切换到其他应用，点击重新开启")
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
