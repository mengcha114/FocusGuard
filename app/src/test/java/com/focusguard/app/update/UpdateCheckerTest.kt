package com.focusguard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本比较是纯函数，直接测真实实现（不 mock）。 */
class UpdateCheckerTest {

    @Test
    fun newerPatchIsNewer() {
        assertTrue(UpdateChecker.compareVersions("3.11.20", "v3.11.21") < 0)
    }

    @Test
    fun newerMinorBeatsOlderMajor() {
        assertTrue(UpdateChecker.compareVersions("3.9.10", "3.11.0") < 0)
    }

    @Test
    fun sameVersionIsZero() {
        assertEquals(0, UpdateChecker.compareVersions("v3.11.21", "3.11.21"))
    }

    @Test
    fun localAheadIsPositive() {
        assertTrue(UpdateChecker.compareVersions("3.11.21", "3.11.20") > 0)
    }

    @Test
    fun suffixIsIgnored() {
        assertEquals(0, UpdateChecker.compareVersions("3.11.21-preview", "3.11.21"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertEquals(0, UpdateChecker.compareVersions("3.11", "3.11.0"))
    }
}
