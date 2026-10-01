package com.focusguard.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 主题模式编码。
 *
 * 新编码：`100 + 风格 × 10 + 外观`。
 * 旧编码 0..3 继续可读（用户已保存的选择无缝迁移）：
 * 0=跟随系统（莫奈） 1=深色·简 2=深色·苔 3=浅色·纸（莫奈）。
 */
object ThemeModes {
    // 旧值（兼容）
    const val DARK_INK = 0
    const val DARK_MONO = 1
    const val DARK_MOSS = 2
    const val LIGHT_PAPER = 3

    // 外观
    const val APPEARANCE_SYSTEM = 0
    const val APPEARANCE_DARK = 1
    const val APPEARANCE_LIGHT = 2

    // 风格
    const val STYLE_INK = 0
    const val STYLE_MONO = 1
    const val STYLE_MOSS = 2
    const val STYLE_OCEAN = 3
    const val STYLE_SAKURA = 4
    const val STYLE_AURORA = 5
    const val STYLE_SUNSET = 6
    const val STYLE_MONET = 7

    fun encode(style: Int, appearance: Int): Int =
        100 + style.coerceIn(0, 9) * 10 + appearance.coerceIn(0, 2)

    fun styleOf(mode: Int): Int = when {
        mode >= 100 -> ((mode - 100) / 10).coerceIn(0, 9)
        mode == DARK_MONO -> STYLE_MONO
        mode == DARK_MOSS -> STYLE_MOSS
        else -> STYLE_MONET
    }

    fun appearanceOf(mode: Int): Int = when {
        mode >= 100 -> ((mode - 100) % 10).coerceIn(0, 2)
        mode == DARK_MONO || mode == DARK_MOSS -> APPEARANCE_DARK
        mode == LIGHT_PAPER -> APPEARANCE_LIGHT
        else -> APPEARANCE_SYSTEM
    }

    fun appearanceLabel(appearance: Int): String = when (appearance) {
        APPEARANCE_DARK -> "深色"
        APPEARANCE_LIGHT -> "浅色"
        else -> "跟随系统"
    }

    fun labelOf(mode: Int): String {
        val style = FocusColors.styles.firstOrNull { it.id == styleOf(mode) }?.label ?: "纸墨"
        return "$style · ${appearanceLabel(appearanceOf(mode))}"
    }
}

/** 由调色板生成 Material 配色（所有风格共用一套映射）。 */
fun schemeFrom(p: FocusColors.Palette): ColorScheme {
    val onAccent = FocusColors.onAccent(p)
    val base = if (p.isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = onAccent,
        primaryContainer = p.accentDeep,
        onPrimaryContainer = if (p.isLight) Color.White else p.bg,
        secondary = p.haze,
        onSecondary = p.bg,
        secondaryContainer = p.card,
        onSecondaryContainer = p.text,
        tertiary = p.glow,
        onTertiary = p.bg,
        background = p.bg,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.card,
        onSurfaceVariant = p.haze,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.card,
        surfaceContainerHighest = p.card,
        surfaceContainerLow = p.surface,
        outline = p.line,
        outlineVariant = p.line.copy(alpha = 0.6f),
        error = p.error,
        onError = if (p.isLight) Color.White else p.bg
    )
}

@Composable
fun FocusGuardTheme(
    themeMode: Int = ThemeModes.DARK_INK,
    accentOverride: Int = 0,
    content: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 读取系统深浅以便「跟随系统」随切换重组
    val sysDark = isSystemInDarkTheme()
    val palette = remember(themeMode, accentOverride, sysDark) {
        FocusColors.paletteFor(themeMode, context, accentOverride)
    }
    MaterialTheme(
        colorScheme = schemeFrom(palette),
        typography = Typography,
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
        ),
        content = content
    )
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

/**
 * 全局主题状态：设置页修改后即时生效（无需重启 Activity）。
 * 首次读取时从 [com.focusguard.app.data.Settings] 初始化。
 */
object ThemeState {
    var mode by androidx.compose.runtime.mutableIntStateOf(-1)
    var accent by androidx.compose.runtime.mutableIntStateOf(0)

    fun ensureLoaded(context: android.content.Context) {
        if (mode >= 0) return
        val s = com.focusguard.app.data.Settings(context)
        mode = s.themeMode
        accent = s.customAccent
    }

    fun update(context: android.content.Context, newMode: Int, newAccent: Int) {
        val s = com.focusguard.app.data.Settings(context)
        s.themeMode = newMode
        s.customAccent = newAccent
        mode = newMode
        accent = newAccent
    }
}
