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
        AppCategory.VIDEO,
        AppCategory.SOCIAL
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
        val store = AppCategoryStore(c)
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
            restrictions(app).forEach { DhizukuEnhancer.setUserRestriction(app, it, true) }
            editor.putBoolean(KEY_RESTRICTED, true)
        }
        // 锁机期间保持「自动设置时间」开启；先记下用户原值，锁机结束还原，
        // 不擅自永久改掉用户「手动设置时间」的偏好（Shizuku 可用时才做）。
        if (ShizukuEnhancer.isReady() && !p.contains(KEY_AUTO_TIME_ORIGINAL)) {
            ShizukuEnhancer.readAutoTime()?.let { editor.putString(KEY_AUTO_TIME_ORIGINAL, it) }
            ShizukuEnhancer.ensureAutoTime()
        }
        if (isFreezeEnabled(app) && p.getStringSet(KEY_SUSPENDED, emptySet()).isNullOrEmpty()) {
            val pkgs = entertainmentPackages(app)
            val ok = pkgs.isNotEmpty() && (
                DhizukuEnhancer.setPackagesSuspended(app, pkgs, true) ||
                    ShizukuEnhancer.suspendPackages(pkgs, true)
                )
            if (ok) editor.putStringSet(KEY_SUSPENDED, pkgs)
            Log.d(TAG, "锁机冻结娱乐应用 ${pkgs.size} 个：$ok")
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
            // 两条路径都尝试，任何一条成功即视为已解冻
            val dz = DhizukuEnhancer.setPackagesSuspended(app, suspended, false)
            val sz = ShizukuEnhancer.suspendPackages(suspended, false)
            if (dz || sz) editor.putStringSet(KEY_SUSPENDED, emptySet())
            Log.d(TAG, "锁机结束解冻 ${suspended.size} 个应用：dhizuku=$dz shizuku=$sz")
        }
        editor.commit()
    }

    /** 服务启动时调用：不在锁机却有残留限制 / 冻结 → 立即清除。 */
    fun cleanupResidue(context: Context, locked: Boolean) {
        if (!locked) runCatching { onLockEnd(context) }.onFailure { Log.w(TAG, "残留清除失败：${it.message}") }
    }
}
