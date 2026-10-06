package com.focusguard.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTogglesTest {

    @Test
    fun turningOffAnEnabledSwitchNeedsQuiz() {
        assertTrue(SecurityToggles.isSecuritySwitchOff(enabledNow = true, next = false))
    }

    @Test
    fun turningOnAStricterSwitchNeedsNoQuiz() {
        assertFalse(SecurityToggles.isSecuritySwitchOff(enabledNow = false, next = true))
    }

    @Test
    fun noChangeNeedsNoQuiz() {
        assertFalse(SecurityToggles.isSecuritySwitchOff(enabledNow = true, next = true))
        assertFalse(SecurityToggles.isSecuritySwitchOff(enabledNow = false, next = false))
    }
}
