package com.focusguard.app.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardBrowserTest {

    @Test
    fun browsersAreRecognized() {
        assertTrue(PrivacyGuard.isBrowser("com.android.chrome"))
        assertTrue(PrivacyGuard.isBrowser("org.mozilla.firefox"))
        assertTrue(PrivacyGuard.isBrowser("com.UCMobile"))
        assertTrue(PrivacyGuard.isBrowser("com.heytap.browser"))
    }

    @Test
    fun nonBrowsersAreNotRecognized() {
        assertFalse(PrivacyGuard.isBrowser("com.tencent.mm"))
        assertFalse(PrivacyGuard.isBrowser("com.zhihu.android"))
        assertFalse(PrivacyGuard.isBrowser("tv.danmaku.bili"))
        assertFalse(PrivacyGuard.isBrowser(""))
    }

    @Test
    fun builtinHintsCanBeDisabled() {
        // 关闭内置兜底后，只用用户自定义列表
        assertFalse(PrivacyGuard.isSensitive("com.icbc.mobile", "工商银行", "", useBuiltin = false))
        assertTrue(PrivacyGuard.isSensitive("com.icbc.mobile", "工商银行", "工商银行", useBuiltin = false))
    }
}
