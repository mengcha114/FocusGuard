package com.focusguard.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 草稿纸工具类型。 */
enum class ScratchTool {
    PEN,
    ERASER,
    LINE,
    RECT,
    OVAL,
    ARROW
}

/** 纸面样式：半透明透视（边看题边算）、深色网格、浅色网格。 */
enum class ScratchPaperStyle(val label: String) {
    TRANSLUCENT("透视"),
    DARK_GRID("暗格"),
    LIGHT_GRID("白格")
}

/** 单条笔迹或几何图形，支持撤销/重做与精准对象擦除。 */
sealed class ScratchItem {
    abstract val colorArgb: Long
    abstract val strokeWidthPx: Float

    class Freehand(
        val points: List<Offset>,
        override val colorArgb: Long,
        override val strokeWidthPx: Float
    ) : ScratchItem() {
        val cachedPath: Path = buildSmoothPath(points)
    }

    class Shape(
        val kind: ScratchTool,
        val start: Offset,
        val end: Offset,
        override val colorArgb: Long,
        override val strokeWidthPx: Float
    ) : ScratchItem()
}

/** 单页草稿状态（每页独立 Undo/Redo 栈）。 */
class ScratchPageState {
    val items = mutableStateListOf<ScratchItem>()
    private val undoStack = ArrayDeque<List<ScratchItem>>()
    private val redoStack = ArrayDeque<List<ScratchItem>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun snapshot(): List<ScratchItem> = items.toList()

    fun commitAdd(item: ScratchItem) {
        pushUndo()
        items.add(item)
    }

    fun commitReplace(newItems: List<ScratchItem>) {
        if (newItems.size == items.size) return
        pushUndo()
        items.clear()
        items.addAll(newItems)
    }

    fun clear() {
        if (items.isEmpty()) return
        pushUndo()
        items.clear()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        items.clear()
        items.addAll(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        items.clear()
        items.addAll(next)
    }

    private fun pushUndo() {
        undoStack.addLast(snapshot())
        if (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
    }
}

/** 多页草稿本状态，关闭草稿纸再打开时内容不丢失。 */
class ScratchpadState {
    val pages = mutableStateListOf(ScratchPageState())
    var currentPageIndex by mutableIntStateOf(0)
    var tool by mutableStateOf(ScratchTool.PEN)
    var paperStyle by mutableStateOf(ScratchPaperStyle.TRANSLUCENT)
    var colorIndex by mutableIntStateOf(0)
    var widthIndex by mutableIntStateOf(1)
    var pageToast by mutableStateOf<String?>(null)
    var pageToastTick by mutableIntStateOf(0)

    val currentPage: ScratchPageState
        get() = pages[currentPageIndex.coerceIn(0, pages.lastIndex)]

    fun goPrevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--
            notifyPage("↑ 第 ${currentPageIndex + 1} / ${pages.size} 页")
        } else {
            notifyPage("已是第 1 页")
        }
    }

    fun goNextPage(autoCreate: Boolean = true) {
        if (currentPageIndex < pages.lastIndex) {
            currentPageIndex++
            notifyPage("↓ 第 ${currentPageIndex + 1} / ${pages.size} 页")
        } else if (autoCreate && pages.size < 20) {
            pages.add(ScratchPageState())
            currentPageIndex = pages.lastIndex
            notifyPage("已新建第 ${currentPageIndex + 1} 页")
        } else {
            notifyPage("已是最后一页")
        }
    }

    fun addNewPage() {
        if (pages.size >= 20) {
            notifyPage("最多支持 20 页")
            return
        }
        pages.add(ScratchPageState())
        currentPageIndex = pages.lastIndex
        notifyPage("已新建第 ${currentPageIndex + 1} 页")
    }

    fun deleteCurrentPage() {
        if (pages.size <= 1) {
            currentPage.clear()
            notifyPage("已清空第 1 页")
            return
        }
        pages.removeAt(currentPageIndex)
        currentPageIndex = currentPageIndex.coerceAtMost(pages.lastIndex)
        notifyPage("第 ${currentPageIndex + 1} / ${pages.size} 页")
    }

    private fun notifyPage(msg: String) {
        pageToast = msg
        pageToastTick++
    }
}

@Composable
fun rememberScratchpadState(): ScratchpadState = remember { ScratchpadState() }

/**
 * 答题页全屏草稿纸浮层。
 *
 * 特性：
 * - 流畅书写：原始指针事件 + 历史点采样 + 二次贝塞尔平滑曲线，零锯齿；
 * - 画图工具：自由画笔、直线、矩形、圆形、箭头，拖拽实时预览；
 * - 精准橡皮擦：对象碰撞擦除 + 光标预览，支持撤销，无半透明黑块问题；
 * - 多页 + 上下划手势换页：双指上下划换页（不与单指书写冲突），右侧边缘条也支持单指上下划换页；
 * - 三种纸面（半透明透视 / 暗色网格 / 白色网格）+ 顶部可折叠原题条，演算出结果即可关掉答题。
 */
@Composable
fun ScratchpadOverlay(
    visible: Boolean,
    state: ScratchpadState,
    questionText: String? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val isLightPaper = state.paperStyle == ScratchPaperStyle.LIGHT_GRID
        val inkColors = remember(isLightPaper) {
            if (isLightPaper) {
                listOf(
                    Color(0xFF1A1F2C), // 墨黑
                    Color(0xFF1D4ED8), // 蓝
                    Color(0xFFDC2626), // 红
                    Color(0xFF059669), // 绿
                    Color(0xFFD97706)  // 橙
                )
            } else {
                listOf(
                    Color(0xFFF8FAFC), // 亮白
                    Color(0xFF38BDF8), // 天蓝
                    Color(0xFFFB7185), // 玫红
                    Color(0xFF34D399), // 翠绿
                    Color(0xFFFBBF24)  // 琥珀黄
                )
            }
        }
        val strokeWidthsDp = remember { listOf(2.2f, 3.8f, 6.2f) }
        val activeColor = inkColors[state.colorIndex.coerceIn(0, inkColors.lastIndex)]
        val activeStrokePx = with(density) {
            strokeWidthsDp[state.widthIndex.coerceIn(0, strokeWidthsDp.lastIndex)].dp.toPx()
        }
        val eraserRadiusPx = with(density) { 18.dp.toPx() }
        val swipeThresholdPx = with(density) { 56.dp.toPx() }
        val minStepPx = with(density) { 2.2.dp.toPx() }

        // 正在绘制中的临时轨迹（不触发大列表拷贝）
        var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
        var shapeStart by remember { mutableStateOf<Offset?>(null) }
        var shapeEnd by remember { mutableStateOf<Offset?>(null) }
        var eraserCursor by remember { mutableStateOf<Offset?>(null) }
        var showQuestionBanner by remember { mutableStateOf(false) }

        // 换页提示自动消失
        LaunchedEffect(state.pageToastTick) {
            if (state.pageToast != null) {
                delay(1200L)
                state.pageToast = null
            }
        }

        val bgColor = when (state.paperStyle) {
            ScratchPaperStyle.TRANSLUCENT -> Color(0xBD0D131D)
            ScratchPaperStyle.DARK_GRID -> Color(0xF711161F)
            ScratchPaperStyle.LIGHT_GRID -> Color(0xFAFAFBF8)
        }
        val gridColor = when (state.paperStyle) {
            ScratchPaperStyle.TRANSLUCENT -> Color.White.copy(alpha = 0.08f)
            ScratchPaperStyle.DARK_GRID -> Color.White.copy(alpha = 0.09f)
            ScratchPaperStyle.LIGHT_GRID -> Color(0xFF334155).copy(alpha = 0.11f)
        }
        val panelBg = if (isLightPaper) Color(0xF2FFFFFF) else Color(0xEE18202C)
        val panelBorder = if (isLightPaper) Color(0x260F172A) else Color(0x33FFFFFF)
        val panelFg = if (isLightPaper) Color(0xFF0F172A) else Color(0xFFF1F5F9)
        val panelMuted = if (isLightPaper) Color(0xFF64748B) else Color(0xFF94A3B8)
        val accent = Color(0xFF38BDF8)

        val currentPage = state.currentPage

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
        ) {
            // ── 全屏画布（网格 + 历史笔迹 + 实时笔迹 + 双指上下划换页手势） ──
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(
                        state.currentPageIndex,
                        state.tool,
                        activeColor,
                        activeStrokePx
                    ) {
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            firstDown.consume()
                            val downPos = firstDown.position

                            var multiTouch = false
                            var swipeTriggered = false
                            var twoFingerStartY = 0f
                            val livePts = ArrayList<Offset>(64)
                            var erasedIds = HashSet<ScratchItem>()

                            fun resetDraft() {
                                activePoints = emptyList()
                                shapeStart = null
                                shapeEnd = null
                                eraserCursor = null
                            }

                            when (state.tool) {
                                ScratchTool.PEN -> {
                                    livePts.add(downPos)
                                    activePoints = livePts.toList()
                                }
                                ScratchTool.ERASER -> {
                                    eraserCursor = downPos
                                    collectErased(currentPage.items, downPos, eraserRadiusPx, erasedIds)
                                    if (erasedIds.isNotEmpty()) {
                                        currentPage.commitReplace(
                                            currentPage.items.filterNot { it in erasedIds }
                                        )
                                        erasedIds.clear()
                                    }
                                }
                                else -> {
                                    shapeStart = downPos
                                    shapeEnd = downPos
                                }
                            }

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pressed = event.changes.filter { it.pressed }

                                if (pressed.isEmpty()) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }

                                // 双指按下 → 进入上下划换页手势，同时取消误触的起笔
                                if (pressed.size >= 2) {
                                    if (!multiTouch) {
                                        multiTouch = true
                                        resetDraft()
                                        twoFingerStartY = pressed.map { it.position.y }.average().toFloat()
                                    }
                                    val curY = pressed.map { it.position.y }.average().toFloat()
                                    val dy = curY - twoFingerStartY
                                    if (!swipeTriggered && abs(dy) >= swipeThresholdPx) {
                                        swipeTriggered = true
                                        if (dy < 0f) {
                                            // 上划 → 下一页
                                            state.goNextPage(autoCreate = true)
                                        } else {
                                            // 下划 → 上一页
                                            state.goPrevPage()
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                    continue
                                }

                                if (multiTouch) {
                                    event.changes.forEach { it.consume() }
                                    continue
                                }

                                val change = pressed.first()
                                change.consume()

                                when (state.tool) {
                                    ScratchTool.PEN -> {
                                        // 采集高刷历史点，快速书写无折角
                                        var added = false
                                        for (hist in change.historical) {
                                            val p = hist.position
                                            val last = livePts.lastOrNull()
                                            if (last == null || hypot(p.x - last.x, p.y - last.y) >= minStepPx) {
                                                livePts.add(p)
                                                added = true
                                            }
                                        }
                                        val cur = change.position
                                        val last = livePts.lastOrNull()
                                        if (last == null || hypot(cur.x - last.x, cur.y - last.y) >= minStepPx) {
                                            livePts.add(cur)
                                            added = true
                                        }
                                        if (added) {
                                            activePoints = livePts.toList()
                                        }
                                    }
                                    ScratchTool.ERASER -> {
                                        val cur = change.position
                                        eraserCursor = cur
                                        collectErased(currentPage.items, cur, eraserRadiusPx, erasedIds)
                                        if (erasedIds.isNotEmpty()) {
                                            currentPage.commitReplace(
                                                currentPage.items.filterNot { it in erasedIds }
                                            )
                                            erasedIds.clear()
                                        }
                                    }
                                    else -> {
                                        shapeEnd = change.position
                                    }
                                }
                            }

                            if (!multiTouch) {
                                when (state.tool) {
                                    ScratchTool.PEN -> {
                                        if (livePts.isNotEmpty()) {
                                            currentPage.commitAdd(
                                                ScratchItem.Freehand(
                                                    points = livePts.toList(),
                                                    colorArgb = activeColor.value.toLong(),
                                                    strokeWidthPx = activeStrokePx
                                                )
                                            )
                                        }
                                    }
                                    ScratchTool.ERASER -> Unit
                                    else -> {
                                        val s = shapeStart
                                        val e = shapeEnd
                                        if (s != null && e != null && hypot(e.x - s.x, e.y - s.y) >= 4f) {
                                            currentPage.commitAdd(
                                                ScratchItem.Shape(
                                                    kind = state.tool,
                                                    start = s,
                                                    end = e,
                                                    colorArgb = activeColor.value.toLong(),
                                                    strokeWidthPx = activeStrokePx
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            resetDraft()
                        }
                    }
            ) {
                // 网格底纹
                val step = 28.dp.toPx()
                var x = step
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }

                // 已提交笔迹与图形
                for (item in currentPage.items) {
                    drawScratchItem(item)
                }

                // 当前书写中的笔迹
                if (activePoints.isNotEmpty()) {
                    drawLiveFreehand(activePoints, activeColor, activeStrokePx)
                }

                // 当前拖拽中的几何图形预览
                val s = shapeStart
                val e = shapeEnd
                if (s != null && e != null && state.tool != ScratchTool.PEN && state.tool != ScratchTool.ERASER) {
                    drawGeometry(state.tool, s, e, activeColor.copy(alpha = 0.85f), activeStrokePx)
                }

                // 橡皮擦圆形光标
                eraserCursor?.let { pos ->
                    drawCircle(
                        color = accent.copy(alpha = 0.16f),
                        radius = eraserRadiusPx,
                        center = pos
                    )
                    drawCircle(
                        color = accent.copy(alpha = 0.75f),
                        radius = eraserRadiusPx,
                        center = pos,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // ── 右侧边缘单指上下划换页手势条 ──
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .width(26.dp)
                    .height(148.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(panelBg.copy(alpha = 0.72f))
                    .border(1.dp, panelBorder, RoundedCornerShape(13.dp))
                    .pointerInput(state.currentPageIndex, state.pages.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val startY = down.position.y
                            var fired = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                ch.consume()
                                if (!ch.pressed) break
                                val dy = ch.position.y - startY
                                if (!fired && abs(dy) >= swipeThresholdPx * 0.7f) {
                                    fired = true
                                    if (dy < 0f) state.goNextPage(autoCreate = true)
                                    else state.goPrevPage()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "上一页",
                        tint = panelMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${state.currentPageIndex + 1}/${state.pages.size}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = panelFg
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "下一页",
                        tint = panelMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // ── 顶部栏：页码/换页、看题开关、纸面切换、关闭 ──
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = panelBg,
                    border = BorderStroke(1.dp, panelBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 页码控制
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { state.goPrevPage() },
                                enabled = state.currentPageIndex > 0,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.ChevronLeft,
                                    contentDescription = "上一页",
                                    tint = if (state.currentPageIndex > 0) panelFg else panelMuted.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = "第 ${state.currentPageIndex + 1}/${state.pages.size} 页",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = panelFg
                            )
                            IconButton(
                                onClick = { state.goNextPage(autoCreate = true) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "下一页",
                                    tint = panelFg,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = { state.addNewPage() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.NoteAdd,
                                    contentDescription = "新建页",
                                    tint = accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // 右侧：题干对照 + 纸面切换 + 收起
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (!questionText.isNullOrBlank()) {
                                TextButton(
                                    onClick = { showQuestionBanner = !showQuestionBanner },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text(
                                        if (showQuestionBanner) "藏题" else "看题",
                                        fontSize = 12.sp,
                                        color = accent
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = accent.copy(alpha = 0.14f),
                                modifier = Modifier.clickable {
                                    val all = ScratchPaperStyle.entries
                                    state.paperStyle = all[(state.paperStyle.ordinal + 1) % all.size]
                                }
                            ) {
                                Text(
                                    text = state.paperStyle.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = accent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "关闭草稿纸",
                                    tint = panelFg,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // 可折叠题目对照条
                if (showQuestionBanner && !questionText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = panelBg,
                        border = BorderStroke(1.dp, panelBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = questionText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = panelFg,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ── 中部轻提示（翻页反馈） ──
            AnimatedVisibility(
                visible = state.pageToast != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xCC0F172A),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                ) {
                    Text(
                        text = state.pageToast ?: "",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // ── 底部浮动工具栏：画笔/橡皮/几何图形 + 颜色/粗细 + 撤销/重做/清空 ──
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = panelBg,
                border = BorderStroke(1.dp, panelBorder),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 第一行：工具选择（画笔、橡皮、直线、矩形、圆、箭头）+ 撤销/重做/清空
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToolChip(Icons.Default.Edit, "画笔", state.tool == ScratchTool.PEN, accent, panelFg, panelMuted) {
                            state.tool = ScratchTool.PEN
                        }
                        ToolChip(Icons.Default.AutoFixNormal, "橡皮", state.tool == ScratchTool.ERASER, accent, panelFg, panelMuted) {
                            state.tool = ScratchTool.ERASER
                        }
                        ToolChip(Icons.Default.HorizontalRule, "直线", state.tool == ScratchTool.LINE, accent, panelFg, panelMuted) {
                            state.tool = ScratchTool.LINE
                        }
                        ToolChip(Icons.Default.CropSquare, "矩形", state.tool == ScratchTool.RECT, accent, panelFg, panelMuted) {
                            state.tool = ScratchTool.RECT
                        }
                        ToolChip(Icons.Default.RadioButtonUnchecked, "圆形", state.tool == ScratchTool.OVAL, accent, panelFg, panelMuted) {
                            state.tool = ScratchTool.OVAL
                        }
                        ToolChip(Icons.Default.NorthEast, "箭头", state.tool == ScratchTool.ARROW, accent, panelFg, panelMuted) {
                            state.tool = ScratchTool.ARROW
                        }
                    }

                    // 第二行：颜色 + 粗细 + 撤销 / 重做 / 清页 / 删页
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            inkColors.forEachIndexed { idx, color ->
                                val selected = state.colorIndex == idx
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (selected) 2.2.dp else 1.dp,
                                            color = if (selected) accent else panelBorder,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            state.colorIndex = idx
                                            if (state.tool == ScratchTool.ERASER) state.tool = ScratchTool.PEN
                                        }
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            strokeWidthsDp.forEachIndexed { idx, w ->
                                val selected = state.widthIndex == idx
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
                                        .clickable { state.widthIndex = idx },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size((w + 3f).dp)
                                            .clip(CircleShape)
                                            .background(if (selected) accent else panelMuted)
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { currentPage.undo() },
                                enabled = currentPage.canUndo,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Undo,
                                    contentDescription = "撤销",
                                    tint = if (currentPage.canUndo) panelFg else panelMuted.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { currentPage.redo() },
                                enabled = currentPage.canRedo,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Redo,
                                    contentDescription = "重做",
                                    tint = if (currentPage.canRedo) panelFg else panelMuted.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { currentPage.clear() },
                                enabled = currentPage.items.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "清空本页",
                                    tint = if (currentPage.items.isNotEmpty()) Color(0xFFFB7185) else panelMuted.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (state.pages.size > 1) {
                                IconButton(
                                    onClick = { state.deleteCurrentPage() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "删除此页",
                                        tint = panelMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "提示：双指上下划或拖动右侧页码条可快速换页/新建页",
                        fontSize = 10.sp,
                        color = panelMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    fg: Color,
    muted: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.65f)) else null,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) accent else muted,
                modifier = Modifier.size(15.dp)
            )
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) accent else fg
            )
        }
    }
}

private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val first = points.first()
    path.moveTo(first.x, first.y)
    if (points.size == 1) {
        path.lineTo(first.x + 0.1f, first.y + 0.1f)
        return path
    }
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        val midX = (prev.x + cur.x) * 0.5f
        val midY = (prev.y + cur.y) * 0.5f
        path.quadraticTo(prev.x, prev.y, midX, midY)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

private fun DrawScope.drawScratchItem(item: ScratchItem) {
    val color = Color(item.colorArgb.toULong())
    when (item) {
        is ScratchItem.Freehand -> {
            if (item.points.size == 1) {
                drawCircle(color = color, radius = item.strokeWidthPx * 0.55f, center = item.points[0])
            } else {
                drawPath(
                    path = item.cachedPath,
                    color = color,
                    style = Stroke(
                        width = item.strokeWidthPx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
        is ScratchItem.Shape -> {
            drawGeometry(item.kind, item.start, item.end, color, item.strokeWidthPx)
        }
    }
}

private fun DrawScope.drawLiveFreehand(points: List<Offset>, color: Color, strokeWidthPx: Float) {
    if (points.size == 1) {
        drawCircle(color = color, radius = strokeWidthPx * 0.55f, center = points[0])
    } else {
        drawPath(
            path = buildSmoothPath(points),
            color = color,
            style = Stroke(
                width = strokeWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

private fun DrawScope.drawGeometry(
    kind: ScratchTool,
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidthPx: Float
) {
    val style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        ScratchTool.LINE -> {
            drawLine(color = color, start = start, end = end, strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
        }
        ScratchTool.RECT -> {
            val left = min(start.x, end.x)
            val top = min(start.y, end.y)
            val w = abs(end.x - start.x)
            val h = abs(end.y - start.y)
            drawRect(color = color, topLeft = Offset(left, top), size = Size(w, h), style = style)
        }
        ScratchTool.OVAL -> {
            val left = min(start.x, end.x)
            val top = min(start.y, end.y)
            val w = abs(end.x - start.x)
            val h = abs(end.y - start.y)
            drawOval(color = color, topLeft = Offset(left, top), size = Size(w, h), style = style)
        }
        ScratchTool.ARROW -> {
            drawLine(color = color, start = start, end = end, strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
            val len = hypot(end.x - start.x, end.y - start.y)
            if (len > 12f) {
                val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                val headLen = min(32f, max(14f, len * 0.22f))
                val spread = Math.toRadians(28.0)
                val p1 = Offset(
                    (end.x - headLen * cos(angle - spread)).toFloat(),
                    (end.y - headLen * sin(angle - spread)).toFloat()
                )
                val p2 = Offset(
                    (end.x - headLen * cos(angle + spread)).toFloat(),
                    (end.y - headLen * sin(angle + spread)).toFloat()
                )
                drawLine(color = color, start = end, end = p1, strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
                drawLine(color = color, start = end, end = p2, strokeWidth = strokeWidthPx, cap = StrokeCap.Round)
            }
        }
        else -> Unit
    }
}

private fun collectErased(
    items: List<ScratchItem>,
    cursor: Offset,
    radius: Float,
    out: MutableSet<ScratchItem>
) {
    for (item in items) {
        if (hitTestItem(item, cursor, radius + item.strokeWidthPx * 0.5f)) {
            out.add(item)
        }
    }
}

private fun hitTestItem(item: ScratchItem, p: Offset, threshold: Float): Boolean {
    return when (item) {
        is ScratchItem.Freehand -> {
            val pts = item.points
            if (pts.isEmpty()) return false
            if (pts.size == 1) return hypot(p.x - pts[0].x, p.y - pts[0].y) <= threshold
            for (i in 1 until pts.size) {
                if (pointToSegmentDistance(p, pts[i - 1], pts[i]) <= threshold) return true
            }
            false
        }
        is ScratchItem.Shape -> {
            when (item.kind) {
                ScratchTool.LINE, ScratchTool.ARROW ->
                    pointToSegmentDistance(p, item.start, item.end) <= threshold
                ScratchTool.RECT -> {
                    val l = min(item.start.x, item.end.x)
                    val r = max(item.start.x, item.end.x)
                    val t = min(item.start.y, item.end.y)
                    val b = max(item.start.y, item.end.y)
                    val tl = Offset(l, t)
                    val tr = Offset(r, t)
                    val br = Offset(r, b)
                    val bl = Offset(l, b)
                    pointToSegmentDistance(p, tl, tr) <= threshold ||
                        pointToSegmentDistance(p, tr, br) <= threshold ||
                        pointToSegmentDistance(p, br, bl) <= threshold ||
                        pointToSegmentDistance(p, bl, tl) <= threshold
                }
                ScratchTool.OVAL -> {
                    val cx = (item.start.x + item.end.x) * 0.5f
                    val cy = (item.start.y + item.end.y) * 0.5f
                    val rx = abs(item.end.x - item.start.x) * 0.5f
                    val ry = abs(item.end.y - item.start.y) * 0.5f
                    if (rx < 2f || ry < 2f) return hypot(p.x - cx, p.y - cy) <= threshold
                    val nx = (p.x - cx) / rx
                    val ny = (p.y - cy) / ry
                    val dist = hypot(nx, ny)
                    val avgR = (rx + ry) * 0.5f
                    abs(dist - 1f) * avgR <= threshold
                }
                else -> false
            }
        }
    }
}

private fun pointToSegmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq <= 0.0001f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
    val projX = a.x + t * dx
    val projY = a.y + t * dy
    return hypot(p.x - projX, p.y - projY)
}
