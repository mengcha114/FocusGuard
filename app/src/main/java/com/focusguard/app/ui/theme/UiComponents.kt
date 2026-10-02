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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
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
    container: Color? = null,
    corner: Dp? = null,
    borderColor: Color? = null
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val a = AppearanceState.get(LocalContext.current)
    val shape = RoundedCornerShape(corner ?: a.cardCorner.dp)
    val fill = container ?: when (a.cardStyle) {
        Appearance.CARD_SOLID -> scheme.surfaceVariant
        Appearance.CARD_OUTLINE -> Color.Transparent
        else -> scheme.surfaceVariant.copy(alpha = a.cardOpacity.coerceIn(40, 100) / 100f)
    }
    val stroke = borderColor ?: when (a.cardStyle) {
        Appearance.CARD_OUTLINE -> scheme.outline
        Appearance.CARD_SOLID -> scheme.outline.copy(alpha = 0.25f)
        else -> scheme.outline.copy(alpha = 0.45f)
    }
    return this.clip(shape).background(fill).border(1.dp, stroke, shape)
}

/**
 * 全局背景层：按 [Appearance.background] 绘制纯色 / 渐变 / 网格光斑 / 自定义图片，
 * 再按需叠加流光光晕。光晕用 Canvas 径向渐变绘制，不依赖 RenderEffect，
 * Android 8+ 全版本都能看到（旧实现在 Android 12 以下直接 return，
 * 且开关只在首次组合时读取一次，所以「开启不起作用」）。
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    secondary: Color = MaterialTheme.colorScheme.tertiary,
    base: Color = MaterialTheme.colorScheme.background,
    surface: Color = MaterialTheme.colorScheme.surface
) {
    val context = LocalContext.current
    val a = AppearanceState.get(context)
    Box(modifier) {
        when (a.background) {
            Appearance.BG_SOLID -> Box(Modifier.fillMaxSize().background(base))
            Appearance.BG_MESH -> MeshBackground(base, surface, accent, secondary)
            Appearance.BG_IMAGE -> ImageBackground(a, base)
            else -> Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(lerp(base, accent, 0.06f), base, lerp(base, secondary, 0.05f)))
                )
            )
        }
        if (a.glow) GlowLayer(accent, secondary, a.glowIntensity / 100f, a.glowMotion && a.motion)
    }
}

/** 兼容旧调用名。 */
@Composable
fun AmbientGlow(
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    secondary: Color = MaterialTheme.colorScheme.tertiary
) = AppBackground(modifier, accent, secondary)

@Composable
private fun GlowLayer(accent: Color, secondary: Color, intensity: Float, moving: Boolean) {
    val on = moving && animationsEnabled()
    val drift = if (on) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(14_000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "drift"
        ).value
    } else 0.5f
    val k = intensity.coerceIn(0.1f, 1f)
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun glow(c: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
            val center = androidx.compose.ui.geometry.Offset(cx, cy)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.35f), Color.Transparent),
                    center = center,
                    radius = r
                ),
                radius = r,
                center = center
            )
        }
        glow(accent, w * (0.82f + 0.12f * drift), h * (0.06f + 0.08f * drift), w * 0.85f, 0.42f * k)
        glow(secondary, w * (0.10f - 0.08f * drift), h * (0.78f - 0.10f * drift), w * 0.80f, 0.34f * k)
        glow(lerp(accent, secondary, 0.5f), w * (0.5f + 0.15f * (drift - 0.5f)), h * 0.45f, w * 0.55f, 0.12f * k)
    }
}

/** 网格光斑：四角多色柔光，适合偏爱浓郁色彩的用户。 */
@Composable
private fun MeshBackground(base: Color, surface: Color, accent: Color, secondary: Color) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        val w = size.width
        val h = size.height
        val spots = listOf(
            Triple(lerp(accent, surface, 0.35f), androidx.compose.ui.geometry.Offset(0f, 0f), 0.55f),
            Triple(lerp(secondary, surface, 0.35f), androidx.compose.ui.geometry.Offset(w, h * 0.35f), 0.50f),
            Triple(lerp(accent, secondary, 0.5f), androidx.compose.ui.geometry.Offset(w * 0.2f, h), 0.45f),
            Triple(lerp(surface, accent, 0.25f), androidx.compose.ui.geometry.Offset(w, h), 0.40f)
        )
        spots.forEach { (c, center, a) ->
            val r = maxOf(w, h) * 0.75f
            drawCircle(
                brush = Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = center, radius = r),
                radius = r,
                center = center
            )
        }
    }
}

/** 自定义图片背景：居中裁切 + 可选模糊 + 主题色遮罩（保证文字可读）。 */
@Composable
private fun ImageBackground(a: Appearance, base: Color) {
    val context = LocalContext.current
    val file = AppearanceState.imageFile(context, a)
    val bitmap = remember(a.imageFile) {
        file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            ?.asImageBitmap()
    }
    Box(Modifier.fillMaxSize().background(base)) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (a.imageBlur > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            Modifier.blur(a.imageBlur.dp) else Modifier
                    )
            )
            Box(Modifier.fillMaxSize().background(base.copy(alpha = a.imageDim.coerceIn(0, 90) / 100f)))
        }
    }
}

/** 系统「动画时长缩放」是否开启（为 0 时所有装饰动画跳过，见 DESIGN.md §3.5）。 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    if (!AppearanceState.get(context).motion) return false
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

/** Material Card 的统一容器色（跟随外观设置：玻璃 / 实色 / 描边 + 不透明度）。 */
@Composable
fun cardContainer(): Color {
    val scheme = MaterialTheme.colorScheme
    val a = AppearanceState.get(LocalContext.current)
    return when (a.cardStyle) {
        Appearance.CARD_SOLID -> scheme.surfaceVariant
        Appearance.CARD_OUTLINE -> scheme.surface.copy(alpha = 0.35f)
        else -> scheme.surfaceVariant.copy(alpha = a.cardOpacity.coerceIn(40, 100) / 100f)
    }
}
