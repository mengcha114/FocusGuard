package com.focusguard.app.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** 底栏条目。 */
data class LiquidTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

internal val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }

@Composable
fun RowScope.LiquidBottomTab(
    index: Int = -1,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/**
 * 严格按照 Kyant0/AndroidLiquidGlass (1.0.1) 官方标准实现的液态玻璃底栏。
 *
 * 官方三层光学结构：
 * 1. 底栏胶囊玻璃层：采样页面背景与滚动内容（模糊柔化 + 边缘真实折射），承载未选中态图标与文字；
 * 2. 隐藏染色镜像层（alpha = 0 → tabsBackdrop）：同步绘制同款磨砂胶囊玻璃底 + 主题强调色染色的图标与文字；
 * 3. 可拖动选中胶囊药丸（height = 56.dp，包裹完整单格图标+文字）：
 *    - 采样 rememberCombinedBackdrop(backdrop, tabsBackdrop)，静止时胶囊内干净呈现主题色高亮图标与文字（零重影、零错位）；
 *    - 按住或拖动时（pressProgress > 0），药丸以弹性弹簧鼓起（pressedScale ≈ 76/56），AGSL lens 折射与边缘彩色色散实时介入，
 *      滑过相邻标签时像真实水滴透镜一样实时染色与弯折，松手平滑吸附回槽。
 */
@Composable
fun LiquidBottomTabs(
    backdrop: Backdrop,
    tabs: List<LiquidTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    refractionHeight: Dp = 24.dp,
    refractionAmount: Dp = 24.dp,
    blurRadius: Dp = 10.dp,
    surfaceAlpha: Float = 0.65f,
    highlightStrength: Float = 0.6f,
    depth: Float = 0.35f,
    saturation: Float = 1.1f,
    brightness: Float = 0f,
    depthEffect: Boolean = true,
    dispersion: Boolean = true,
    borderVisible: Boolean = true,
    borderStrength: Float = 0.45f,
    pillScalePercent: Int = 100,
    content: @Composable RowScope.() -> Unit
) {
    if (tabs.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val isLightTheme = scheme.background.luminance() > 0.5f
    val accentColor = scheme.primary
    val inactiveColor = if (isLightTheme) {
        scheme.onSurface.copy(alpha = 0.68f)
    } else {
        scheme.onSurface.copy(alpha = 0.72f)
    }
    val containerColor = if (isLightTheme) {
        Color(0xFFFAFAFA).copy(alpha = surfaceAlpha.coerceIn(0.32f, 0.92f))
    } else {
        Color(0xFF141821).copy(alpha = surfaceAlpha.coerceIn(0.32f, 0.90f))
    }
    val capsuleShape = RoundedCornerShape(percent = 50)
    val tabsCount = tabs.size
    val showRim = borderVisible && borderStrength > 0f && highlightStrength > 0f

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val innerBarWidthPx = (constraints.maxWidth.toFloat() - with(density) { 8.dp.toPx() })
            .coerceAtLeast(1f)
        val tabWidthPx = innerBarWidthPx / tabsCount
        val pillScale = (pillScalePercent.coerceIn(85, 105) / 100f)
        val pillWidthDp = with(density) { (tabWidthPx * pillScale).toDp() }
        val pillHeightDp = (56.dp * pillScale.coerceIn(0.90f, 1.04f))
        val pillInsetX = (tabWidthPx - with(density) { pillWidthDp.toPx() }) * 0.5f

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val maxW = constraints.maxWidth.coerceAtLeast(1)
                val fraction = (offsetAnimation.value / maxW).fastCoerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedIndex) }

        val dampedDragAnimation = remember(animationScope, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 76f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(selectedIndex) {
            if (selectedIndex != currentIndex) {
                currentIndex = selectedIndex
                dampedDragAnimation.animateToValue(selectedIndex.toFloat())
            }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope, tabWidthPx, isLtr) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // ── 第 1 层：整条底栏磨砂液态玻璃 + 未选中态图标/文字 ──
        CompositionLocalProvider(LocalContentColor provides inactiveColor) {
            Row(
                Modifier
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { capsuleShape },
                        effects = {
                            vibrancy()
                            if (blurRadius > 0.dp) blur(blurRadius.toPx())
                            if (saturation != 1f || brightness != 0f) {
                                colorControls(brightness = brightness, saturation = saturation)
                            }
                            if (refractionHeight > 0.dp && refractionAmount > 0.dp) {
                                lens(
                                    refractionHeight.toPx(),
                                    refractionAmount.toPx(),
                                    depthEffect = depthEffect,
                                    chromaticAberration = dispersion
                                )
                            }
                        },
                        highlight = if (!showRim) {
                            null
                        } else {
                            {
                                Highlight(
                                    width = (0.25f + 0.35f * highlightStrength * borderStrength).dp,
                                    alpha = (0.18f + 0.55f * highlightStrength *
                                        (0.4f + 0.6f * borderStrength)).coerceAtMost(0.90f)
                                )
                            }
                        },
                        shadow = if (depth <= 0f) {
                            null
                        } else {
                            {
                                Shadow(
                                    radius = (10f + 16f * depth).dp,
                                    alpha = (0.12f + 0.28f * depth).coerceAtMost(0.55f)
                                )
                            }
                        },
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(64.dp)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // ── 第 2 层：隐藏主题色镜像层（官方核心机制） ──
        // 同步绘制底栏磨砂玻璃底 + 主题强调色染色的图标和文字，录进 tabsBackdrop。
        // 这样第 3 层选中胶囊采样时，既能完全遮住第 1 层未选中深灰字（绝无叠字重影），
        // 又能在胶囊内部呈现鲜明的主题色图标与文字（1:1 还原图二效果）。
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.16f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { capsuleShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            if (blurRadius > 0.dp) blur(blurRadius.toPx())
                            if (saturation != 1f || brightness != 0f) {
                                colorControls(brightness = brightness, saturation = saturation)
                            }
                            if (refractionHeight > 0.dp && refractionAmount > 0.dp && progress > 0f) {
                                lens(
                                    refractionHeight.toPx() * progress,
                                    refractionAmount.toPx() * progress,
                                    depthEffect = depthEffect,
                                    chromaticAberration = dispersion
                                )
                            }
                        },
                        highlight = if (!showRim) {
                            null
                        } else {
                            {
                                val progress = dampedDragAnimation.pressProgress
                                Highlight.Default.copy(alpha = progress * highlightStrength)
                            }
                        },
                        shadow = null,
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // ── 第 3 层：选中胶囊药丸（静止为干净浮起胶囊，按住拖动化作液态折射水滴） ──
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    val pillW = pillWidthDp.toPx()
                    translationX = if (isLtr) {
                        dampedDragAnimation.value * tabWidthPx + pillInsetX + panelOffset
                    } else {
                        innerBarWidthPx - (dampedDragAnimation.value + 1f) * tabWidthPx +
                            pillInsetX + (tabWidthPx - pillW) + panelOffset
                    }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { capsuleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        if (refractionHeight > 0.dp && refractionAmount > 0.dp && progress > 0.005f) {
                            val h = (refractionHeight * 0.48f).coerceIn(8.dp, 20.dp) * progress
                            val a = (refractionAmount * 0.62f).coerceIn(8.dp, 28.dp) * progress
                            lens(
                                h.toPx(),
                                a.toPx(),
                                depthEffect = depthEffect,
                                chromaticAberration = dispersion
                            )
                        }
                    },
                    highlight = if (!showRim) {
                        null
                    } else {
                        {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight(
                                width = (0.25f + 0.30f * highlightStrength).dp,
                                alpha = lerp(
                                    0.22f * highlightStrength * borderStrength,
                                    0.95f * highlightStrength,
                                    progress
                                ).coerceIn(0f, 1f)
                            )
                        }
                    },
                    shadow = if (depth <= 0f) {
                        null
                    } else {
                        {
                            val progress = dampedDragAnimation.pressProgress
                            Shadow(
                                radius = androidx.compose.ui.unit.lerp(8.dp, 20.dp, progress) *
                                    (0.5f + 0.5f * depth),
                                alpha = lerp(0.10f * depth, 0.55f * (0.4f + 0.6f * depth), progress)
                            )
                        }
                    },
                    innerShadow = if (depth <= 0f) {
                        null
                    } else {
                        {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = (8.dp * progress) * (0.5f + 0.5f * depth),
                                alpha = progress * (0.35f + 0.65f * depth)
                            )
                        }
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        // 静止态：浅色下呈现温润高亮的白瓷胶囊（如图二），深色下呈现柔和微亮胶囊；
                        // 按压态：表面退让为透明水滴，让折射与色散完全显现。
                        val restPillColor = if (isLightTheme) {
                            Color.White.copy(alpha = 0.52f)
                        } else {
                            Color.White.copy(alpha = 0.11f)
                        }
                        drawRect(restPillColor, alpha = 1f - progress)
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(pillHeightDp)
                .width(pillWidthDp)
        )
    }
}
