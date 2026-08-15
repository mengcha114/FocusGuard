package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 备忘录个性化设置（局部覆盖，不影响全局主题）。
 *
 * - 字号三档：小 / 标准 / 大
 * - 文字颜色：跟随卡片 / 暖纸白 / 琥珀 / 灰绿
 * - 卡片背景：跟随主题 / 墨 / 暖纸 / 琥珀夜光
 * - 提醒提前量：准时 / 提前 5 分钟 / 提前 15 分钟
 *
 * 全部选项色值取自 DESIGN.md 纸墨时间令牌表，不引入新色相。
 */
class MemoPrefs(context: Context) {

    companion object {
        private const val PREFS = "focus_guard_memo_prefs"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_CARD_STYLE = "card_style"
        private const val KEY_REMINDER_LEAD = "reminder_lead_minutes"

        /** 字号档位。 */
        const val FONT_SMALL = 0
        const val FONT_NORMAL = 1
        const val FONT_LARGE = 2

        /** 文字颜色模式。 */
        const val TEXT_AUTO = 0      // 跟随卡片背景自动取明/暗
        const val TEXT_PAPER = 1     // 暖纸白 #EDE6D6
        const val TEXT_AMBER = 2     // 琥珀 #E2A65D
        const val TEXT_SAGE = 3      // 灰绿 #8AAE8C

        /** 卡片背景模式。 */
        const val CARD_AUTO = 0      // 跟随全局主题玻璃卡
        const val CARD_INK = 1       // 墨 #151B22（暗）
        const val CARD_PAPER = 2     // 暖纸 #F0E9DA（亮）
        const val CARD_AMBER_NIGHT = 3 // 琥珀夜光 #221A10（暗棕）
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 字号档位：0 小 / 1 标准 / 2 大。 */
    var fontScale: Int
        get() = prefs.getInt(KEY_FONT_SCALE, FONT_NORMAL).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_FONT_SCALE, value.coerceIn(0, 2)).apply()

    /** 条目文字大小（sp）。 */
    fun itemFontSize(): Float = when (fontScale) {
        FONT_SMALL -> 12f
        FONT_LARGE -> 16f
        else -> 14f
    }

    /** 文字颜色模式。 */
    var textColor: Int
        get() = prefs.getInt(KEY_TEXT_COLOR, TEXT_AUTO).coerceIn(0, 3)
        set(value) = prefs.edit().putInt(KEY_TEXT_COLOR, value.coerceIn(0, 3)).apply()

    /** 卡片背景模式。 */
    var cardStyle: Int
        get() = prefs.getInt(KEY_CARD_STYLE, CARD_AUTO).coerceIn(0, 3)
        set(value) = prefs.edit().putInt(KEY_CARD_STYLE, value.coerceIn(0, 3)).apply()

    /** 提醒提前量（分钟）：0 / 5 / 15。 */
    var reminderLeadMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_LEAD, 0).coerceIn(0, 15)
        set(value) = prefs.edit().putInt(KEY_REMINDER_LEAD, value.coerceIn(0, 15)).apply()
}
