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
 * - 提醒形式：通知 / 提示音 / 震动 / 全屏，可任意组合
 *
 * 全部选项色值取自 DESIGN.md 纸墨时间令牌表，不引入新色相。
 */
class MemoPrefs(context: Context) {

    companion object {
        private const val PREFS = "focus_guard_memo_prefs"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_CARD_STYLE = "card_style"
        private const val KEY_CARD_CORNER = "card_corner"
        private const val KEY_LIST_DENSITY = "list_density"
        private const val KEY_REMINDER_LEAD = "reminder_lead_minutes"
        private const val KEY_REMINDER_FLAGS = "reminder_flags"

        /** 字号档位。 */
        const val FONT_SMALL = 0
        const val FONT_NORMAL = 1
        const val FONT_LARGE = 2

        /** 文字颜色模式。 */
        const val TEXT_AUTO = 0      // 跟随卡片背景自动取明/暗
        const val TEXT_PAPER = 1     // 暖纸白 #EDE6D6
        const val TEXT_AMBER = 2     // 琥珀 #E2A65D
        const val TEXT_SAGE = 3      // 灰绿 #8AAE8C
        const val TEXT_SKY = 4       // 雾蓝 #A9C6E8
        const val TEXT_ROSE = 5      // 淡粉 #E7B7B7

        /** 卡片背景模式。 */
        const val CARD_AUTO = 0      // 跟随全局主题玻璃卡
        const val CARD_INK = 1       // 墨 #151B22（暗）
        const val CARD_PAPER = 2     // 暖纸 #F0E9DA（亮）
        const val CARD_AMBER_NIGHT = 3 // 琥珀夜光 #221A10（暗棕）
        const val CARD_SLATE = 4     // 夜蓝 #141C2B（暗蓝）
        const val CARD_MINT = 5      // 薄荷 #E8F5EE（亮绿）
        const val CARD_ROSE = 6      // 豆沙 #F6E9E6（亮粉）

        /** 卡片圆角档位。 */
        const val CORNER_SMALL = 0
        const val CORNER_MEDIUM = 1
        const val CORNER_LARGE = 2

        /** 列表密度。 */
        const val DENSITY_COMPACT = 0
        const val DENSITY_COMFORTABLE = 1

        /** 提醒形式位标记（可按位组合）。 */
        const val REMINDER_NOTIFY = 1      // 通知横幅：基础形式，永远开启
        const val REMINDER_SOUND = 2       // 提示音
        const val REMINDER_VIBRATE = 4     // 震动
        const val REMINDER_FULLSCREEN = 8  // 亮屏时全屏弹出
        private const val REMINDER_ALL =
            REMINDER_NOTIFY or REMINDER_SOUND or REMINDER_VIBRATE or REMINDER_FULLSCREEN

        /**
         * 默认值 = 通知 + 提示音 + 震动，与升级前「高重要性渠道默认响铃振动」一致。
         * 老数据没有这个 key 时也落到这里，保证不会静默无提醒。
         */
        const val REMINDER_DEFAULT = REMINDER_NOTIFY or REMINDER_SOUND or REMINDER_VIBRATE

        /** 清洗位标记：只保留已知位，并强制打开通知位（不允许全不选）。 */
        fun sanitizeReminderFlags(raw: Int): Int = (raw and REMINDER_ALL) or REMINDER_NOTIFY
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
        get() = prefs.getInt(KEY_TEXT_COLOR, TEXT_AUTO).coerceIn(0, 5)
        set(value) = prefs.edit().putInt(KEY_TEXT_COLOR, value.coerceIn(0, 5)).apply()

    /** 卡片背景模式。 */
    var cardStyle: Int
        get() = prefs.getInt(KEY_CARD_STYLE, CARD_AUTO).coerceIn(0, 6)
        set(value) = prefs.edit().putInt(KEY_CARD_STYLE, value.coerceIn(0, 6)).apply()

    /** 卡片圆角档位。 */
    var cardCorner: Int
        get() = prefs.getInt(KEY_CARD_CORNER, CORNER_MEDIUM).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_CARD_CORNER, value.coerceIn(0, 2)).apply()

    /** 列表密度：紧凑 / 舒适。 */
    var listDensity: Int
        get() = prefs.getInt(KEY_LIST_DENSITY, DENSITY_COMFORTABLE).coerceIn(0, 1)
        set(value) = prefs.edit().putInt(KEY_LIST_DENSITY, value.coerceIn(0, 1)).apply()

    /** 卡片圆角（dp）。 */
    fun cardCornerDp(): Int = when (cardCorner) {
        CORNER_SMALL -> 9
        CORNER_LARGE -> 20
        else -> 13
    }

    /** 单条待办上下内边距（dp）。 */
    fun itemVerticalPadding(): Int =
        if (listDensity == DENSITY_COMPACT) 7 else 11

    /** 列表条目间距（dp）。 */
    fun listSpacing(): Int =
        if (listDensity == DENSITY_COMPACT) 6 else 10

    /** 提醒提前量（分钟）：0 / 5 / 15 / 30 / 60。 */
    var reminderLeadMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_LEAD, 0).coerceIn(0, 60)
        set(value) = prefs.edit().putInt(KEY_REMINDER_LEAD, value.coerceIn(0, 60)).apply()

    /**
     * 提醒形式组合（位标记，见 [REMINDER_NOTIFY] 等）。
     * 读写都过 [sanitizeReminderFlags]，保证通知位始终存在。
     */
    var reminderFlags: Int
        get() = sanitizeReminderFlags(prefs.getInt(KEY_REMINDER_FLAGS, REMINDER_DEFAULT))
        set(value) = prefs.edit()
            .putInt(KEY_REMINDER_FLAGS, sanitizeReminderFlags(value))
            .apply()

    fun hasReminderFlag(flag: Int): Boolean = (reminderFlags and flag) != 0
}
