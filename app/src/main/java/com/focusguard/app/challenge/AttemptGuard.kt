package com.focusguard.app.challenge

import android.content.Context
import android.content.SharedPreferences
import com.focusguard.app.data.LockClock
import com.focusguard.app.data.SystemLockClock

/**
 * 答题防试错规则（锁机答题、设置放宽验证、停止守护、应用管控共用）。
 *
 * 规则：
 * - 每道题答错立即换题，不允许同一道题反复试（选择题四选一，反复试必中）；
 * - 连续答错 [MAX_WRONG] 次后进入 [COOLDOWN_MS] 冷却，期间不能作答；
 * - 「换一题」每个会话最多 [MAX_REFRESH] 次；
 * - 答对后错误计数清零；
 * - 冷却用单调时钟：退出弹窗、杀进程、改系统时间都不能跳过；重启后按整段冷却重新计时。
 *
 * 不同场景用不同 [scope] 隔离计数（锁机答题与设置验证互不影响）。
 */
class AttemptGuard internal constructor(
    private val prefs: SharedPreferences,
    private val clock: LockClock,
    private val scope: String
) {

    constructor(context: Context, scope: String) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        SystemLockClock(context),
        scope
    )

    companion object {
        private const val PREFS = "focus_guard_attempt_guard"

        /** 连续答错多少次进入冷却。 */
        const val MAX_WRONG = 2

        /** 冷却时长。 */
        const val COOLDOWN_MS = 5 * 60_000L

        /** 每个会话「换一题」次数上限。 */
        const val MAX_REFRESH = 5

        /** 场景：锁机答题解锁 / 暂停。 */
        const val SCOPE_LOCK = "lock"

        /** 场景：设置页放宽限制、停止守护、应用管控规则修改。 */
        const val SCOPE_VERIFY = "verify"
    }

    private fun k(name: String) = "${scope}_$name"

    /** 当前连续答错次数。 */
    val wrongCount: Int get() = prefs.getInt(k("wrong"), 0)

    /** 冷却前还能答错几次。 */
    val wrongLeft: Int get() = (MAX_WRONG - wrongCount).coerceAtLeast(0)

    /** 已用换题次数。 */
    val refreshUsed: Int get() = prefs.getInt(k("refresh"), 0)

    val refreshLeft: Int get() = (MAX_REFRESH - refreshUsed).coerceAtLeast(0)

    val canRefresh: Boolean get() = refreshLeft > 0 && !isInCooldown

    /** 冷却剩余毫秒。 */
    val cooldownRemainingMs: Long
        get() {
            val base = prefs.getLong(k("cooldown_base"), 0L)
            if (base <= 0L) return 0L
            val left = COOLDOWN_MS - (clock.elapsed() - base)
            // 重启后单调时钟归零（base 成了「未来」值）：按整段冷却重新计时，绝不直接放行
            if (left > COOLDOWN_MS) {
                prefs.edit().putLong(k("cooldown_base"), clock.elapsed()).commit()
                return COOLDOWN_MS
            }
            if (left <= 0L) {
                // 冷却结束：清零错误计数，开始新一轮
                prefs.edit().putLong(k("cooldown_base"), 0L).putInt(k("wrong"), 0).commit()
                return 0L
            }
            return left
        }

    val isInCooldown: Boolean get() = cooldownRemainingMs > 0

    /** 冷却剩余秒数（向上取整，用于界面倒计时）。 */
    val cooldownSeconds: Int get() = ((cooldownRemainingMs + 999) / 1000).toInt()

    /**
     * 记录一次答错（含超时）。
     * @return true = 因此进入冷却
     */
    fun recordWrong(): Boolean {
        if (isInCooldown) return true
        val n = wrongCount + 1
        val editor = prefs.edit().putInt(k("wrong"), n)
        val enterCooldown = n >= MAX_WRONG
        if (enterCooldown) editor.putLong(k("cooldown_base"), clock.elapsed())
        editor.commit()
        return enterCooldown
    }

    /** 答对：错误计数清零。 */
    fun recordCorrect() {
        prefs.edit().putInt(k("wrong"), 0).commit()
    }

    /**
     * 消耗一次换题机会。
     * @return 是否成功（冷却中或次数用完返回 false）
     */
    fun tryRefresh(): Boolean {
        if (!canRefresh) return false
        prefs.edit().putInt(k("refresh"), refreshUsed + 1).commit()
        return true
    }

    /**
     * 开启新会话：换题次数清零（错误计数与冷却保留，防止关掉弹窗重开刷新）。
     * 锁机场景在新一轮锁机开始时调用；验证场景在验证通过后调用。
     */
    fun resetSession() {
        prefs.edit().putInt(k("refresh"), 0).commit()
    }

    /** 完全清零（新一轮锁机开始 / 锁机结束时调用）。 */
    fun resetAll() {
        prefs.edit()
            .putInt(k("refresh"), 0)
            .putInt(k("wrong"), 0)
            .putLong(k("cooldown_base"), 0L)
            .commit()
    }

    /** 冷却倒计时文案，如「4:59」。 */
    fun cooldownText(): String {
        val s = cooldownSeconds
        return "%d:%02d".format(s / 60, s % 60)
    }
}
