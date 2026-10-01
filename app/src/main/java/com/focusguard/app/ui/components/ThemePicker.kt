package com.focusguard.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.ui.theme.FocusColors
import com.focusguard.app.ui.theme.ThemeModes
import com.focusguard.app.ui.theme.ThemeState
import com.focusguard.app.ui.theme.pressScale

/**
 * 主题选择器：外观（跟随系统/深色/浅色）× 风格（8 套）+ 自定义强调色。
 * 选择即时生效（[ThemeState]），属于纯外观设置，不触发放宽验证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePicker() {
    val context = LocalContext.current
    ThemeState.ensureLoaded(context)
    val mode = ThemeState.mode
    val accent = ThemeState.accent
    val style = ThemeModes.styleOf(mode)
    val appearance = ThemeModes.appearanceOf(mode)
    val sysDark = FocusColors.systemDark(context)
    val showDark = when (appearance) {
        ThemeModes.APPEARANCE_DARK -> true
        ThemeModes.APPEARANCE_LIGHT -> false
        else -> sysDark
    }

    fun apply(newStyle: Int = style, newAppearance: Int = appearance, newAccent: Int = accent) {
        ThemeState.update(context, ThemeModes.encode(newStyle, newAppearance), newAccent)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ── 外观 ──
        Text("外观", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(
                ThemeModes.APPEARANCE_SYSTEM,
                ThemeModes.APPEARANCE_DARK,
                ThemeModes.APPEARANCE_LIGHT
            ).forEachIndexed { i, a ->
                SegmentedButton(
                    selected = appearance == a,
                    onClick = { apply(newAppearance = a) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3)
                ) { Text(ThemeModes.appearanceLabel(a), fontSize = 13.sp) }
            }
        }

        // ── 风格 ──
        Text("风格", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(FocusColors.styles, key = { it.id }) { s ->
                val p = if (s.id == ThemeModes.STYLE_MONET) {
                    FocusColors.dynamicPalette(context, showDark) ?: if (showDark) s.dark else s.light
                } else if (showDark) s.dark else s.light
                StyleCard(
                    label = s.label,
                    palette = p,
                    selected = style == s.id,
                    onClick = { apply(newStyle = s.id) }
                )
            }
        }

        // ── 强调色 ──
        Text("强调色", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Swatch(
                    color = null,
                    selected = accent == 0,
                    description = "使用风格默认强调色",
                    onClick = { apply(newAccent = 0) }
                )
            }
            items(FocusColors.accentSwatches) { c ->
                Swatch(
                    color = c,
                    selected = accent == c.toArgb(),
                    description = "强调色 ${FocusColors.hex(c)}",
                    onClick = { apply(newAccent = c.toArgb()) }
                )
            }
        }
        Text(
            text = "强调色对比度不足时会自动校正，保证文字清晰",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/** 风格预览卡：迷你界面（背景 + 卡片 + 强调色按钮 + 光晕）。 */
@Composable
private fun StyleCard(
    label: String,
    palette: FocusColors.Palette,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        tween(200), label = "styleBorder"
    )
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(200), label = "styleBorderW")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pressScale(interaction)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "$label 风格"
            }
            .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 112.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.bg)
                .background(
                    Brush.radialGradient(
                        listOf(palette.glow.copy(alpha = 0.35f), Color.Transparent),
                        radius = 160f
                    )
                )
                .border(borderWidth, border, RoundedCornerShape(16.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(width = 40.dp, height = 6.dp).clip(CircleShape).background(palette.text.copy(alpha = 0.85f)))
                Box(Modifier.size(width = 28.dp, height = 4.dp).clip(CircleShape).background(palette.haze.copy(alpha = 0.7f)))
                Box(
                    Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
                        .background(palette.card).border(1.dp, palette.line, RoundedCornerShape(8.dp))
                )
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp)
                    .clip(RoundedCornerShape(6.dp)).background(palette.accent)
            )
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(palette.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, tint = FocusColors.onAccent(palette), modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Swatch(color: Color?, selected: Boolean, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, tween(180), label = "swatchRing")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .border(ring, MaterialTheme.colorScheme.onBackground, CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                this.selected = selected
                contentDescription = description
            }
            .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
    ) {
        if (color == null) {
            Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
