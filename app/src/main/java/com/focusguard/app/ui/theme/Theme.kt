package com.focusguard.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 主题模式：0=深色·墨 1=深色·简 2=深色·苔 3=浅色·纸。 */
object ThemeModes {
    const val DARK_INK = 0
    const val DARK_MONO = 1
    const val DARK_MOSS = 2
    const val LIGHT_PAPER = 3

    fun labelOf(mode: Int): String = when (mode) {
        DARK_INK -> "深色·墨"
        DARK_MONO -> "深色·简"
        DARK_MOSS -> "深色·苔"
        LIGHT_PAPER -> "浅色·纸"
        else -> "深色·墨"
    }
}

/** 深色·墨（默认）—— 纸墨时间 · 琥珀夜光。 */
private val DarkInkScheme = darkColorScheme(
    primary = FocusColors.ink.accent,
    onPrimary = FocusColors.ink.bg,
    primaryContainer = FocusColors.ink.accentDeep,
    onPrimaryContainer = FocusColors.ink.bg,
    secondary = FocusColors.ink.haze,
    onSecondary = FocusColors.ink.bg,
    secondaryContainer = FocusColors.ink.card,
    onSecondaryContainer = FocusColors.ink.text,
    tertiary = FocusColors.ink.accentDeep,
    onTertiary = FocusColors.ink.bg,
    background = FocusColors.ink.bg,
    onBackground = FocusColors.ink.text,
    surface = FocusColors.ink.surface,
    onSurface = FocusColors.ink.text,
    surfaceVariant = FocusColors.ink.card,
    onSurfaceVariant = FocusColors.ink.haze,
    outline = FocusColors.ink.line,
    error = FocusColors.ink.error,
    onError = FocusColors.ink.bg
)

/** 深色·简 —— 极简计时 · 纯黑白信号。 */
private val DarkMonoScheme = darkColorScheme(
    primary = FocusColors.mono.accent,
    onPrimary = FocusColors.mono.bg,
    primaryContainer = FocusColors.mono.accentDeep,
    onPrimaryContainer = FocusColors.mono.bg,
    secondary = FocusColors.mono.haze,
    onSecondary = FocusColors.mono.bg,
    secondaryContainer = FocusColors.mono.card,
    onSecondaryContainer = FocusColors.mono.text,
    tertiary = FocusColors.mono.accentDeep,
    onTertiary = FocusColors.mono.bg,
    background = FocusColors.mono.bg,
    onBackground = FocusColors.mono.text,
    surface = FocusColors.mono.surface,
    onSurface = FocusColors.mono.text,
    surfaceVariant = FocusColors.mono.card,
    onSurfaceVariant = FocusColors.mono.haze,
    outline = FocusColors.mono.line,
    error = FocusColors.mono.error,
    onError = FocusColors.mono.bg
)

/** 深色·苔 —— 苔原专注 · 鼠尾草绿。 */
private val DarkMossScheme = darkColorScheme(
    primary = FocusColors.moss.accent,
    onPrimary = FocusColors.moss.bg,
    primaryContainer = FocusColors.moss.accentDeep,
    onPrimaryContainer = FocusColors.moss.bg,
    secondary = FocusColors.moss.haze,
    onSecondary = FocusColors.moss.bg,
    secondaryContainer = FocusColors.moss.card,
    onSecondaryContainer = FocusColors.moss.text,
    tertiary = Color(0xFFC9A87C),
    onTertiary = FocusColors.moss.bg,
    background = FocusColors.moss.bg,
    onBackground = FocusColors.moss.text,
    surface = FocusColors.moss.surface,
    onSurface = FocusColors.moss.text,
    surfaceVariant = FocusColors.moss.card,
    onSurfaceVariant = FocusColors.moss.haze,
    outline = FocusColors.moss.line,
    error = FocusColors.moss.error,
    onError = FocusColors.moss.bg
)

/** 浅色·纸 —— 暖纸浅色（仅设置/主界面）。 */
private val LightPaperScheme = lightColorScheme(
    primary = FocusColors.paper.accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8D9BE),
    onPrimaryContainer = FocusColors.paper.accentDeep,
    secondary = FocusColors.paper.haze,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = FocusColors.paper.card,
    onSecondaryContainer = FocusColors.paper.text,
    tertiary = FocusColors.paper.accentDeep,
    onTertiary = Color(0xFFFFFFFF),
    background = FocusColors.paper.bg,
    onBackground = FocusColors.paper.text,
    surface = FocusColors.paper.surface,
    onSurface = FocusColors.paper.text,
    surfaceVariant = FocusColors.paper.card,
    onSurfaceVariant = FocusColors.paper.haze,
    outline = FocusColors.paper.line,
    error = FocusColors.paper.error,
    onError = Color(0xFFFFFFFF)
)

@Composable
fun FocusGuardTheme(
    themeMode: Int = ThemeModes.DARK_INK,
    content: @Composable () -> Unit
) {
    val scheme = when (themeMode) {
        ThemeModes.DARK_MONO -> DarkMonoScheme
        ThemeModes.DARK_MOSS -> DarkMossScheme
        ThemeModes.LIGHT_PAPER -> LightPaperScheme
        else -> DarkInkScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
