package com.focusguard.app.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 纸墨时间统一卡片样式（DESIGN.md §3.4 + 玻璃材质）：
 * 半透明容器 + 细边框（无 Material 阴影），配合页面背景光斑呈现高级材质感。
 */
@Composable
fun Modifier.inkCard(
    container: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    corner: Dp = 12.dp,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
): Modifier = this
    .clip(RoundedCornerShape(corner))
    .background(container)
    .border(1.dp, borderColor, RoundedCornerShape(corner))

/**
 * 环境光斑：两团模糊的强调色光晕铺在页面背景层，
 * 与半透明卡片（[inkCard]）叠加形成玻璃拟态。
 *
 * 仅 Android 12+（RenderEffect 模糊可用）；低版本自动跳过，
 * 半透明卡片在纯色背景上依旧成立。
 */
@Composable
fun AmbientGlow(
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    Box(modifier) {
        Box(
            modifier = Modifier
                .size(340.dp)
                .align(Alignment.TopEnd)
                .offset(x = 100.dp, y = (-90).dp)
                .blur(70.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent))
                )
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-100).dp, y = 70.dp)
                .blur(70.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.18f), Color.Transparent))
                )
        )
    }
}
