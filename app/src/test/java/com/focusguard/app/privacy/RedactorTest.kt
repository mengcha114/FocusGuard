package com.focusguard.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun longDigitRunsAreMasked() {
        val out = Redactor.redact("正在查看尾号 6225880131234567 的银行卡")
        assertFalse(out.contains("6225880131234567"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun mediumDigitRunsKeepFirstAndLast() {
        assertEquals("验证码 1***6", Redactor.redact("验证码 123456"))
    }

    @Test
    fun shortNumbersAndYearsAreKept() {
        assertEquals("2026 年做 20 道题", Redactor.redact("2026 年做 20 道题"))
    }

    @Test
    fun longTextIsTruncatedAndNewlinesFlattened() {
        val out = Redactor.redact("第一行\n" + "字".repeat(200))
        assertFalse(out.contains('\n'))
        assertTrue(out.endsWith("…"))
        assertTrue(out.length <= 121)
    }

    @Test
    fun blankInputReturnsEmpty() {
        assertEquals("", Redactor.redact(null))
        assertEquals("", Redactor.redact("   "))
    }
}
