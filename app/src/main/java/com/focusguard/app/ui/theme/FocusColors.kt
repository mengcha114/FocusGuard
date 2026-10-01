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

    /** 纸墨 · 琥珀夜光（默认）——深邃夜空，告别死黑，文字高可读。 */
    val ink = Palette(
        bg = Color(0xFF111622), surface = Color(0xFF18202F), card = Color(0xFF1F293D),
        line = Color(0xFF334155), accent = Color(0xFFF59E0B), accentDeep = Color(0xFFD97706),
        text = Color(0xFFF8FAFC), haze = Color(0xFF94A3B8), faint = Color(0xFF64748B),
        error = Color(0xFFF87171), success = Color(0xFF34D399), glow = Color(0xFFFBBF24)
    )
    val paper = Palette(
        bg = Color(0xFFF8FAFC), surface = Color(0xFFF1F5F9), card = Color(0xFFE2E8F0),
        line = Color(0xFFCBD5E1), accent = Color(0xFFD97706), accentDeep = Color(0xFFB45309),
        text = Color(0xFF0F172A), haze = Color(0xFF475569), faint = Color(0xFF64748B),
        error = Color(0xFFEF4444), success = Color(0xFF10B981), isLight = true, glow = Color(0xFFFBBF24)
    )

    /** 极简 · 炭黑与信号红（告别死黑，提升灰阶对比）。 */
    val mono = Palette(
        bg = Color(0xFF121418), surface = Color(0xFF1A1D24), card = Color(0xFF222730),
        line = Color(0xFF363E4D), accent = Color(0xFFEF4444), accentDeep = Color(0xFFDC2626),
        text = Color(0xFFF8FAFC), haze = Color(0xFF94A3B8), faint = Color(0xFF64748B),
        error = Color(0xFFF87171), success = Color(0xFF4ADE80), glow = Color(0xFFF87171)
    )
    private val monoLight = Palette(
        bg = Color(0xFFF8FAFC), surface = Color(0xFFF1F5F9), card = Color(0xFFE2E8F0),
        line = Color(0xFFCBD5E1), accent = Color(0xFFDC2626), accentDeep = Color(0xFFB91C1C),
        text = Color(0xFF0F172A), haze = Color(0xFF475569), faint = Color(0xFF64748B),
        error = Color(0xFFEF4444), success = Color(0xFF16A34A), isLight = true, glow = Color(0xFFEF4444)
    )

    /** 苔原 · 鼠尾草与松针绿。 */
    val moss = Palette(
        bg = Color(0xFF0F1713), surface = Color(0xFF16231D), card = Color(0xFF1E2F27),
        line = Color(0xFF2D463A), accent = Color(0xFF34D399), accentDeep = Color(0xFF059669),
        text = Color(0xFFF0FDF4), haze = Color(0xFF86EFAC).copy(alpha = 0.85f), faint = Color(0xFF6EE7B7).copy(alpha = 0.6f),
        error = Color(0xFFF87171), success = Color(0xFF10B981), glow = Color(0xFF6EE7B7)
    )
    private val mossLight = Palette(
        bg = Color(0xFFF0FDF4), surface = Color(0xFFDCFCE7), card = Color(0xFFBBF7D0),
        line = Color(0xFF86EFAC), accent = Color(0xFF059669), accentDeep = Color(0xFF047857),
        text = Color(0xFF064E3B), haze = Color(0xFF065F46), faint = Color(0xFF047857),
        error = Color(0xFFDC2626), success = Color(0xFF059669), isLight = true, glow = Color(0xFF34D399)
    )

    /** 深海 · 冰川蓝。 */
    private val ocean = Palette(
        bg = Color(0xFF0C1322), surface = Color(0xFF131D33), card = Color(0xFF1B2947),
        line = Color(0xFF2A3F6D), accent = Color(0xFF38BDF8), accentDeep = Color(0xFF0284C7),
        text = Color(0xFFF0F9FF), haze = Color(0xFF7DD3FC), faint = Color(0xFF38BDF8).copy(alpha = 0.6f),
        error = Color(0xFFF87171), success = Color(0xFF34D399), glow = Color(0xFF818CF8)
    )
    private val oceanLight = Palette(
        bg = Color(0xFFF0F9FF), surface = Color(0xFFE0F2FE), card = Color(0xFFBAE6FD),
        line = Color(0xFF7DD3FC), accent = Color(0xFF0284C7), accentDeep = Color(0xFF0369A1),
        text = Color(0xFF082F49), haze = Color(0xFF075985), faint = Color(0xFF0369A1),
        error = Color(0xFFDC2626), success = Color(0xFF059669), isLight = true, glow = Color(0xFF0284C7)
    )

    /** 樱夜 · 柔粉紫晕。 */
    private val sakura = Palette(
        bg = Color(0xFF1A131F), surface = Color(0xFF241A2B), card = Color(0xFF2E2237),
        line = Color(0xFF4A3757), accent = Color(0xFFF472B6), accentDeep = Color(0xFFDB2777),
        text = Color(0xFFFDF2F8), haze = Color(0xFFF9A8D4), faint = Color(0xFFF472B6).copy(alpha = 0.65f),
        error = Color(0xFFF87171), success = Color(0xFF34D399), glow = Color(0xFFC084FC)
    )
    private val sakuraLight = Palette(
        bg = Color(0xFFFDF2F8), surface = Color(0xFFFCE7F3), card = Color(0xFFFBCFE8),
        line = Color(0xFFF472B6), accent = Color(0xFFDB2777), accentDeep = Color(0xFFBE185D),
        text = Color(0xFF831843), haze = Color(0xFF9D174D), faint = Color(0xFFBE185D),
        error = Color(0xFFDC2626), success = Color(0xFF059669), isLight = true, glow = Color(0xFFC084FC)
    )

    /** 极光 · 青紫霓虹。 */
    private val aurora = Palette(
        bg = Color(0xFF0F1626), surface = Color(0xFF17223B), card = Color(0xFF1F2F52),
        line = Color(0xFF334A7D), accent = Color(0xFF2DD4BF), accentDeep = Color(0xFF0D9488),
        text = Color(0xFFF0FDFA), haze = Color(0xFF99F6E4), faint = Color(0xFF5EEAD4).copy(alpha = 0.65f),
        error = Color(0xFFF87171), success = Color(0xFF34D399), glow = Color(0xFFA78BFA)
    )
    private val auroraLight = Palette(
        bg = Color(0xFFF0FDFA), surface = Color(0xFFCCFBF1), card = Color(0xFF99F6E4),
        line = Color(0xFF5EEAD4), accent = Color(0xFF0D9488), accentDeep = Color(0xFF0F766E),
        text = Color(0xFF134E4A), haze = Color(0xFF115E59), faint = Color(0xFF0F766E),
        error = Color(0xFFDC2626), success = Color(0xFF059669), isLight = true, glow = Color(0xFF8B5CF6)
    )

    /** 日落 · 珊瑚橙。 */
    private val sunset = Palette(
        bg = Color(0xFF1C1310), surface = Color(0xFF281A16), card = Color(0xFF35231D),
        line = Color(0xFF54372E), accent = Color(0xFFFB923C), accentDeep = Color(0xFFEA580C),
        text = Color(0xFFFFF7ED), haze = Color(0xFFFED7AA), faint = Color(0xFFFDBA74).copy(alpha = 0.65f),
        error = Color(0xFFF87171), success = Color(0xFF34D399), glow = Color(0xFFFDE047)
    )
    private val sunsetLight = Palette(
        bg = Color(0xFFFFF7ED), surface = Color(0xFFFFEDD5), card = Color(0xFFFED7AA),
        line = Color(0xFFFDBA74), accent = Color(0xFFEA580C), accentDeep = Color(0xFFC2410C),
        text = Color(0xFF7C2D12), haze = Color(0xFF9A3412), faint = Color(0xFFC2410C),
        error = Color(0xFFDC2626), success = Color(0xFF059669), isLight = true, glow = Color(0xFFEAB308)
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
        val withAccentPalette = if (custom != 0) withAccent(base, Color(custom)) else base

        // 高对比度文字增强模式
        val highContrast = context?.let { runCatching { com.focusguard.app.data.Settings(it).highContrastText }.getOrDefault(false) } ?: false
        return if (highContrast) {
            Palette(
                bg = withAccentPalette.bg,
                surface = withAccentPalette.surface,
                card = withAccentPalette.card,
                line = if (withAccentPalette.isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                accent = withAccentPalette.accent,
                accentDeep = withAccentPalette.accentDeep,
                text = if (withAccentPalette.isLight) Color(0xFF000000) else Color(0xFFFFFFFF),
                haze = if (withAccentPalette.isLight) Color(0xFF1E293B) else Color(0xFFE2E8F0),
                faint = if (withAccentPalette.isLight) Color(0xFF334155) else Color(0xFFCBD5E1),
                error = withAccentPalette.error,
                success = withAccentPalette.success,
                isLight = withAccentPalette.isLight,
                glow = withAccentPalette.glow
            )
        } else withAccentPalette
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
