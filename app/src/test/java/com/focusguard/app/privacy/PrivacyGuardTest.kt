package com.focusguard.app.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardTest {

    @Test
    fun builtinPackageHintsCatchBankAndPasswordApps() {
        assertTrue(PrivacyGuard.isSensitive("com.icbc.mobile", "工商银行", ""))
        assertTrue(PrivacyGuard.isSensitive("com.eg.android.AlipayGphone", "支付宝", ""))
        assertTrue(PrivacyGuard.isSensitive("com.bitwarden.mobile", "Bitwarden", ""))
        assertTrue(PrivacyGuard.isSensitive("com.xiaomi.gallery", "相册", ""))
        assertTrue(PrivacyGuard.isSensitive("com.tencent.mm", "微信", ""))
    }

    @Test
    fun builtinLabelHintsCatchChineseNames() {
        assertTrue(PrivacyGuard.isSensitive("com.unknown.app", "招商银行", ""))
        assertTrue(PrivacyGuard.isSensitive("com.unknown.app", "国家医保服务平台", ""))
        assertTrue(PrivacyGuard.isSensitive("com.unknown.app", "个人所得税", ""))
        assertTrue(PrivacyGuard.isSensitive("com.unknown.app", "私密保险箱", ""))
    }

    @Test
    fun ordinaryAppsAreNotMarkedSensitive() {
        assertFalse(PrivacyGuard.isSensitive("com.tencent.tmgp.sgame", "王者荣耀", ""))
        assertFalse(PrivacyGuard.isSensitive("com.zhihu.android", "知乎", ""))
        assertFalse(PrivacyGuard.isSensitive("com.android.settings", "设置", ""))
    }

    @Test
    fun userListMatchesPackageOrLabelWithCommonSeparators() {
        val list = "com.example.secret；某个小众App, 云盘"
        assertTrue(PrivacyGuard.isSensitive("com.example.secret", "", list))
        assertTrue(PrivacyGuard.isSensitive("com.foo.bar", "某个小众App", list))
        assertTrue(PrivacyGuard.isSensitive("com.foo.bar", "我的云盘", list))
        assertFalse(PrivacyGuard.isSensitive("com.foo.bar", "计算器", list))
    }

    @Test
    fun blankInputIsNotSensitiveButBlankLabelWithSensitivePackageIs() {
        assertFalse(PrivacyGuard.isSensitive("", "", ""))
        assertTrue(PrivacyGuard.isSensitive("com.cmbchina.ccd.pluto.cmbActivity", "", ""))
    }
}
