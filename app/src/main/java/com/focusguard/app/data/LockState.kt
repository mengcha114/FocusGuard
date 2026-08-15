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

        // ── 时间篡改防御：单调时钟基准（elapsedRealtime 不受用户改系统时间影响） ──
        private const val KEY_LOCK_ELAPSED_BASE = "lock_elapsed_base"
        private const val KEY_LOCK_DURATION_MS = "lock_duration_ms"
        private const val KEY_LOCK_WALL_BASE = "lock_wall_base"

        /** 墙钟与单调钟偏差超过此值（毫秒）判定为时间篡改。 */
        const val TIME_TAMPER_THRESHOLD_MS = 3 * 60_000L

        /** 单次锁机内「换一题」按钮可用次数上限（持久化：退出重进不重置）。 */
        private const val KEY_CHALLENGE_REFRESHES = "challenge_refreshes"
        private const val MAX_CHALLENGE_REFRESHES = 5


        private const val KEY_LOCK_SOURCE = "lock_source"
        private const val KEY_LOCK_STRENGTH = "lock_strength"
        private const val KEY_FRIEND_CIPHER = "friend_cipher"
        private const val KEY_FRIEND_SHIFT = "friend_shift"
        private const val KEY_PAUSE_ENABLED = "pause_enabled"
        private const val KEY_PAUSE_QUOTA = "pause_quota"
        private const val KEY_PAUSE_USED = "pause_used"
        private const val KEY_PAUSE_MINUTES = "pause_minutes"
        private const val KEY_PAUSE_UNTIL = "pause_until"
        private const val KEY_POMODORO_END = "pomodoro_end"
        private const val KEY_POMODORO_IS_WORK = "pomodoro_is_work"
        private const val KEY_POMODORO_RUNNING = "pomodoro_running"
        private const val KEY_POMODORO_DONE_DATE = "pomodoro_done_date"
        private const val KEY_POMODORO_DONE_COUNT = "pomodoro_done_count"
        private const val KEY_POMODORO_ROUNDS_LEFT = "pomodoro_rounds_left"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // 每次构造时校准重启基准：elapsedRealtime 在设备重启时归零，
        // 若持久化的基准来自上一次启动周期（base > 当前 elapsed），
        // 不校准会把剩余时长算成「原时长 + 设备已运行时长」（用户反馈
        // 重启后锁机变成 100 多小时）。用墙钟剩余重新锚定即可恢复。
        recalibrateAfterReboot()
    }

    /**
     * 重启校准：检测单调时钟基准是否来自上一次启动周期，是则用墙钟剩余
     * 重新锚定（防篡改基准在新周期重新生效，锁定时长不变）。
     */
    private fun recalibrateAfterReboot() {
        val base = prefs.getLong(KEY_LOCK_ELAPSED_BASE, 0L)
        if (base <= 0L) return
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (base <= nowElapsed) return
        // 设备重启过（elapsed 归零，base 成了"未来"值）
        val wallRemaining = (prefs.getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis())
            .coerceAtLeast(0L)
        if (wallRemaining <= 0L) {
            // 墙钟剩余已尽（重启期间已到期）：清锁
            prefs.edit()
                .putLong(KEY_LOCK_UNTIL, 0L)
                .putLong(KEY_LOCK_ELAPSED_BASE, 0L)
                .putLong(KEY_LOCK_DURATION_MS, 0L)
                .putLong(KEY_LOCK_WALL_BASE, 0L)
                .apply()
        } else {
            // 用墙钟剩余重新锚定新启动周期
            prefs.edit()
                .putLong(KEY_LOCK_ELAPSED_BASE, nowElapsed)
                .putLong(KEY_LOCK_DURATION_MS, wallRemaining)
                .putLong(KEY_LOCK_WALL_BASE, System.currentTimeMillis())
                .apply()
        }
    }

    // ── 定时锁机 ──────────────────────────────────────

    /** 锁机截止时间戳（墙钟，兼容旧数据/展示），0 表示未锁机。 */
    var lockUntil: Long
        get() = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCK_UNTIL, value).apply()

    /** 锁机起始时的单调时钟（elapsedRealtime），>0 表示启用防篡改基准。 */
    private var lockElapsedBase: Long
        get() = prefs.getLong(KEY_LOCK_ELAPSED_BASE, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCK_ELAPSED_BASE, value).apply()

    /** 锁机总时长（毫秒）。 */
    private var lockDurationMs: Long
        get() = prefs.getLong(KEY_LOCK_DURATION_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCK_DURATION_MS, value).apply()

    /** 锁机起始时的墙钟（用于篡改检测的预期值计算）。 */
    private var lockWallBase: Long
        get() = prefs.getLong(KEY_LOCK_WALL_BASE, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCK_WALL_BASE, value).apply()

    /** 锁机来源：TIMER / AI / POMODORO。 */
    var lockSource: String
        get() = prefs.getString(KEY_LOCK_SOURCE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LOCK_SOURCE, value).apply()

    /** 单调时钟上的剩余毫秒数（防篡改核心）。 */
    private val remainingMs: Long
        get() = if (lockElapsedBase > 0) {
            (lockDurationMs - (android.os.SystemClock.elapsedRealtime() - lockElapsedBase))
                .coerceAtLeast(0L)
        } else {
            (lockUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        }

    val isLocked: Boolean
        get() = remainingMs > 0

    val remainingSeconds: Int
        get() = (remainingMs / 1000).toInt()

    /**
     * 检测时间篡改：返回墙钟与单调钟推进的偏差（毫秒，绝对值）。
     * 用户把系统时间前后拨动超过 [TIME_TAMPER_THRESHOLD_MS] 时非零。
     */
    fun detectTimeTamper(): Long {
        if (lockElapsedBase <= 0) return 0L
        val expectedWall = lockWallBase + (android.os.SystemClock.elapsedRealtime() - lockElapsedBase)
        return Math.abs(System.currentTimeMillis() - expectedWall)
    }

    /** 时间篡改惩罚：按偏差幅度追加锁定时长（上限 30 分钟）。 */
    fun applyTamperPenalty(tamperMs: Long) {
        if (tamperMs <= 0L || lockElapsedBase <= 0) return
        val extra = (tamperMs * 2).coerceIn(5 * 60_000L, 30 * 60_000L)
        lockDurationMs += extra
        lockUntil = System.currentTimeMillis() + remainingMs
    }

    // ── 锁机期间「换一题」次数（防破解） ──────────────
    // 存 prefs 持久化：用户退出答题页再重进，次数不重置
    // （之前放在 Compose remember 里，退出重进就清零——被用户发现
    // "切换次数用完后再退出重进就又可以切换了"）。锁机结束自动归零。

    /** 本次锁机已使用的「换一题」次数。 */
    var challengeRefreshCount: Int
        get() = prefs.getInt(KEY_CHALLENGE_REFRESHES, 0)
        set(value) = prefs.edit().putInt(KEY_CHALLENGE_REFRESHES, value.coerceAtLeast(0)).apply()

    /** 记录一次换题。返回是否已达上限（≥5 次）。 */
    fun recordChallengeRefresh(): Boolean {
        challengeRefreshCount = challengeRefreshCount + 1
        return challengeRefreshCount >= MAX_CHALLENGE_REFRESHES
    }

    fun startLock(minutes: Int, source: String) {
        val durationMs = minutes * 60_000L
        lockElapsedBase = android.os.SystemClock.elapsedRealtime()
        lockWallBase = System.currentTimeMillis()
        lockDurationMs = durationMs
        lockUntil = lockWallBase + durationMs
        lockSource = source
        // 新一轮锁机：重置「换一题」次数
        challengeRefreshCount = 0
    }



    /**
     * 解锁强度（1-4）：
     * 1 = 答对 1 题解锁
     * 2 = 连续答对 5 题解锁
     * 3 = 朋友辅助：手机显示凯撒密文+偏移，朋友解密后输入密码解锁
     * 4 = 无法解锁，只能等时间结束
     */
    var unlockStrength: Int
        get() = prefs.getInt(KEY_LOCK_STRENGTH, 1)
        set(value) = prefs.edit().putInt(KEY_LOCK_STRENGTH, value.coerceIn(1, 4)).apply()

    /** 强度 3 的凯撒密文。 */
    var friendCipher: String
        get() = prefs.getString(KEY_FRIEND_CIPHER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FRIEND_CIPHER, value).apply()

    /** 强度 3 的凯撒偏移量。 */
    var friendShift: Int
        get() = prefs.getInt(KEY_FRIEND_SHIFT, 0)
        set(value) = prefs.edit().putInt(KEY_FRIEND_SHIFT, value).apply()

    /** 生成并保存强度 3 的密文与偏移。 */
    fun setupFriendChallenge() {
        val (cipher, shift) = com.focusguard.app.util.CaesarHelper.generateChallenge()
        friendCipher = cipher
        friendShift = shift
    }

    /** 强度 3：验证朋友解密出的密码。 */
    fun verifyFriendPassword(input: String): Boolean {
        val plain = com.focusguard.app.util.CaesarHelper.decrypt(friendCipher, friendShift)
        return plain.isNotEmpty() && input.trim().equals(plain, ignoreCase = true)
    }

    fun releaseLock() {
        lockUntil = 0L
        lockElapsedBase = 0L
        lockDurationMs = 0L
        lockWallBase = 0L
        lockSource = ""
        friendCipher = ""
        friendShift = 0
        pauseUsed = 0
        pauseUntil = 0L
        // 锁机结束：重置「换一题」次数
        challengeRefreshCount = 0
    }

    // ── 锁机暂停（需答题获取暂停时长） ─────────────────

    /** 是否允许锁机中途暂停。 */
    var pauseEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAUSE_ENABLED, value).apply()

    /** 锁机期间允许暂停的总次数。 */
    var pauseQuota: Int
        get() = prefs.getInt(KEY_PAUSE_QUOTA, 3)
        set(value) = prefs.edit().putInt(KEY_PAUSE_QUOTA, value.coerceAtLeast(0)).apply()

    /** 已使用的暂停次数。 */
    var pauseUsed: Int
        get() = prefs.getInt(KEY_PAUSE_USED, 0)
        set(value) = prefs.edit().putInt(KEY_PAUSE_USED, value.coerceAtLeast(0)).apply()

    /** 每次暂停的时长（分钟）。 */
    var pauseMinutes: Int
        get() = prefs.getInt(KEY_PAUSE_MINUTES, 5)
        set(value) = prefs.edit().putInt(KEY_PAUSE_MINUTES, value.coerceIn(1, 60)).apply()

    /** 当前暂停截止时间戳，0 表示未在暂停中。 */
    var pauseUntil: Long
        get() = prefs.getLong(KEY_PAUSE_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_PAUSE_UNTIL, value).apply()

    /** 是否正处于暂停中（暂停期间不拦截）。 */
    val isPaused: Boolean
        get() = pauseUntil > System.currentTimeMillis()

    /** 暂停剩余秒数。 */
    val pauseRemainingSeconds: Int
        get() = ((pauseUntil - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0L)
            .toInt()

    /** 是否还有暂停配额可用。 */
    val canPause: Boolean
        get() = pauseEnabled && pauseUsed < pauseQuota && !isPaused

    /** 开始一次暂停（消耗一次配额）。 */
    fun startPause() {
        if (!canPause) return
        pauseUntil = System.currentTimeMillis() + pauseMinutes * 60_000L
        pauseUsed += 1
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
     * 普通锁机：整段时间都锁（暂停中除外）。
     * 番茄钟：只有专注阶段锁，休息阶段放开让用户自由使用。
     */
    val shouldBlockNow: Boolean
        get() {
            if (!isLocked) return false
            if (isPaused) return false
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
