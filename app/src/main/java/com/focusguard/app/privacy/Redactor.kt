package com.focusguard.app.privacy

/**
 * 日志脱敏：检测理由（可能被 AI 写成引用屏幕内容，例如"正在查看尾号 6225 的银行卡"）
 * 在写入日志、决策缓存与导出文件前统一处理——打码长数字串并截断长度。
 */
object Redactor {

    private const val MAX_LENGTH = 120
    private val LONG_DIGITS = Regex("""\d{11,}""")      // 手机号 / 身份证 / 银行卡
    private val MID_DIGITS = Regex("""(?<!\d)(\d)\d{4,9}(\d)(?!\d)""") // 6–10 位：保留首尾

    fun redact(text: String?, maxLength: Int = MAX_LENGTH): String {
        if (text.isNullOrBlank()) return ""
        var s = LONG_DIGITS.replace(text) { "***" }
        s = MID_DIGITS.replace(s) { m -> "${m.groupValues[1]}***${m.groupValues[2]}" }
        s = s.replace('\n', ' ').trim()
        return if (s.length > maxLength) s.take(maxLength) + "…" else s
    }
}
