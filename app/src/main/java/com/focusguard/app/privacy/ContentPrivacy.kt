package com.focusguard.app.privacy

/**
 * 内容级隐私兜底（纯本地，不联网）。
 *
 * 内置敏感应用特征靠包名/应用名匹配，遇到未收录的应用会漏：小众银行、网银网页版
 * （前台包名是浏览器）、政务 H5、朋友发来的证件照。这里在**上传之前**对已经读到的
 * 屏幕文字做模式识别，命中就不上传——不依赖能否识别出前台应用。
 *
 * 判定全部是本地正则，无 IO、可单测。宁可少检测一次，也不把这类内容发出去。
 */
object ContentPrivacy {

    /** 身份证号（18 位，末位可为 X）。 */
    private val ID_CARD = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")

    /** 手机号（11 位，1 开头）。 */
    private val PHONE = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")

    /** 输入框里常见分隔：把 6225-8801-3123-4567 / 6225 8801 3123 4567 归一后再匹配。 */
    private val SEPARATORS = Regex("""[\s\-_.·•]+""")

    /** 银行卡号（16–19 位连续数字）。 */
    private val BANK_CARD = Regex("""(?<!\d)\d{16,19}(?!\d)""")

    /** 需要上下文才能判定的词：单独出现不算敏感，配合数字串才判定。 */
    private val CONTEXT_WORDS = listOf(
        "验证码", "动态口令", "支付密码", "交易密码", "登录密码", "取款密码",
        "余额", "转账", "汇款", "收款", "开户", "卡号", "账号", "证件号",
        "身份证", "银行卡", "信用卡", "储蓄卡", "有效期", "安全码", "CVV",
        "医保卡", "社保卡", "公积金", "税号", "发票号", "保单号"
    )

    /**
     * 命中即视为敏感的关键词（无需配合数字）。
     * 「密码」「卡号」「账号」单出现就跳过：银行/支付的登录与下单页几乎必然包含它们，
     * 而学习类应用只会在登录页出现，误跳的代价只是少检测一次。
     */
    private val DIRECT_WORDS = listOf(
        "身份证号", "银行卡号", "信用卡号", "支付密码", "交易密码", "取款密码",
        "短信验证码", "校验码", "安全码", "CVV", "CVC",
        "密码", "卡号", "账号", "账户", "登录密码", "网银", "转账", "汇款",
        "余额", "支付", "收款", "开户", "验证码"
    )

    /**
     * @return 命中的类型描述；未命中返回 null
     */
    fun inspect(text: String?): String? {
        if (text.isNullOrBlank()) return null
        // 关键词判定用原文（保留"卡号 6225-…"这类上下文），数字判定用去掉分隔符的版本
        val raw = text
        val t = SEPARATORS.replace(text, "")
        if (ID_CARD.containsMatchIn(t)) return "疑似身份证号"
        if (PHONE.containsMatchIn(t)) return "疑似手机号"
        if (BANK_CARD.containsMatchIn(t)) return "疑似银行卡号"
        DIRECT_WORDS.firstOrNull { raw.contains(it, ignoreCase = true) }?.let { return "含「$it」" }
        // 上下文词 + 4 位以上数字串相邻出现
        val hasDigits = Regex("""\d{4,}""").containsMatchIn(t)
        if (hasDigits) {
            CONTEXT_WORDS.firstOrNull { t.contains(it, ignoreCase = true) }?.let { return "含「$it」与数字串" }
        }
        return null
    }
}
