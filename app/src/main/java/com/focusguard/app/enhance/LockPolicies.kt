package com.focusguard.app.enhance

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import com.focusguard.app.detection.AppCategory
import com.focusguard.app.detection.AppCategoryStore

/**
 * 锁机期间的系统级加固（Dhizuku 优先，Shizuku 兜底），由守护服务在锁机开始 / 结束 /
 * 服务启动时调用（必须在后台线程）。
 *
 * - 所有限制只在锁机期间生效，锁机结束、服务销毁时撤销；
 * - 施加状态持久化，服务启动时若「不在锁机却有残留」立即清除，防止崩溃后限制一直挂着；
 * - 冻结娱乐 App、禁止恢复出厂为可选项，默认关闭。
 */
object LockPolicies {

    private const val TAG = "LockPolicies"
    private const val PREFS = "focus_guard_lock_policies"
    private const val KEY_RESTRICTED = "restricted"
    private const val KEY_SUSPENDED = "suspended_pkgs"

    /** 冻结是由谁施加的（dhizuku / shizuku）：解冻必须用同一个施加者。 */
    private const val KEY_SUSPENDED_BY = "suspended_by"

    /** 用户启用的加固项（缺省时用各项的 defaultOn）。 */
    private const val KEY_HARDENING = "hardening_enabled"

    /** 当前已施加的加固项（用于锁机结束精确撤销）。 */
    private const val KEY_HARDENING_APPLIED = "hardening_applied"

    /** 「自动设置时间」的用户原值（锁机结束还原）。 */
    private const val KEY_AUTOTIME_ORIGINAL = "autotime_original_bool"

    /** 「应用封锁期间」的挂起记录（真正停掉被锁应用，计时结束/答题解封后解除）。 */
    private const val KEY_BLOCK_FROZEN = "block_suspended_pkgs"
    private const val KEY_BLOCK_FROZEN_BY = "block_suspended_by"

    /** AI 对话手动下达的长期冻结（不受锁机结束影响，解冻需答题）。 */
    private const val KEY_PERSIST = "persist_suspended_pkgs"
    private const val KEY_PERSIST_BY = "persist_suspended_by"
    private const val KEY_FREEZE_ENABLED = "freeze_enabled"
    private const val KEY_AUTO_TIME_ORIGINAL = "auto_time_original"
    private const val KEY_BLOCK_RESET = "block_factory_reset"

    /** 锁机开始时 Shizuku 是否在线；用于锁机中判断「授权工具被破解工具掐掉」。 */
    private const val KEY_SHIZUKU_WAS_READY = "shizuku_was_ready"
    private const val KEY_SHIZUKU_LOST_LOGGED = "shizuku_lost_logged"

    /** v3.11.64 一次性迁移标记（已把「挂起 Shizuku」补进老用户的加固集合）。 */
    private const val KEY_MIGRATED_SHIZUKU = "migrated_shizuku_lock"

    /** 本次锁机是否已经关过 adb（以及 adb/无线调试的原值，锁机结束恢复）。 */
    private const val KEY_SHELL_LOCK_APPLIED = "shell_lock_applied"
    private const val KEY_ADB_ORIGINAL = "shell_lock_adb_original"
    private const val KEY_ADB_WIFI_ORIGINAL = "shell_lock_wifi_original"
    private const val KEY_DAEMONS_KILLED = "shell_lock_daemons_killed"

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val DHIZUKU_PKG = "com.rosan.dhizuku"

    /** `Settings.Global.ADB_WIFI_ENABLED` 是 @hide，不能用 SDK 常量，只能写字面量。 */
    private const val GLOBAL_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /**
     * 「娱乐」类目：游戏、短视频、长视频、社交——即锁机期间应当被冻结的对象。
     * （此前只含游戏/视频，漏掉了社交这类同样消耗时间的应用。）
     */
    private val ENTERTAINMENT = setOf(
        AppCategory.GAME,
        AppCategory.SHORT_VIDEO,
        AppCategory.VIDEO,
        AppCategory.SOCIAL
    )

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isFreezeEnabled(c: Context) = prefs(c).getBoolean(KEY_FREEZE_ENABLED, false)
    fun setFreezeEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_FREEZE_ENABLED, on).apply()
    fun isBlockResetEnabled(c: Context) = prefs(c).getBoolean(KEY_BLOCK_RESET, false)
    fun setBlockResetEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_BLOCK_RESET, on).apply()

    /** 当前已冻结的应用（供设置页展示）。 */
    fun suspendedPackages(c: Context): Set<String> = prefs(c).getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()

    /**
     * 本次锁机是否**主动**切断了 Shizuku。
     *
     * 用于区分「被破解工具掐掉」和「我们自己按加固项关掉」：后者不能去恢复，
     * 否则每 20 秒的补设就会把自己的防线拆掉。
     * 必须同时满足「开关开着」和「本次确实切断了」：没有 Dhizuku 时开关开着也切不断，
     * 那种情况下 Shizuku 仍然可用，不能把它当成已被我们挂起而停止使用。
     */
    fun isShizukuSuspendedByUs(c: Context): Boolean =
        isHardeningEnabled(c, Hardening.SHIZUKU_LOCK) && isHardeningApplied(c, Hardening.SHIZUKU_LOCK)

    /** 某一项加固在**本次锁机**里是否真正生效（只读记录，不重新施加）。 */
    fun isHardeningApplied(c: Context, item: Hardening): Boolean =
        prefs(c).getStringSet(KEY_HARDENING_APPLIED, emptySet()).orEmpty().contains(item.key)

    /**
     * 本次锁机要施加的用户限制。
     *
     * 注意：**每一项都必须同时出现在 [ALL_RESTRICTIONS] 里**，否则锁机结束不会撤销，
     * 用户会被永久限制（这是本模块最容易出错的地方）。
     */
    private fun restrictions(c: Context): List<String> = buildList {
        add(UserManager.DISALLOW_CONFIG_DATE_TIME)  // 禁止改系统时间
        add(UserManager.DISALLOW_SAFE_BOOT)         // 禁止安全模式（会禁用第三方应用）
        add(UserManager.DISALLOW_ADD_USER)          // 禁止新建用户绕过
        val shizukuOn = ShizukuEnhancer.isAvailable()
        // 调试开关：Shizuku 依赖 adb，它在跑时不施加（默认开，可在设置里关）。
        // 跳过必须留痕：防护状态页会据此提示「adb 仍可用」，不能假装已生效。
        // 例外：开了「锁机期间挂起 Shizuku」时反正会切断 Shizuku，就不必再跳过，
        // 否则 adb 一直可用，破解工具接电脑照样能强停我们。
        if (shizukuOn && !isHardeningEnabled(c, Hardening.SHIZUKU_LOCK)) {
            if (isHardeningEnabled(c, Hardening.NO_DEBUG)) {
                Log.w(TAG, "Shizuku 运行中，跳过禁调试（否则 Shizuku 会掉线）")
            }
        } else if (isHardeningEnabled(c, Hardening.NO_DEBUG)) {
            add(UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
        if (isBlockResetEnabled(c)) add(UserManager.DISALLOW_FACTORY_RESET)
        // 加固项（默认开/关见 Hardening）
        if (isHardeningEnabled(c, Hardening.NO_INSTALL)) {
            add(UserManager.DISALLOW_INSTALL_APPS)
            add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        }
        if (isHardeningEnabled(c, Hardening.NO_APPS_CONTROL)) add(UserManager.DISALLOW_APPS_CONTROL)
        if (isHardeningEnabled(c, Hardening.NO_USB)) add(UserManager.DISALLOW_USB_FILE_TRANSFER)
        if (isHardeningEnabled(c, Hardening.NO_UNINSTALL)) add(UserManager.DISALLOW_UNINSTALL_APPS)
    }

    /** 撤销时用**全量**列表：比施加列表多没关系，少一项就会残留。 */
    private val ALL_RESTRICTIONS = listOf(
        UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_USB_FILE_TRANSFER, UserManager.DISALLOW_UNINSTALL_APPS
    )

    /**
     * 可选的锁机加固项（设置页逐项开关）。
     *
     * 前 4 项默认开（「最强锁机」的基线），其余默认关由用户自行决定 ——
     * 每项只在锁机期间生效，锁机结束按 [KEY_HARDENING_APPLIED] 精确撤销。
     */
    enum class Hardening(
        val key: String,
        val label: String,
        val hint: String,
        val defaultOn: Boolean,
        /** 系统自带的设备管理员也能施加（不需要 Dhizuku）。 */
        val adminCapable: Boolean = false
    ) {
        AUTO_TIME(
            "auto_time", "强制自动时间",
            "锁机期间强制「自动设置时间/时区」，改时间缩短不了锁机（原生能力，不需要 Shizuku）。默认关", false
        ),
        NO_INSTALL(
            "no_install", "禁止安装应用",
            "锁机期间装不了任何新应用（分屏/虚拟机/破解工具都进不来）；本应用的更新也会被挡。默认关", false
        ),
        NO_APPS_CONTROL(
            "no_apps_control", "禁止管理应用",
            "锁机期间在设置里不能强行停止/清除其它应用的数据。默认关", false
        ),
        NO_DEBUG(
            "no_debug", "禁止 USB 调试",
            "锁机期间关掉开发者选项与 adb（电脑连不上就卸不了/停不了）；Shizuku 在运行时不施加，否则它会掉线。默认关（会改系统设置）", false
        ),
        NO_USB(
            "no_usb", "禁止 USB 传文件",
            "锁机期间插电脑也看不到手机里的文件", false
        ),
        NO_CAMERA(
            "no_camera", "禁止相机",
            "锁机期间相机打不开（防拍照搜题）", false, adminCapable = true
        ),
        NO_CAPTURE(
            "no_capture", "禁止截图录屏",
            "锁机期间无法截图/录屏；注意与「锁机期间仍做 AI 检测」冲突（检测要截图），两者只开一个", false
        ),
        DENY_PERMS(
            "deny_perms", "权限自动拒绝",
            "锁机期间其它应用申请权限一律自动拒绝", false
        ),
        NO_UPDATE(
            "no_update", "禁止系统更新",
            "锁机期间禁用系统更新，避免更新重启打断锁机", false
        ),
        NO_UNINSTALL(
            "no_uninstall", "禁止卸载任何应用",
            "锁机期间任何应用都卸不掉（包括你自己想删的）", false
        ),
        INPUT_LOCK(
            "input_lock", "输入法白名单",
            "锁机期间只允许系统输入法，防第三方输入法自带的浏览器/面板绕过；只有小众输入法的机器慎用", false
        ),
        A11Y_LOCK(
            "a11y_lock", "无障碍白名单",
            "锁机期间只允许本应用与系统无障碍服务运行，破解工具无法用无障碍自动点按来撤销 Shizuku 授权或关闭守护；需要 Dhizuku，且会暂时停用第三方无障碍服务。默认关", false
        ),
        SHIZUKU_LOCK(
            "shizuku_lock", "切断 shell 通道（Shizuku/调试）",
            "游龙这类工具自带无线调试配对（Stellar），不需要 Shizuku 也能拿到 shell，一条 am force-stop 就能破锁且系统不会自动拉起。锁机开始时关闭 USB/无线调试、清掉它已跑起来的 shell 守护进程并挂起 Shizuku；锁机结束自动恢复开关。需要 Dhizuku，会暂时停用依赖 Shizuku/无线调试的应用。默认开", true
        ),
        LOCK_NOW(
            "lock_now", "执法瞬间锁屏",
            "AI 执法时先把屏幕灭掉，必须重新解锁设备才看到锁机界面", false, adminCapable = true
        ),
        CRACK_FREEZE(
            "crack_freeze", "冻结破解工具",
            "锁机期间冻结「冰箱/小黑屋/Island/黑阈/Auto.js/自动点击器/多开分身/虚拟机/远程控制/修改器/游龙安全护盾」等能用来破解或自动答题的工具，并阻止卸载 Shizuku/Dhizuku；锁机开始后新出现的工具也会当场冻结，锁机结束自动解冻。默认开（需 Dhizuku 或 Shizuku）", true
        ),
        CRACK_HIDE(
            "crack_hide", "隐藏破解工具",
            "比冻结更彻底：图标从桌面与搜索里消失；锁机结束自动恢复", false
        ),
        KEYGUARD_LOCK(
            "keyguard_lock", "必须用密码解锁",
            "锁机期间禁用指纹/人脸/智能锁（连蓝牙也不会自动解锁）：只能输密码，增加解锁成本；请先确认自己记得密码。默认关",
            false, adminCapable = true
        ),
        CRACK_DETECT(
            "crack_detect", "破解环境检测（只留痕）",
            "锁机期间检测 root / Xposed / 调试器并写入检测日志；不做拒绝解锁，避免误伤与死锁。默认关", false
        )
    }

    /** 加固项是否启用（未设置过时用默认值）。 */
    fun isHardeningEnabled(c: Context, item: Hardening): Boolean {
        val set = prefs(c).getStringSet(KEY_HARDENING, null)
            ?: return item.defaultOn
        return set.contains(item.key)
    }

    /**
     * 一次性迁移（v3.11.64）：已经开过任一项防破解的用户，自动补上「挂起 Shizuku」。
     *
     * 老用户的 KEY_HARDENING 是显式集合，新增项的 defaultOn 对他们不生效；
     * 不迁移的话，「一键开启推荐防破解」的人升级后反而缺了最关键的这一项。
     */
    fun migrateShizukuLock(c: Context) {
        val p = prefs(c)
        if (p.getBoolean(KEY_MIGRATED_SHIZUKU, false)) return
        val current = p.getStringSet(KEY_HARDENING, null)
            ?: Hardening.entries.filter { it.defaultOn }.map { it.key }.toSet()
        val optedIn = setOf(
            Hardening.CRACK_FREEZE.key,
            Hardening.CRACK_HIDE.key,
            Hardening.CRACK_DETECT.key,
            Hardening.A11Y_LOCK.key
        )
        val next = if (current.any { it in optedIn }) current + Hardening.SHIZUKU_LOCK.key else current
        p.edit()
            .putStringSet(KEY_HARDENING, next)
            .putBoolean(KEY_MIGRATED_SHIZUKU, true)
            .apply()
    }

    fun setHardeningEnabled(c: Context, item: Hardening, on: Boolean) {
        val p = prefs(c)
        val current = p.getStringSet(KEY_HARDENING, null)
            ?: Hardening.entries.filter { it.defaultOn }.map { it.key }.toSet()
        val next = current.toMutableSet()
        if (on) next.add(item.key) else next.remove(item.key)
        p.edit().putStringSet(KEY_HARDENING, next).apply()
    }

    /** 当前生效的加固项数量（状态卡展示用）。 */
    fun enabledHardeningCount(c: Context): Int =
        Hardening.entries.count { isHardeningEnabled(c, it) }

    /** 本次锁机的系统限制是否全部施加成功（失败会由巡检重试）。 */
    fun restrictionsApplied(c: Context): Boolean = prefs(c).getBoolean(KEY_RESTRICTED, false)

    /**
     * 一键开启推荐防破解组合。
     *
     * 只收紧、不放宽：打开冻结/隐藏/检测，以及禁强停/清数据、禁安装、禁卸载、
     * 禁 USB、禁调试、禁恢复出厂、强制自动时间。没有 Dhizuku/Shizuku 时，这些开关
     * 会先记下来，等授权后自动生效；界面据实显示缺口，不假装已生效。
     */
    fun enableRecommendedDefense(c: Context) {
        val recommended = setOf(
            Hardening.CRACK_FREEZE,
            Hardening.CRACK_HIDE,
            Hardening.CRACK_DETECT,
            Hardening.NO_DEBUG,
            Hardening.NO_USB,
            Hardening.NO_APPS_CONTROL,
            Hardening.NO_INSTALL,
            Hardening.NO_UNINSTALL,
            Hardening.AUTO_TIME,
            Hardening.A11Y_LOCK,
            Hardening.SHIZUKU_LOCK
        ).map { it.key }
        val p = prefs(c)
        val current = p.getStringSet(KEY_HARDENING, null)
            ?: Hardening.entries.filter { it.defaultOn }.map { it.key }.toSet()
        p.edit()
            .putStringSet(KEY_HARDENING, current + recommended)
            .putBoolean(KEY_FREEZE_ENABLED, true)
            .putBoolean(KEY_BLOCK_RESET, true)
            .apply()
    }

    /** 系统自带（含系统更新版）输入法包名，用作输入法白名单。 */
    private fun systemInputMethods(context: Context): List<String> {
        val pm = context.packageManager
        val intent = android.content.Intent("android.view.inputmethod.InputMethod")
        val services = runCatching { pm.queryIntentServices(intent, 0) }.getOrDefault(emptyList())
        return services.mapNotNull { it.serviceInfo?.packageName }
            .filter { pkg ->
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                ai != null && (ai.flags and (
                    android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    )) != 0
            }
            .distinct()
    }

    private fun isSystemApp(context: Context, pkg: String): Boolean = runCatching {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    /** 只返回本机真实安装的包，避免把不存在的包名传给 DPM 导致整批失败。 */
    private fun installedPackages(context: Context, pkgs: Collection<String>): List<String> =
        pkgs.filter { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }

    /** 读 Global 开关的当前值（读不到就用 fallback，绝不因此中断加固）。 */
    private fun globalFlag(context: Context, key: String, fallback: Boolean): Boolean = runCatching {
        android.provider.Settings.Global.getInt(
            context.contentResolver, key, if (fallback) 1 else 0
        ) != 0
    }.getOrDefault(fallback)

    /**
     * 记下锁机前的 adb / 无线调试开关原值。
     *
     * 必须在**任何**禁调试动作之前调用：一旦加上 DISALLOW_DEBUGGING_FEATURES（或写
     * adb_enabled=0），adb 就已经是关的，此时再读就把「关」当成用户原值，锁机结束
     * 会永久关掉用户原本开着的调试。
     */
    private fun recordShellLockOriginals(app: Context, p: android.content.SharedPreferences) {
        if (p.getBoolean(KEY_SHELL_LOCK_APPLIED, false)) return
        p.edit()
            .putBoolean(KEY_ADB_ORIGINAL, globalFlag(app, Settings.Global.ADB_ENABLED, true))
            .putBoolean(KEY_ADB_WIFI_ORIGINAL, globalFlag(app, GLOBAL_ADB_WIFI_ENABLED, true))
            .putBoolean(KEY_SHELL_LOCK_APPLIED, true)
            .apply()
    }

    /**
     * 清掉破解工具**已经跑起来**的 shell 守护进程。
     *
     * 游龙内置 Stellar（Shizuku 兼容层 + libadb 无线配对）：一旦配对过，
     * 就会留下 `stellar_daemon` / `stellar_server` 这类 shell 身份的常驻进程。
     * 只挂起它的 App 杀不掉这些进程，它们仍能继续 `am force-stop` 我们。
     * 这里借我们自己的 Shizuku（同为 shell 身份）先清掉，其余通道随后关闭。
     *
     * 模式里的方括号是为了避免 pkill 匹配到执行它的这条 `sh -c` 自身。
     * 杀完必须再查一次进程表：部分机型的 toybox 没有 pkill，只看命令是否发起会
     * 误判成「已清理」，于是一整轮锁机都不再重试。
     */
    private fun killCrackDaemons(app: Context): Boolean {
        if (!ShizukuEnhancer.isReady()) return false
        // 用 runForOutput 等它真正跑完：紧接着就要关 adb，异步发起可能被一起掐断
        val out = ShizukuEnhancer.runForOutput(
            "sh", "-c",
            "pkill -f stellar[_]daemon; pkill -f stellar[_]server; " +
                "pkill -f roro[.]stellar; pkill -f com[.]youlong[.]hd; " +
                "sleep 0.3; ps -A 2>/dev/null | grep -E 'stellar[_](daemon|server)|roro[.]stellar'",
            timeoutMs = 3_000L
        )
        // 输出为空 = 进程表里已经找不到这些守护进程
        val cleared = out != null && out.isBlank()
        Log.d(TAG, "清理破解工具 shell 守护：${if (cleared) "已清理" else "仍有残留/无法执行"}")
        return cleared
    }

    /** 恢复锁机期间关掉的 adb / 无线调试开关（幂等；Dhizuku 不在就留到下次重试）。 */
    private fun restoreShellLock(app: Context, p: android.content.SharedPreferences) {
        if (!p.getBoolean(KEY_SHELL_LOCK_APPLIED, false)) return
        if (!DhizukuEnhancer.ensureReady(app)) return
        val adb = p.getBoolean(KEY_ADB_ORIGINAL, true)
        val wifi = p.getBoolean(KEY_ADB_WIFI_ORIGINAL, true)
        val a = DhizukuEnhancer.setGlobalSetting(
            app, android.provider.Settings.Global.ADB_ENABLED, if (adb) "1" else "0"
        )
        val w = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            DhizukuEnhancer.setGlobalSetting(
                app, GLOBAL_ADB_WIFI_ENABLED, if (wifi) "1" else "0"
            )
        } else {
            true  // Android 11 以下没有无线调试开关，无需恢复
        }
        if (a && w) {
            p.edit()
                .remove(KEY_SHELL_LOCK_APPLIED)
                .remove(KEY_ADB_ORIGINAL)
                .remove(KEY_ADB_WIFI_ORIGINAL)
                .apply()
        }
    }

    /**
     * 锁机期间允许运行的无障碍服务：本应用 + 系统无障碍服务。
     *
     * 刻意不保留第三方无障碍服务：破解工具正是靠它们自动点按设置界面、
     * 撤销 Shizuku 授权或关掉守护；锁机结束会恢复为「不限」。
     */
    private fun allowedAccessibilityPackages(context: Context): List<String> {
        val pm = context.packageManager
        val queried = runCatching {
            pm.queryIntentServices(Intent("android.accessibilityservice.AccessibilityService"), 0)
        }.getOrDefault(emptyList()).mapNotNull { it.serviceInfo?.packageName }
        val enabled = runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
        }.getOrNull().orEmpty()
        return (queried + enabled + context.packageName)
            .filter { it == context.packageName || isSystemApp(context, it) }
            .distinct()
    }

    /**
     * 施加启用的加固项，返回**实际生效**的项。
     * 没生效的（工具未就绪 / 系统拒绝）不记账，下一个巡检会重试。
     */
    private fun applyHardening(app: Context): Set<String> {
        val p = prefs(app)
        val applied = p.getStringSet(KEY_HARDENING_APPLIED, emptySet()).orEmpty().toMutableSet()
        val dzReady = DhizukuEnhancer.ensureReady(app)

        // 禁相机：Dhizuku（Device Owner）或系统自带设备管理员（disable-camera）任一可用即可
        if (isHardeningEnabled(app, Hardening.NO_CAMERA)) {
            val ok = (dzReady && DhizukuEnhancer.setCameraDisabled(app, true)) ||
                (AdminEnhancer.isActive(app) && AdminEnhancer.setCameraDisabled(app, true))
            if (ok) applied.add(Hardening.NO_CAMERA.key)
        }
        // 必须用密码解锁：Dhizuku 或系统自带设备管理员任一可用即可
        if (isHardeningEnabled(app, Hardening.KEYGUARD_LOCK)) {
            val flags = android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS or
                android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT or
                android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FACE or
                android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_IRIS
            val ok = (dzReady && DhizukuEnhancer.setKeyguardDisabledFeatures(app, flags)) ||
                (AdminEnhancer.isActive(app) && AdminEnhancer.setKeyguardDisabledFeatures(app, flags))
            if (ok) applied.add(Hardening.KEYGUARD_LOCK.key)
        }
        // 破解环境检测不需要任何特权：放在 Dhizuku 门槛之前，避免“开关开了但没跑”
        if (isHardeningEnabled(app, Hardening.CRACK_DETECT)) {
            // 只留痕：root/Xposed 直接改内存我们挡不住，如实记录，不拒绝解锁
            CrackGuard.reportIfNeeded(app)
        }
        // 其余项都需要 Device Owner 权限（Dhizuku）
        if (!dzReady) return applied

        if (isHardeningEnabled(app, Hardening.AUTO_TIME)) {
            if (!p.contains(KEY_AUTOTIME_ORIGINAL)) {
                val original = runCatching {
                    android.provider.Settings.Global.getInt(
                        app.contentResolver, android.provider.Settings.Global.AUTO_TIME, 1
                    ) == 1
                }.getOrDefault(true)
                p.edit().putBoolean(KEY_AUTOTIME_ORIGINAL, original).apply()
            }
            val a = DhizukuEnhancer.setAutoTimeEnabled(app, true)
            val b = DhizukuEnhancer.setAutoTimeZoneEnabled(app, true)
            if (a || b) applied.add(Hardening.AUTO_TIME.key)
        }
        if (isHardeningEnabled(app, Hardening.NO_CAPTURE) &&
            DhizukuEnhancer.setScreenCaptureDisabled(app, true)
        ) {
            applied.add(Hardening.NO_CAPTURE.key)
        }
        if (isHardeningEnabled(app, Hardening.DENY_PERMS) &&
            DhizukuEnhancer.setPermissionPolicyAutoDeny(app, true)
        ) {
            applied.add(Hardening.DENY_PERMS.key)
        }
        if (isHardeningEnabled(app, Hardening.NO_UPDATE) &&
            DhizukuEnhancer.setSystemUpdateBlocked(app, true)
        ) {
            applied.add(Hardening.NO_UPDATE.key)
        }
        if (isHardeningEnabled(app, Hardening.INPUT_LOCK)) {
            val methods = systemInputMethods(app)
            if (methods.isNotEmpty() && DhizukuEnhancer.setPermittedInputMethods(app, methods)) {
                applied.add(Hardening.INPUT_LOCK.key)
            }
        }
        // 无障碍白名单：断掉破解工具「自动点按撤销 Shizuku 授权/关闭守护」的路径
        if (isHardeningEnabled(app, Hardening.A11Y_LOCK)) {
            val allowed = allowedAccessibilityPackages(app)
            if (allowed.isNotEmpty() &&
                DhizukuEnhancer.setPermittedAccessibilityServices(app, allowed)
            ) {
                applied.add(Hardening.A11Y_LOCK.key)
            }
        }
        // 冻结破解工具的同时锁住授权工具：破解工具常先卸载/替换 Shizuku、Dhizuku
        if (isHardeningEnabled(app, Hardening.CRACK_FREEZE)) {
            val toolchain = installedPackages(app, listOf(SHIZUKU_PKG, DHIZUKU_PKG))
            toolchain.forEach {
                if (!DhizukuEnhancer.setUninstallBlocked(app, it, true)) {
                    Log.w(TAG, "阻止卸载 $it 失败，下轮重试")
                }
            }
        }
        // 挂起 Shizuku：这是「游龙第一下就破锁且无法自愈」的唯一入口——
        // 拿到 shell 后 `am force-stop` / `pm clear` / `pm disable` 都是一条命令的事，
        // 而强行停止后闹钟、看门狗、START_STICKY 全部失效，系统层面无法自动拉起。
        // 所以必须在锁机开始时就把 shell 通道关掉，而不是等它动手后再补救。
        if (isHardeningEnabled(app, Hardening.SHIZUKU_LOCK)) {
            // 原值在锁机开始时已记录；这里只兜底「服务中途才启动/上次记录丢失」
            recordShellLockOriginals(app, p)
            // 每轮锁机只清一次：它可能在上锁前就已完成无线配对并留下 shell 守护进程，
            // 只挂起它的 App 杀不掉这些进程，它们仍能继续 am force-stop 我们。
            if (!p.getBoolean(KEY_DAEMONS_KILLED, false) && killCrackDaemons(app)) {
                p.edit().putBoolean(KEY_DAEMONS_KILLED, true).apply()
            }
            // 关 adb：游龙自带 libadb 配对（不需要 Shizuku 也能拿 shell），这是它的入口
            val adbOff = DhizukuEnhancer.setGlobalSetting(app, Settings.Global.ADB_ENABLED, "0")
            val wifiOff =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    DhizukuEnhancer.setGlobalSetting(
                        app, GLOBAL_ADB_WIFI_ENABLED, "0"
                    )
                } else {
                    true  // Android 11 以下没有无线调试开关
                }
            val shizuku = installedPackages(app, listOf(SHIZUKU_PKG))
            // 没装 Shizuku = 本来就没有这条路
            val shizukuOff = shizuku.isEmpty() ||
                DhizukuEnhancer.setPackagesSuspended(app, shizuku, true) ||
                stillSuspended(app, shizuku.toSet())
            // 三样都断了才算生效：adb 没关掉却记成「已切断」，状态页会骗人
            if (adbOff && wifiOff && shizukuOff) {
                applied.add(Hardening.SHIZUKU_LOCK.key)
            } else {
                Log.w(TAG, "切断 shell 通道未全部成功（adb=$adbOff wifi=$wifiOff shizuku=$shizukuOff），下轮重试")
            }
        }
        if (isHardeningEnabled(app, Hardening.CRACK_HIDE) && CrackGuard.applyHide(app)) {
            applied.add(Hardening.CRACK_HIDE.key)
        }
        return applied
    }

    /** 锁机结束：按记录撤销加固项。工具未就绪时保留记录，下次服务启动再试。 */
    private fun revertHardening(app: Context) {
        val p = prefs(app)
        val applied = p.getStringSet(KEY_HARDENING_APPLIED, emptySet()).orEmpty()
        // 设备管理员（免 Dhizuku）能力先无条件撤销：相机、锁屏特性都不能留残留
        runCatching { AdminEnhancer.setCameraDisabled(app, false) }
        runCatching {
            AdminEnhancer.setKeyguardDisabledFeatures(
                app, android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE
            )
        }
        if (!DhizukuEnhancer.ensureReady(app)) {
            // 只记了「禁相机」这种 admin 也能做的项 → 已经撤完，直接清记录
            if ((applied - Hardening.NO_CAMERA.key - Hardening.KEYGUARD_LOCK.key).isEmpty()) {
                p.edit().putStringSet(KEY_HARDENING_APPLIED, emptySet()).apply()
            } else {
                Log.w(TAG, "Dhizuku 未就绪，加固暂不撤销，稍后重试（记录 ${applied.size} 项）")
            }
            return
        }
        // 无条件撤销全部项：并发写入/记录丢失都不会留下「关不掉的相机」这类残留
        if (true) {
            val original = p.getBoolean(KEY_AUTOTIME_ORIGINAL, true)
            DhizukuEnhancer.setAutoTimeEnabled(app, original)
            DhizukuEnhancer.setAutoTimeZoneEnabled(app, original)
            p.edit().remove(KEY_AUTOTIME_ORIGINAL).apply()
        }
        if (applied.contains(Hardening.NO_CAMERA.key)) DhizukuEnhancer.setCameraDisabled(app, false)
        if (applied.contains(Hardening.NO_CAPTURE.key)) DhizukuEnhancer.setScreenCaptureDisabled(app, false)
        if (applied.contains(Hardening.DENY_PERMS.key)) DhizukuEnhancer.setPermissionPolicyAutoDeny(app, false)
        if (applied.contains(Hardening.NO_UPDATE.key)) DhizukuEnhancer.setSystemUpdateBlocked(app, false)
        if (applied.contains(Hardening.INPUT_LOCK.key)) DhizukuEnhancer.setPermittedInputMethods(app, null)
        if (applied.contains(Hardening.A11Y_LOCK.key)) {
            DhizukuEnhancer.setPermittedAccessibilityServices(app, null)
        }
        // 先恢复 Shizuku 与 adb：锁机结束还挂起/关着，用户其它依赖 Shizuku 的应用会一起失效
        if (applied.contains(Hardening.SHIZUKU_LOCK.key)) {
            restoreShellLock(app, p)
            DhizukuEnhancer.setPackagesSuspended(
                app, installedPackages(app, listOf(SHIZUKU_PKG)), false
            )
        }
        // 授权工具解封不依赖 applied 记录：记录丢失也不能让 Shizuku/Dhizuku 永远卸不掉
        installedPackages(app, listOf(SHIZUKU_PKG, DHIZUKU_PKG)).forEach {
            DhizukuEnhancer.setUninstallBlocked(app, it, false)
        }
        if (applied.contains(Hardening.KEYGUARD_LOCK.key)) {
            DhizukuEnhancer.setKeyguardDisabledFeatures(
                app, android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE
            )
        }
        // 被隐藏的破解工具无条件恢复（记录丢失也不会留下「消失的应用」）
        runCatching { CrackGuard.revertHide(app) }
        p.edit().putStringSet(KEY_HARDENING_APPLIED, emptySet()).apply()
        Log.d(TAG, "锁机结束已撤销 ${applied.size} 项加固")
    }

    /**
     * 需要冻结的娱乐应用：**用户手动标记的** ∪ **AI 学习到的**分类。
     * 只用手动标记的话，绝大多数用户不会去一个个标，功能等于没用；
     * 学习结果里只取娱乐四类，学习/办公/系统不冻。用户标记优先级更高。
     */
    private fun entertainmentPackages(c: Context): Set<String> {
        val store = AppCategoryStore.shared(c)
        val merged = HashMap<String, AppCategory>()
        merged.putAll(store.allLearned())
        merged.putAll(store.allUserOverrides())
        return merged.filterValues { it in ENTERTAINMENT }.keys - c.packageName
    }

    /** 锁机开始。幂等。 */
    fun onLockStart(context: Context) {
        val app = context.applicationContext
        // 锁机优先：撤下应用封锁悬浮窗，避免两层自制窗口抢 z-order
        runCatching { com.focusguard.app.enforce.AppBlockOverlay.hide() }
        val p = prefs(app)
        val editor = p.edit()
        // 先记 adb/无线调试原值，再施加任何禁调试限制：顺序反了会把「已关」当成用户原值
        if (isHardeningEnabled(app, Hardening.SHIZUKU_LOCK)) recordShellLockOriginals(app, p)
        // 记录锁机开始时的授权工具状态：锁机中掉了才能判断是「被破解工具掐掉」
        editor.putBoolean(KEY_SHIZUKU_WAS_READY, ShizukuEnhancer.isReady())
        editor.putBoolean(KEY_SHIZUKU_LOST_LOGGED, false)
        // 破解工具的 shell 守护必须赶在「禁调试 / 关 adb」之前清掉：那两步会让
        // Shizuku 随 adb 一起掉线，之后就再没有 shell 身份去杀这些常驻进程了。
        if (isHardeningEnabled(app, Hardening.SHIZUKU_LOCK) &&
            !p.getBoolean(KEY_DAEMONS_KILLED, false) && killCrackDaemons(app)
        ) {
            editor.putBoolean(KEY_DAEMONS_KILLED, true)
        }
        if (!p.getBoolean(KEY_RESTRICTED, false) && DhizukuEnhancer.ensureReady(app)) {
            val allOk = restrictions(app).all { DhizukuEnhancer.setUserRestriction(app, it, true) }
            // 只有全部设置成功才记账：此前无条件置位，一次失败后整轮锁机不再重试
            if (allOk) editor.putBoolean(KEY_RESTRICTED, true)
            else Log.w(TAG, "部分用户限制设置失败，下个巡检重试")
        }
        // 锁机期间保持「自动设置时间」开启；先记下用户原值，锁机结束还原，
        // 不擅自永久改掉用户「手动设置时间」的偏好（Shizuku 可用时才做）。
        if (ShizukuEnhancer.isReady() && !p.contains(KEY_AUTO_TIME_ORIGINAL)) {
            ShizukuEnhancer.readAutoTime()?.let { editor.putString(KEY_AUTO_TIME_ORIGINAL, it) }
            ShizukuEnhancer.ensureAutoTime()
        }
        // 逐项加固（相机/截图/权限策略/系统更新/输入法/自动时间）
        editor.putStringSet(KEY_HARDENING_APPLIED, applyHardening(app))
        if (p.getStringSet(KEY_SUSPENDED, emptySet()).isNullOrEmpty()) {
            val entertainment = if (isFreezeEnabled(app)) entertainmentPackages(app) else emptySet()
            // 防破解：把能用来掐我们进程 / 自动答题 / 多开绕过的工具一起冻住
            val cracks = if (isHardeningEnabled(app, Hardening.CRACK_FREEZE)) {
                CrackGuard.matched(app)
            } else {
                emptySet()
            }
            val pkgs = entertainment + cracks
            val by = freeze(app, pkgs)
            if (by != null) {
                editor.putStringSet(KEY_SUSPENDED, pkgs)
                editor.putString(KEY_SUSPENDED_BY, by)
            }
            Log.d(TAG, "锁机冻结 ${pkgs.size} 个应用，途径=$by")
        }
        editor.commit()
    }

    /** 锁机结束：撤销全部限制、解冻应用。失败的项保留记录，下次服务启动再试。 */
    fun onLockEnd(context: Context) {
        val app = context.applicationContext
        // 陌生人守卫加的冻结/隐藏先还原（它记的是自己那一份，引用计数在内部处理）
        runCatching { com.focusguard.app.enforce.LockTopGuard.releaseAll(app) }
        val p = prefs(app)
        val editor = p.edit()
        editor.remove(KEY_SHIZUKU_WAS_READY)
        editor.remove(KEY_SHIZUKU_LOST_LOGGED)
        // 下一轮锁机要重新清一次守护进程（期间可能又被拉起过）
        editor.remove(KEY_DAEMONS_KILLED)
        // 必须先撤限制、再恢复 adb：DISALLOW_DEBUGGING_FEATURES 还挂着时写 adb_enabled=1
        // 会被系统挡回去，用户原本开着的调试就再也回不来。
        // 无条件撤销**全量**列表：此前以 KEY_RESTRICTED 为条件，而该标志只在当初
        // 「全部设置成功」时才置位 —— 任一项失败就导致已生效的限制（禁改时间/禁安全模式/
        // 禁安装…）永不撤销，用户被永久限制。撤销全量列表是无害且幂等的。
        if (DhizukuEnhancer.ensureReady(app)) {
            ALL_RESTRICTIONS.forEach { DhizukuEnhancer.setUserRestriction(app, it, false) }
            editor.putBoolean(KEY_RESTRICTED, false)
        }
        runCatching { revertHardening(app) }.onFailure { Log.w(TAG, "撤销加固失败：${it.message}") }
        val autoTimeOriginal = p.getString(KEY_AUTO_TIME_ORIGINAL, null)
        if (autoTimeOriginal != null) {
            ShizukuEnhancer.restoreAutoTime(autoTimeOriginal)
            editor.remove(KEY_AUTO_TIME_ORIGINAL)
        }
        val suspended = p.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        if (suspended.isNotEmpty()) {
            val freed = unfreezeAll(app, p, suspended)
            if (freed) {
                editor.putStringSet(KEY_SUSPENDED, emptySet())
                editor.putString(KEY_SUSPENDED_BY, "")
            }
            Log.d(TAG, "锁机结束解冻 ${suspended.size} 个应用：${if (freed) "已完成" else "未完成，保留记录稍后重试"}")
        }
        editor.commit()
    }

    /**
     * 冻结应用，返回真正生效的途径（dhizuku / shizuku），都失败返回 null。
     *
     * 记录途径是必须的：Android 的挂起状态**按施加者记录**，
     * `pm unsuspend`（shell 身份）解不了 Dhizuku（device owner）施加的挂起。
     */
    private fun freeze(context: Context, pkgs: Set<String>): String? {
        if (pkgs.isEmpty()) return null
        if (DhizukuEnhancer.setPackagesSuspended(context, pkgs, true)) return "dhizuku"
        if (ShizukuEnhancer.suspendPackages(pkgs, true)) return "shizuku"
        return null
    }

    /**
     * 锁机进行中**当场**冻结一个破解工具（守护巡检发现它到前台/盖住锁机页时调用）。
     *
     * 为什么不能只靠锁机开始时的那一批：破解工具可能是锁机开始后才被打开，
     * 甚至是被临时装进来的新包名；等锁机结束再「下轮生效」等于放它进来一次。
     * 挂起会让它的进程立即被杀，它自己的悬浮窗/无障碍界面随之消失。
     *
     * @return true = 已冻结（或本来就在冻结列表里）
     */
    fun freezeCrackNow(context: Context, pkg: String): Boolean {
        val app = context.applicationContext
        if (pkg.isBlank() || pkg == app.packageName) return false
        val p = prefs(app)
        if (pkg in suspendedPackages(app)) return true
        val by = freeze(app, setOf(pkg))
        if (by == null) {
            CrackGuard.report(app, pkg, "无法冻结（Dhizuku/Shizuku 均未就绪），只能反复顶回锁机页")
            return false
        }
        // 两条路径混用时记账为空串：解冻时两条都会试一遍
        val existingBy = p.getString(KEY_SUSPENDED_BY, "").orEmpty()
        val mergedBy = if (existingBy.isEmpty() || existingBy == by) by else ""
        p.edit()
            .putStringSet(KEY_SUSPENDED, suspendedPackages(app) + pkg)
            .putString(KEY_SUSPENDED_BY, mergedBy)
            .apply()
        CrackGuard.report(app, pkg, "打开即冻结（锁机结束自动解冻）")
        return true
    }

    /**
     * 解冻并**逐包验证**，全部确认解除才返回 true。
     *
     * 之前是「两条路径都试，任一成功即当作已解冻」：Shizuku 的 `pm unsuspend`
     * 对 Dhizuku 施加的挂起会返回成功（退出码 0）但包仍然挂起，于是代码清空记录
     * ⇒ 应用永久冻结、重启也救不回来。现在只用同一施主解除 + PackageManager 验证。
     */
    private fun unfreezeAll(
        context: Context,
        p: android.content.SharedPreferences,
        pkgs: Set<String>
    ): Boolean {
        val by = p.getString(KEY_SUSPENDED_BY, "").orEmpty()
        if (by.isEmpty() || by == "dhizuku") DhizukuEnhancer.setPackagesSuspended(context, pkgs, false)
        if (by.isEmpty() || by == "shizuku") ShizukuEnhancer.suspendPackages(pkgs, false)
        // 施主长时间不可用 → 另一条路径也试一次（对别的施加者的挂起是无害空操作）
        if (stillSuspended(context, pkgs)) {
            DhizukuEnhancer.setPackagesSuspended(context, pkgs, false)
            ShizukuEnhancer.suspendPackages(pkgs, false)
        }
        return !stillSuspended(context, pkgs)
    }

    /** 逐包确认是否仍处于挂起状态（权威判据，不依赖命令返回值）。 */
    private fun stillSuspended(context: Context, pkgs: Set<String>): Boolean = pkgs.any { pkg ->
        runCatching {
            val flags = context.packageManager.getPackageInfo(pkg, 0).applicationInfo?.flags ?: 0
            (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        }.getOrDefault(false)
    }

    /** 未锁机时的低频重试：仍有冻结记录就再试，成功才清记录。 */
    fun retryUnfreeze(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        // adb 开关若因 Dhizuku 短暂不可用没恢复，这里补一次（不能一直关着）
        runCatching { restoreShellLock(app, p) }
        val suspended = p.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        if (suspended.isEmpty()) return
        if (unfreezeAll(app, p, suspended)) {
            p.edit().putStringSet(KEY_SUSPENDED, emptySet()).putString(KEY_SUSPENDED_BY, "").commit()
            Log.d(TAG, "残留冻结已解除 ${suspended.size} 个")
        } else {
            Log.w(TAG, "仍有 ${suspended.size} 个应用处于冻结，稍后重试")
        }
    }

    // ── 应用封锁期间的挂起（引用计数） ─────────────────────

    /** 封锁挂起集合。 */
    fun blockFrozen(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCK_FROZEN, emptySet()).orEmpty()

    /**
     * 应用被封锁期间**真正停掉它**（后台播放、画中画、分屏都会中止）。
     *
     * 只盖一层悬浮窗挡不住「后台还在放」；挂起需要 Dhizuku 或 Shizuku，
     * 都没就绪时返回 false，调用方只做「盖住」。
     */
    fun suspendBlock(context: Context, pkg: String): Boolean {
        val app = context.applicationContext
        if (pkg.isBlank()) return false
        val p = prefs(app)
        val current = blockFrozen(app)
        if (pkg in current) return true
        if (pkg in suspendedPackages(app) || pkg in persistentFrozen(app)) {
            // 已经被别的机制挂起了：只登记引用，不重复施加
            p.edit().putStringSet(KEY_BLOCK_FROZEN, current + pkg).apply()
            return true
        }
        val by = freeze(app, setOf(pkg)) ?: return false
        p.edit()
            .putStringSet(KEY_BLOCK_FROZEN, current + pkg)
            .putString(KEY_BLOCK_FROZEN_BY, by)
            .apply()
        Log.d(TAG, "封锁期间已挂起 $pkg（途径=$by）")
        return true
    }

    /**
     * 解除封锁挂起。**引用计数**：锁机期冻结或 AI 手动冻结里还有这个包时，
     * 只清本记录、不真解冻（否则会把别人的冻结一起解掉）。
     */
    fun releaseBlock(context: Context, pkg: String) {
        val app = context.applicationContext
        val p = prefs(app)
        val current = blockFrozen(app)
        if (pkg !in current) return
        p.edit().putStringSet(KEY_BLOCK_FROZEN, current - pkg).apply()
        if (pkg in suspendedPackages(app) || pkg in persistentFrozen(app)) return
        DhizukuEnhancer.setPackagesSuspended(app, setOf(pkg), false)
        ShizukuEnhancer.suspendPackages(setOf(pkg), false)
        Log.d(TAG, "封锁结束已解除 $pkg 的挂起")
    }

    // ── 手动（AI 对话）冻结 ─────────────────────────────

    /** 手动冻结集合（不受锁机结束影响）。 */
    fun persistentFrozen(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PERSIST, emptySet()).orEmpty()

    /** 是否存在需要答题才能解除的冻结（AI 对话下达的）。 */
    fun hasPersistentFreeze(context: Context): Boolean = persistentFrozen(context).isNotEmpty()

    /** 锁机期冻结 ∪ 手动冻结：设置页展示与「强制解冻」用。 */
    fun allFrozen(context: Context): Set<String> =
        suspendedPackages(context) + persistentFrozen(context)

    /** 手动冻结指定应用（需 Dhizuku 或 Shizuku），返回生效途径，失败返回 null。 */
    fun freezePersistent(context: Context, pkgs: Set<String>): String? {
        val app = context.applicationContext
        if (pkgs.isEmpty()) return null
        val p = prefs(app)
        val target = persistentFrozen(app) + pkgs
        val by = freeze(app, target) ?: return null
        p.edit().putStringSet(KEY_PERSIST, target).putString(KEY_PERSIST_BY, by).apply()
        return by
    }

    /**
     * 解除手动冻结（答题通过后调用），逐包验证。
     * 仍在「锁机期冻结」集合里的包不动（那是锁机管的，锁机结束才会解）。
     */
    fun unfreezePersistent(context: Context, pkgs: Set<String>): Boolean {
        val app = context.applicationContext
        val p = prefs(app)
        val current = persistentFrozen(app)
        val release = pkgs.intersect(current) - suspendedPackages(app)
        if (release.isNotEmpty()) {
            DhizukuEnhancer.setPackagesSuspended(app, release, false)
            ShizukuEnhancer.suspendPackages(release, false)
        }
        // 只有**确认已解开**的包才从记录里移除：失败必须保留，
        // 否则又变成「记录丢了、应用还冻着」且用户以为已解冻（旧版本的坑）。
        val released = pkgs.filterNot { stillSuspended(app, setOf(it)) }.toSet()
        val left = current - released
        p.edit()
            .putStringSet(KEY_PERSIST, left)
            .putString(KEY_PERSIST_BY, if (left.isEmpty()) "" else p.getString(KEY_PERSIST_BY, "").orEmpty())
            .apply()
        return left.isEmpty()
    }

    /** 设备上当前**真正**处于挂起状态的应用（不看记录，供「记录丢了但还冻着」自救）。 */
    fun suspendedOnDevice(context: Context): List<String> {
        val pm = context.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        return apps
            .filter { info ->
                info.packageName != context.packageName &&
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
            }
            .map { it.packageName }
            .sorted()
    }

    /**
     * 把设备上所有挂起中的应用强制解冻（两条路径都试 + 验证）。
     *
     * 用于「记录已被清掉、但应用还冻着」的场景 —— 旧版本会在解冻其实失败时
     * 误判成功并清空记录，之后就再也不知道冻过哪些应用了。
     *
     * @return (解开数, 仍挂起数)
     */
    fun forceUnfreezeAllSuspended(context: Context): Pair<Int, Int> {
        val app = context.applicationContext
        // 被「隐藏」的破解工具也要一起恢复：隐藏记录（KEY_HIDDEN）丢了的话，
        // 只有这里能救回来（suspendedOnDevice 看不到隐藏状态）。
        runCatching { CrackGuard.revertHide(app) }
        val targets = suspendedOnDevice(app)
        if (targets.isEmpty()) {
            prefs(app).edit()
                .putStringSet(KEY_SUSPENDED, emptySet()).putString(KEY_SUSPENDED_BY, "")
                .putStringSet(KEY_PERSIST, emptySet()).putString(KEY_PERSIST_BY, "")
                .putStringSet(KEY_BLOCK_FROZEN, emptySet()).putString(KEY_BLOCK_FROZEN_BY, "")
                .apply()
            return 0 to 0
        }
        DhizukuEnhancer.setPackagesSuspended(app, targets, false)
        ShizukuEnhancer.suspendPackages(targets, false)
        val left = suspendedOnDevice(app)
        if (left.isEmpty()) {
            prefs(app).edit()
                .putStringSet(KEY_SUSPENDED, emptySet()).putString(KEY_SUSPENDED_BY, "")
                .putStringSet(KEY_PERSIST, emptySet()).putString(KEY_PERSIST_BY, "")
                .putStringSet(KEY_BLOCK_FROZEN, emptySet()).putString(KEY_BLOCK_FROZEN_BY, "")
                .apply()
        }
        return (targets.size - left.size) to left.size
    }

    /**
     * 设置页的「强制解冻」，由用户自己选路径。
     *
     * 挂起状态是按**施加者**记录的：当初用 Dhizuku 冻结的，用 Shizuku 的
     * `pm unsuspend` 会返回成功但解不开。所以 [by] 传 "dhizuku" / "shizuku"
     * 时只用那一条；传 null 或其他值则两条都试。选定的那条没解开时自动补试
     * 另一条，并把真正生效的施加者写回记录。
     *
     * @return 是否（经 PackageManager 验证）已全部解开
     */
    fun forceUnfreeze(context: Context, by: String? = null): Boolean {
        val app = context.applicationContext
        val p = prefs(app)
        // 强制解冻要连手动冻结一起解开
        val suspended = allFrozen(app)
        if (suspended.isEmpty()) return true
        val only: String? = when (by) {
            "dhizuku", "shizuku" -> by
            else -> null
        }
        if (only == null || only == "dhizuku") {
            DhizukuEnhancer.setPackagesSuspended(app, suspended, false)
        }
        if (only == null || only == "shizuku") {
            ShizukuEnhancer.suspendPackages(suspended, false)
        }
        var ok = !stillSuspended(app, suspended)
        if (!ok && only != null) {
            val other = if (only == "dhizuku") "shizuku" else "dhizuku"
            if (other == "dhizuku") DhizukuEnhancer.setPackagesSuspended(app, suspended, false)
            else ShizukuEnhancer.suspendPackages(suspended, false)
            ok = !stillSuspended(app, suspended)
            if (ok) p.edit().putString(KEY_SUSPENDED_BY, other).commit()
        }
        if (ok) {
            p.edit()
                .putStringSet(KEY_SUSPENDED, emptySet()).putString(KEY_SUSPENDED_BY, "")
                .putStringSet(KEY_PERSIST, emptySet()).putString(KEY_PERSIST_BY, "")
                .commit()
        } else {
            // 没解开的保留在记录里，用户可再点一次或换路径（不要谎报成功）
            val left = suspended.filter { stillSuspended(app, setOf(it)) }.toSet()
            p.edit()
                .putStringSet(KEY_SUSPENDED, left)
                .putStringSet(KEY_PERSIST, left)
                .commit()
        }
        return ok
    }

    /**
     * 锁机中 Shizuku 从「在线」变成「掉线」时，先尝试恢复被破解工具挂起/隐藏的
     * Shizuku，并留一条检测日志。
     *
     * 破解工具（游龙这类「防锁机」工具）拿到 Shizuku 权限后，最直接的做法就是
     * 把 Shizuku 本体挂起/隐藏，让锁机应用失去冻结能力；能自动恢复的部分在这里做，
     * 无法自动恢复的（进程被杀、授权被撤销）如实留痕，不假装还在。
     */
    private fun recoverShizukuIfTampered(app: Context) {
        val p = prefs(app)
        if (!p.getBoolean(KEY_SHIZUKU_WAS_READY, false)) return
        // 我们自己在锁机开始时挂起的：这是防线本身，不能「自愈」掉
        if (isShizukuSuspendedByUs(app)) return
        if (ShizukuEnhancer.isReady()) return
        // 只有 Dhizuku 还能用时才有资格「改别人」；否则只能记录
        if (DhizukuEnhancer.ensureReady(app)) {
            val suspended = installedPackages(app, listOf(SHIZUKU_PKG))
                .filter { stillSuspended(app, setOf(it)) }
            if (suspended.isNotEmpty()) {
                DhizukuEnhancer.setPackagesSuspended(app, suspended, false)
                Log.w(TAG, "检测到 Shizuku 被挂起（疑似破解工具所为），已尝试恢复")
            }
            if (DhizukuEnhancer.isApplicationHidden(app, SHIZUKU_PKG)) {
                DhizukuEnhancer.setApplicationHidden(app, listOf(SHIZUKU_PKG), false)
                Log.w(TAG, "检测到 Shizuku 被隐藏（疑似破解工具所为），已尝试恢复")
            }
        }
        if (!p.getBoolean(KEY_SHIZUKU_LOST_LOGGED, false)) {
            p.edit().putBoolean(KEY_SHIZUKU_LOST_LOGGED, true).apply()
            Log.w(TAG, "锁机中 Shizuku 掉线：冻结能力已降级")
            runCatching {
                com.focusguard.app.data.LogStore(app).addLog(
                    com.focusguard.app.data.DetectionLog(
                        classification = "NEUTRAL",
                        confidence = 1f,
                        reason = "锁机中 Shizuku 掉线：可能被破解工具关闭/撤销授权，冻结能力已降级",
                        action = "NONE",
                        source = "APP_CATEGORY",
                        appLabel = ""
                    )
                )
            }
        }
    }

    /**
     * 锁机期间的定期补设（低频调用，必须后台线程）：
     * 上次没设置成功的限制/加固这里重试，避免「一次失败 = 整轮锁机都没保护」。
     */
    fun reassertWhileLocked(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        runCatching { recoverShizukuIfTampered(app) }
        if (DhizukuEnhancer.ensureReady(app) && !p.getBoolean(KEY_RESTRICTED, false)) {
            val allOk = restrictions(app).all { DhizukuEnhancer.setUserRestriction(app, it, true) }
            if (allOk) p.edit().putBoolean(KEY_RESTRICTED, true).apply()
            else Log.w(TAG, "用户限制补设仍未全部成功，下轮再试")
        }
        val applied = applyHardening(app)
        p.edit().putStringSet(KEY_HARDENING_APPLIED, applied).apply()
    }

    /** 服务启动时调用：不在锁机却有残留限制 / 冻结 → 立即清除。 */
    fun cleanupResidue(context: Context, locked: Boolean) {
        if (!locked) runCatching { onLockEnd(context) }.onFailure { Log.w(TAG, "残留清除失败：${it.message}") }
    }
}
