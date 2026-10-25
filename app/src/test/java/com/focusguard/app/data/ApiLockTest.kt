package com.focusguard.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API 锁定指纹的纯函数测试。
 *
 * 锁定语义：测试连接成功后保存「当前配置的指纹」，之后只有指纹一致才算锁定。
 * 因此指纹必须对四个配置项的变化都敏感，且对地址末尾斜杠/首尾空白不敏感
 * （否则用户只是少打一个 `/` 就被判定成"改了 API"，锁定形同虚设或误锁）。
 */
class ApiLockTest {

    private val base = "https://api.moonshot.cn/v1"
    private val model = "moonshot-v1-8k-vision-preview"
    private val format = "openai"
    private val key = "sk-test-key"

    @Test
    fun sameConfigProducesSameFingerprint() {
        val a = Settings.fingerprintOf(base, model, format, key)
        val b = Settings.fingerprintOf(base, model, format, key)
        assertEquals(a, b)
    }

    @Test
    fun trailingSlashAndWhitespaceAreNormalized() {
        val a = Settings.fingerprintOf(base, model, format, key)
        val b = Settings.fingerprintOf("  $base/  ", " $model ", " $format ", key)
        assertEquals(a, b)
    }

    @Test
    fun changingAnyConfigFieldChangesFingerprint() {
        val origin = Settings.fingerprintOf(base, model, format, key)
        assertNotEquals(origin, Settings.fingerprintOf("https://api.openai.com/v1", model, format, key))
        assertNotEquals(origin, Settings.fingerprintOf(base, "gpt-4o-mini", format, key))
        assertNotEquals(origin, Settings.fingerprintOf(base, model, "anthropic", key))
        assertNotEquals(origin, Settings.fingerprintOf(base, model, format, "sk-other-key"))
    }

    @Test
    fun fingerprintIsSha256Hex() {
        val fp = Settings.fingerprintOf(base, model, format, key)
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in "0123456789abcdef" })
    }

    @Test
    fun lockedOnlyWhenSwitchOnAndFingerprintMatches() {
        val fp = Settings.fingerprintOf(base, model, format, key)
        assertTrue(Settings.isApiLocked(true, fp, fp))
    }

    @Test
    fun changedConfigBreaksTheLock() {
        val old = Settings.fingerprintOf(base, model, format, key)
        val changed = Settings.fingerprintOf(base, model, format, "sk-another-key")
        // 用户通过答题改了密钥：指纹不再匹配 → 配置层不再认为已锁定
        assertTrue(!Settings.isApiLocked(true, old, changed))
    }

    @Test
    fun disabledSwitchOrEmptyFingerprintMeansUnlocked() {
        val fp = Settings.fingerprintOf(base, model, format, key)
        assertTrue(!Settings.isApiLocked(false, fp, fp))
        assertTrue(!Settings.isApiLocked(true, "", fp))
    }
}
