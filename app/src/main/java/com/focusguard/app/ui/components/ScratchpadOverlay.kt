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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.ui.theme.FocusColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 橡皮图标：直接采用 Material Symbols 官方 `ink_eraser` 的矢量路径。
 *
 * 之前是自己用多边形拼的，观感粗糙、也不够像橡皮；
 * 这里改用官方路径（原 viewBox 为 `0 -960 960 960`，用 group 平移回 0..960 坐标系）。
 */
private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "InkEraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f
    ).apply {
        group(translationY = 960f) {
            addPath(
                pathData = addPathNodes(
                    "M678-220h203v60H618l60-60Zm-499 60-81-84q-18-17-17.5-41.5T97-327l458-498" +
                        "q16-17 40.57-17 24.56 0 41.43 17l205 215q17 17 18 42t-16 42L503-160H179Z" +
                        "m301-60 324-352-207-217-455 503 64 66h274Zm0-260Z"
                ),
                fill = SolidColor(Color.Black)
            )
        }
    }.build()
}

/** 草稿纸工具类型。 */
enum class ScratchTool(val label: String, val icon: ImageVector) {
    PEN("画笔", Icons.Default.Edit),
    ERASER("橡皮", EraserIcon),
    LINE("直线", Icons.Default.HorizontalRule),
    RECT("矩形", Icons.Default.CropSquare),
    OVAL("圆形", Icons.Default.RadioButtonUnchecked),
    ARROW("箭头", Icons.Default.NorthEast)
}

/** 纸面样式：半透（边算边对照）、主题纸（跟随当前主题）、纸白（暖白纸）。 */
enum class ScratchPaperStyle(val label: String) {
    TRANSLUCENT("半透"),
    THEME("主题纸"),
    PAPER("纸白")
}

/** 单条笔迹或几何图形，支持撤销/重做与对象级擦除。 */
sealed class ScratchItem {
    abstract val colorValue: ULong
    abstract val strokeWidthPx: Float

    class Freehand(
        val points: List<Offset>,
        override val colorValue: ULong,
        override val strokeWidthPx: Float
    ) : ScratchItem() {
        val cachedPath: Path = buildSmoothPath(points)
    }

    class Shape(
        val kind: ScratchTool,
        val start: Offset,
        val end: Offset,
        override val colorValue: ULong,
        override val strokeWidthPx: Float
    ) : ScratchItem()
}

/** 单页草稿状态（每页独立撤销/重做栈）。 */
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

/** 多页草稿本状态：关闭草稿纸再打开内容仍在。 */
class ScratchpadState {
    val pages = mutableStateListOf(ScratchPageState())
    var currentPageIndex by mutableIntStateOf(0)
    var tool by mutableStateOf(ScratchTool.PEN)
    var paperStyle by mutableStateOf(ScratchPaperStyle.TRANSLUCENT)
    var colorIndex by mutableIntStateOf(0)
    var widthIndex by mutableIntStateOf(1)
    /** 橡皮尺寸档位：0=小(12dp) 1=中(24dp) 2=大(40dp)。 */
    var eraserSizeIndex by mutableIntStateOf(1)
    /** 当前展开尺寸面板的工具（null=收起）。双击画笔/橡皮切换。 */
    var sizePickerTool by mutableStateOf<ScratchTool?>(null)
    /** 双击判定：上一次点击的工具序号与时刻。 */
    var lastToolTapIndex by mutableIntStateOf(-1)
    var lastToolTapAt by mutableLongStateOf(0L)
    var showQuestion by mutableStateOf(false)
    var brushPanelOpen by mutableStateOf(false)
    var pageToast by mutableStateOf<String?>(null)
    var pageToastTick by mutableIntStateOf(0)

    val currentPage: ScratchPageState
        get() = pages[currentPageIndex.coerceIn(0, pages.lastIndex)]

    fun goPrevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--
            notifyPage("第 ${currentPageIndex + 1} / ${pages.size} 页")
        } else {
            notifyPage("已是第 1 页")
        }
    }

    fun goNextPage(autoCreate: Boolean = true) {
        if (currentPageIndex < pages.lastIndex) {
            currentPageIndex++
            notifyPage("第 ${currentPageIndex + 1} / ${pages.size} 页")
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
/**
 * 草稿纸状态的进程级持有者。
 *
 * 之前用 `remember { ScratchpadState() }`，关掉草稿纸再打开时组合被销毁重建，
 * 画的内容就全没了（用户反馈：退出再进就清空）。这里把实例挂在进程上，
 * 只在「换题 / 解锁」时显式清空。
 */
object ScratchpadHolder {
    var state: ScratchpadState? = null
}

fun rememberScratchpadState(): ScratchpadState = remember {
    ScratchpadHolder.state ?: ScratchpadState().also { ScratchpadHolder.state = it }
}

/** 清空草稿（换题时调用）。 */
fun clearScratchpad(state: ScratchpadState) {
    state.pages.clear()
    state.pages.add(ScratchPageState())
    state.currentPageIndex = 0
}

/**
 * 答题草稿纸浮层（与 FocusGuard 主题令牌完全统一）。
 *
 * - 配色全部来自传入的 [palette]（锁机/封锁页）或 `MaterialTheme.colorScheme`（设置类弹窗），
 *   不再有任何硬编码野生色，与主界面纸张、锁机页、莫奈动态色保持一致；
 * - 单行顶栏（收起 / 页码胶囊 / 看题 / 纸面）+ 单层紧凑悬浮工具坞（工具 · 颜色 · 笔宽 · 撤销/重做/清空），
 *   大画布、无冗余说明文字，风格与 `inkCard` / `liquidPanel` 同源；
 * - 书写：高刷历史点采样 + 二次贝塞尔平滑；橡皮擦为对象级碰撞擦除（无半透明黑块）；
 * - 换页：画布内双指上下划（末页上划自动新建）+ 顶栏页码步进，不与单指书写冲突。
 */
@Composable
fun ScratchpadOverlay(
    visible: Boolean,
    state: ScratchpadState,
    questionText: String? = null,
    onClose: () -> Unit,
    palette: FocusColors.Palette? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        val scheme = MaterialTheme.colorScheme
        val uiBg = palette?.bg ?: scheme.background
        val uiCard = palette?.card ?: scheme.surfaceVariant
        val uiLine = palette?.line ?: scheme.outline
        val uiAccent = palette?.accent ?: scheme.primary
        val uiText = palette?.text ?: scheme.onBackground
        val uiHaze = palette?.haze ?: scheme.onSurfaceVariant
        val uiFaint = palette?.faint ?: scheme.outline
        val uiError = palette?.error ?: scheme.error
        val uiSuccess = palette?.success ?: scheme.tertiary
        val paletteIsLight = palette?.isLight ?: (scheme.background.luminance() > 0.5f)

        val paperIsLight = when (state.paperStyle) {
            ScratchPaperStyle.PAPER -> true
            else -> paletteIsLight
        }
        val paperBg = when (state.paperStyle) {
            ScratchPaperStyle.TRANSLUCENT -> uiBg.copy(alpha = 0.90f)
            ScratchPaperStyle.THEME -> uiBg
            ScratchPaperStyle.PAPER -> Color(0xFFFDFDF8)
        }
        val gridColor = if (paperIsLight) {
            Color(0xFF64748B).copy(alpha = 0.13f)
        } else {
            uiLine.copy(alpha = 0.22f)
        }
        val inkColors = remember(uiText, uiAccent, uiError, uiSuccess, uiHaze, paperIsLight) {
            if (paperIsLight) {
                listOf(Color(0xFF0F172A), uiAccent, uiError, uiSuccess, Color(0xFF64748B))
            } else {
                listOf(uiText, uiAccent, uiError, uiSuccess, uiHaze)
            }
        }
        val widthDps = remember { listOf(2.2f, 3.8f, 6.2f) }

        val density = LocalDensity.current
        val activeColor = inkColors[state.colorIndex.coerceIn(0, inkColors.lastIndex)]
        val activeStrokePx = with(density) {
            widthDps[state.widthIndex.coerceIn(0, widthDps.lastIndex)].dp.toPx()
        }
        val eraserDp = listOf(12f, 24f, 40f)[state.eraserSizeIndex.coerceIn(0, 2)]
        val eraserRadiusPx = with(density) { eraserDp.dp.toPx() }
        val swipeThresholdPx = with(density) { 56.dp.toPx() }
        val minStepPx = with(density) { 2.2.dp.toPx() }
        val panelRadius = 16.dp

        // 草稿纸自身的玻璃图层：记录纸面与笔迹，供顶栏/底栏浮层做真实折射
        val scratchBackdrop = rememberLayerBackdrop()
        val appCtx = LocalContext.current
        val liquidOn = com.focusguard.app.ui.theme.AppearanceState.get(appCtx).cardStyle ==
            com.focusguard.app.ui.theme.Appearance.CARD_LIQUID
        // 画布缩放与平移（双指捏合缩放 / 双指拖动平移）
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }

        var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
        var shapeStart by remember { mutableStateOf<Offset?>(null) }
        var shapeEnd by remember { mutableStateOf<Offset?>(null) }
        var eraserCursor by remember { mutableStateOf<Offset?>(null) }

        LaunchedEffect(state.pageToastTick) {
            if (state.pageToast != null) {
                delay(1100L)
                state.pageToast = null
            }
        }

        val currentPage = state.currentPage

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(paperBg)
        ) {
            // ── 画布：网格 + 笔迹；双指捏合缩放/拖动，双指上下划翻页 ──
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(scratchBackdrop)
                    .pointerInput(state.currentPageIndex, state.tool, activeColor, activeStrokePx) {
                        fun inv(p: Offset): Offset {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            return Offset(
                                (p.x - pan.x - cx) / zoom + cx,
                                (p.y - pan.y - cy) / zoom + cy
                            )
                        }
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            firstDown.consume()
                            val downCanvas = inv(firstDown.position)

                            var multiTouch = false
                            var zoomMode = false
                            var swipeFired = false
                            var startSpread = 0f
                            var startZoom = zoom
                            var startPan = pan
                            var startCentroid = Offset.Zero
                            var lastSpread = 0f
                            var lastCentroid = Offset.Zero

                            val livePts = ArrayList<Offset>(64)
                            val erased = HashSet<ScratchItem>()

                            fun resetDraft() {
                                activePoints = emptyList()
                                shapeStart = null
                                shapeEnd = null
                                eraserCursor = null
                            }

                            when (state.tool) {
                                ScratchTool.PEN -> {
                                    livePts.add(downCanvas)
                                    activePoints = livePts.toList()
                                }
                                ScratchTool.ERASER -> {
                                    eraserCursor = downCanvas
                                    collectErased(
                                        currentPage.items, downCanvas, eraserRadiusPx / zoom, erased
                                    )
                                    if (erased.isNotEmpty()) {
                                        currentPage.commitReplace(
                                            currentPage.items.filterNot { it in erased }
                                        )
                                        erased.clear()
                                    }
                                }
                                else -> {
                                    shapeStart = downCanvas
                                    shapeEnd = downCanvas
                                }
                            }

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                if (pressed.size >= 2) {
                                    if (!multiTouch) {
                                        multiTouch = true
                                        resetDraft()
                                        startSpread = spreadOf(pressed)
                                        startZoom = zoom
                                        startPan = pan
                                        startCentroid = centroidOf(pressed)
                                        lastCentroid = startCentroid
                                    }
                                    val spread = spreadOf(pressed)
                                    val centroid = centroidOf(pressed)
                                    // 去掉缩放死区：双指径向距离一有变化就立刻缩放，避免起手发涩
                                    if (!zoomMode && startSpread > 1f &&
                                        abs(spread - startSpread) > 0.5f
                                    ) zoomMode = true
                                    if (zoomMode) {
                                        // 分两步：① 以手势中心为锚点增量缩放 ② 叠加双指整体位移
                                        // 只做①会出现「捏合跟手、但整体拖动不动」——ratio≈1 时
                                        // 锚点公式算出的 pan 不变，所以必须再叠加质心位移。
                                        val base = if (lastSpread > 1f) lastSpread else startSpread
                                        val ratio = if (base > 1f) spread / base else 1f
                                        val newZoom = (zoom * ratio).coerceIn(0.8f, 6f)
                                        val c = Offset(size.width / 2f, size.height / 2f)
                                        if (zoom > 0.01f) {
                                            pan = centroid - c - (centroid - c - pan) * (newZoom / zoom)
                                        }
                                        pan += centroid - lastCentroid
                                        zoom = newZoom
                                        lastSpread = spread
                                        lastCentroid = centroid
                                        eraserCursor = null
                                    } else if (!swipeFired && startZoom <= 1.05f) {
                                        // 未缩放时：双指上下划换页
                                        val dy = centroid.y - startCentroid.y
                                        if (abs(dy) >= swipeThresholdPx) {
                                            swipeFired = true
                                            if (dy < 0f) {
                                                state.goNextPage(autoCreate = true)
                                            } else {
                                                state.goPrevPage()
                                            }
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
                                        var added = false
                                        for (hist in change.historical) {
                                            val p = inv(hist.position)
                                            val last = livePts.lastOrNull()
                                            if (last == null ||
                                                hypot(p.x - last.x, p.y - last.y) >= minStepPx
                                            ) {
                                                livePts.add(p)
                                                added = true
                                            }
                                        }
                                        val cur = inv(change.position)
                                        val last = livePts.lastOrNull()
                                        if (last == null ||
                                            hypot(cur.x - last.x, cur.y - last.y) >= minStepPx
                                        ) {
                                            livePts.add(cur)
                                            added = true
                                        }
                                        if (added) activePoints = livePts.toList()
                                    }
                                    ScratchTool.ERASER -> {
                                        val cur = inv(change.position)
                                        eraserCursor = cur
                                        collectErased(
                                            currentPage.items, cur, eraserRadiusPx / zoom, erased
                                        )
                                        if (erased.isNotEmpty()) {
                                            currentPage.commitReplace(
                                                currentPage.items.filterNot { it in erased }
                                            )
                                            erased.clear()
                                        }
                                    }
                                    else -> shapeEnd = inv(change.position)
                                }
                            }

                            if (!multiTouch) {
                                when (state.tool) {
                                    ScratchTool.PEN -> if (livePts.isNotEmpty()) {
                                        currentPage.commitAdd(
                                            ScratchItem.Freehand(
                                                points = livePts.toList(),
                                                colorValue = activeColor.value,
                                                strokeWidthPx = activeStrokePx
                                            )
                                        )
                                    }
                                    ScratchTool.ERASER -> Unit
                                    else -> {
                                        val sp = shapeStart
                                        val ep = shapeEnd
                                        if (sp != null && ep != null &&
                                            hypot(ep.x - sp.x, ep.y - sp.y) >= 4f
                                        ) {
                                            currentPage.commitAdd(
                                                ScratchItem.Shape(
                                                    kind = state.tool,
                                                    start = sp,
                                                    end = ep,
                                                    colorValue = activeColor.value,
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
                val cx = size.width / 2f
                val cy = size.height / 2f
                withTransform({
                    translate(pan.x, pan.y)
                    scale(zoom, zoom, Offset(cx, cy))
                }) {
                    val step = 28.dp.toPx()
                    var gx = step
                    while (gx < size.width) {
                        drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                        gx += step
                    }
                    var gy = step
                    while (gy < size.height) {
                        drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                        gy += step
                    }
                    for (item in currentPage.items) drawScratchItem(item)
                    if (activePoints.isNotEmpty()) {
                        drawLiveFreehand(activePoints, activeColor, activeStrokePx)
                    }
                    val ds = shapeStart
                    val de = shapeEnd
                    if (ds != null && de != null &&
                        state.tool != ScratchTool.PEN && state.tool != ScratchTool.ERASER
                    ) {
                        drawGeometry(
                            state.tool, ds, de, activeColor.copy(alpha = 0.85f), activeStrokePx
                        )
                    }
                    eraserCursor?.let { pos ->
                        drawCircle(
                            color = uiAccent.copy(alpha = 0.16f),
                            radius = eraserRadiusPx / zoom,
                            center = pos
                        )
                        drawCircle(
                            color = uiAccent.copy(alpha = 0.75f),
                            radius = eraserRadiusPx / zoom,
                            center = pos,
                            style = Stroke(width = 1.5.dp.toPx() / zoom)
                        )
                    }
                }
            }

            // ── 顶栏（单行胶囊） ──
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(panelRadius))
                        .then(liquidPanel(scratchBackdrop, panelRadius, uiCard, liquidOn))
                        .border(1.dp, uiLine.copy(alpha = 0.55f), RoundedCornerShape(panelRadius))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "收起草稿纸",
                                tint = uiHaze,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            "草稿",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = uiText
                        )
                        Spacer(Modifier.weight(1f))
                        // 页码步进
                        IconButton(
                            onClick = { state.goPrevPage() },
                            enabled = state.currentPageIndex > 0,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.ChevronLeft,
                                contentDescription = "上一页",
                                tint = if (state.currentPageIndex > 0) uiText
                                else uiFaint.copy(alpha = 0.5f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Text(
                            "${state.currentPageIndex + 1}/${state.pages.size}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = uiHaze
                        )
                        IconButton(
                            onClick = { state.goNextPage(autoCreate = true) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "下一页",
                                tint = uiText,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(onClick = { state.addNewPage() }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "新建页",
                                tint = uiAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (!questionText.isNullOrBlank()) {
                            CactusChip(
                                label = if (state.showQuestion) "藏题" else "看题",
                                selected = state.showQuestion,
                                accent = uiAccent,
                                haze = uiHaze,
                                onClick = { state.showQuestion = !state.showQuestion }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        CactusChip(
                            label = state.paperStyle.label,
                            selected = state.paperStyle != ScratchPaperStyle.TRANSLUCENT,
                            accent = uiAccent,
                            haze = uiHaze,
                            onClick = {
                                val all = ScratchPaperStyle.entries
                                state.paperStyle =
                                    all[(state.paperStyle.ordinal + 1) % all.size]
                            }
                        )
                        if (zoom != 1f || pan != Offset.Zero) {
                            Spacer(Modifier.width(6.dp))
                            CactusChip(
                                label = "×" + String.format("%.1f", zoom),
                                selected = true,
                                accent = uiAccent,
                                haze = uiHaze,
                                onClick = {
                                    zoom = 1f
                                    pan = Offset.Zero
                                }
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }

                if (state.showQuestion && !questionText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .then(liquidPanel(scratchBackdrop, 12.dp, uiCard, liquidOn))
                            .border(1.dp, uiLine.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = questionText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = uiText,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ── 居中轻提示 ──
            AnimatedVisibility(
                visible = state.pageToast != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = uiCard.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, uiLine.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = state.pageToast ?: "",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = uiText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }

            // ── 底部单层紧凑工具坞 ──
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(panelRadius))
                    .then(liquidPanel(scratchBackdrop, panelRadius, uiCard, liquidOn))
                    .border(1.dp, uiLine.copy(alpha = 0.55f), RoundedCornerShape(panelRadius))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScratchTool.entries.forEach { tool ->
                        ToolChip(
                            icon = tool.icon,
                            selected = state.tool == tool,
                            accent = uiAccent,
                            haze = uiHaze,
                            onClick = { state.tool = tool }
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    DockDivider(uiLine)
                    inkColors.forEachIndexed { idx, color ->
                        val sel = state.colorIndex == idx
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (sel) 20.dp else 18.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (sel) 2.dp else 1.dp,
                                    color = if (sel) uiAccent else uiLine.copy(alpha = 0.7f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    state.colorIndex = idx
                                    if (state.tool == ScratchTool.ERASER) {
                                        state.tool = ScratchTool.PEN
                                    }
                                }
                        )
                    }
                    // 尺寸三档常驻显示（用户反馈：收起来后「按钮就没了」）。
                    // 用分隔线 + 与颜色点不同的形态区分，不再依赖双击。
                    DockDivider(uiLine)
                    // 橡皮模式下调的是「橡皮大小」三档，其余工具调笔宽三档
                    val sizeDps = if (state.tool == ScratchTool.ERASER) {
                        listOf(12f, 24f, 40f)
                    } else {
                        widthDps
                    }
                    val sizeSelIndex = if (state.tool == ScratchTool.ERASER) {
                        state.eraserSizeIndex
                    } else {
                        state.widthIndex
                    }
                    sizeDps.forEachIndexed { idx, w ->
                        val sel = sizeSelIndex == idx
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (sel) uiAccent.copy(alpha = 0.16f) else Color.Transparent
                                )
                                .clickable {
                                    if (state.tool == ScratchTool.ERASER) {
                                        state.eraserSizeIndex = idx
                                    } else {
                                        state.widthIndex = idx
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                // 三档圆点必须**单调递增**：之前把大档压到 22dp，
                                // 渲染成 15/27/22 → 看起来是「小 大 中」，顺序错乱。
                                modifier = Modifier
                                    .size(
                                        if (state.tool == ScratchTool.ERASER) {
                                            when (idx) {
                                                0 -> 10.dp
                                                1 -> 16.dp
                                                else -> 22.dp
                                            }
                                        } else {
                                            (w + 3f).dp
                                        }
                                    )
                                    .clip(CircleShape)
                                    .background(if (sel) uiAccent else uiHaze)
                            )
                        }
                    }
                    DockDivider(uiLine)
                    IconAction(Icons.Default.Undo, "撤销", uiText, uiFaint, currentPage.canUndo) {
                        currentPage.undo()
                    }
                    IconAction(Icons.Default.Redo, "重做", uiText, uiFaint, currentPage.canRedo) {
                        currentPage.redo()
                    }
                    IconAction(
                        Icons.Default.DeleteSweep,
                        "清空",
                        uiError,
                        uiFaint,
                        currentPage.items.isNotEmpty()
                    ) { currentPage.clear() }
                    if (state.pages.size > 1) {
                        IconAction(
                            Icons.Default.DeleteOutline,
                            "删除此页",
                            uiHaze,
                            uiFaint,
                            true
                        ) { state.deleteCurrentPage() }
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}

/** 草稿纸浮层的液态玻璃材质；未开启液态模式时退回实心卡片底。 */
private fun liquidPanel(
    backdrop: com.kyant.backdrop.Backdrop,
    radius: androidx.compose.ui.unit.Dp,
    tint: Color,
    enabled: Boolean
): Modifier {
    if (!enabled) return Modifier.background(tint.copy(alpha = 0.94f))
    val shape = RoundedCornerShape(radius)
    return Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(7.dp.toPx())
            lens(
                16.dp.toPx(),
                34.dp.toPx(),
                depthEffect = true,
                chromaticAberration = true
            )
        },
        highlight = { com.kyant.backdrop.highlight.Highlight(width = 0.7.dp, alpha = 0.62f) },
        shadow = { com.kyant.backdrop.shadow.Shadow(radius = 18.dp, alpha = 0.22f) },
        onDrawSurface = { drawRect(tint.copy(alpha = 0.16f)) }
    )
}

private fun spreadOf(
    changes: List<androidx.compose.ui.input.pointer.PointerInputChange>
): Float {
    if (changes.size < 2) return 0f
    return (changes[0].position - changes[1].position).getDistance()
}

private fun centroidOf(
    changes: List<androidx.compose.ui.input.pointer.PointerInputChange>
): Offset {
    var sx = 0f
    var sy = 0f
    for (c in changes) {
        sx += c.position.x
        sy += c.position.y
    }
    val n = changes.size.coerceAtLeast(1)
    return Offset(sx / n, sy / n)
}

@Composable
private fun CactusChip(
    label: String,
    selected: Boolean,
    accent: Color,
    haze: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accent.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.55f) else Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) accent else haze,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ToolChip(
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    haze: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) accent else haze,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    active: Color,
    inactive: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) active else inactive.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DockDivider(color: Color) {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(20.dp)
            .background(color.copy(alpha = 0.5f))
    )
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
        path.quadraticTo(prev.x, prev.y, (prev.x + cur.x) * 0.5f, (prev.y + cur.y) * 0.5f)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

private fun DrawScope.drawScratchItem(item: ScratchItem) {
    val color = Color(item.colorValue)
    when (item) {
        is ScratchItem.Freehand -> {
            if (item.points.size == 1) {
                drawCircle(
                    color = color,
                    radius = item.strokeWidthPx * 0.55f,
                    center = item.points[0]
                )
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
        is ScratchItem.Shape ->
            drawGeometry(item.kind, item.start, item.end, color, item.strokeWidthPx)
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
        ScratchTool.LINE -> drawLine(
            color = color, start = start, end = end,
            strokeWidth = strokeWidthPx, cap = StrokeCap.Round
        )
        ScratchTool.RECT -> drawRect(
            color = color,
            topLeft = Offset(min(start.x, end.x), min(start.y, end.y)),
            size = androidx.compose.ui.geometry.Size(
                abs(end.x - start.x), abs(end.y - start.y)
            ),
            style = style
        )
        ScratchTool.OVAL -> drawOval(
            color = color,
            topLeft = Offset(min(start.x, end.x), min(start.y, end.y)),
            size = androidx.compose.ui.geometry.Size(
                abs(end.x - start.x), abs(end.y - start.y)
            ),
            style = style
        )
        ScratchTool.ARROW -> {
            drawLine(
                color = color, start = start, end = end,
                strokeWidth = strokeWidthPx, cap = StrokeCap.Round
            )
            val len = hypot(end.x - start.x, end.y - start.y)
            if (len > 12f) {
                val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                val head = min(32f, max(14f, len * 0.22f))
                val spread = Math.toRadians(28.0)
                val p1 = Offset(
                    (end.x - head * cos(angle - spread)).toFloat(),
                    (end.y - head * sin(angle - spread)).toFloat()
                )
                val p2 = Offset(
                    (end.x - head * cos(angle + spread)).toFloat(),
                    (end.y - head * sin(angle + spread)).toFloat()
                )
                drawLine(
                    color = color, start = end, end = p1,
                    strokeWidth = strokeWidthPx, cap = StrokeCap.Round
                )
                drawLine(
                    color = color, start = end, end = p2,
                    strokeWidth = strokeWidthPx, cap = StrokeCap.Round
                )
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
        if (hitTestItem(item, cursor, radius + item.strokeWidthPx * 0.5f)) out.add(item)
    }
}

private fun hitTestItem(item: ScratchItem, p: Offset, threshold: Float): Boolean = when (item) {
    is ScratchItem.Freehand -> {
        val pts = item.points
        if (pts.isEmpty()) {
            false
        } else if (pts.size == 1) {
            hypot(p.x - pts[0].x, p.y - pts[0].y) <= threshold
        } else {
            var hit = false
            for (i in 1 until pts.size) {
                if (pointToSegmentDistance(p, pts[i - 1], pts[i]) <= threshold) {
                    hit = true
                    break
                }
            }
            hit
        }
    }
    is ScratchItem.Shape -> when (item.kind) {
        ScratchTool.LINE, ScratchTool.ARROW ->
            pointToSegmentDistance(p, item.start, item.end) <= threshold
        ScratchTool.RECT -> {
            val l = min(item.start.x, item.end.x)
            val r = max(item.start.x, item.end.x)
            val t = min(item.start.y, item.end.y)
            val b = max(item.start.y, item.end.y)
            pointToSegmentDistance(p, Offset(l, t), Offset(r, t)) <= threshold ||
                pointToSegmentDistance(p, Offset(r, t), Offset(r, b)) <= threshold ||
                pointToSegmentDistance(p, Offset(r, b), Offset(l, b)) <= threshold ||
                pointToSegmentDistance(p, Offset(l, b), Offset(l, t)) <= threshold
        }
        ScratchTool.OVAL -> {
            val cx = (item.start.x + item.end.x) * 0.5f
            val cy = (item.start.y + item.end.y) * 0.5f
            val rx = abs(item.end.x - item.start.x) * 0.5f
            val ry = abs(item.end.y - item.start.y) * 0.5f
            if (rx < 2f || ry < 2f) {
                hypot(p.x - cx, p.y - cy) <= threshold
            } else {
                val d = hypot((p.x - cx) / rx, (p.y - cy) / ry)
                abs(d - 1f) * ((rx + ry) * 0.5f) <= threshold
            }
        }
        else -> false
    }
}

private fun pointToSegmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq <= 0.0001f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}
