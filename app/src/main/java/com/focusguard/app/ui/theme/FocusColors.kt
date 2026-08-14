package com.focusguard.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 「纸墨时间」设计令牌——Compose 与传统 View（悬浮窗）双栈共享的单一真相源。
 *
 * 详见仓库根目录 DESIGN.md。四个主题 = 三个视觉方向 + 浅色补充：
 * - 深色·墨（默认）：纸墨时间 · 琥珀夜光
 * - 深色·简：极简计时 · 纯黑白信号
 * - 深色·苔：苔原专注 · 鼠尾草绿
 * - 浅色·纸：暖纸浅色
 */
object FocusColors {

    class Palette(
        val bg: Color,
        val surface: Color,
        val card: Color,
        val line: Color,
        val accent: Color,
        val accentDeep: Color,
        val text: Color,
        val haze: Color,
        val faint: Color,
        val error: Color,
        val success: Color,
        /** 浅色主题标记：锁机页/悬浮窗（夜光表盘）回退深色·墨。 */
        val isLight: Boolean = false
    )

    /** 深色·墨（默认）—— 纸墨时间 · 琥珀夜光。 */
    val ink = Palette(
        bg = Color(0xFF0E1217),
        surface = Color(0xFF151B22),
        card = Color(0xFF1B232C),
        line = Color(0xFF2A343F),
        accent = Color(0xFFE2A65D),
        accentDeep = Color(0xFFB07E42),
        text = Color(0xFFEDE6D6),
        haze = Color(0xFF8F887A),
        faint = Color(0xFF5B574E),
        error = Color(0xFFC9776A),
        success = Color(0xFF8AAE8C)
    )

    /** 深色·简 —— 极简计时 · 纯黑白信号。 */
    val mono = Palette(
        bg = Color(0xFF050607),
        surface = Color(0xFF0C0E10),
        card = Color(0xFF121518),
        line = Color(0xFF23272B),
        accent = Color(0xFFE5484D),
        accentDeep = Color(0xFFB93A3E),
        text = Color(0xFFF2F2F0),
        haze = Color(0xFF9A9C9E),
        faint = Color(0xFF5C5E60),
        error = Color(0xFFFF6B61),
        success = Color(0xFF8FBF8F)
    )

    /** 深色·苔 —— 苔原专注 · 鼠尾草绿。 */
    val moss = Palette(
        bg = Color(0xFF0D1411),
        surface = Color(0xFF131B17),
        card = Color(0xFF18211C),
        line = Color(0xFF263029),
        accent = Color(0xFF9BB894),
        accentDeep = Color(0xFF7A9673),
        text = Color(0xFFE4E9E2),
        haze = Color(0xFF90988D),
        faint = Color(0xFF575E55),
        error = Color(0xFFC9776A),
        success = Color(0xFFA9C3A0)
    )

    /** 浅色·纸 —— 暖纸浅色（仅设置/主界面；锁机页回退 ink）。 */
    val paper = Palette(
        bg = Color(0xFFF4F0E6),
        surface = Color(0xFFEDE7D8),
        card = Color(0xFFE5DECD),
        line = Color(0xFFD6CDB9),
        accent = Color(0xFFA9742F),
        accentDeep = Color(0xFF8C5F24),
        text = Color(0xFF1D1A14),
        haze = Color(0xFF6B6455),
        faint = Color(0xFF9A9180),
        error = Color(0xFFB15347),
        success = Color(0xFF4E7A58),
        isLight = true
    )

    /** 按主题模式取值；未知值回退默认墨。 */
    fun paletteFor(mode: Int): Palette = when (mode) {
        ThemeModes.DARK_MONO -> mono
        ThemeModes.DARK_MOSS -> moss
        ThemeModes.LIGHT_PAPER -> paper
        else -> ink
    }

    /** 锁机页/悬浮窗用：浅色主题回退深色·墨（夜光表盘需暗场）。 */
    fun paletteForLockScreen(mode: Int): Palette {
        val p = paletteFor(mode)
        return if (p.isLight) ink else p
    }

    /** 传统 View 侧使用：把令牌转成 `#RRGGBB` 十六进制字符串。 */
    fun hex(c: Color): String =
        String.format("#%06X", c.toArgb() and 0xFFFFFF)
}
