package com.focusguard.app.privacy

import com.focusguard.app.data.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「AI 标记为隐私敏感」名单的纯逻辑（用 FakePrefs，不依赖 Android）。 */
class SensitiveLearningTest {

    private val prefs = FakePrefs()

    @Test
    fun markIsIdempotentAndQueryable() {
        assertFalse(SensitiveLearning.isMarkedIn(prefs, "com.icbc.mobile"))
        SensitiveLearning.markIn(prefs, "com.icbc.mobile")
        SensitiveLearning.markIn(prefs, "com.icbc.mobile")
        assertTrue(SensitiveLearning.isMarkedIn(prefs, "com.icbc.mobile"))
        assertEquals(setOf("com.icbc.mobile"), SensitiveLearning.packagesOf(prefs))
    }

    @Test
    fun blankPackageIsIgnored() {
        SensitiveLearning.markIn(prefs, "")
        assertTrue(SensitiveLearning.packagesOf(prefs).isEmpty())
        assertFalse(SensitiveLearning.isMarkedIn(prefs, ""))
    }

    @Test
    fun clearRemovesAll() {
        SensitiveLearning.markIn(prefs, "a.b.c")
        SensitiveLearning.markIn(prefs, "d.e.f")
        SensitiveLearning.clearIn(prefs)
        assertTrue(SensitiveLearning.packagesOf(prefs).isEmpty())
    }
}
