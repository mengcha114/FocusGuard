package com.focusguard.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.abs
import kotlin.math.roundToInt

/** 底栏条目。 */
data class LiquidTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * 可拖动的液态玻璃底栏。
 *
 * 交互遵循官方 Interactive Glass Bottom Bar 的思路：
 * - 整条底栏是一块玻璃胶囊，采样页面背景图层；
 * - 上面浮着一块玻璃指示器，可以横向拖动；
 * - 拖动时指示器跟手，松手吸附到最近的标签并切换对应功能；
 * - 点按等价于选中对应标签。
 *
 * 液态观感的关键是「低模糊 + 强边缘折射」：模糊只有 5dp，
 * 折射 16/36dp 并带色散，避免变成普通的毛玻璃。
 */
@Composable
fun LiquidBottomBar(
    backdrop: Backdrop,
    tabs: List<LiquidTab>,
    selectedRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    val density = LocalDensity.current
    val barShape = RoundedCornerShape(percent = 50)
    val itemShape = RoundedCornerShape(percent = 50)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)

    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var indicatorX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val itemWidthPx = if (trackWidthPx > 0f) trackWidthPx / tabs.size else 0f

    val selectedIndex = tabs.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)

    // 外部切换页面时，指示器平滑滑到对应位置；拖动中不插手
    LaunchedEffect(selectedIndex, trackWidthPx, dragging) {
        if (dragging || trackWidthPx <= 0f) return@LaunchedEffect
        animate(
            initialValue = indicatorX,
            targetValue = selectedIndex * itemWidthPx,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
        ) { value, _ -> indicatorX = value }
    }

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(66.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { barShape },
                effects = {
                    vibrancy()
                    // 模糊刻意压小：大半径只会变成毛玻璃，玻璃感来自边缘折射
                    blur(5f.dp.toPx())
                    lens(16f.dp.toPx(), 36f.dp.toPx(), chromaticAberration = true)
                },
                onDrawSurface = {
                    drawRect(surfaceColor.copy(alpha = 0.22f))
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.14f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.03f)
                            )
                        )
                    )
                }
            )
            .pointerInput(tabs.size, trackWidthPx) {
                if (trackWidthPx <= 0f) return@pointerInput
                val itemWidth = trackWidthPx / tabs.size
                val slop = with(density) { 4.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    val startX = down.position.x
                    var latestX = startX
                    indicatorX = (startX - itemWidth / 2f)
                        .coerceIn(0f, (trackWidthPx - itemWidth).coerceAtLeast(0f))
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        latestX = change.position.x
                        if (abs(latestX - startX) > slop) moved = true
                        indicatorX = (latestX - itemWidth / 2f)
                            .coerceIn(0f, (trackWidthPx - itemWidth).coerceAtLeast(0f))
                        change.consume()
                    }
                    dragging = false
                    // 点按取按下的位置，拖动取松手位置，都换算成最近的标签
                    val target = if (moved) latestX else startX
                    val index = (target / itemWidth).toInt().coerceIn(0, tabs.lastIndex)
                    onSelect(tabs[index].route)
                }
            }
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
    ) {
        // 玻璃指示器：跟随手指，松手后由 LaunchedEffect 吸附
        if (itemWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(indicatorX.roundToInt(), 0) }
                    .width(with(density) { itemWidthPx.toDp() })
                    .fillMaxHeight()
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { itemShape },
                        effects = {
                            vibrancy()
                            blur(4f.dp.toPx())
                            lens(
                                refractionHeight = 18f.dp.toPx(),
                                refractionAmount = 42f.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = true
                            )
                        },
                        layerBlock = {
                            val scale = if (dragging) 1.02f else 1f
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = {
                            drawRect(primaryColor.copy(alpha = 0.22f))
                            drawRect(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.28f),
                                        Color.Transparent
                                    )
                                )
                            )
                        }
                    )
            )
        }

        // 图标与文字（不拦截手势，交给上层统一处理点按/拖动）
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = if (tab.route == selectedRoute) primaryColor else inactiveColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        tab.label,
                        fontSize = 10.sp,
                        color = if (tab.route == selectedRoute) primaryColor else inactiveColor
                    )
                }
            }
        }
    }
}
