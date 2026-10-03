package com.focusguard.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContentPrivacyTest {

    @Test
    fun idCardIsDetected() {
        assertNotNull(ContentPrivacy.inspect("身份证号 110101199003078515"))
        assertNotNull(ContentPrivacy.inspect("证件 11010119900307851X"))
    }

    @Test
    fun bankCardIsDetected() {
        assertNotNull(ContentPrivacy.inspect("卡号 6225880131234567 转账"))
        assertNotNull(ContentPrivacy.inspect("6225880131234567890"))
    }

    @Test
    fun phoneIsDetected() {
        assertNotNull(ContentPrivacy.inspect("联系电话 13812345678"))
    }

    @Test
    fun verificationCodeWithContextIsDetected() {
        assertNotNull(ContentPrivacy.inspect("您的验证码是 835214，请勿泄露"))
        assertNotNull(ContentPrivacy.inspect("短信验证码：9021"))
    }

    @Test
    fun balanceAndAccountWordsWithDigitsAreDetected() {
        assertNotNull(ContentPrivacy.inspect("账户余额 12345.67 元"))
        assertNotNull(ContentPrivacy.inspect("支付密码 8888"))
    }

    @Test
    fun ordinaryStudyScreenIsNotFlagged() {
        assertNull(ContentPrivacy.inspect("第三章 导数与微分 习题 3.2 求下列函数的导数"))
        assertNull(ContentPrivacy.inspect("今天学习了 3 小时数学，做了 20 道题"))
        assertNull(ContentPrivacy.inspect("2026 年 3 月 15 日 星期天 天气晴"))
        assertNull(ContentPrivacy.inspect(""))
        assertNull(ContentPrivacy.inspect(null))
    }
}
