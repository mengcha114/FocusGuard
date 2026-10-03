package com.focusguard.app.enhance

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 增强（可选；无 Shizuku 或未授权时所有方法静默返回 false，功能不受影响）。
 *
 * 以 shell 身份执行系统命令：
 * - [selfHeal]：服务启动时的权限与保活自愈（使用情况访问、电池白名单、后台运行、
 *   待机分组固定为 active、悬浮窗 / 通知 / 精确闹钟授权）；
 * - [ensureAccessibility]：**仅锁机期间**，无障碍被关掉时自动写回（追加，不覆盖其他服务）；
 * - [ensureAutoTime]：锁机期间把「自动设置时间」写回开启，减少改时间的尝试；
 * - [collapseStatusBar]：收起通知栏（比无障碍方式更可靠）；
 * - [suspendPackages]：锁机期间冂结娱乐 App（`pm suspend`，见 [AppFreezer]）；
 * - [wakeDhizuku]：开机后主动拉起 Dhizuku 进程，缩短系统级锁机恢复时间。
 *
 * Shizuku 13.1.5 已移除公开的 `Shizuku.newProcess`，这里通过 [Shizuku.getBinder]
 * 反射调用服务端 AIDL `IShizukuService.newProcess`。
 */
object ShizukuEnhancer {

    private const val TAG = "ShizukuEnhancer"
    private const val DHIZUKU_PKG = "com.rosan.dhizuku"

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

    private fun newProcess(cmd: Array<String>): Process? {
        if (!isReady()) return null
        val binder = Shizuku.getBinder() ?: return null
        val serviceClass = Class.forName("moe.shizuku.server.IShizukuService")
        val stubClass = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
        val service = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val remote = serviceClass.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).invoke(service, cmd, null, null) ?: return null
        // 服务端返回 IRemoteProcess；用 ShizukuRemoteProcess 包装成标准 Process
        val wrapper = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
        val iface = Class.forName("moe.shizuku.server.IRemoteProcess")
        val ctor = wrapper.getDeclaredConstructor(iface)
        ctor.isAccessible = true
        return ctor.newInstance(remote) as Process
    }

    /**
     * 以 shell 身份执行命令（异步，不等待结果）。
     * @return 是否成功发起
     */
    fun runCommand(vararg cmd: String): Boolean = try {
        val ok = newProcess(arrayOf(*cmd)) != null
        if (ok) Log.d(TAG, "已发起命令：${cmd.joinToString(" ")}")
        ok
    } catch (e: Throwable) {
        // 包装类不可用时退回仅发起、不读输出
        Log.w(TAG, "Shizuku 命令执行失败：${e.message}")
        false
    }

    /**
     * 执行命令并读取标准输出（最多等待 [timeoutMs]）。失败返回 null。
     * 只在后台线程调用。
     */
    fun runForOutput(vararg cmd: String, timeoutMs: Long = 3_000L): String? {
        return try {
            val p = newProcess(arrayOf(*cmd)) ?: return null
            val out = StringBuilder()
            val reader = Thread {
                runCatching { out.append(p.inputStream.bufferedReader().readText()) }
            }.apply { start() }
            reader.join(timeoutMs)
            runCatching { p.destroy() }
            out.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku 读取输出失败：${e.message}")
            null
        }
    }

    /**
     * 权限与保活自愈（幂等、安全，只作用于本应用）：
     * 1. 使用情况访问（识别前台应用）
     * 2. 电池优化白名单 + 允许后台运行 + 待机分组固定为 active（防国产 ROM 冂结 / 清理）
     * 3. 悬浮窗、通知、精确闹钟授权（新用户免去逐项手动开启）
     */
    fun selfHeal(context: Context) {
        if (!isReady()) return
        val pkg = context.packageName
        runCommand("appops", "set", pkg, "GET_USAGE_STATS", "allow")
        runCommand("dumpsys", "deviceidle", "whitelist", "+$pkg")
        runCommand("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow")
        runCommand("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")
        runCommand("am", "set-standby-bucket", pkg, "active")
        runCommand("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
        runCommand("appops", "set", pkg, "SCHEDULE_EXACT_ALARM", "allow")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCommand("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS")
        }
    }

    /**
     * 锁机期间无障碍被关闭 → 写回开启。
     *
     * `settings put` 是整表覆盖，这里先读出现有列表再**追加**本应用，绝不丢掉
     * 用户的其他无障碍服务（TalkBack 等）。只在锁机期间调用，解锁后不干预。
     * 必须在后台线程调用（会等待命令输出）。
     */
    fun ensureAccessibility(context: Context): Boolean {
        if (!isReady()) return false
        val self = android.content.ComponentName(context, com.focusguard.app.access.GuardAccessibilityService::class.java)
            .flattenToShortString()
        val current = runForOutput("settings", "get", "secure", "enabled_accessibility_services")
            ?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
        if (current.split(':').any { it.equals(self, true) }) return true
        val merged = if (current.isEmpty()) self else "$current:$self"
        val ok = runCommand("settings", "put", "secure", "enabled_accessibility_services", merged)
        runCommand("settings", "put", "secure", "accessibility_enabled", "1")
        Log.d(TAG, "锁机中无障碍已被关闭，已写回")
        return ok
    }

    /** 读取当前「自动设置时间」的值（"0"/"1"），失败返回 null。 */
    fun readAutoTime(): String? = runForOutput("settings", "get", "global", "auto_time")?.trim()

    /**
     * 锁机期间保持「自动设置时间」开启（改时间本身已有单调时钟防护，这里减少尝试）。
     * 原值由调用方在锁机开始时记录，锁机结束用 [restoreAutoTime] 还原，不擅自改用户偏好。
     */
    fun ensureAutoTime(): Boolean {
        if (!isReady()) return false
        val v = runForOutput("settings", "get", "global", "auto_time")?.trim()
        if (v == "1") return true
        return runCommand("settings", "put", "global", "auto_time", "1")
    }

    /** 还原「自动设置时间」为用户原值（"0" 时关闭；其他值不处理）。 */
    fun restoreAutoTime(original: String?): Boolean {
        if (!isReady() || original.isNullOrBlank()) return false
        return runCommand("settings", "put", "global", "auto_time", original.trim())
    }

    /** 收起通知栏 / 快捷设置面板。 */
    fun collapseStatusBar(): Boolean = isReady() && runCommand("cmd", "statusbar", "collapse")

    /** 冂结 / 解冂结指定应用（`pm suspend`）。只用于用户在应用管控中标记的娱乐 App。 */
    fun suspendPackages(packages: Collection<String>, suspended: Boolean): Boolean {
        if (!isReady() || packages.isEmpty()) return false
        val verb = if (suspended) "suspend" else "unsuspend"
        var ok = true
        packages.forEach { ok = runCommand("pm", verb, "--user", "0", it) && ok }
        return ok
    }

    /** 开机后用 Shizuku 拉起 Dhizuku 进程（Dhizuku 未运行时 Provider 不可达）。 */
    fun wakeDhizuku(): Boolean {
        if (!isReady()) return false
        return runCommand("monkey", "-p", DHIZUKU_PKG, "-c", "android.intent.category.LAUNCHER", "1") ||
            runCommand("am", "start", "-n", "$DHIZUKU_PKG/.ui.activity.MainActivity")
    }
}
