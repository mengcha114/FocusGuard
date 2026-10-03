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
    private fun restrictions(c: Context): List<String> = buildList {
        add(UserManager.DISALLOW_CONFIG_DATE_TIME)  // 禁止改系统时间
        add(UserManager.DISALLOW_SAFE_BOOT)         // 禁止安全模式（会禁用第三方应用）
        add(UserManager.DISALLOW_ADD_USER)          // 禁止新建用户绕过
        if (!ShizukuEnhancer.isAvailable()) add(UserManager.DISALLOW_DEBUGGING_FEATURES)
        if (isBlockResetEnabled(c)) add(UserManager.DISALLOW_FACTORY_RESET)
    }

    private val ALL_RESTRICTIONS = listOf(
        UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_FACTORY_RESET
    )

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
        if (isFreezeEnabled(app) && p.getStringSet(KEY_SUSPENDED, emptySet()).isNullOrEmpty()) {
            val pkgs = entertainmentPackages(app)
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
        val suspended = p.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
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
            p.edit().putStringSet(KEY_SUSPENDED, emptySet()).putString(KEY_SUSPENDED_BY, "").commit()
        }
        return ok
    }

    /** 服务启动时调用：不在锁机却有残留限制 / 冻结 → 立即清除。 */
    fun cleanupResidue(context: Context, locked: Boolean) {
        if (!locked) runCatching { onLockEnd(context) }.onFailure { Log.w(TAG, "残留清除失败：${it.message}") }
    }
}
