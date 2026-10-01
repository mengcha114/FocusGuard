package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 锁机状态的持久化存储。
 *
 * 定时锁机与番茄钟的倒计时必须存到磁盘，
 * 否则进程被系统杀掉或用户重启应用后锁机状态就会丢失，
 * 用户可以通过"杀进程"绕过锁机，功能形同虚设。
 *
 * 计时原则：锁机、暂停、番茄钟阶段**全部**以单调时钟为准（[LockClock.elapsed]），
 * 墙钟只用于展示与篡改检测。改系统时间无法缩短任何一段计时。
 */
class LockState internal constructor(
    private val prefs: SharedPreferences,
    private val clock: LockClock
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        SystemLockClock(context)
    )

    companion object {
        private const val PREFS = "focus_guard_lock_state"
        private const val KEY_LOCK_UNTIL = "lock_until"

        // ── 单调时钟基准 ──
        private const val KEY_LOCK_ELAPSED_BASE = "lock_elapsed_base"
        private const val KEY_LOCK_DURATION_MS = "lock_duration_ms"
        private const val KEY_LOCK_WALL_BASE = "lock_wall_base"
        private const val KEY_BOOT_COUNT = "lock_boot_count"

        // ── 存活快照：守护心跳定期写入，用于重启恢复与「被杀时长」补回 ──
        private const val KEY_SNAP_REMAINING = "snap_remaining_ms"
        private const val KEY_SNAP_PHASE_REMAINING = "snap_phase_remaining_ms"
        private const val KEY_SNAP_ELAPSED = "snap_elapsed"
        private const val KEY_SNAP_UPTIME = "snap_uptime"

        /** 墙钟与单调钟偏差超过此值（毫秒）判定为时间篡改。 */
        const val TIME_TAMPER_THRESHOLD_MS = 3 * 60_000L

        /** 守护中断（按设备清醒时间计）超过此值才视为被杀并补回。 */
        const val KILL_GAP_THRESHOLD_MS = 10_000L

        /** 单次补回 / 惩罚上限，防止异常数据把锁机拉到离谱的长度。 */
        private const val MAX_EXTEND_MS = 8 * 60 * 60_000L

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
        private const val KEY_PAUSE_ELAPSED_BASE = "pause_elapsed_base"
        private const val KEY_PAUSE_DURATION_MS = "pause_duration_ms"
        private const val KEY_POMODORO_PHASE_BASE = "pomodoro_phase_base"
        private const val KEY_POMODORO_PHASE_MS = "pomodoro_phase_ms"
        private const val KEY_POMODORO_IS_WORK = "pomodoro_is_work"
        private const val KEY_POMODORO_RUNNING = "pomodoro_running"
        private const val KEY_POMODORO_DONE_DATE = "pomodoro_done_date"
        private const val KEY_POMODORO_DONE_COUNT = "pomodoro_done_count"
        private const val KEY_POMODORO_ROUNDS_LEFT = "pomodoro_rounds_left"
        private const val KEY_POMODORO_WORK_MIN = "pomodoro_work_minutes"
        private const val KEY_POMODORO_BREAK_MIN = "pomodoro_break_minutes"

        const val SOURCE_POMODORO = "POMODORO"

        /** 强度 4：无法解锁、无法暂停，只能等时间结束。 */
        const val STRENGTH_LOCKED_FOREVER = 4
    }

    init {
        recalibrateAfterReboot()
    }

    // ── 底层读写 ──────────────────────────────────────

    private fun long(key: String) = prefs.getLong(key, 0L)

    /** 基准是否来自上一次启动周期（单调时钟已归零）。 */
    private fun isStaleBoot(base: Long): Boolean {
        if (base <= 0L) return false
        val boot = clock.bootCount()
        val storedBoot = prefs.getInt(KEY_BOOT_COUNT, -1)
        if (boot >= 0 && storedBoot >= 0 && boot != storedBoot) return true
        return base > clock.elapsed()
    }

    /**
     * 重启校准。
     *
     * 旧实现按墙钟剩余恢复：关机前把系统时间往后拨，重启后锁就没了。
     * 现在以守护心跳写下的「剩余时长快照」为准（误差 ≤ 一次心跳），
     * 关机时间不计入锁机；暂停在重启后作废。无快照时才退回墙钟剩余。
     */
    private fun recalibrateAfterReboot() {
        val base = long(KEY_LOCK_ELAPSED_BASE)
        if (!isStaleBoot(base)) return
        val now = clock.elapsed()
        val snapRemaining = long(KEY_SNAP_REMAINING)
        val remaining = if (snapRemaining > 0L) {
            snapRemaining
        } else {
            (long(KEY_LOCK_UNTIL) - clock.wall()).coerceAtLeast(0L)
        }
        val editor = prefs.edit()
        if (remaining <= 0L) {
            editor.putLong(KEY_LOCK_UNTIL, 0L)
                .putLong(KEY_LOCK_ELAPSED_BASE, 0L)
                .putLong(KEY_LOCK_DURATION_MS, 0L)
                .putLong(KEY_LOCK_WALL_BASE, 0L)
                .putBoolean(KEY_POMODORO_RUNNING, false)
        } else {
            editor.putLong(KEY_LOCK_ELAPSED_BASE, now)
                .putLong(KEY_LOCK_DURATION_MS, remaining)
                .putLong(KEY_LOCK_WALL_BASE, clock.wall())
                .putLong(KEY_LOCK_UNTIL, clock.wall() + remaining)
            if (prefs.getBoolean(KEY_POMODORO_RUNNING, false)) {
                val phaseRemaining = long(KEY_SNAP_PHASE_REMAINING).coerceIn(1L, remaining)
                editor.putLong(KEY_POMODORO_PHASE_BASE, now)
                    .putLong(KEY_POMODORO_PHASE_MS, phaseRemaining)
            }
        }
        editor.putInt(KEY_BOOT_COUNT, clock.bootCount())
            .putLong(KEY_PAUSE_ELAPSED_BASE, 0L)
            .putLong(KEY_PAUSE_DURATION_MS, 0L)
            .putLong(KEY_SNAP_REMAINING, remaining)
            .putLong(KEY_SNAP_ELAPSED, now)
            .putLong(KEY_SNAP_UPTIME, clock.uptime())
            .commit()
    }

    // ── 定时锁机 ──────────────────────────────────────

    /** 锁机截止时间戳（墙钟，仅用于展示），0 表示未锁机。 */
    val lockUntil: Long
        get() = long(KEY_LOCK_UNTIL)

    /** 锁机来源：PLAIN / POMODORO / AI / AI_CHAT。 */
    val lockSource: String
        get() = prefs.getString(KEY_LOCK_SOURCE, "") ?: ""

    /** 单调时钟上的剩余毫秒数（防篡改核心）。 */
    val remainingMs: Long
        get() {
            val base = long(KEY_LOCK_ELAPSED_BASE)
            return if (base > 0) {
                (long(KEY_LOCK_DURATION_MS) - (clock.elapsed() - base)).coerceAtLeast(0L)
            } else {
                (long(KEY_LOCK_UNTIL) - clock.wall()).coerceAtLeast(0L)
            }
        }

    val isLocked: Boolean
        get() = remainingMs > 0

    val remainingSeconds: Int
        get() = (remainingMs / 1000).toInt()

    /** 把剩余时长调整为 [targetRemainingMs]（只允许变长）。 */
    private fun extendRemainingTo(targetRemainingMs: Long) {
        val base = long(KEY_LOCK_ELAPSED_BASE)
        if (base <= 0L || targetRemainingMs <= remainingMs) return
        val duration = (clock.elapsed() - base) + targetRemainingMs
        prefs.edit()
            .putLong(KEY_LOCK_DURATION_MS, duration)
            .putLong(KEY_LOCK_UNTIL, clock.wall() + targetRemainingMs)
            .apply()
    }

    /**
     * 开始锁机。
     *
     * 已在锁机（含暂停中）时**只能加码不能放宽**：剩余时长取较长者、
     * 解锁强度取较高者，来源与番茄钟阶段保持不变。
     * 堵住「暂停期间开一个 1 分钟强度 1 的锁机覆盖 4 小时强度 4 锁机」。
     *
     * @return true=新开一轮锁机；false=合并进当前锁机
     */
    fun startLock(minutes: Int, source: String, strength: Int): Boolean {
        val durationMs = minutes.coerceAtLeast(1) * 60_000L
        val level = strength.coerceIn(1, STRENGTH_LOCKED_FOREVER)
        if (isLocked) {
            extendRemainingTo(durationMs)
            if (level > unlockStrength) {
                prefs.edit().putInt(KEY_LOCK_STRENGTH, level).apply()
                if (level == 3 && friendCipher.isEmpty()) setupFriendChallenge()
            }
            return false
        }
        val now = clock.elapsed()
        val wall = clock.wall()
        prefs.edit()
            .putLong(KEY_LOCK_ELAPSED_BASE, now)
            .putLong(KEY_LOCK_WALL_BASE, wall)
            .putLong(KEY_LOCK_DURATION_MS, durationMs)
            .putLong(KEY_LOCK_UNTIL, wall + durationMs)
            .putInt(KEY_BOOT_COUNT, clock.bootCount())
            .putString(KEY_LOCK_SOURCE, source)
            .putInt(KEY_LOCK_STRENGTH, level)
            .putInt(KEY_CHALLENGE_REFRESHES, 0)
            .putInt(KEY_PAUSE_USED, 0)
            .putLong(KEY_PAUSE_ELAPSED_BASE, 0L)
            .putLong(KEY_PAUSE_DURATION_MS, 0L)
            .putBoolean(KEY_POMODORO_RUNNING, false)
            .putLong(KEY_SNAP_REMAINING, durationMs)
            .putLong(KEY_SNAP_ELAPSED, now)
            .putLong(KEY_SNAP_UPTIME, clock.uptime())
            .commit()
        if (level == 3) setupFriendChallenge()
        return true
    }

    fun releaseLock() {
        prefs.edit()
            .putLong(KEY_LOCK_UNTIL, 0L)
            .putLong(KEY_LOCK_ELAPSED_BASE, 0L)
            .putLong(KEY_LOCK_DURATION_MS, 0L)
            .putLong(KEY_LOCK_WALL_BASE, 0L)
            .putString(KEY_LOCK_SOURCE, "")
            .putString(KEY_FRIEND_CIPHER, "")
            .putInt(KEY_FRIEND_SHIFT, 0)
            .putInt(KEY_PAUSE_USED, 0)
            .putLong(KEY_PAUSE_ELAPSED_BASE, 0L)
            .putLong(KEY_PAUSE_DURATION_MS, 0L)
            .putInt(KEY_CHALLENGE_REFRESHES, 0)
            .putBoolean(KEY_POMODORO_RUNNING, false)
            .putLong(KEY_SNAP_REMAINING, 0L)
            .putLong(KEY_SNAP_PHASE_REMAINING, 0L)
            .putLong(KEY_SNAP_ELAPSED, 0L)
            .putLong(KEY_SNAP_UPTIME, 0L)
            .commit()
    }

    // ── 时间篡改 ──────────────────────────────────────

    /**
     * 检测时间篡改：返回墙钟与单调钟推进的偏差（毫秒，绝对值）。
     * 暂停中同样有效（守护每 tick 调用，不受 shouldBlockNow 影响）。
     */
    fun detectTimeTamper(): Long {
        val base = long(KEY_LOCK_ELAPSED_BASE)
        if (base <= 0 || !isLocked) return 0L
        val expectedWall = long(KEY_LOCK_WALL_BASE) + (clock.elapsed() - base)
        return Math.abs(clock.wall() - expectedWall)
    }

    /**
     * 时间篡改惩罚：按偏差幅度追加锁定时长（5–30 分钟），并把墙钟基准
     * 重锚到当前时间——同一次改动只罚一次（旧实现不重锚，每 tick 叠罚）。
     */
    fun applyTamperPenalty(tamperMs: Long) {
        val base = long(KEY_LOCK_ELAPSED_BASE)
        if (tamperMs <= 0L || base <= 0) return
        val extra = (tamperMs * 2).coerceIn(5 * 60_000L, 30 * 60_000L)
        val newDuration = long(KEY_LOCK_DURATION_MS) + extra
        val wallBase = clock.wall() - (clock.elapsed() - base)
        prefs.edit()
            .putLong(KEY_LOCK_DURATION_MS, newDuration)
            .putLong(KEY_LOCK_WALL_BASE, wallBase)
            .commit()
        prefs.edit().putLong(KEY_LOCK_UNTIL, clock.wall() + remainingMs).apply()
    }

    // ── 存活快照与被杀补回 ────────────────────────────

    /** 守护心跳调用：记录当前剩余时长（锁机外清空）。 */
    fun writeSnapshot() {
        val editor = prefs.edit()
        if (isLocked) {
            editor.putLong(KEY_SNAP_REMAINING, remainingMs)
                .putLong(KEY_SNAP_PHASE_REMAINING, pomodoroPhaseRemainingMs)
                .putLong(KEY_SNAP_ELAPSED, clock.elapsed())
                .putLong(KEY_SNAP_UPTIME, clock.uptime())
        } else {
            editor.putLong(KEY_SNAP_REMAINING, 0L)
                .putLong(KEY_SNAP_ELAPSED, 0L)
                .putLong(KEY_SNAP_UPTIME, 0L)
        }
        editor.apply()
    }

    /**
     * 守护复活时调用：若守护在设备清醒状态下中断了一段时间（被清后台 /
     * 强行停止），把这段时间补回锁机。深度睡眠期间 uptime 不走，
     * 正常息屏被系统回收不会误罚。
     *
     * @return 补回的毫秒数，0 表示无需补回
     */
    fun compensateGuardGap(): Long {
        val snapElapsed = long(KEY_SNAP_ELAPSED)
        val snapRemaining = long(KEY_SNAP_REMAINING)
        if (snapElapsed <= 0L || snapRemaining <= 0L) return 0L
        val elapsedGap = clock.elapsed() - snapElapsed
        val awakeGap = clock.uptime() - long(KEY_SNAP_UPTIME)
        if (elapsedGap <= 0L || awakeGap < KILL_GAP_THRESHOLD_MS) return 0L
        val extend = awakeGap.coerceAtMost(minOf(elapsedGap, MAX_EXTEND_MS))
        // 期望剩余 = 快照剩余 − 睡眠部分（睡眠时间正常计入锁机）
        val target = snapRemaining - (elapsedGap - extend)
        val before = remainingMs
        if (target <= before) return 0L
        val base = long(KEY_LOCK_ELAPSED_BASE)
        if (base <= 0L) return 0L
        extendRemainingTo(target)
        if (pomodoroRunning) {
            // 被杀的时间同样不计入番茄钟当前阶段
            prefs.edit().putLong(KEY_POMODORO_PHASE_BASE, long(KEY_POMODORO_PHASE_BASE) + extend).apply()
        }
        writeSnapshot()
        return target - before
    }

    // ── 锁机期间「换一题」次数（防破解） ──────────────
    // 存 prefs 持久化：用户退出答题页再重进，次数不重置。锁机结束自动归零。

    /** 本次锁机已使用的「换一题」次数。 */
    var challengeRefreshCount: Int
        get() = prefs.getInt(KEY_CHALLENGE_REFRESHES, 0)
        set(value) = prefs.edit().putInt(KEY_CHALLENGE_REFRESHES, value.coerceAtLeast(0)).apply()

    /** 记录一次换题。返回是否已达上限（≥5 次）。 */
    fun recordChallengeRefresh(): Boolean {
        challengeRefreshCount = challengeRefreshCount + 1
        return challengeRefreshCount >= MAX_CHALLENGE_REFRESHES
    }

    /**
     * 解锁强度（1-4）：
     * 1 = 答对 1 题解锁
     * 2 = 连续答对 5 题解锁
     * 3 = 朋友辅助
     * 4 = 无法解锁、无法暂停，只能等时间结束
     *
     * 只读：强度只能经 [startLock] 设定，锁机中只升不降。
     */
    val unlockStrength: Int
        get() = prefs.getInt(KEY_LOCK_STRENGTH, 1).coerceIn(1, STRENGTH_LOCKED_FOREVER)

    /** 强度 3 的凯撒密文。 */
    val friendCipher: String
        get() = prefs.getString(KEY_FRIEND_CIPHER, "") ?: ""

    /** 强度 3 的凯撒偏移量。 */
    val friendShift: Int
        get() = prefs.getInt(KEY_FRIEND_SHIFT, 0)

    /** 生成并保存强度 3 的密文与偏移。 */
    fun setupFriendChallenge() {
        val (cipher, shift) = com.focusguard.app.util.CaesarHelper.generateChallenge()
        prefs.edit().putString(KEY_FRIEND_CIPHER, cipher).putInt(KEY_FRIEND_SHIFT, shift).apply()
    }

    /** 强度 3：验证朋友解密出的密码。 */
    fun verifyFriendPassword(input: String): Boolean {
        val plain = com.focusguard.app.util.CaesarHelper.decrypt(friendCipher, friendShift)
        return plain.isNotEmpty() && input.trim().equals(plain, ignoreCase = true)
    }

    // ── 锁机暂停（需答题获取暂停时长） ─────────────────

    /**
     * 配置暂停规则。锁机中调用只能收紧（关闭、减次数、缩短时长），
     * 强度 4 一律禁止暂停。
     */
    fun configurePause(enabled: Boolean, quota: Int, minutes: Int) {
        var on = enabled && unlockStrength < STRENGTH_LOCKED_FOREVER
        var q = quota.coerceAtLeast(0)
        var m = minutes.coerceIn(1, 60)
        if (isLocked) {
            on = on && pauseEnabled
            q = minOf(q, pauseQuota)
            m = minOf(m, pauseMinutes)
        }
        prefs.edit()
            .putBoolean(KEY_PAUSE_ENABLED, on)
            .putInt(KEY_PAUSE_QUOTA, q)
            .putInt(KEY_PAUSE_MINUTES, m)
            .apply()
    }

    /** 是否允许锁机中途暂停（强度 4 恒为 false）。 */
    val pauseEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_ENABLED, false) &&
            unlockStrength < STRENGTH_LOCKED_FOREVER

    /** 锁机期间允许暂停的总次数。 */
    val pauseQuota: Int
        get() = prefs.getInt(KEY_PAUSE_QUOTA, 3)

    /** 已使用的暂停次数。 */
    val pauseUsed: Int
        get() = prefs.getInt(KEY_PAUSE_USED, 0)

    /** 每次暂停的时长（分钟）。 */
    val pauseMinutes: Int
        get() = prefs.getInt(KEY_PAUSE_MINUTES, 5)

    /** 暂停剩余毫秒（单调时钟，改系统时间无效）。 */
    private val pauseRemainingMs: Long
        get() {
            val base = long(KEY_PAUSE_ELAPSED_BASE)
            if (base <= 0L) return 0L
            return (long(KEY_PAUSE_DURATION_MS) - (clock.elapsed() - base)).coerceAtLeast(0L)
        }

    /** 是否正处于暂停中（暂停期间不拦截）。 */
    val isPaused: Boolean
        get() = isLocked && pauseRemainingMs > 0

    /** 暂停剩余秒数。 */
    val pauseRemainingSeconds: Int
        get() = (pauseRemainingMs / 1000).toInt()

    /** 是否还有暂停配额可用。 */
    val canPause: Boolean
        get() = pauseEnabled && pauseUsed < pauseQuota && !isPaused

    /** 开始一次暂停（消耗一次配额）。 */
    fun startPause() {
        if (!canPause) return
        prefs.edit()
            .putLong(KEY_PAUSE_ELAPSED_BASE, clock.elapsed())
            .putLong(KEY_PAUSE_DURATION_MS, pauseMinutes * 60_000L)
            .putInt(KEY_PAUSE_USED, pauseUsed + 1)
            .apply()
    }

    // ── 番茄钟 ────────────────────────────────────────

    /** 专注阶段时长（分钟）。 */
    val pomodoroWorkMinutes: Int
        get() = prefs.getInt(KEY_POMODORO_WORK_MIN, 25).coerceIn(5, 180)

    /** 休息阶段时长（分钟）。 */
    val pomodoroBreakMinutes: Int
        get() = prefs.getInt(KEY_POMODORO_BREAK_MIN, 5).coerceIn(1, 60)

    /**
     * 开始番茄钟锁机。总锁机时长 = 轮数 ×（专注 + 休息）。
     * 已在锁机时退化为普通加码（不打断当前锁机）。
     */
    fun startPomodoro(rounds: Int, workMinutes: Int, breakMinutes: Int, strength: Int): Boolean {
        val r = rounds.coerceIn(1, 12)
        val work = workMinutes.coerceIn(5, 180)
        val rest = breakMinutes.coerceIn(1, 60)
        if (!startLock(r * (work + rest), SOURCE_POMODORO, strength)) return false
        prefs.edit()
            .putInt(KEY_POMODORO_WORK_MIN, work)
            .putInt(KEY_POMODORO_BREAK_MIN, rest)
            .putBoolean(KEY_POMODORO_RUNNING, true)
            .putBoolean(KEY_POMODORO_IS_WORK, true)
            .putInt(KEY_POMODORO_ROUNDS_LEFT, r)
            .putLong(KEY_POMODORO_PHASE_BASE, clock.elapsed())
            .putLong(KEY_POMODORO_PHASE_MS, work * 60_000L)
            .commit()
        return true
    }

    val pomodoroIsWorkPhase: Boolean
        get() = prefs.getBoolean(KEY_POMODORO_IS_WORK, true)

    val pomodoroRunning: Boolean
        get() = prefs.getBoolean(KEY_POMODORO_RUNNING, false)

    /** 今日完成的番茄钟数量，跨天自动归零。 */
    val pomodoroCompletedToday: Int
        get() {
            val storedDate = prefs.getString(KEY_POMODORO_DONE_DATE, "") ?: ""
            return if (storedDate == today()) prefs.getInt(KEY_POMODORO_DONE_COUNT, 0) else 0
        }

    /** 番茄钟剩余轮数。 */
    val pomodoroRoundsLeft: Int
        get() = prefs.getInt(KEY_POMODORO_ROUNDS_LEFT, 0)

    private val pomodoroPhaseRemainingMs: Long
        get() {
            if (!pomodoroRunning) return 0L
            val base = long(KEY_POMODORO_PHASE_BASE)
            if (base <= 0L) return 0L
            return (long(KEY_POMODORO_PHASE_MS) - (clock.elapsed() - base)).coerceAtLeast(0L)
        }

    /** 番茄钟阶段剩余秒数。 */
    val pomodoroRemainingSeconds: Int
        get() = (pomodoroPhaseRemainingMs / 1000).toInt()

    /** 当前阶段总秒数（进度环基准）。 */
    val pomodoroPhaseTotalSeconds: Int
        get() = (long(KEY_POMODORO_PHASE_MS) / 1000).toInt().coerceAtLeast(1)

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
            if (lockSource != SOURCE_POMODORO || !pomodoroRunning) return true
            return pomodoroIsWorkPhase
        }

    /**
     * 推进番茄钟阶段（守护每 tick 调用，阶段未到期时不做任何事）。
     * 新阶段基准接在上一阶段的理论结束点，tick 延迟不会累计误差。
     *
     * @return true 表示本次调用发生了阶段切换
     */
    fun tickPomodoro(): Boolean {
        if (!pomodoroRunning || !isLocked) return false
        if (pomodoroPhaseRemainingMs > 0) return false
        val phaseEnd = long(KEY_POMODORO_PHASE_BASE) + long(KEY_POMODORO_PHASE_MS)
        if (pomodoroIsWorkPhase) {
            val count = pomodoroCompletedToday + 1
            prefs.edit()
                .putString(KEY_POMODORO_DONE_DATE, today())
                .putInt(KEY_POMODORO_DONE_COUNT, count)
                .putBoolean(KEY_POMODORO_IS_WORK, false)
                .putLong(KEY_POMODORO_PHASE_BASE, phaseEnd)
                .putLong(KEY_POMODORO_PHASE_MS, pomodoroBreakMinutes * 60_000L)
                .commit()
        } else {
            val left = pomodoroRoundsLeft - 1
            if (left <= 0) {
                prefs.edit().putInt(KEY_POMODORO_ROUNDS_LEFT, 0).commit()
                releaseLock()
            } else {
                prefs.edit()
                    .putInt(KEY_POMODORO_ROUNDS_LEFT, left)
                    .putBoolean(KEY_POMODORO_IS_WORK, true)
                    .putLong(KEY_POMODORO_PHASE_BASE, phaseEnd)
                    .putLong(KEY_POMODORO_PHASE_MS, pomodoroWorkMinutes * 60_000L)
                    .commit()
            }
        }
        return true
    }

    private fun today(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd",
        java.util.Locale.getDefault()
    ).format(java.util.Date(clock.wall()))
}
