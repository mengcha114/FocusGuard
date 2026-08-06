package com.focusguard.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class GuardDeviceAdminReceiver : DeviceAdminReceiver() {
    
    companion object {
        private const val TAG = "GuardDeviceAdmin"
    }
    
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
        Toast.makeText(context, "专注卫士设备管理员已激活", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device admin disabled")
        Toast.makeText(context, "专注卫士设备管理员已停用", Toast.LENGTH_SHORT).show()
    }
}
