package com.focusguard.app.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardBrowserTest {

    @Test
    fun knownBrowsersAreRecognizedByPackageName() {
        // 常见浏览器命中包名特征（快速路径）
        assertTrue(PrivacyGuard.isBrowserByPackageName("com.android.chrome"))
        assertTrue(PrivacyGuard.isBrowserByPackageName("org.mozilla.firefox"))
        assertTrue(PrivacyGuard.isBrowserByPackageName("com.UCMobile"))
        assertTrue(PrivacyGuard.isBrowserByPackageName("com.heytap.browser"))
        // 厂商 / 小众浏览器
        assertTrue(PrivacyGuard.isBrowserByPackageName("com.tencent.mtt"))
        assertTrue(PrivacyGuard.isBrowserByPackageName("com.qihoo.browser"))
        assertTrue(PrivacyGuard.isBrowserByPackageName("com.vivo.browser"))
        assertTrue(PrivacyGuard.isBrowserByPackageName("com.huawei.browser"))
        assertTrue(PrivacyGuard.isBrowserByPackageName("com.yandex.browser"))
    }

    @Test
    fun nonBrowsersAreNotRecognizedByPackageName() {
        assertFalse(PrivacyGuard.isBrowserByPackageName("com.tencent.mm"))
        assertFalse(PrivacyGuard.isBrowserByPackageName("com.zhihu.android"))
        assertFalse(PrivacyGuard.isBrowserByPackageName("tv.danmaku.bili"))
        assertFalse(PrivacyGuard.isBrowserByPackageName(""))
    }

    @Test
    fun unknownPackageNeedsSystemCheck() {
        // 包名认不出时不能直接当成浏览器，也不能当不是——交给 isBrowserApp 查系统；
        // 未命中包名特征时该函数返回 false，仅表示"包名这条快速路径不通"
        assertFalse(PrivacyGuard.isBrowserByPackageName("com.example.mystery"))
    }

    @Test
    fun builtinHintsCanBeDisabled() {
        // 关闭内置兜底后，只用用户自定义列表
        assertFalse(PrivacyGuard.isSensitive("com.icbc.mobile", "工商银行", "", useBuiltin = false))
        assertTrue(PrivacyGuard.isSensitive("com.icbc.mobile", "工商银行", "工商银行", useBuiltin = false))
    }
}
