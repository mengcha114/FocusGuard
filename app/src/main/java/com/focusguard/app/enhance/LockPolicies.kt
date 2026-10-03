package com.focusguard.app.enhance

import android.content.Context
import android.os.UserManager
import android.util.Log
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

    /** AI 对话手动下达的长期冻结（不受锁机结束影响，解冻需答题）。 */
    private const val KEY_PERSIST = "persist_suspended_pkgs"
    private const val KEY_PERSIST_BY = "persist_suspended_by"
    private const val KEY_FREEZE_ENABLED = "freeze_enabled"
    private const val KEY_AUTO_TIME_ORIGINAL = "auto_time_original"
    private const val KEY_BLOCK_RESET = "block_factory_reset"

    /**
     * 「娱乐」类目：游戏、短视频、长视频、社交——即锁机期间应当被冻结的对象。
     * （此前只含游戏/视频，漏掉了社交这类同样消耗时间的应用。）
     */
    private val ENTERTAINMENT = setOf(
        AppCategory.GAME,
        AppCategory.SHORT_VIDEO,
        AppCategory.VIDEO
    )

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isFreezeEnabled(c: Context) = prefs(c).getBoolean(KEY_FREEZE_ENABLED, false)
    fun setFreezeEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_FREEZE_ENABLED, on).apply()
    fun isBlockResetEnabled(c: Context) = prefs(c).getBoolean(KEY_BLOCK_RESET, false)
    fun setBlockResetEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_BLOCK_RESET, on).apply()

    /** 当前锁机已冻结的应用（供设置页展示）。 */
    fun suspendedPackages(c: Context): Set<String> = prefs(c).getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()

    /**
     * 本次锁机要施加的用户限制。
     * 注意：禁用 USB 调试会让依赖 adb 的 Shizuku 立即停止，所以 Shizuku 在运行时不加这一项。
     */
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
        // 调试开关：Shizuku 依赖 adb，它在跑时不施加（默认开，可在设置里关）
        if (!shizukuOn && isHardeningEnabled(c, Hardening.NO_DEBUG)) {
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
        val defaultOn: Boolean
    ) {
        AUTO_TIME(
            "auto_time", "强制自动时间",
            "锁机期间强制「自动设置时间/时区」，改时间缩短不了锁机（原生能力，不需要 Shizuku）", true
        ),
        NO_INSTALL(
            "no_install", "禁止安装应用",
            "锁机期间装不了任何新应用（分屏/虚拟机/破解工具都进不来）；本应用的更新也会被挡", true
        ),
        NO_APPS_CONTROL(
            "no_apps_control", "禁止管理应用",
            "锁机期间在设置里不能强行停止/清除其它应用的数据", true
        ),
        NO_DEBUG(
            "no_debug", "禁止 USB 调试",
            "锁机期间关掉开发者选项与 adb（电脑连不上就卸不了/停不了）；Shizuku 在运行时不施加，否则它会掉线", true
        ),
        NO_USB(
            "no_usb", "禁止 USB 传文件",
            "锁机期间插电脑也看不到手机里的文件", false
        ),
        NO_CAMERA(
            "no_camera", "禁止相机",
            "锁机期间相机打不开（防拍照搜题）", false
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
        LOCK_NOW(
            "lock_now", "执法瞬间锁屏",
            "AI 执法时先把屏幕灭掉，必须重新解锁设备才看到锁机界面", false
        ),
        CRACK_FREEZE(
            "crack_freeze", "冻结破解工具",
            "锁机期间冻结「冰箱/小黑屋/Island/黑阈/Auto.js/自动点击器/多开分身/虚拟机/远程控制/修改器」等能用来破解或自动答题的工具；锁机结束自动解冻", true
        ),
        CRACK_HIDE(
            "crack_hide", "隐藏破解工具",
            "比冻结更彻底：图标从桌面与搜索里消失；锁机结束自动恢复", false
        ),
        CRACK_DETECT(
            "crack_detect", "破解环境检测（只留痕）",
            "锁机期间检测 root / Xposed / 调试器并写入检测日志；不做拒绝解锁，避免误伤与死锁", true
        )
    }

    /** 加固项是否启用（未设置过时用默认值）。 */
    fun isHardeningEnabled(c: Context, item: Hardening): Boolean {
        val set = prefs(c).getStringSet(KEY_HARDENING, null)
            ?: return item.defaultOn
        return set.contains(item.key)
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

    /**
     * 施加启用的加固项，返回**实际生效**的项。
     * 没生效的（工具未就绪 / 系统拒绝）不记账，下一个巡检会重试。
     */
    private fun applyHardening(app: Context): Set<String> {
        val p = prefs(app)
        val applied = p.getStringSet(KEY_HARDENING_APPLIED, emptySet()).orEmpty().toMutableSet()
        if (!DhizukuEnhancer.ensureReady(app)) return applied

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
        if (isHardeningEnabled(app, Hardening.NO_CAMERA) &&
            DhizukuEnhancer.setCameraDisabled(app, true)
        ) {
            applied.add(Hardening.NO_CAMERA.key)
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
        if (isHardeningEnabled(app, Hardening.CRACK_HIDE) && CrackGuard.applyHide(app)) {
            applied.add(Hardening.CRACK_HIDE.key)
        }
        if (isHardeningEnabled(app, Hardening.CRACK_DETECT)) {
            // 只留痕：root/Xposed 直接改内存我们挡不住，如实记录，不拒绝解锁
            CrackGuard.reportIfNeeded(app)
        }
        return applied
    }

    /** 锁机结束：按记录撤销加固项。工具未就绪时保留记录，下次服务启动再试。 */
    private fun revertHardening(app: Context) {
        val p = prefs(app)
        val applied = p.getStringSet(KEY_HARDENING_APPLIED, emptySet()).orEmpty()
        if (!DhizukuEnhancer.ensureReady(app)) {
            Log.w(TAG, "Dhizuku 未就绪，加固暂不撤销，稍后重试（记录 ${applied.size} 项）")
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
        val p = prefs(app)
        val editor = p.edit()
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
        val p = prefs(app)
        runCatching { revertHardening(app) }.onFailure { Log.w(TAG, "撤销加固失败：${it.message}") }
        val editor = p.edit()
        if (p.getBoolean(KEY_RESTRICTED, false) && DhizukuEnhancer.ensureReady(app)) {
            ALL_RESTRICTIONS.forEach { DhizukuEnhancer.setUserRestriction(app, it, false) }
            editor.putBoolean(KEY_RESTRICTED, false)
        }
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
        val suspended = p.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        if (suspended.isEmpty()) return
        if (unfreezeAll(app, p, suspended)) {
            p.edit().putStringSet(KEY_SUSPENDED, emptySet()).putString(KEY_SUSPENDED_BY, "").commit()
            Log.d(TAG, "残留冻结已解除 ${suspended.size} 个")
        } else {
            Log.w(TAG, "仍有 ${suspended.size} 个应用处于冻结，稍后重试")
        }
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
        val targets = suspendedOnDevice(app)
        if (targets.isEmpty()) {
            prefs(app).edit()
                .putStringSet(KEY_SUSPENDED, emptySet()).putString(KEY_SUSPENDED_BY, "")
                .putStringSet(KEY_PERSIST, emptySet()).putString(KEY_PERSIST_BY, "")
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
     * 锁机期间的定期补设（低频调用，必须后台线程）：
     * 上次没设置成功的限制/加固这里重试，避免「一次失败 = 整轮锁机都没保护」。
     */
    fun reassertWhileLocked(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
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
