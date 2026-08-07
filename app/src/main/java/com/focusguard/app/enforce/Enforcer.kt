package com.focusguard.app.enforce

import android.content.Context
import android.util.Log
import com.focusguard.app.data.Settings

/**
 * 执法器。
 *
 * 锁机 = 软件层面的全屏覆盖（勒索病毒式）：
 * 拉起 [LockScreenActivity] 盖住整个屏幕，拦截返回键，
 * 由无障碍服务在用户尝试切换到其他应用时把锁屏页顶回前台。
 * 不依赖设备管理员，因此无需系统级权限。
 */
class Enforcer(private val context: Context) {

    companion object {
        private const val TAG = "Enforcer"
    }

    fun enforce(mode: Settings.EnforcementMode, reason: String): String {
        return when (mode) {
            Settings.EnforcementMode.LOCK -> {
                lockScreen()
                "LOCK"
            }
            Settings.EnforcementMode.EXIT -> {
                exitAndBlock(reason)
                "EXIT"
            }
            Settings.EnforcementMode.WARN -> {
                showWarning(reason)
                "WARN"
            }
        }
    }

    /** 软件全屏锁机：拉起全屏锁屏页，无需设备管理员。 */
    private fun lockScreen() {
        try {
            // 关键：必须先启动锁机守护服务 + 看门狗！
            // 否则 AI 执法弹出的锁机页被上滑销毁后没有任何东西把它拉回
            // （守护只在手动锁机/开应用时才启动，AI 执法路径此前漏了这一步）
            com.focusguard.app.service.LockGuardService.ensureRunning(context)
            com.focusguard.app.service.GuardWatchdogWorker.schedule(context)

            LockScreenActivity.show(context)
            Log.d(TAG, "软件锁机已启动（全屏覆盖 + 守护已就位）")
        } catch (e: Exception) {
            Log.e(TAG, "拉起锁屏页失败", e)
            exitAndBlock("锁机失败: ${e.message}")
        }
    }

    private fun exitAndBlock(reason: String) {
        try {
            // 退出当前应用并显示遮挡界面
            val intent = android.content.Intent(context, BlockActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("reason", reason)
            }
            context.startActivity(intent)
            Log.d(TAG, "Block activity launched")
        } catch (e: Exception) {
            Log.e(TAG, "Exit and block failed", e)
        }
    }

    private fun showWarning(reason: String) {
        // For warn mode, we just show a notification
        Log.d(TAG, "Warning mode: $reason")
    }
}
