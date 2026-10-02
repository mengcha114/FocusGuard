package com.focusguard.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LockStateTest {

    private lateinit var prefs: FakePrefs
    private lateinit var clock: FakeClock
    private lateinit var state: LockState

    private val min = 60_000L

    @Before
    fun setUp() {
        prefs = FakePrefs()
        clock = FakeClock()
        state = LockState(prefs, clock)
    }

    /** 模拟进程重建（重新构造 LockState，触发重启校准）。 */
    private fun reload(): LockState = LockState(prefs, clock).also { state = it }

    // ── 基本计时 ──

    @Test
    fun lockCountsDownOnMonotonicClock() {
        state.startLock(30, "PLAIN", 1)
        clock.advance(10 * min)
        assertEquals(20 * 60, state.remainingSeconds)
        clock.advance(20 * min)
        assertFalse(state.isLocked)
    }

    @Test
    fun changingWallClockDoesNotShortenLock() {
        state.startLock(30, "PLAIN", 1)
        clock.wallMs += 24 * 60 * min
        assertEquals(30 * 60, state.remainingSeconds)
    }

    // ── 暂停绕过 ──

    @Test
    fun pauseUsesMonotonicClockAndCannotBeExtendedByRewindingTime() {
        state.startLock(240, "PLAIN", 1)
        state.configurePause(true, 3, 5)
        state.startPause()
        assertTrue(state.isPaused)
        // 回拨系统时间一天：旧实现下 pauseUntil 永远大于墙钟，可以一直暂停
        clock.wallMs -= 24 * 60 * min
        clock.advance(5 * min + 1000)
        assertFalse(state.isPaused)
        assertTrue(state.shouldBlockNow)
    }

    @Test
    fun strengthFourCannotPause() {
        state.startLock(60, "PLAIN", LockState.STRENGTH_LOCKED_FOREVER)
        state.configurePause(true, 3, 5)
        assertFalse(state.pauseEnabled)
        state.startPause()
        assertFalse(state.isPaused)
    }

    @Test
    fun pauseConfigCanOnlyTightenDuringLock() {
        state.startLock(60, "PLAIN", 1)
        state.configurePause(true, 2, 5)
        state.configurePause(true, 10, 30)
        assertEquals(2, state.pauseQuota)
        assertEquals(5, state.pauseMinutes)
    }

    // ── 篡改惩罚只罚一次 ──

    @Test
    fun tamperPenaltyAppliesOnlyOncePerChange() {
        state.startLock(30, "PLAIN", 1)
        clock.wallMs += 60 * min
        val tamper = state.detectTimeTamper()
        assertTrue(tamper > LockState.TIME_TAMPER_THRESHOLD_MS)
        state.applyTamperPenalty(tamper)
        val afterFirst = state.remainingSeconds
        assertEquals(30 * 60 + 30 * 60, afterFirst)
        // 下一个 tick：偏差已重锚，不再叠罚
        clock.advance(600)
        assertTrue(state.detectTimeTamper() < LockState.TIME_TAMPER_THRESHOLD_MS)
    }

    @Test
    fun tamperDetectedWhilePaused() {
        state.startLock(60, "PLAIN", 1)
        state.configurePause(true, 1, 5)
        state.startPause()
        clock.wallMs -= 60 * min
        assertTrue(state.detectTimeTamper() > LockState.TIME_TAMPER_THRESHOLD_MS)
    }

    // ── 覆盖锁机 ──

    @Test
    fun newShortLockCannotOverrideActiveLock() {
        state.startLock(240, "PLAIN", LockState.STRENGTH_LOCKED_FOREVER)
        state.configurePause(true, 1, 5) // 强度 4 下不生效
        val started = state.startLock(1, "AI_CHAT", 1)
        assertFalse(started)
        assertEquals(240 * 60, state.remainingSeconds)
        assertEquals(LockState.STRENGTH_LOCKED_FOREVER, state.unlockStrength)
        assertEquals("PLAIN", state.lockSource)
    }

    @Test
    fun newLongerLockExtendsAndRaisesStrength() {
        state.startLock(10, "PLAIN", 1)
        state.startLock(60, "AI", 2)
        assertEquals(60 * 60, state.remainingSeconds)
        assertEquals(2, state.unlockStrength)
    }

    // ── 番茄钟 ──

    @Test
    fun pomodoroAdvancesWithCustomDurations() {
        state.startPomodoro(rounds = 2, workMinutes = 50, breakMinutes = 10, strength = 1)
        assertEquals(120 * 60, state.remainingSeconds)
        assertTrue(state.shouldBlockNow)

        clock.advance(50 * min)
        assertTrue(state.tickPomodoro())
        assertFalse(state.pomodoroIsWorkPhase)
        assertFalse(state.shouldBlockNow)
        assertEquals(10 * 60, state.pomodoroRemainingSeconds)
        assertEquals(1, state.pomodoroCompletedToday)

        clock.advance(10 * min)
        assertTrue(state.tickPomodoro())
        assertTrue(state.pomodoroIsWorkPhase)
        assertEquals(1, state.pomodoroRoundsLeft)

        clock.advance(60 * min)
        state.tickPomodoro() // 专注结束 → 休息
        state.tickPomodoro() // 休息到期（同一时刻已过）→ 全部完成
        assertFalse(state.isLocked)
    }

    @Test
    fun pomodoroBreakCannotBeExtendedByRewindingTime() {
        state.startPomodoro(rounds = 2, workMinutes = 25, breakMinutes = 5, strength = 1)
        clock.advance(25 * min)
        state.tickPomodoro()
        assertFalse(state.shouldBlockNow)
        clock.wallMs -= 24 * 60 * min
        clock.advance(5 * min)
        state.tickPomodoro()
        assertTrue(state.shouldBlockNow)
    }

    @Test
    fun tickPomodoroIsNoOpBeforePhaseEnds() {
        state.startPomodoro(4, 25, 5, 1)
        clock.advance(10 * min)
        assertFalse(state.tickPomodoro())
        assertTrue(state.pomodoroIsWorkPhase)
    }

    // ── 清后台补回 ──

    @Test
    fun killGapIsCompensated() {
        state.startLock(60, "PLAIN", 1)
        clock.advance(10 * min)
        state.writeSnapshot()
        // 守护被杀 20 分钟（设备清醒）
        clock.advance(20 * min)
        val added = reload().compensateGuardGap()
        assertEquals(20 * min, added)
        assertEquals(50 * 60, state.remainingSeconds)
    }

    @Test
    fun deepSleepIsNotPenalized() {
        state.startLock(60, "PLAIN", 1)
        clock.advance(10 * min)
        state.writeSnapshot()
        clock.sleep(20 * min)
        assertEquals(0L, reload().compensateGuardGap())
        assertEquals(30 * 60, state.remainingSeconds)
    }

    @Test
    fun shortGapIsIgnored() {
        state.startLock(60, "PLAIN", 1)
        state.writeSnapshot()
        clock.advance(5_000)
        assertEquals(0L, state.compensateGuardGap())
    }

    // ── 重启 ──

    @Test
    fun rebootRestoresFromSnapshotNotWallClock() {
        state.startLock(60, "PLAIN", 1)
        clock.advance(10 * min)
        state.writeSnapshot()
        // 关机前把系统时间往后拨 2 小时，关机 1 小时
        clock.wallMs += 120 * min
        clock.reboot(offMs = 60 * min)
        val restored = reload()
        assertTrue(restored.isLocked)
        assertEquals(50 * 60, restored.remainingSeconds)
    }

    @Test
    fun rebootCancelsActivePause() {
        state.startLock(60, "PLAIN", 1)
        state.configurePause(true, 1, 15)
        state.startPause()
        state.writeSnapshot()
        clock.reboot(offMs = min)
        val restored = reload()
        assertFalse(restored.isPaused)
        assertTrue(restored.shouldBlockNow)
    }

    // ── 答错冷却 ──

    @Test
    fun cooldownStartsAfterFreeWrongAnswersUsedUp() {
        state.startLock(60, "PLAIN", 1)
        repeat(LockState.FREE_WRONG_ANSWERS) { assertFalse(state.recordWrongAnswer()) }
        assertFalse(state.isInCooldown)
        assertEquals(0, state.freeWrongLeft)
        assertTrue(state.recordWrongAnswer())
        assertTrue(state.isInCooldown)
        clock.advance(4 * min)
        assertTrue(state.isInCooldown)
        clock.advance(min + 1000)
        assertFalse(state.isInCooldown)
        // 冷却结束后再答错，再次进入 5 分钟冷却
        assertTrue(state.recordWrongAnswer())
        assertTrue(state.isInCooldown)
    }

    @Test
    fun cooldownIgnoresWallClockAndSurvivesReload() {
        state.startLock(60, "PLAIN", 1)
        repeat(LockState.FREE_WRONG_ANSWERS + 1) { state.recordWrongAnswer() }
        clock.wallMs += 24 * 60 * min
        assertTrue(reload().isInCooldown)
    }

    @Test
    fun cooldownRestartsFullyAfterReboot() {
        state.startLock(60, "PLAIN", 1)
        state.writeSnapshot()
        repeat(LockState.FREE_WRONG_ANSWERS + 1) { state.recordWrongAnswer() }
        clock.reboot(offMs = min)
        val s = reload()
        assertTrue(s.isInCooldown)
        assertEquals(LockState.WRONG_COOLDOWN_MS, s.cooldownRemainingMs)
    }

    @Test
    fun newLockResetsWrongCount() {
        state.startLock(60, "PLAIN", 1)
        repeat(LockState.FREE_WRONG_ANSWERS + 1) { state.recordWrongAnswer() }
        state.releaseLock()
        state.startLock(30, "PLAIN", 1)
        assertEquals(LockState.FREE_WRONG_ANSWERS, state.freeWrongLeft)
        assertFalse(state.isInCooldown)
    }

    @Test
    fun releaseClearsEverything() {
        state.startLock(60, "PLAIN", 3)
        state.releaseLock()
        assertFalse(state.isLocked)
        assertEquals("", state.friendCipher)
        assertEquals(0, state.challengeRefreshCount)
    }
}
