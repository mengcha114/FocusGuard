package com.focusguard.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.ui.theme.inkCard
import com.focusguard.app.ui.theme.pressScale

/**
 * 通用视觉组件：统一卡片、选项块、分段标题。
 * 所有选中态变化走 200ms 颜色过渡，按压带弹簧回弹（受系统动画缩放约束）。
 */

/** 区块：标题 + 可选说明 + 内容卡片。 */
@Composable
fun FxSection(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .inkCard(corner = 20.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        content()
    }
}

/** 可选块（替代 FilterChip）：选中时强调色描边 + 浅色填充，按压回弹。 */
@Composable
fun FxOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surface.copy(alpha = 0.5f),
        tween(200), label = "optBg"
    )
    val border by animateColorAsState(
        if (selected) scheme.primary else scheme.outline.copy(alpha = 0.5f),
        tween(200), label = "optBorder"
    )
    val fg by animateColorAsState(
        when {
            !enabled -> scheme.onSurface.copy(alpha = 0.35f)
            selected -> scheme.primary
            else -> scheme.onSurface
        },
        tween(200), label = "optFg"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(14.dp))
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = fg)
        if (caption != null) {
            Text(caption, fontSize = 11.sp, color = fg.copy(alpha = 0.7f))
        }
    }
}

/** 等宽选项行。 */
@Composable
fun <T> FxOptionRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    caption: ((T) -> String?)? = null,
    enabled: Boolean = true
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FxOption(
                label = label(o),
                caption = caption?.invoke(o),
                selected = o == selected,
                onClick = { onSelect(o) },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 主按钮：强调色实底，按压回弹。 */
@Composable
fun FxPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(interaction)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 提示条（信息 / 警告）。 */
@Composable
fun FxNotice(text: String, tone: Color = MaterialTheme.colorScheme.primary, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tone.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.35f)), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 19.sp)
    }
}

/** 展开/收起动画容器。 */
@Composable
fun FxExpand(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(160))
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** 小圆点徽标。 */
@Composable
fun FxDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}
