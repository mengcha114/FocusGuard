package com.focusguard.app.enforce

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.focusguard.app.admin.GuardDeviceAdminReceiver
import com.focusguard.app.data.Settings

class Enforcer(private val context: Context) {
    
    companion object {
        private const val TAG = "Enforcer"
    }
    
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, GuardDeviceAdminReceiver::class.java)
    
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
    
    private fun lockScreen() {
        try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                Log.d(TAG, "Screen locked via DeviceAdmin")
            } else {
                Log.w(TAG, "DeviceAdmin not active, falling back to block screen")
                exitAndBlock("设备管理员未激活")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lock screen failed", e)
            exitAndBlock("锁屏失败: ${e.message}")
        }
    }
    
    private fun exitAndBlock(reason: String) {
        try {
            // Launch block activity
            val intent = Intent(context, BlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
        // The actual notification is handled by the service
        Log.d(TAG, "Warning mode: $reason")
    }
}
