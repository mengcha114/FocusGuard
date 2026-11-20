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
import com.kyant.backdrop.effects.colorControls
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
 * - 按住选中玻璃：整条玻璃轻微放大（pressedScale），选中玻璃跟手；
 * - 拖动选中玻璃：跟着手指走，按拖动速度做横向拉伸 / 纵向压扁的弹性形变；
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
    // 以下参数直接对应设置页的「高光强度 / 立体感 / 背景饱和度 / 背景亮度 / 折射景深 / 边缘色散」，
    // 底栏必须和卡片用同一套，否则调了参数底栏没反应。
    highlightStrength: Float = 0.6f,
    depth: Float = 0.35f,
    saturation: Float = 1.1f,
    brightness: Float = 0f,
    depthEffect: Boolean = true,
    dispersion: Boolean = true,
    borderVisible: Boolean = true,
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
                // 按压时只轻微放大，避免选中玻璃鼓出底栏、盖住相邻图标
                pressedScale = 1.12f,
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

        // 注意：手势必须挂在「选中玻璃」上（官方做法）。如果挂到整条底栏的父级，
        // 子级 tab 的 clickable 会在 Main 阶段先消费掉 down 事件，
        // 父级的 awaitFirstDown(requireUnconsumed = true) 永远等不到按下，
        // 结果是按压缩放和拖动都失效。
        // 第 1 层：整条玻璃条 + 真实内容（按住放大时 layerBlock 只放大内容）
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
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
                    // 关键：不传 highlight 时库会默认画一圈 1dp、alpha=1 的白边，
                    // 这就是「描边强度调到 0 仍有一条粗白边」的来源。改为跟随设置，
                    // 描边强度为 0 时彻底不画高光边。
                    highlight = if (!borderVisible || highlightStrength <= 0f) {
                        null
                    } else {
                        {
                            Highlight(
                                width = (0.3f + 0.8f * highlightStrength).dp,
                                alpha = (0.15f + 0.6f * highlightStrength).coerceAtMost(1f)
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
                    // 这里只录「染色后的图标/文字」，绝不能再画一遍玻璃表面：
                    // 选中玻璃采样的是这一层，如果这层里有一整条带底色的玻璃，
                    // 透镜放大后就会变成一大块白色圆角块（用户截图里的“巨大白圆”）。
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // 第 3 层：可拖动的选中玻璃，弹性形变全部来自 DampedDragAnimation
        // 玻璃块宽度 = tabWidth * 1.12；graphicsLayer 里的 size 是玻璃块自己的宽度，
        // 不能拿来做 RTL 的整条宽度计算，所以单独记一个内容宽度。
        val contentWidth = with(density) { constraints.maxWidth.toFloat() - 8.dp.toPx() }
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    // 玻璃块比 tab 略宽 12%，把放大后的图标/文字完整包住；
                    // 同时补偿多出来的宽度，保证中心仍然对准 tab 中心。
                    val overshoot = tabWidth * 0.12f
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth - overshoot / 2f + panelOffset
                        else contentWidth - (dampedDragAnimation.value + 1f) * tabWidth +
                            overshoot / 2f + panelOffset
                }
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { barShape },
                    effects = {
                        val press = dampedDragAnimation.pressProgress
                        vibrancy()
                        // 这里不加 blur：采样源里的图标是「放大后的选中图标」，
                        // 模糊会把图标边缘抹掉，色散也就看不出来了。
                        // 背景层本身只有渐变/图片，没有文字，不存在映射到正文的问题。
                        if (saturation != 1f || brightness != 0f) {
                            colorControls(brightness = brightness, saturation = saturation)
                        }
                        // 常驻折射 + 色散：静止时就是一块液态透镜，按压时再增强。
                        // 折射高度接近玻璃块自身高度，内部才会整体弯折，而不是只有一圈边。
                        // 注意要同时判断 refractionAmount：设置里把「折射强度」拉到 0 时
                        // 上面的 h 仍会算出 16dp，不加这个判断透镜关不掉。
                        val h = (16.dp + refractionHeight * 0.45f)
                            .coerceAtMost(38.dp) * (0.88f + 0.12f * press)
                        val a = (28.dp + refractionAmount * 0.55f)
                            .coerceAtMost(96.dp) * (0.88f + 0.12f * press)
                        if (refractionHeight > 0.dp && refractionAmount > 0.dp) {
                            lens(
                                h.toPx(),
                                a.toPx(),
                                depthEffect = depthEffect,
                                chromaticAberration = dispersion
                            )
                        }
                    },
                    highlight = if (!borderVisible || highlightStrength <= 0f) {
                        null
                    } else {
                        {
                            // 玻璃块边缘比卡片略亮一点，但不能再用 0.8~1.0 的纯白，
                            // 那是之前「白圆」观感的另一半来源。
                            Highlight(
                                width = (0.6f + 0.9f * highlightStrength).dp,
                                alpha = (0.30f + 0.5f * highlightStrength) *
                                    (0.9f + 0.1f * dampedDragAnimation.pressProgress)
                            )
                        }
                    },
                    shadow = if (depth <= 0f) {
                        null
                    } else {
                        {
                            Shadow(
                                radius = (6f + 10f * depth).dp,
                                alpha = (0.15f + 0.55f * depth) *
                                    (0.6f + 0.4f * dampedDragAnimation.pressProgress)
                            )
                        }
                    },
                    innerShadow = if (depth <= 0f) {
                        null
                    } else {
                        {
                            InnerShadow(
                                radius = (4f + 6f * depth).dp,
                                alpha = (0.20f + 0.45f * depth) *
                                    (0.7f + 0.3f * dampedDragAnimation.pressProgress)
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
