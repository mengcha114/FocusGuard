package com.focusguard.app.token

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Token 预算与配额管理。
 *
 * 即使前面所有省 token 的手段都失效（比如用户一直在陌生应用里切换页面），
 * 预算上限也能保证费用不会失控。这是最后一道防线。
 *
 * 设计要点：
 * 1. 按天配额，跨天自动重置
 * 2. 记录累计消耗，界面上可以直接看到花了多少
 * 3. 配额耗尽后退化为纯本地规则判定，功能不中断、只是精度下降
 * 4. 自适应间隔：长时间判定稳定时自动拉长检测周期，进一步减少调用
 */
class TokenBudget(context: Context) {

    companion object {
        private const val PREFS = "focus_guard_token_budget"
        private const val KEY_DATE = "budget_date"
        private const val KEY_CALLS_TODAY = "calls_today"
        private const val KEY_TOKENS_TODAY = "tokens_today"
        private const val KEY_SAVED_CALLS_TODAY = "saved_calls_today"
        private const val KEY_DAILY_CALL_LIMIT = "daily_call_limit"
        private const val KEY_TOTAL_TOKENS = "total_tokens"
        private const val KEY_TOTAL_CALLS = "total_calls"
        private const val KEY_TOTAL_SAVED = "total_saved"

        /** 一次低分辨率截图 + 提示词的粗略 token 估算值。 */
        const val ESTIMATED_TOKENS_PER_CALL = 480
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        rolloverIfNewDay()
    }

    /** 每日最多允许的大模型调用次数，0 表示不限制。 */
    var dailyCallLimit: Int
        get() = prefs.getInt(KEY_DAILY_CALL_LIMIT, 120)
        set(value) = prefs.edit().putInt(KEY_DAILY_CALL_LIMIT, value.coerceAtLeast(0)).apply()

    val callsToday: Int
        get() { rolloverIfNewDay(); return prefs.getInt(KEY_CALLS_TODAY, 0) }

    val tokensToday: Int
        get() { rolloverIfNewDay(); return prefs.getInt(KEY_TOKENS_TODAY, 0) }

    /** 今日被本地手段拦下、成功避免的调用次数。 */
    val savedCallsToday: Int
        get() { rolloverIfNewDay(); return prefs.getInt(KEY_SAVED_CALLS_TODAY, 0) }

    val totalTokens: Int
        get() = prefs.getInt(KEY_TOTAL_TOKENS, 0)

    val totalCalls: Int
        get() = prefs.getInt(KEY_TOTAL_CALLS, 0)

    val totalSavedCalls: Int
        get() = prefs.getInt(KEY_TOTAL_SAVED, 0)

    /** 今日剩余可用调用次数，不限制时返回 Int.MAX_VALUE。 */
    val remainingCallsToday: Int
        get() {
            val limit = dailyCallLimit
            if (limit <= 0) return Int.MAX_VALUE
            return (limit - callsToday).coerceAtLeast(0)
        }

    fun canCallAi(): Boolean = remainingCallsToday > 0

    /**
     * 今日节约率（0-100）。
     * 分母是"如果不做任何优化本该发生的调用总数"。
     */
    fun savedPercentToday(): Int {
        val actual = callsToday
        val saved = savedCallsToday
        val total = actual + saved
        if (total == 0) return 100
        return saved * 100 / total
    }

    /** 记录一次真实的大模型调用。 */
    fun recordCall(estimatedTokens: Int = ESTIMATED_TOKENS_PER_CALL) {
        rolloverIfNewDay()
        prefs.edit()
            .putInt(KEY_CALLS_TODAY, prefs.getInt(KEY_CALLS_TODAY, 0) + 1)
            .putInt(KEY_TOKENS_TODAY, prefs.getInt(KEY_TOKENS_TODAY, 0) + estimatedTokens)
            .putInt(KEY_TOTAL_CALLS, prefs.getInt(KEY_TOTAL_CALLS, 0) + 1)
            .putInt(KEY_TOTAL_TOKENS, prefs.getInt(KEY_TOTAL_TOKENS, 0) + estimatedTokens)
            .apply()
    }

    /** 记录一次被本地手段成功避免的调用。 */
    fun recordSavedCall() {
        rolloverIfNewDay()
        prefs.edit()
            .putInt(KEY_SAVED_CALLS_TODAY, prefs.getInt(KEY_SAVED_CALLS_TODAY, 0) + 1)
            .putInt(KEY_TOTAL_SAVED, prefs.getInt(KEY_TOTAL_SAVED, 0) + 1)
            .apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    private fun rolloverIfNewDay() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val stored = prefs.getString(KEY_DATE, null)
        if (stored != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_CALLS_TODAY, 0)
                .putInt(KEY_TOKENS_TODAY, 0)
                .putInt(KEY_SAVED_CALLS_TODAY, 0)
                .apply()
        }
    }
}
