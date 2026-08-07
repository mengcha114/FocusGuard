package com.focusguard.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 主题模式：0=深色紫 1=深色蓝 2=深色绿 3=浅色。 */
object ThemeModes {
    const val DARK_PURPLE = 0
    const val DARK_BLUE = 1
    const val DARK_GREEN = 2
    const val LIGHT = 3

    fun labelOf(mode: Int): String = when (mode) {
        DARK_PURPLE -> "深色·紫"
        DARK_BLUE -> "深色·蓝"
        DARK_GREEN -> "深色·绿"
        LIGHT -> "浅色"
        else -> "深色·紫"
    }
}

private val DarkPurpleScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    background = Color(0xFF141416),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val DarkBlueScheme = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF0F4B74),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFB9C8DA),
    onSecondary = Color(0xFF233240),
    secondaryContainer = Color(0xFF3A4857),
    onSecondaryContainer = Color(0xFFD5E4F6),
    tertiary = Color(0xFFD8BDE0),
    onTertiary = Color(0xFF3B2943),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E2E9),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val DarkGreenScheme = darkColorScheme(
    primary = Color(0xFFA7D8A0),
    onPrimary = Color(0xFF0D3A12),
    primaryContainer = Color(0xFF25612A),
    onPrimaryContainer = Color(0xFFC3EFBA),
    secondary = Color(0xFFB9CCB4),
    onSecondary = Color(0xFF243426),
    secondaryContainer = Color(0xFF3A4A3B),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFFA3CDA8),
    onTertiary = Color(0xFF083518),
    background = Color(0xFF101410),
    onBackground = Color(0xFFE0E4DE),
    surface = Color(0xFF101410),
    onSurface = Color(0xFFE0E4DE),
    surfaceVariant = Color(0xFF424843),
    onSurfaceVariant = Color(0xFFC2C8C1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFDF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFDF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun FocusGuardTheme(
    themeMode: Int = ThemeModes.DARK_PURPLE,
    content: @Composable () -> Unit
) {
    val scheme = when (themeMode) {
        ThemeModes.DARK_BLUE -> DarkBlueScheme
        ThemeModes.DARK_GREEN -> DarkGreenScheme
        ThemeModes.LIGHT -> LightScheme
        else -> DarkPurpleScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
