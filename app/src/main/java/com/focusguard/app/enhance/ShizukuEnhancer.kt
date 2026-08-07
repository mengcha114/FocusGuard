package com.focusguard.app.enhance

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 增强：以 shell/root 身份执行系统命令，实现权限自愈。
 *
 * ## 能力
 * - [selfHeal]：自动授权「使用情况访问」（appops）+ 电池优化白名单（deviceidle）
 * - 无需用户跳系统设置页手动开启，且 ROM 重置权限后能自动恢复
 *
 * ## 实现说明
 * Shizuku 13.1.5 已移除公开的 `Shizuku.newProcess`，但服务器 AIDL
 * `IShizukuService.newProcess` 仍然存在。这里通过 [Shizuku.getBinder]
 * 拿到服务器 Binder 后**反射**调用（api 库的 aidl 类不在编译期 classpath）。
 *
 * ## 降级
 * 未安装 Shizuku / 未授权 / 反射失败 → 全部静默返回 false，
 * 上层引导用户手动开启权限即可，不影响锁机功能。
 */
object ShizukuEnhancer {

    private const val TAG = "ShizukuEnhancer"

    /** Shizuku 服务是否在线。 */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** 本应用是否已被 Shizuku 授权。 */
    fun isPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Shizuku 可用且已授权。 */
    fun isReady(): Boolean = isAvailable() && isPermissionGranted()

    /** 拉起 Shizuku 授权界面（用户需在 Shizuku 管理器里点允许）。 */
    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (!isAvailable()) return
            if (!isPermissionGranted()) Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.w(TAG, "请求 Shizuku 授权失败：${e.message}")
        }
    }

    /**
     * 以 shell/root 身份执行命令（反射 IShizukuService.newProcess）。
     *
     * @param cmd 命令与参数，例如 `runCommand("appops", "set", pkg, "GET_USAGE_STATS", "allow")`
     * @return 是否成功发起（命令异步执行，不等待结果）
     */
    fun runCommand(vararg cmd: String): Boolean {
        return try {
            if (!isReady()) return false
            val binder = Shizuku.getBinder() ?: return false

            // 反射：IShizukuService.Stub.asInterface(binder).newProcess(cmd, null, null)
            val serviceClass = Class.forName("moe.shizuku.server.IShizukuService")
            val stubClass = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, binder)
            val newProcess = serviceClass.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcess.invoke(service, cmd, null, null)
            Log.d(TAG, "已发起命令：${cmd.joinToString(" ")}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku 命令执行失败：${e.message}")
            false
        }
    }

    /**
     * 权限自愈（幂等、安全）：
     * 1. 使用情况访问授权（appops）
     * 2. 电池优化白名单（deviceidle）
     *
     * 注意：不做无障碍自动写回——`settings put secure enabled_accessibility_services`
     * 是整表覆盖，会把用户其他无障碍服务（TalkBack 等）全部关掉。
     */
    fun selfHeal(context: Context) {
        val pkg = context.packageName
        runCommand("appops", "set", pkg, "GET_USAGE_STATS", "allow")
        runCommand("dumpsys", "deviceidle", "whitelist", "+$pkg")
    }
}
