package com.focusguard.app.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.focusguard.app.access.GuardAccessibilityService
import com.focusguard.app.admin.GuardDeviceAdminReceiver

/**
 * 统一的权限状态查询入口。
 *
 * 权限可能在系统设置里被随时撤销，因此每次都向系统实时查询，
 * 不依赖本地保存的标记，避免界面显示"已授权"而实际功能失效。
 */
object PermissionChecker {

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isAdminActive(ComponentName(context, GuardDeviceAdminReceiver::class.java))
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        // 服务实例存在说明确实已连接，比读设置字符串更可靠
        if (GuardAccessibilityService.instance != null) return true
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val fullName = "${context.packageName}/${GuardAccessibilityService::class.java.name}"
            // 部分 ROM 写成相对类名（包名/.access.XxxService），两种都要匹配
            val shortName = "${context.packageName}/.${
                GuardAccessibilityService::class.java.name.removePrefix("${context.packageName}.")
            }"
            enabled.split(':').any { entry ->
                entry.equals(fullName, ignoreCase = true) ||
                    entry.equals(shortName, ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isNotificationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isUsageStatsGranted(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
                as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
