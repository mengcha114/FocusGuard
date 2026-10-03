package com.focusguard.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptGuardTest {
    private val prefs = FakePrefs()
    private val clock = FakeClock()
    private fun guard(scope: String = AttemptGuard.SCOPE_VERIFY) = AttemptGuard(prefs, clock, scope)
    private val min = 60_000L

    @Test fun twoWrongEntersFiveMinuteCooldown() {
        val g = guard()
        assertFalse(g.recordWrong()); assertEquals(1, g.wrongLeft)
        assertTrue(g.recordWrong()); assertTrue(g.isInCooldown)
        clock.advance(5 * min - 1000); assertTrue(g.isInCooldown)
        clock.advance(2000); assertFalse(g.isInCooldown); assertEquals(2, g.wrongLeft)
    }

    @Test fun cooldownSurvivesReopenAndWallClockChange() {
        guard().apply { recordWrong(); recordWrong() }
        clock.wallMs += 24 * 60 * min
        assertTrue(guard().isInCooldown)
    }

    @Test fun cooldownRestartsAfterReboot() {
        guard().apply { recordWrong(); recordWrong() }
        clock.reboot(offMs = min)
        assertEquals(AttemptGuard.COOLDOWN_MS, guard().cooldownRemainingMs)
    }

    @Test fun refreshLimitedToFive() {
        val g = guard()
        repeat(5) { assertTrue(g.tryRefresh()) }
        assertFalse(g.tryRefresh()); assertEquals(0, guard().refreshLeft)
        g.resetSession(); assertTrue(g.tryRefresh())
    }

    @Test fun noRefreshDuringCooldown() {
        val g = guard(); g.recordWrong(); g.recordWrong()
        assertFalse(g.tryRefresh())
    }

    @Test fun correctClearsWrongCount() {
        val g = guard(); g.recordWrong(); g.recordCorrect()
        assertFalse(g.recordWrong())
    }

    @Test fun scopesAreIndependent() {
        guard(AttemptGuard.SCOPE_LOCK).apply { recordWrong(); recordWrong() }
        assertFalse(guard(AttemptGuard.SCOPE_VERIFY).isInCooldown)
    }
}
