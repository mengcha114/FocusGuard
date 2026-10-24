package com.focusguard.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.focusguard.app.access.GuardAccessibilityService

/**
 * 统一的权限状态查询入口。
 *
 * 权限可能在系统设置里被随时撤销，因此每次都向系统实时查询，
 * 不依赖本地保存的标记，避免界面显示"已授权"而实际功能失效。
 */
object PermissionChecker {

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        // 服务实例存在说明确实已连接，比读设置字符串更可靠
        if (GuardAccessibilityService.instance != null) return true
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            // 组件全名由 manifest 的包名决定，与 applicationId 可能不同（存在 applicationIdSuffix
            // 的版本里二者不一致），因此必须用 ComponentName 生成，不能拼接 packageName。
            val component = android.content.ComponentName(context, GuardAccessibilityService::class.java)
            val names = setOf(component.flattenToString(), component.flattenToShortString())
            enabled.split(':').any { it.trim().let { e -> names.any { it.equals(e, ignoreCase = true) } } }
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

    /**
     * Android 12+ 的「闹钟和提醒」（精确闹钟）权限。
     *
     * 低版本恒为 true（那时没有这个权限）。Android 14 起新装默认**关闭**：
     * 不给也能跑，但守护被杀后那套「2 秒/30 秒自愈闹钟」会退化成非精确
     * （Doze 下最长 ~15 分钟），而且**非精确闹钟不享受后台启动前台服务的豁免**。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager)
                ?.canScheduleExactAlarms() ?: false
        }.getOrDefault(false)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
