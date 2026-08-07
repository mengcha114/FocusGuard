package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用临时封锁存储（「仅锁该软件」模式）。
 *
 * 与 [com.focusguard.app.usage.UsageRuleStore] 的区别：
 * - UsageRuleStore 是用户主动配置的长期规则（每日限额）
 * - 本存储是 **AI 执法动态下发**的临时封锁：判定某应用娱乐后，
 *   封锁到 [block] 设定的截止时间；到期自动失效，无需手动清理。
 *
 * 语义：封锁期间用户打开该应用 → 全屏 AppBlockActivity 挡住，
 * 退出该应用去用别的则不受影响（这正是"仅锁该软件"）。
 */
class AppBlockStore(context: Context) {

    companion object {
        private const val PREFS = "focus_guard_app_block"
        private const val KEY_PREFIX = "block_until_"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 封锁某应用到指定截止时间（毫秒时间戳）。 */
    fun block(packageName: String, untilMillis: Long) {
        prefs.edit().putLong(KEY_PREFIX + packageName, untilMillis).apply()
    }

    /** 该应用当前是否处于封锁期。 */
    fun isBlocked(packageName: String): Boolean =
        blockedUntil(packageName) > System.currentTimeMillis()

    /** 封锁截止时间戳，未封锁返回 0。 */
    fun blockedUntil(packageName: String): Long {
        val until = prefs.getLong(KEY_PREFIX + packageName, 0L)
        if (until <= 0) return 0L
        if (until <= System.currentTimeMillis()) {
            // 已过期：顺手清理
            prefs.edit().remove(KEY_PREFIX + packageName).apply()
            return 0L
        }
        return until
    }

    /** 解除封锁。 */
    fun clear(packageName: String) {
        prefs.edit().remove(KEY_PREFIX + packageName).apply()
    }
}
