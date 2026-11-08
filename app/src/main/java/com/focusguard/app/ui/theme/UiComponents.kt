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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
    if (a.cardStyle == Appearance.CARD_LIQUID && container == null && borderColor == null) {
        return this.liquidGlass(
            shape = shape,
            scheme = scheme,
            opacity = a.cardOpacity.coerceIn(40, 100) / 100f,
            borderStrength = a.cardBorder / 100f
        )
    }
    val fill = container ?: when (a.cardStyle) {
        Appearance.CARD_SOLID -> scheme.surfaceVariant
        Appearance.CARD_OUTLINE -> Color.Transparent
        else -> scheme.surfaceVariant.copy(alpha = a.cardOpacity.coerceIn(40, 100) / 100f)
    }
    val stroke = borderColor ?: when (a.cardStyle) {
        Appearance.CARD_OUTLINE -> scheme.outline.copy(alpha = (a.cardBorder / 70f).coerceIn(0f, 1f))
        Appearance.CARD_SOLID -> scheme.outline.copy(alpha = (a.cardBorder / 100f * 0.55f).coerceIn(0f, 1f))
        else -> scheme.outline.copy(alpha = (a.cardBorder / 100f * 0.9f).coerceIn(0f, 1f))
    }
    return this.clip(shape).background(fill).border(1.dp, stroke, shape)
}

/**
 * 液态玻璃卡片：参考 AndroidLiquidGlass 的观感，用纯绘制近似实现。
 *
 * 真·折射需要把背景重绘到离屏图层再上着色器，而全屏 RenderEffect 在部分
 * Mali/Adreno 机型上会原生崩溃（见 [ImageBackground] 的注释），因此这里用
 * 稳定且零依赖的做法：高光渐变填充 + 双色渐变描边 + 左上/右下两处折射光斑。
 */
private fun Modifier.liquidGlass(
    shape: androidx.compose.ui.graphics.Shape,
    scheme: androidx.compose.material3.ColorScheme,
    opacity: Float,
    borderStrength: Float
): Modifier {
    val base = scheme.surfaceVariant
    val fill = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.18f),
            base.copy(alpha = (opacity * 0.62f).coerceIn(0.18f, 0.72f)),
            Color.White.copy(alpha = 0.04f)
        )
    )
    // 上亮、下暗、再回亮的描边，模拟玻璃边缘的光线弯折
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.55f),
            scheme.outline.copy(alpha = 0.10f + borderStrength * 0.35f),
            Color.White.copy(alpha = 0.10f)
        )
    )
    return this
        .clip(shape)
        .background(fill)
        .border(1.dp, rim, shape)
        .drawWithContent {
            drawContent()
            // 左上主高光
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.16f, 0f),
                    radius = size.width * 0.8f
                )
            )
            // 右下折射亮带
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    radius = size.width
                )
            )
        }
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
    // 渐变/光晕配色：预设优先，否则跟随主题色。
    // 浅色主题传同一下标会拿到浅色变体，避免"换配色后白字看不清"。
    val isLight = base.luminance() > 0.5f
    val presetGradient = Appearance.gradientPreset(a.gradientStyle, isLight)
    val presetGlow = Appearance.presetGlow(a.gradientStyle, isLight)
    val glowA = presetGlow?.first ?: accent
    val glowB = presetGlow?.second ?: secondary
    Box(modifier) {
        when (a.background) {
            Appearance.BG_SOLID -> Box(Modifier.fillMaxSize().background(base))
            Appearance.BG_MESH -> MeshBackground(base, surface, accent, secondary)
            Appearance.BG_IMAGE -> ImageBackground(a, base)
            else -> Box(
                Modifier.fillMaxSize().background(
                    if (presetGradient != null) Brush.verticalGradient(presetGradient)
                    else Brush.verticalGradient(
                        listOf(lerp(base, accent, 0.06f), base, lerp(base, secondary, 0.05f))
                    )
                )
            )
        }
        if (a.glow) GlowLayer(glowA, glowB, a.glowIntensity / 100f, a.glowMotion && a.motion)
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
fun ImageBackground(a: Appearance, base: Color) {
    val context = LocalContext.current
    val file = AppearanceState.imageFile(context, a)
    // 关键：自定义背景图**不能在主线程整图解码**。
    // 原来在 remember 里直接 decodeFile（2160 长边 ≈ 几十 MB），低端机上会把主线程卡住，
    // 表现为「主界面打开就闪退且没有任何日志」——安全模式页不画背景图，所以它能正常打开。
    // 现在：放到 IO 线程解码 + 降采样一档，解码失败就退回纯色/渐变背景（绝不阻塞、绝不崩）。
    val bitmapState = remember(a.imageFile, a.imageBlur) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(a.imageFile, a.imageBlur) {
        bitmapState.value = null
        val f = file ?: return@LaunchedEffect
        // 模糊不再用 Modifier.blur（RenderEffect）：部分 Mali/Adreno 机型对全屏
        // RenderEffect 会直接原生崩溃（无 Java 堆栈），表现就是"设完背景图就闪退"。
        // 改为按模糊强度降采样后放大绘制：视觉接近模糊，且完全走 CPU/常规绘制。
        val blurOn = a.imageBlur > 0
        // 不模糊时必须 1:1 解码：以前无脑 sample=2，等于把 2560 的图压成 1280
        // 再铺满 2K 屏，用户看到的就是「传了原图还是糊」。
        // 模糊档位也要克制：默认 12dp 以前用 sample=8（2560→320），糊得离谱；
        // 现在最多降到 1/2（1280），强模糊才到 1/4。
        val sample = when {
            !blurOn -> 1
            a.imageBlur <= 24 -> 2
            else -> 4
        }
        bitmapState.value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                try {
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
                } catch (oom: OutOfMemoryError) {
                    // 低内存机型：退回 RGB_565，宁可少点色深也别白屏
                    opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val bitmap = bitmapState.value
    Box(Modifier.fillMaxSize().background(base)) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier.fillMaxSize()
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
        Appearance.CARD_LIQUID -> scheme.surfaceVariant.copy(
            alpha = (a.cardOpacity.coerceIn(40, 100) / 100f) * 0.62f
        )
        else -> scheme.surfaceVariant.copy(alpha = a.cardOpacity.coerceIn(40, 100) / 100f)
    }
}
