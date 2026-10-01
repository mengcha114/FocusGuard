package com.focusguard.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp

/**
 * 设计令牌——Compose 与传统 View（悬浮窗）双栈共享的单一真相源。
 *
 * 主题 = 风格（配色方向）× 外观（跟随系统 / 深色 / 浅色）。每种风格各有
 * 深浅两版调色板；「莫奈」风格在 Android 12+ 从壁纸取色。
 * 详见仓库根目录 DESIGN.md。
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
        val isLight: Boolean = false,
        /** 背景光斑第二色（与 accent 组成柔和双色光晕；单色风格与 accent 相同）。 */
        val glow: Color = accent
    )

    // ── 风格定义：每种风格的深色 / 浅色调色板 ──────────────────

    /** 纸墨 · 琥珀夜光（默认）。 */
    val ink = Palette(
        bg = Color(0xFF0E1217), surface = Color(0xFF151B22), card = Color(0xFF1B232C),
        line = Color(0xFF2A343F), accent = Color(0xFFE2A65D), accentDeep = Color(0xFFB07E42),
        text = Color(0xFFEDE6D6), haze = Color(0xFF8F887A), faint = Color(0xFF5B574E),
        error = Color(0xFFC9776A), success = Color(0xFF8AAE8C)
    )
    val paper = Palette(
        bg = Color(0xFFF4F0E6), surface = Color(0xFFEDE7D8), card = Color(0xFFE5DECD),
        line = Color(0xFFD6CDB9), accent = Color(0xFFA9742F), accentDeep = Color(0xFF8C5F24),
        text = Color(0xFF1D1A14), haze = Color(0xFF6B6455), faint = Color(0xFF9A9180),
        error = Color(0xFFB15347), success = Color(0xFF4E7A58), isLight = true
    )

    /** 极简 · 黑白信号红。 */
    val mono = Palette(
        bg = Color(0xFF050607), surface = Color(0xFF0C0E10), card = Color(0xFF121518),
        line = Color(0xFF23272B), accent = Color(0xFFE5484D), accentDeep = Color(0xFFB93A3E),
        text = Color(0xFFF2F2F0), haze = Color(0xFF9A9C9E), faint = Color(0xFF5C5E60),
        error = Color(0xFFFF6B61), success = Color(0xFF8FBF8F)
    )
    private val monoLight = Palette(
        bg = Color(0xFFFAFAF9), surface = Color(0xFFF2F2F1), card = Color(0xFFE9E9E8),
        line = Color(0xFFD9D9D7), accent = Color(0xFFD2363C), accentDeep = Color(0xFFA9292E),
        text = Color(0xFF111213), haze = Color(0xFF5E6062), faint = Color(0xFF9A9C9E),
        error = Color(0xFFC62F2F), success = Color(0xFF3F7A46), isLight = true
    )

    /** 苔原 · 鼠尾草绿。 */
    val moss = Palette(
        bg = Color(0xFF0D1411), surface = Color(0xFF131B17), card = Color(0xFF18211C),
        line = Color(0xFF263029), accent = Color(0xFF9BB894), accentDeep = Color(0xFF7A9673),
        text = Color(0xFFE4E9E2), haze = Color(0xFF90988D), faint = Color(0xFF575E55),
        error = Color(0xFFC9776A), success = Color(0xFFA9C3A0), glow = Color(0xFFC9A87C)
    )
    private val mossLight = Palette(
        bg = Color(0xFFF1F4EE), surface = Color(0xFFE8EDE4), card = Color(0xFFDDE5D8),
        line = Color(0xFFC9D3C3), accent = Color(0xFF557A4F), accentDeep = Color(0xFF41603C),
        text = Color(0xFF16201A), haze = Color(0xFF5B665A), faint = Color(0xFF8E988C),
        error = Color(0xFFAE4F43), success = Color(0xFF3F6D47), isLight = true,
        glow = Color(0xFFB08A55)
    )

    /** 深海 · 冰川蓝。 */
    private val ocean = Palette(
        bg = Color(0xFF0A1220), surface = Color(0xFF0F1A2C), card = Color(0xFF142238),
        line = Color(0xFF22324C), accent = Color(0xFF6CB4FF), accentDeep = Color(0xFF3F86D6),
        text = Color(0xFFE3ECF7), haze = Color(0xFF8798AE), faint = Color(0xFF52627A),
        error = Color(0xFFF07A7A), success = Color(0xFF6FCFB0), glow = Color(0xFF7E8CFF)
    )
    private val oceanLight = Palette(
        bg = Color(0xFFF2F6FB), surface = Color(0xFFE8EFF8), card = Color(0xFFDCE6F3),
        line = Color(0xFFC6D4E6), accent = Color(0xFF1F6FC9), accentDeep = Color(0xFF16559C),
        text = Color(0xFF0E1A2A), haze = Color(0xFF55657A), faint = Color(0xFF8C9AAD),
        error = Color(0xFFC0392B), success = Color(0xFF1E8A67), isLight = true,
        glow = Color(0xFF5868E0)
    )

    /** 樱夜 · 柔粉紫晕。 */
    private val sakura = Palette(
        bg = Color(0xFF15101A), surface = Color(0xFF1C1522), card = Color(0xFF241B2B),
        line = Color(0xFF362A3F), accent = Color(0xFFF29BB8), accentDeep = Color(0xFFC9718F),
        text = Color(0xFFF3E8EF), haze = Color(0xFFA08F9C), faint = Color(0xFF65586A),
        error = Color(0xFFFF8A80), success = Color(0xFF9BD1B0), glow = Color(0xFFB59BF2)
    )
    private val sakuraLight = Palette(
        bg = Color(0xFFFBF4F7), surface = Color(0xFFF5EAF0), card = Color(0xFFEEDDE6),
        line = Color(0xFFE0C9D5), accent = Color(0xFFC2457A), accentDeep = Color(0xFF9C3561),
        text = Color(0xFF231620), haze = Color(0xFF6E5A67), faint = Color(0xFFA592A0),
        error = Color(0xFFB83A3A), success = Color(0xFF3C7D58), isLight = true,
        glow = Color(0xFF8A63D2)
    )

    /** 极光 · 青紫霓虹。 */
    private val aurora = Palette(
        bg = Color(0xFF080B14), surface = Color(0xFF0E1220), card = Color(0xFF141A2C),
        line = Color(0xFF232B44), accent = Color(0xFF5EEAD4), accentDeep = Color(0xFF2CB9A4),
        text = Color(0xFFE6EEF8), haze = Color(0xFF8A95AD), faint = Color(0xFF515B75),
        error = Color(0xFFFF7A90), success = Color(0xFF7EE0A1), glow = Color(0xFFA78BFA)
    )
    private val auroraLight = Palette(
        bg = Color(0xFFF3F6FA), surface = Color(0xFFE9EEF6), card = Color(0xFFDDE4F0),
        line = Color(0xFFC7D1E2), accent = Color(0xFF0F8F7E), accentDeep = Color(0xFF0B6E61),
        text = Color(0xFF0E1424), haze = Color(0xFF56607A), faint = Color(0xFF8C95AB),
        error = Color(0xFFC2334D), success = Color(0xFF237D4A), isLight = true,
        glow = Color(0xFF7354D6)
    )

    /** 日落 · 珊瑚橙。 */
    private val sunset = Palette(
        bg = Color(0xFF140E0C), surface = Color(0xFF1C1411), card = Color(0xFF251A16),
        line = Color(0xFF3A2A23), accent = Color(0xFFFF8A5C), accentDeep = Color(0xFFD9653A),
        text = Color(0xFFF7EAE2), haze = Color(0xFFA8928A), faint = Color(0xFF6C5A53),
        error = Color(0xFFFF6B6B), success = Color(0xFF9CCF8E), glow = Color(0xFFFFC56B)
    )
    private val sunsetLight = Palette(
        bg = Color(0xFFFCF5F0), surface = Color(0xFFF6EBE3), card = Color(0xFFEFDDD1),
        line = Color(0xFFE2C9B9), accent = Color(0xFFCC5427), accentDeep = Color(0xFFA2411D),
        text = Color(0xFF26170F), haze = Color(0xFF715E53), faint = Color(0xFFA8958A),
        error = Color(0xFFB8342C), success = Color(0xFF3F7A3A), isLight = true,
        glow = Color(0xFFD99A2B)
    )

    /** 风格条目：供设置页渲染色板预览。 */
    class Style(val id: Int, val label: String, val dark: Palette, val light: Palette)

    val styles: List<Style> = listOf(
        Style(ThemeModes.STYLE_INK, "纸墨", ink, paper),
        Style(ThemeModes.STYLE_MONO, "极简", mono, monoLight),
        Style(ThemeModes.STYLE_MOSS, "苔原", moss, mossLight),
        Style(ThemeModes.STYLE_OCEAN, "深海", ocean, oceanLight),
        Style(ThemeModes.STYLE_SAKURA, "樱夜", sakura, sakuraLight),
        Style(ThemeModes.STYLE_AURORA, "极光", aurora, auroraLight),
        Style(ThemeModes.STYLE_SUNSET, "日落", sunset, sunsetLight),
        Style(ThemeModes.STYLE_MONET, "莫奈", ink, paper)
    )

    private fun styleOf(id: Int): Style = styles.firstOrNull { it.id == id } ?: styles[0]

    /** 系统当前是否深色。 */
    fun systemDark(context: Context?): Boolean = context?.let {
        (it.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    } ?: true

    /**
     * 按主题模式取调色板（全应用、锁机页、悬浮窗统一入口）。
     * [accentOverride] 为自定义强调色（0 = 不覆盖），会自动校正对比度。
     */
    fun paletteFor(mode: Int, context: Context? = null, accentOverride: Int = 0): Palette {
        val style = ThemeModes.styleOf(mode)
        val dark = when (ThemeModes.appearanceOf(mode)) {
            ThemeModes.APPEARANCE_DARK -> true
            ThemeModes.APPEARANCE_LIGHT -> false
            else -> systemDark(context)
        }
        val base = if (style == ThemeModes.STYLE_MONET && context != null) {
            dynamicPalette(context, dark) ?: if (dark) ink else paper
        } else {
            val s = styleOf(style)
            if (dark) s.dark else s.light
        }
        val custom = if (accentOverride != 0) accentOverride
        else context?.let { customAccentOf(it) } ?: 0
        return if (custom != 0) withAccent(base, Color(custom)) else base
    }

    /** 锁机页/悬浮窗调色板：与全局一致。 */
    fun paletteForLockScreen(mode: Int, context: Context? = null): Palette =
        paletteFor(mode, context)

    private fun customAccentOf(context: Context): Int = runCatching {
        com.focusguard.app.data.Settings(context).customAccent
    }.getOrDefault(0)

    /** WCAG 对比度。 */
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return if (la > lb) la / lb else lb / la
    }

    /**
     * 套用自定义强调色：与背景对比度不足 4.5:1 时向文字色方向逐步提亮/压暗，
     * 保证按钮文字与进度环在任何风格上都清晰（DESIGN.md §4）。
     */
    fun withAccent(base: Palette, picked: Color): Palette {
        var accent = picked.copy(alpha = 1f)
        var t = 0f
        while (contrast(accent, base.bg) < 4.5 && t < 1f) {
            t += 0.1f
            accent = lerp(picked.copy(alpha = 1f), base.text, t)
        }
        val deep = lerp(accent, base.bg, 0.25f)
        return Palette(
            bg = base.bg, surface = base.surface, card = base.card, line = base.line,
            accent = accent, accentDeep = deep, text = base.text, haze = base.haze,
            faint = base.faint, error = base.error, success = base.success,
            isLight = base.isLight, glow = lerp(accent, base.glow, 0.5f)
        )
    }

    /** 强调色上文字的颜色（对比度更高的那个）。 */
    fun onAccent(p: Palette): Color =
        if (contrast(p.bg, p.accent) >= contrast(Color.White, p.accent)) p.bg else Color.White

    /** Android 12+ 莫奈取色：从壁纸生成调色板；低版本或失败返回 null。 */
    fun dynamicPalette(context: Context, dark: Boolean): Palette? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            Palette(
                bg = scheme.background,
                surface = scheme.surface,
                card = scheme.surfaceVariant,
                line = scheme.outline,
                accent = scheme.primary,
                accentDeep = scheme.primaryContainer,
                text = scheme.onBackground,
                haze = scheme.onSurfaceVariant,
                faint = scheme.outline,
                error = scheme.error,
                success = scheme.tertiary,
                isLight = !dark,
                glow = scheme.tertiary
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 传统 View 侧使用：把令牌转成 `#RRGGBB` 十六进制字符串。 */
    fun hex(c: Color): String =
        String.format("#%06X", c.toArgb() and 0xFFFFFF)

    /** 设置页自定义强调色候选。 */
    val accentSwatches: List<Color> = listOf(
        Color(0xFFE2A65D), Color(0xFFFF8A5C), Color(0xFFE5484D), Color(0xFFF29BB8),
        Color(0xFFA78BFA), Color(0xFF6CB4FF), Color(0xFF5EEAD4), Color(0xFF9BB894),
        Color(0xFFFFD166), Color(0xFFB0B7C3)
    )
}
