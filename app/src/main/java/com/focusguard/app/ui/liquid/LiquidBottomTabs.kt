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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.TransformOrigin
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
                // 以「图标中心」为缩放原点：放大时图标留在原位，
                // 文字只会被推下去一点，不会挤进上方那颗玻璃里。
                transformOrigin = TransformOrigin(0.5f, 0.30f)
            },
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
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
                                // 库内部会把描边宽度 ×2，所以这里给细一点，
                                // 避免整条底栏出现一圈很粗的白边。
                                width = (0.2f + 0.4f * highlightStrength).dp,
                                alpha = (0.12f + 0.4f * highlightStrength).coerceAtMost(1f)
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
                lerp(1f, 1.08f, dampedDragAnimation.pressProgress)
            },
            // 选中格常驻放大：玻璃里看到的图标比外面大一圈。
            // 跟随拖动位置取最近的 tab，玻璃滑到哪一格，哪一格就被放大。
            LocalLiquidBottomTabSelectedScale provides { index ->
                val active = dampedDragAnimation.value.fastRoundToInt()
                    .fastCoerceIn(0, tabs.lastIndex)
                if (index == active) 1.18f else 1f
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

        // 第 3 层：可拖动的选中玻璃。
        // 做成「图标大小」的圆形透镜（而不是盖住整个格子的胶囊）：
        // 透镜半径和图标差不多大，图标的边缘正好落在折射区里，
        // RuntimeShader 才会在图标轮廓上产生真实的弯折和彩色色散；
        // 同时不会盖住下面的文字标签，也就不再出现「放大后重影的文字」。
        val contentWidth = with(density) { constraints.maxWidth.toFloat() - 8.dp.toPx() }
        val bubblePx = with(density) { 38.dp.toPx() }
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    // 水平：居中在所在格子；垂直：对齐图标中心（图标中心约在内容区 16dp 处），
                    // 让透镜正好罩住图标、不压到文字。
                    val gap = (tabWidth - bubblePx) / 2f
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + gap + panelOffset
                        else contentWidth - dampedDragAnimation.value * tabWidth - gap -
                            bubblePx + panelOffset
                    translationY = -11.8.dp.toPx()
                }
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { barShape },
                    effects = {
                        val press = dampedDragAnimation.pressProgress
                        vibrancy()
                        if (saturation != 1f || brightness != 0f) {
                            colorControls(brightness = brightness, saturation = saturation)
                        }
                        // 折射区覆盖整个透镜（半径 19dp）：图标不再落在「不变形」的
                        // 中心区，边缘会被弯折并分离出彩色，才是参考图里的色散效果。
                        // 这里也不加 blur，模糊会把色散边缘直接抹掉。
                        val h = (14.dp + refractionHeight * 0.40f)
                            .coerceAtMost(36.dp) * (0.90f + 0.10f * press)
                        val a = (6.dp + refractionAmount * 0.20f)
                            .coerceAtMost(28.dp) * (0.90f + 0.10f * press)
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
                            // 静止只有一圈很淡的棱边，按压时才亮起来（不再有白圈）
                            val press = dampedDragAnimation.pressProgress
                            Highlight(
                                width = (0.2f + 0.35f * highlightStrength).dp,
                                alpha = (0.08f * highlightStrength + 0.45f * press *
                                    highlightStrength).coerceIn(0f, 0.6f)
                            )
                        }
                    },
                    shadow = if (depth <= 0f) {
                        null
                    } else {
                        {
                            Shadow(
                                radius = (4f + 8f * depth).dp,
                                alpha = (0.10f + 0.30f * depth) *
                                    (0.6f + 0.4f * dampedDragAnimation.pressProgress)
                            )
                        }
                    },
                    innerShadow = if (depth <= 0f) {
                        null
                    } else {
                        {
                            InnerShadow(
                                radius = (3f + 4f * depth).dp,
                                alpha = (0.08f + 0.22f * depth) *
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
                        // 只补一点点玻璃亮度，避免把里面的图标染白
                        drawRect(
                            Color.White.copy(alpha = if (isLightTheme) 0.05f else 0.06f)
                        )
                    }
                )
                .size(38.dp)
        )
    }
}
