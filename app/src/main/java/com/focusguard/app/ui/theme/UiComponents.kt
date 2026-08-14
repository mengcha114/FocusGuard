package com.focusguard.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 纸墨时间统一卡片样式（DESIGN.md §3.4）：
 * 细边框分栏代替 Material 阴影；圆角收敛 12dp；卡片容器统一 surfaceVariant。
 */
@Composable
fun Modifier.inkCard(
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    corner: Dp = 12.dp,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
): Modifier = this
    .clip(RoundedCornerShape(corner))
    .background(container)
    .border(1.dp, borderColor, RoundedCornerShape(corner))
