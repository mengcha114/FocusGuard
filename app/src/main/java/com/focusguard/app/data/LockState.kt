package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 锁机状态的持久化存储。
 *
 * 定时锁机与番茄钟的倒计时必须存到磁盘，
 * 否则进程被系统杀掉或用户重启应用后锁机状态就会丢失，
 * 用户可以通过"杀进程"绕过锁机，功能形同虚设。
 */
class LockState(context: Context) {

    companion object {
        private const val PREFS = "focus_guard_lock_state"
        private const val KEY_LOCK_UNTIL = "lock_until"
        private const val KEY_LOCK_SOURCE = "lock_source"
        private const val KEY_POMODORO_END = "pomodoro_end"
        private const val KEY_POMODORO_IS_WORK = "pomodoro_is_work"
        private const val KEY_POMODORO_RUNNING = "pomodoro_running"
        private const val KEY_POMODORO_DONE_DATE = "pomodoro_done_date"
        private const val KEY_POMODORO_DONE_COUNT = "pomodoro_done_count"
        private const val KEY_POMODORO_ROUNDS_LEFT = "pomodoro_rounds_left"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 定时锁机 ──────────────────────────────────────

    /** 锁机截止时间戳，0 表示未锁机。 */
    var lockUntil: Long
        get() = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCK_UNTIL, value).apply()

    /** 锁机来源：TIMER / AI / POMODORO。 */
    var lockSource: String
        get() = prefs.getString(KEY_LOCK_SOURCE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LOCK_SOURCE, value).apply()

    val isLocked: Boolean
        get() = lockUntil > System.currentTimeMillis()

    val remainingSeconds: Int
        get() = ((lockUntil - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0L)
            .toInt()

    fun startLock(minutes: Int, source: String) {
        lockUntil = System.currentTimeMillis() + minutes * 60_000L
        lockSource = source
    }

    fun releaseLock() {
        lockUntil = 0L
        lockSource = ""
    }

    // ── 番茄钟 ────────────────────────────────────────

    /** 当前阶段结束时间戳，0 表示未启动。 */
    var pomodoroEnd: Long
        get() = prefs.getLong(KEY_POMODORO_END, 0L)
        set(value) = prefs.edit().putLong(KEY_POMODORO_END, value).apply()

    var pomodoroIsWorkPhase: Boolean
        get() = prefs.getBoolean(KEY_POMODORO_IS_WORK, true)
        set(value) = prefs.edit().putBoolean(KEY_POMODORO_IS_WORK, value).apply()

    var pomodoroRunning: Boolean
        get() = prefs.getBoolean(KEY_POMODORO_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_POMODORO_RUNNING, value).apply()

    /** 今日完成的番茄钟数量，跨天自动归零。 */
    var pomodoroCompletedToday: Int
        get() {
            val storedDate = prefs.getString(KEY_POMODORO_DONE_DATE, "") ?: ""
            return if (storedDate == today()) {
                prefs.getInt(KEY_POMODORO_DONE_COUNT, 0)
            } else {
                0
            }
        }
        set(value) = prefs.edit()
            .putString(KEY_POMODORO_DONE_DATE, today())
            .putInt(KEY_POMODORO_DONE_COUNT, value)
            .apply()

    /** 番茄钟剩余轮数。 */
    var pomodoroRoundsLeft: Int
        get() = prefs.getInt(KEY_POMODORO_ROUNDS_LEFT, 0)
        set(value) = prefs.edit().putInt(KEY_POMODORO_ROUNDS_LEFT, value.coerceAtLeast(0)).apply()

    /**
     * 当前是否应该处于「屏幕被锁住」的状态。
     *
     * 普通锁机：整段时间都锁。
     * 番茄钟：只有专注阶段锁，休息阶段放开让用户自由使用。
     */
    val shouldBlockNow: Boolean
        get() {
            if (!isLocked) return false
            if (lockSource != "POMODORO") return true
            // 番茄钟模式下，休息阶段不阻塞
            return pomodoroIsWorkPhase
        }

    /** 番茄钟阶段剩余秒数。 */
    val pomodoroRemainingSeconds: Int
        get() = ((pomodoroEnd - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0L)
            .toInt()

    /**
     * 推进番茄钟到下一阶段。返回 true 表示整个番茄钟序列已结束。
     */
    fun advancePomodoroPhase(): Boolean {
        return if (pomodoroIsWorkPhase) {
            // 专注结束 → 进入休息
            pomodoroCompletedToday += 1
            pomodoroIsWorkPhase = false
            pomodoroEnd = System.currentTimeMillis() + 5 * 60_000L
            false
        } else {
            // 休息结束 → 进入下一轮专注，或全部结束
            val left = pomodoroRoundsLeft - 1
            pomodoroRoundsLeft = left
            if (left <= 0) {
                pomodoroRunning = false
                releaseLock()
                true
            } else {
                pomodoroIsWorkPhase = true
                pomodoroEnd = System.currentTimeMillis() + 25 * 60_000L
                false
            }
        }
    }

    private fun today(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd",
        java.util.Locale.getDefault()
    ).format(java.util.Date())
}
