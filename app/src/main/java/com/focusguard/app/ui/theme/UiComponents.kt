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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

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
    modifier: Modifier = Modifier,
    secondary: Color = MaterialTheme.colorScheme.tertiary
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    // 两团光斑缓慢漂移呼吸（12s 周期，幅度克制）；系统关闭动画时静止
    val on = animationsEnabled()
    val drift = if (on) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(12_000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "drift"
        ).value
    } else 0.5f
    Box(modifier) {
        Box(
            modifier = Modifier
                .size(360.dp)
                .align(Alignment.TopEnd)
                .offset(x = (90 + 30 * drift).dp, y = (-100 + 40 * drift).dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.22f + 0.08f * drift), Color.Transparent))
                )
        )
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-110 + 40 * drift).dp, y = (80 - 30 * drift).dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(listOf(secondary.copy(alpha = 0.16f + 0.06f * (1 - drift)), Color.Transparent))
                )
        )
    }
}

/** 系统「动画时长缩放」是否开启（为 0 时所有装饰动画跳过，见 DESIGN.md §3.5）。 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }
}

/**
 * 按压回弹：按下缩到 [pressedScale]，松手弹簧回弹。
 * 传入与 clickable / Button 共用的 [interactionSource] 才能感知按压。
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val on = animationsEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed && on) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
