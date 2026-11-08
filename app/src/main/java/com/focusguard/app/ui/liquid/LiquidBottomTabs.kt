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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
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

/** 选中格的常驻放大倍数（只作用于图标镜像层，让玻璃里出现放大的选中图标）。 */
internal val LocalLiquidBottomTabSelectedScale =
    staticCompositionLocalOf { { _: Int -> 1f } }

@Composable
fun RowScope.LiquidBottomTab(
    index: Int = -1,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    val selectedScale = LocalLiquidBottomTabSelectedScale.current
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
                val s = scale() * selectedScale(index)
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/**
 * 官方 LiquidBottomTabs 的完整移植（AndroidLiquidGlass catalog，Apache-2.0）。
 *
 * 交互：
 * - 按住：整条玻璃按 pressedScale（78/56 ≈ 1.39）放大，按压处出现跟手高光；
 * - 拖动：选中玻璃跟着手指走，按拖动速度做横向拉伸 / 纵向压扁的弹性形变；
 * - 松手：弹簧吸附到最近的标签并切换页面；
 * - 点击：同样支持，且不经过拖动阈值，响应即时。
 *
 * 之所以不是简单的高斯模糊：玻璃背景来自页面背景图层，边缘用
 * RuntimeShader 做真实折射（lens），并带色散，这才有液态感。
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
    blurRadius: Dp = 8.dp,
    surfaceAlpha: Float = 0.40f,
    content: @Composable RowScope.() -> Unit
) {
    if (tabs.isEmpty()) return
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val accentColor = MaterialTheme.colorScheme.primary
    val containerColor = if (isLightTheme) {
        MaterialTheme.colorScheme.surface.copy(alpha = surfaceAlpha)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = (surfaceAlpha - 0.06f).coerceAtLeast(0.2f))
    }
    val barShape = RoundedCornerShape(percent = 50)

    // 官方三层结构中的「图标层」：镜像一份内容、完全透明，仅通过 layerBackdrop
    // 把图标/文字录进 backdrop，供选中玻璃采样。少了这层，选中玻璃只能采到背景，
    // 会把它覆盖的那一格图标整块盖住（设置页图标消失、右侧出现空白圆）。
    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8.dp.toPx()) / tabs.size
        }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth)
                    .fastCoerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedIndex) }

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabs.size - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabs.lastIndex)
                    if (target != currentIndex) {
                        currentIndex = target
                        animateToValue(target.toFloat())
                        onTabSelected(target)
                    } else {
                        animateToValue(target.toFloat())
                    }
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, tabs.lastIndex.toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        // 外部（点击/导航返回）切换时同步指示器
        LaunchedEffect(selectedIndex) {
            if (selectedIndex != currentIndex) {
                currentIndex = selectedIndex
                dampedDragAnimation.animateToValue(selectedIndex.toFloat())
            }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // 第 1 层：整条玻璃条 + 真实内容（按住放大时 layerBlock 只放大内容）
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        vibrancy()
                        blur(blurRadius.toPx())
                        lens(refractionHeight.toPx(), refractionAmount.toPx())
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

        // 第 2 层：镜像内容，alpha = 0 看不见，只把图标录进 tabsBackdrop。
        // 选中玻璃采样「背景 + 这层图标」，玻璃里才会出现放大的选中图标。
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            },
            // 选中格常驻放大：玻璃里看到的图标/文字比外面大一圈。
            // 跟随拖动位置取最近的 tab，玻璃滑到哪一格，哪一格就被放大。
            LocalLiquidBottomTabSelectedScale provides { index ->
                val active = dampedDragAnimation.value.fastRoundToInt()
                    .fastCoerceIn(0, tabs.lastIndex)
                if (index == active) 1.34f else 1f
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { barShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(blurRadius.toPx())
                            lens(
                                refractionHeight.toPx() * progress,
                                refractionAmount.toPx() * progress
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                        },
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

        // 第 3 层：可拖动的选中玻璃，弹性形变全部来自 DampedDragAnimation
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    // 玻璃块比 tab 略宽 12%，把放大后的图标/文字完整包住；
                    // 同时补偿多出来的宽度，保证中心仍然对准 tab 中心。
                    val overshoot = tabWidth * 0.12f
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth - overshoot / 2f + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth +
                            overshoot / 2f + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { barShape },
                    effects = {
                        val press = dampedDragAnimation.pressProgress
                        // 常驻折射 + 色散：静止时就是一块液态透镜，按压时再增强。
                        // 折射高度接近玻璃块自身高度，内部才会整体弯折，而不是只有一圈边。
                        val h = (16.dp + refractionHeight * 0.45f)
                            .coerceAtMost(38.dp) * (0.88f + 0.12f * press)
                        val a = (28.dp + refractionAmount * 0.55f)
                            .coerceAtMost(96.dp) * (0.88f + 0.12f * press)
                        lens(
                            h.toPx(),
                            a.toPx(),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        // 更粗更亮的边缘高光，做出玻璃凸起的感觉
                        Highlight(
                            width = 1.6.dp,
                            alpha = 0.80f + 0.20f * dampedDragAnimation.pressProgress
                        )
                    },
                    shadow = {
                        Shadow(alpha = 0.28f + 0.72f * dampedDragAnimation.pressProgress)
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 6.dp + 4.dp * dampedDragAnimation.pressProgress,
                            alpha = 0.35f + 0.65f * dampedDragAnimation.pressProgress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        // 常驻只加一点点白，让玻璃更透亮；按压时再压暗一点
                        val press = dampedDragAnimation.pressProgress
                        drawRect(
                            Color.White.copy(alpha = if (isLightTheme) 0.05f else 0.07f)
                        )
                        drawRect(Color.Black.copy(alpha = 0.02f * press))
                    }
                )
                .height(56.dp)
                .fillMaxWidth(1.12f / tabs.size)
        )
    }
}
