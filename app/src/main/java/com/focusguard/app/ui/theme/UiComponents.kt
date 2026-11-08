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
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 全局背景抓取器。
 *
 * 由 [MainActivity] 在根布局创建并注入：背景层用 `Modifier.layerBackdrop`
 * 把「纯色/渐变/光晕/自定义图」录进一个离屏图层，液态玻璃卡片再用
 * `drawBackdrop` 采样这块背景做真实折射。为 null 时（锁机页、全屏提醒页等
 * 独立 Activity）自动退回纯绘制的近似效果。
 */
val LocalAppBackdrop = staticCompositionLocalOf<com.kyant.backdrop.Backdrop?> { null }

/** 当前是否处于「液态卡片 + 有背景图层」状态（可直接用 glassSurface）。 */
@Composable
fun isGlassActive(): Boolean {
    val a = AppearanceState.get(LocalContext.current)
    return a.cardStyle == Appearance.CARD_LIQUID && LocalAppBackdrop.current != null
}

/**
 * 液态玻璃表面：给导航栏、顶部栏这类整块区域用。
 *
 * 与卡片同一套 backdrop 采样，但参数更偏"厚玻璃"：模糊更大、边缘折射更明显。
 * 只有在「卡片风格 = 液态」且存在背景图层时才生效，否则原样返回。
 */
@Composable
fun Modifier.glassSurface(
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape,
    tint: Color = MaterialTheme.colorScheme.surface,
    surfaceAlpha: Float = 0.30f,
    blurRadius: Dp = 6.dp,
    refractionHeight: Dp? = null,
    refractionAmount: Dp? = null
): Modifier {
    val a = AppearanceState.get(LocalContext.current)
    val backdrop = LocalAppBackdrop.current
    if (a.cardStyle != Appearance.CARD_LIQUID || backdrop == null) return this
    val strength = a.cardBorder.coerceIn(0, 100) / 100f
    val refHeight = refractionHeight
        ?: a.liquidRefractionHeight.coerceIn(8, 40).dp
    val refAmount = refractionAmount
        ?: (refHeight * (a.liquidRefraction.coerceIn(0, 100) / 100f * 2.4f))
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            if (a.liquidRefraction > 0) {
                lens(
                    refHeight.toPx(),
                    refAmount.toPx(),
                    depthEffect = true,
                    chromaticAberration = true
                )
            }
        },
        highlight = if (strength <= 0f) {
            { null }
        } else {
            {
                com.kyant.backdrop.highlight.Highlight(
                    width = (0.3f + strength * 0.5f).dp,
                    alpha = (0.30f + strength * 0.5f)
                )
            }
        },
        onDrawSurface = {
            drawRect(tint.copy(alpha = surfaceAlpha))
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.10f * strength + 0.03f),
                        Color.Transparent,
                        Color.White.copy(alpha = 0.03f)
                    )
                )
            )
        }
    )
}

/** 弹窗容器色：液态模式下透明，由 [liquidPanel] 画玻璃背景。 */
@Composable
fun dialogGlassColor(fallback: Color): Color =
    if (isGlassActive()) Color.Transparent else fallback

/**
 * 弹窗用的液态玻璃面板。
 *
 * 弹窗是独立 Window，采样不到主界面背景图层；如果做成半透明，
 * 会把后面的正文文字一起透出来，直接影响阅读。所以这里只保留玻璃的
 * 「高光 + 弯折描边」质感，底色提高到接近不透明，保证文字清晰。
 */
@Composable
fun Modifier.liquidPanel(
    shape: androidx.compose.ui.graphics.Shape,
    tint: Color = MaterialTheme.colorScheme.surface
): Modifier {
    val a = AppearanceState.get(LocalContext.current)
    if (a.cardStyle != Appearance.CARD_LIQUID) return this
    val strength = a.cardBorder.coerceIn(0, 100) / 100f
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.05f).compositeOver(tint.copy(alpha = 0.985f)),
                    tint.copy(alpha = 0.985f),
                    Color.White.copy(alpha = 0.02f).compositeOver(tint.copy(alpha = 0.985f))
                )
            )
        )
        // 斜向高光：不透明底上也能看出玻璃质感
        .background(
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.10f),
                    Color.Transparent,
                    Color.White.copy(alpha = 0.03f)
                )
            )
        )
        .border(
            if (strength <= 0f) 0.dp else 0.5.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.30f * strength),
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.12f * strength),
                    Color.White.copy(alpha = 0.05f * strength)
                )
            ),
            shape
        )
}

/** 颜色叠加（把半透明白叠到底色上，避免弹窗透出后方文字）。 */
private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    if (a >= 1f) return this
    val outA = a + background.alpha * (1f - a)
    if (outA <= 0f) return Color.Transparent
    return Color(
        red = (red * a + background.red * background.alpha * (1f - a)) / outA,
        green = (green * a + background.green * background.alpha * (1f - a)) / outA,
        blue = (blue * a + background.blue * background.alpha * (1f - a)) / outA,
        alpha = outA
    )
}

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
    if (a.cardStyle == Appearance.CARD_LIQUID) {
        val backdrop = LocalAppBackdrop.current
        if (backdrop != null) {
            // 真·液态玻璃：采样背景图层，做提饱和 + 低模糊 + 边缘折射/色散。
            // 折射参数来自「界面主题 → 卡片」里的滑块（折射高度 / 折射强度）。
            val surface = container ?: scheme.surfaceVariant
            val strength = a.cardBorder.coerceIn(0, 100) / 100f
            val refHeight = a.liquidRefractionHeight.coerceIn(8, 40).dp
            // 强度 0 → 完全取消折射；100 → 约 3 倍高度的位移
            val refAmount = refHeight * (a.liquidRefraction.coerceIn(0, 100) / 100f * 2.4f)
            val hasRefraction = a.liquidRefraction > 0
            var m = this
                .clip(shape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(6f.dp.toPx())
                        if (hasRefraction) {
                            lens(
                                refHeight.toPx(),
                                refAmount.toPx(),
                                depthEffect = true,
                                chromaticAberration = true
                            )
                        }
                    },
                    // 描边强度为 0 时彻底不加白边/高光
                    highlight = if (strength <= 0f) {
                        { null }
                    } else {
                        {
                            com.kyant.backdrop.highlight.Highlight(
                                width = (0.3f + strength * 0.7f).dp,
                                alpha = (0.35f + strength * 0.65f)
                            )
                        }
                    },
                    onDrawSurface = {
                        drawRect(
                            surface.copy(
                                alpha = (a.cardOpacity.coerceIn(40, 100) / 100f) * 0.26f
                            )
                        )
                        drawRect(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.20f * strength + 0.04f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.04f)
                                )
                            )
                        )
                    }
                )
            m = if (strength <= 0f) {
                m
            } else if (borderColor != null) {
                m.border(0.6.dp, borderColor.copy(alpha = borderColor.alpha * strength), shape)
            } else {
                m.border(
                    0.6.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.45f * strength),
                            scheme.outline.copy(alpha = 0.20f * strength),
                            Color.White.copy(alpha = 0.08f * strength)
                        )
                    ),
                    shape
                )
            }
            return m
        }
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
        // 模糊不再靠 inSampleSize 粗粒度降采样（1/2、1/4 跳档），
        // 而是整图解码后按模糊值连续缩小再放大：
        // 模糊值只影响观感，不会把原图固定压成某个低分辨率。
        // 滑动调节时先 debounce，避免每移动 1dp 就重解码一次大图。
        if (blurOn) kotlinx.coroutines.delay(120)
        bitmapState.value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = 1
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                var bmp = try {
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                } catch (oom: OutOfMemoryError) {
                    // 低内存机型：退回 RGB_565，宁可少点色深也别白屏
                    opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                }
                if (bmp != null && blurOn) {
                    // 缩到约 720 宽后做三次盒式模糊，再交给 Compose 平滑放大：
                    // 这样是连续的朦胧感，不是把原图降采样后放大的马赛克方块。
                    val targetW = 720.coerceAtMost(bmp.width)
                    val targetH = (bmp.height * (targetW.toFloat() / bmp.width))
                        .toInt().coerceAtLeast(1)
                    val small = android.graphics.Bitmap.createScaledBitmap(
                        bmp, targetW, targetH, true
                    )
                    if (small !== bmp) bmp.recycle()
                    bmp = com.focusguard.app.util.FastBlur.blur(
                        small,
                        (a.imageBlur * 0.55f).toInt().coerceIn(1, 24)
                    )
                }
                bmp?.asImageBitmap()
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
