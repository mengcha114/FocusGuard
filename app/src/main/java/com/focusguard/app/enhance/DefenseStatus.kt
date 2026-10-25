package com.focusguard.app.enhance

import android.content.Context
import com.focusguard.app.data.LockState
import com.focusguard.app.util.PermissionChecker

/**
 * 当前设备与当前设置的「事实状态」。
 *
 * 只回答哪些能力真的可用、哪些开关真的能生效；不做「最强/系统级」这类宣传式评级。
 */
data class DefenseStatus(
    val overlay: Boolean,
    val accessibility: Boolean,
    val usageStats: Boolean,
    val notification: Boolean,
    val exactAlarm: Boolean,
    val batteryIgnored: Boolean,
    val dhizuku: Boolean,
    val shizukuAvailable: Boolean,
    val shizukuReady: Boolean,
    val admin: Boolean,
    val antiCrackRequested: Boolean,
    val blockingRequested: Boolean,
    val antiCrackEffective: Boolean,
    val restrictionsApplied: Boolean,
    val lockedNow: Boolean,
    val gaps: List<String>
) {
    /** 锁机页实际走哪条路径（只陈述事实，不宣称强度）。 */
    val lockPath: String
        get() = when {
            dhizuku -> "Dhizuku"
            overlay -> "悬浮窗"
            else -> "缺少悬浮窗"
        }

    val antiCrackLabel: String
        get() = when {
            !antiCrackRequested -> "未开启"
            !blockingRequested -> "已开启（仅检测，不阻断）"
            antiCrackEffective && lockedNow -> "已开启（本次生效）"
            antiCrackEffective -> "已开启（锁机时生效）"
            else -> "已开启，但本机缺少授权，关键项未生效"
        }
}

object DefenseStatusReader {

    /**
     * 读取实时状态。开销小（只读系统权限与本地记录），可在 Compose 的 remember 中调用。
     */
    fun evaluate(context: Context): DefenseStatus {
        val app = context.applicationContext
        val overlay = PermissionChecker.canDrawOverlays(app)
        val accessibility = PermissionChecker.isAccessibilityEnabled(app)
        val usageStats = PermissionChecker.isUsageStatsGranted(app)
        val notification = PermissionChecker.isNotificationGranted(app)
        val exactAlarm = PermissionChecker.canScheduleExactAlarms(app)
        val batteryIgnored = PermissionChecker.isBatteryOptimizationIgnored(app)

        val dhizuku = DhizukuEnhancer.isReadyCached()
        val shizukuAvailable = ShizukuEnhancer.isAvailable()
        val shizukuReady = shizukuAvailable && ShizukuEnhancer.isPermissionGranted()
        val admin = AdminEnhancer.isActive(app)
        val locked = LockState(app).isLocked

        val freezeOn = LockPolicies.isHardeningEnabled(app, LockPolicies.Hardening.CRACK_FREEZE)
        val hideOn = LockPolicies.isHardeningEnabled(app, LockPolicies.Hardening.CRACK_HIDE)
        val detectOn = LockPolicies.isHardeningEnabled(app, LockPolicies.Hardening.CRACK_DETECT)
        val shellLockOn = LockPolicies.isHardeningEnabled(app, LockPolicies.Hardening.SHIZUKU_LOCK)
        val shellLockApplied = LockPolicies.isHardeningApplied(app, LockPolicies.Hardening.SHIZUKU_LOCK)
        val antiCrackRequested = freezeOn || hideOn || detectOn || shellLockOn
        // 冻结可走 Shizuku 或 Dhizuku；隐藏与切断 shell 只走 Dhizuku；检测只留痕。
        val freezeEffective = freezeOn && (dhizuku || shizukuReady)
        val hideEffective = hideOn && dhizuku
        val shellLockEffective = shellLockOn && dhizuku && (!locked || shellLockApplied)
        val antiCrackEffective = freezeEffective || hideEffective || shellLockEffective
        val detectOnly = antiCrackRequested && !freezeOn && !hideOn && !shellLockOn
        val restrictionsApplied = LockPolicies.restrictionsApplied(app)
        val noDebug = LockPolicies.isHardeningEnabled(app, LockPolicies.Hardening.NO_DEBUG)

        val gaps = buildList {
            if (!overlay) add("未开悬浮窗：锁机页可能被手势切走")
            if (!accessibility) add("未开无障碍：无法拦截设置、卸载和通知栏")
            if (!usageStats) add("未开使用情况：无法识别前台应用")
            if (!notification) add("未开通知：守护状态与恢复提示不可见")
            if (!exactAlarm) add("未开精确闹钟：守护被杀后恢复会变慢")
            if (!batteryIgnored) add("未加入电池优化白名单：后台守护可能被系统清理")

            when {
                !antiCrackRequested ->
                    add("防破解未开启：破解工具、自动答题、清数据/卸载仍可绕过")
                detectOnly ->
                    add("防破解仅开启检测：root/Xposed 只留痕，不阻断")
                else -> {
                    if (freezeOn && !freezeEffective) {
                        add("冻结未生效：需要 Shizuku（免电脑）或 Dhizuku（一次性电脑）授权")
                    }
                    if (hideOn && !hideEffective) {
                        add("隐藏未生效：需要 Dhizuku 授权，当前只能冻结")
                    }
                }
            }

            if (noDebug && shizukuAvailable && !shellLockOn) {
                add("Shizuku 运行中，禁调试未生效：adb 仍可用")
            } else if (noDebug && !dhizuku && !shellLockOn) {
                add("禁调试需要 Dhizuku 授权，当前 adb 可用")
            }

            if (shellLockOn) {
                if (!dhizuku) {
                    add("切断 shell 通道需要 Dhizuku 授权：adb/无线调试仍开着，破解工具能直接强停本应用")
                } else if (locked && !shellLockApplied) {
                    add("shell 通道尚未切断成功：adb/无线调试可能仍可用，下轮巡检会重试")
                }
            }

            if (!dhizuku) {
                add("未授权 Dhizuku：禁安装/禁卸载/禁强停/清数据等系统限制不会生效")
            } else if (locked && !restrictionsApplied) {
                add("系统限制未全部生效，下轮巡检会重试")
            }

            if (!admin) add("未激活设备管理员：卸载本应用更容易")
        }

        return DefenseStatus(
            overlay = overlay,
            accessibility = accessibility,
            usageStats = usageStats,
            notification = notification,
            exactAlarm = exactAlarm,
            batteryIgnored = batteryIgnored,
            dhizuku = dhizuku,
            shizukuAvailable = shizukuAvailable,
            shizukuReady = shizukuReady,
            admin = admin,
            antiCrackRequested = antiCrackRequested,
            blockingRequested = freezeOn || hideOn || shellLockOn,
            antiCrackEffective = antiCrackEffective,
            restrictionsApplied = restrictionsApplied,
            lockedNow = locked,
            gaps = gaps
        )
    }
}
