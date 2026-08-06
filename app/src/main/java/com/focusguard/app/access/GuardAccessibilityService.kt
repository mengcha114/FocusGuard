package com.focusguard.app.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.focusguard.app.data.LockState
import com.focusguard.app.enforce.LockScreenActivity

class GuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GuardAccessibility"
        var instance: GuardAccessibilityService? = null
            private set
    }

    private var lockState: LockState? = null

    /** 上次重新拉起锁屏页的时间，避免高频事件导致刷屏。 */
    private var lastReassertAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lockState = LockState(this)
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val state = lockState ?: return
        if (!state.isLocked) return

        val pkg = event.packageName?.toString() ?: return
        // 自身界面不拦截，否则会陷入自我重启循环
        if (pkg == packageName) return

        // 锁机期间任何应用切到前台都立即被顶回锁屏页
        val now = System.currentTimeMillis()
        if (now - lastReassertAt < 400L) return
        lastReassertAt = now

        Log.d(TAG, "锁机中检测到前台切换至 $pkg，重新拉起锁屏页")
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            LockScreenActivity.show(this)
        } catch (e: Exception) {
            Log.w(TAG, "拉起锁屏页失败：${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "无障碍服务已销毁")
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
