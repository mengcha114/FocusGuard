package com.focusguard.app.enforce

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 悬浮窗答题模式专用原生草稿纸浮层（不依赖 Compose Window，可直接盖在 LockOverlayManager 之上）。
 *
 * 与 Compose 版 [com.focusguard.app.ui.components.ScratchpadOverlay] 功能对齐：
 * - 高刷历史点采样 + 二次贝塞尔平滑书写；
 * - 画笔、橡皮擦、直线、矩形、圆形、箭头；
 * - 撤销 / 重做 / 清空 / 多页管理；
 * - 双指上下划换页（末页自动新建）+ 右侧页码条单指上下划换页；
 * - 三种纸面（半透明透视 / 暗色网格 / 白色网格）+ 可展开题目对照栏。
 */
class ScratchpadOverlayView(
    context: Context,
    private val questionProvider: () -> String,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private enum class Tool(val label: String) {
        PEN("画笔"),
        ERASER("橡皮"),
        LINE("直线"),
        RECT("矩形"),
        OVAL("圆形"),
        ARROW("箭头")
    }

    private enum class Paper(val label: String) {
        TRANSLUCENT("透视"),
        DARK_GRID("暗格"),
        LIGHT_GRID("白格")
    }

    private sealed class Item(val color: Int, val widthPx: Float) {
        class Stroke(val xs: FloatArray, val ys: FloatArray, color: Int, widthPx: Float) : Item(color, widthPx) {
            val path: Path = buildPath(xs, ys)
        }
        class Shape(
            val kind: Tool,
            val x1: Float,
            val y1: Float,
            val x2: Float,
            val y2: Float,
            color: Int,
            widthPx: Float
        ) : Item(color, widthPx)
    }

    private class Page {
        val items = ArrayList<Item>()
        val undoStack = ArrayDeque<List<Item>>()
        val redoStack = ArrayDeque<List<Item>>()

        fun commitAdd(item: Item) {
            pushUndo()
            items.add(item)
        }

        fun commitReplace(next: List<Item>) {
            if (next.size == items.size) return
            pushUndo()
            items.clear()
            items.addAll(next)
        }

        fun clear() {
            if (items.isEmpty()) return
            pushUndo()
            items.clear()
        }

        fun undo(): Boolean {
            val prev = undoStack.removeLastOrNull() ?: return false
            redoStack.addLast(items.toList())
            items.clear()
            items.addAll(prev)
            return true
        }

        fun redo(): Boolean {
            val next = redoStack.removeLastOrNull() ?: return false
            undoStack.addLast(items.toList())
            items.clear()
            items.addAll(next)
            return true
        }

        private fun pushUndo() {
            undoStack.addLast(items.toList())
            if (undoStack.size > 40) undoStack.removeFirst()
            redoStack.clear()
        }
    }

    private val pages = ArrayList<Page>().apply { add(Page()) }
    private var pageIdx = 0
    private var tool = Tool.PEN
    private var paper = Paper.TRANSLUCENT
    private var colorIdx = 0
    private var widthIdx = 1
    private var showQuestion = false

    private val darkColors = intArrayOf(
        Color.parseColor("#F8FAFC"),
        Color.parseColor("#38BDF8"),
        Color.parseColor("#FB7185"),
        Color.parseColor("#34D399"),
        Color.parseColor("#FBBF24")
    )
    private val lightColors = intArrayOf(
        Color.parseColor("#1A1F2C"),
        Color.parseColor("#1D4ED8"),
        Color.parseColor("#DC2626"),
        Color.parseColor("#059669"),
        Color.parseColor("#D97706")
    )
    private val widthsDp = floatArrayOf(2.2f, 3.8f, 6.2f)

    private val canvasView = DrawingSurface(context)
    private val pageLabel: TextView
    private val edgeLabel: TextView
    private val paperBtn: TextView
    private val questionBtn: TextView
    private val questionBox: TextView
    private val toastView: TextView
    private val toolButtons = LinkedHashMap<Tool, TextView>()
    private val colorDots = ArrayList<View>()
    private val widthDots = ArrayList<View>()
    private val hideToastRunnable = Runnable { toastView.visibility = View.GONE }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density
    private fun currentPage(): Page = pages[pageIdx.coerceIn(0, pages.lastIndex)]
    private fun activeColor(): Int = (if (paper == Paper.LIGHT_GRID) lightColors else darkColors)[colorIdx]
    private fun activeWidthPx(): Float = dpf(widthsDp[widthIdx])

    init {
        isClickable = true
        isFocusable = true

        // 1. 全屏绘制画布
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 2. 右侧边缘单指上下划换页条
        edgeLabel = TextView(context).apply {
            text = "▲\n1/1\n▼"
            textSize = 9.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setLineSpacing(dpf(2f), 1f)
            background = GradientDrawable().apply {
                cornerRadius = dpf(13f)
                setColor(Color.parseColor("#B818202C"))
                setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
            }
            var startY = 0f
            var fired = false
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = ev.y
                        fired = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = ev.y - startY
                        if (!fired && abs(dy) >= dpf(38f)) {
                            fired = true
                            if (dy < 0f) nextPage(true) else prevPage()
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        addView(
            edgeLabel,
            LayoutParams(dp(28f), dp(128f)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = dp(6f)
            }
        )

        // 3. 顶部工具条 + 可展开题干栏
        val topWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(42f), dp(12f), 0)
        }
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg("#EE18202C", 16f)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        topBar.addView(smallBtn("◀") { prevPage() })
        pageLabel = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(4f), 0, dp(4f), 0)
        }
        topBar.addView(pageLabel)
        topBar.addView(smallBtn("▶") { nextPage(true) })
        topBar.addView(smallBtn("+页", "#38BDF8") { addPage() })

        topBar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        questionBtn = smallBtn("看题", "#38BDF8") {
            showQuestion = !showQuestion
            refreshQuestionBanner()
        }
        topBar.addView(questionBtn)

        paperBtn = smallBtn(paper.label, "#38BDF8") {
            val all = Paper.entries
            paper = all[(paper.ordinal + 1) % all.size]
            refreshUi()
        }
        topBar.addView(paperBtn)

        topBar.addView(smallBtn("收起", "#FB7185") { onClose() })
        topWrapper.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        questionBox = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setLineSpacing(dpf(3f), 1f)
            background = pillBg("#EE18202C", 12f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            maxLines = 5
            visibility = View.GONE
        }
        topWrapper.addView(
            questionBox,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            }
        )
        addView(topWrapper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // 4. 翻页轻提示
        toastView = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = pillBg("#CC0F172A", 20f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            visibility = View.GONE
        }
        addView(toastView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 5. 底部工具栏
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#EE18202C", 18f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        val toolScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val toolRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (t in Tool.entries) {
            val btn = TextView(context).apply {
                text = t.label
                textSize = 12f
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                setOnClickListener {
                    tool = t
                    refreshUi()
                }
            }
            toolButtons[t] = btn
            toolRow.addView(
                btn,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6f)
                }
            )
        }
        toolScroll.addView(toolRow)
        bottomBar.addView(toolScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        for (i in 0 until darkColors.size) {
            val dot = View(context).apply {
                setOnClickListener {
                    colorIdx = i
                    if (tool == Tool.ERASER) tool = Tool.PEN
                    refreshUi()
                }
            }
            colorDots.add(dot)
            actionRow.addView(
                dot,
                LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(7f) }
            )
        }
        for (i in widthsDp.indices) {
            val wDot = TextView(context).apply {
                text = when (i) {
                    0 -> "细"
                    1 -> "中"
                    else -> "粗"
                }
                textSize = 11f
                gravity = Gravity.CENTER
                setOnClickListener {
                    widthIdx = i
                    refreshUi()
                }
            }
            widthDots.add(wDot)
            actionRow.addView(
                wDot,
                LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(4f) }
            )
        }
        actionRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        actionRow.addView(smallBtn("撤销") {
            if (currentPage().undo()) canvasView.invalidate()
        })
        actionRow.addView(smallBtn("重做") {
            if (currentPage().redo()) canvasView.invalidate()
        })
        actionRow.addView(smallBtn("清空", "#FB7185") {
            currentPage().clear()
            canvasView.invalidate()
        })
        bottomBar.addView(actionRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        bottomBar.addView(
            TextView(context).apply {
                text = "双指上下划或拖动右侧页码条可快速换页/新建页"
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(4f), 0, 0)
            }
        )

        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dp(12f)
                rightMargin = dp(12f)
                bottomMargin = dp(14f)
            }
        )

        refreshUi()
    }

    fun refreshQuestionBanner() {
        val q = questionProvider()
        questionBtn.text = if (showQuestion) "藏题" else "看题"
        questionBox.text = q
        questionBox.visibility = if (showQuestion && q.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun prevPage() {
        if (pageIdx > 0) {
            pageIdx--
            showToast("↑ 第 ${pageIdx + 1} / ${pages.size} 页")
            refreshUi()
        } else {
            showToast("已是第 1 页")
        }
    }

    private fun nextPage(autoCreate: Boolean) {
        if (pageIdx < pages.lastIndex) {
            pageIdx++
            showToast("↓ 第 ${pageIdx + 1} / ${pages.size} 页")
            refreshUi()
        } else if (autoCreate && pages.size < 20) {
            pages.add(Page())
            pageIdx = pages.lastIndex
            showToast("已新建第 ${pageIdx + 1} 页")
            refreshUi()
        } else {
            showToast("已是最后一页")
        }
    }

    private fun addPage() {
        if (pages.size >= 20) {
            showToast("最多支持 20 页")
            return
        }
        pages.add(Page())
        pageIdx = pages.lastIndex
        showToast("已新建第 ${pageIdx + 1} 页")
        refreshUi()
    }

    private fun showToast(msg: String) {
        toastView.text = msg
        toastView.visibility = View.VISIBLE
        removeCallbacks(hideToastRunnable)
        postDelayed(hideToastRunnable, 1200L)
    }

    private fun refreshUi() {
        pageLabel.text = "第 ${pageIdx + 1}/${pages.size} 页"
        edgeLabel.text = "▲\n${pageIdx + 1}/${pages.size}\n▼"
        paperBtn.text = paper.label
        for ((t, btn) in toolButtons) {
            val sel = t == tool
            btn.setTextColor(if (sel) Color.parseColor("#38BDF8") else Color.parseColor("#E2E8F0"))
            btn.background = if (sel) {
                GradientDrawable().apply {
                    cornerRadius = dpf(10f)
                    setColor(Color.parseColor("#2E38BDF8"))
                    setStroke(dp(1f), Color.parseColor("#A638BDF8"))
                }
            } else null
        }
        val palette = if (paper == Paper.LIGHT_GRID) lightColors else darkColors
        colorDots.forEachIndexed { idx, v ->
            val sel = idx == colorIdx
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette[idx])
                setStroke(dp(if (sel) 2.5f else 1f), if (sel) Color.parseColor("#38BDF8") else Color.parseColor("#55FFFFFF"))
            }
        }
        widthDots.forEachIndexed { idx, v ->
            val tv = v as TextView
            val sel = idx == widthIdx
            tv.setTextColor(if (sel) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
            tv.background = if (sel) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2E38BDF8"))
                }
            } else null
        }
        refreshQuestionBanner()
        canvasView.invalidate()
    }

    private fun pillBg(hex: String, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp)
        setColor(Color.parseColor(hex))
        setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
    }

    private fun smallBtn(text: String, colorHex: String = "#F1F5F9", onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(colorHex))
            setPadding(dp(7f), dp(4f), dp(7f), dp(4f))
            setOnClickListener { onClick() }
        }

    private inner class DrawingSurface(ctx: Context) : View(ctx) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1f
        }
        private val eraserFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#2A38BDF8")
        }
        private val eraserRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.5f)
            color = Color.parseColor("#CC38BDF8")
        }

        private val liveXs = ArrayList<Float>(128)
        private val liveYs = ArrayList<Float>(128)
        private val livePath = Path()
        private var shapeActive = false
        private var sx = 0f
        private var sy = 0f
        private var ex = 0f
        private var ey = 0f
        private var eraserActive = false
        private var erX = 0f
        private var erY = 0f

        private var multiTouch = false
        private var swipeFired = false
        private var twoFingerStartY = 0f
        private val rectTmp = RectF()
        private val minStep = dpf(2.2f)
        private val eraserRadius = dpf(18f)
        private val swipeThreshold = dpf(56f)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bg = when (paper) {
                Paper.TRANSLUCENT -> Color.parseColor("#BD0D131D")
                Paper.DARK_GRID -> Color.parseColor("#F711161F")
                Paper.LIGHT_GRID -> Color.parseColor("#FAFAFBF8")
            }
            canvas.drawColor(bg)

            gridPaint.color = when (paper) {
                Paper.TRANSLUCENT -> Color.parseColor("#16FFFFFF")
                Paper.DARK_GRID -> Color.parseColor("#18FFFFFF")
                Paper.LIGHT_GRID -> Color.parseColor("#1C334155")
            }
            val step = dpf(28f)
            var x = step
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                x += step
            }
            var y = step
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                y += step
            }

            for (item in currentPage().items) {
                drawItem(canvas, item)
            }

            if (liveXs.isNotEmpty()) {
                val c = activeColor()
                val w = activeWidthPx()
                if (liveXs.size == 1) {
                    fillPaint.color = c
                    canvas.drawCircle(liveXs[0], liveYs[0], w * 0.55f, fillPaint)
                } else {
                    strokePaint.color = c
                    strokePaint.strokeWidth = w
                    canvas.drawPath(livePath, strokePaint)
                }
            }

            if (shapeActive) {
                drawShape(canvas, tool, sx, sy, ex, ey, activeColor(), activeWidthPx())
            }

            if (eraserActive) {
                canvas.drawCircle(erX, erY, eraserRadius, eraserFillPaint)
                canvas.drawCircle(erX, erY, eraserRadius, eraserRingPaint)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    multiTouch = false
                    swipeFired = false
                    when (tool) {
                        Tool.PEN -> {
                            liveXs.clear()
                            liveYs.clear()
                            liveXs.add(ev.x)
                            liveYs.add(ev.y)
                            rebuildLivePath()
                        }
                        Tool.ERASER -> {
                            eraserActive = true
                            erX = ev.x
                            erY = ev.y
                            eraseAt(erX, erY)
                        }
                        else -> {
                            shapeActive = true
                            sx = ev.x
                            sy = ev.y
                            ex = ev.x
                            ey = ev.y
                        }
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount >= 2 && !multiTouch) {
                        multiTouch = true
                        clearLive()
                        twoFingerStartY = avgY(ev)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount >= 2 || multiTouch) {
                        if (!multiTouch) {
                            multiTouch = true
                            clearLive()
                            twoFingerStartY = avgY(ev)
                        }
                        if (ev.pointerCount >= 2 && !swipeFired) {
                            val dy = avgY(ev) - twoFingerStartY
                            if (abs(dy) >= swipeThreshold) {
                                swipeFired = true
                                if (dy < 0f) nextPage(true) else prevPage()
                            }
                        }
                        invalidate()
                        return true
                    }

                    when (tool) {
                        Tool.PEN -> {
                            var added = false
                            for (h in 0 until ev.historySize) {
                                val hx = ev.getHistoricalX(h)
                                val hy = ev.getHistoricalY(h)
                                if (appendLivePoint(hx, hy)) added = true
                            }
                            if (appendLivePoint(ev.x, ev.y)) added = true
                            if (added) {
                                rebuildLivePath()
                                invalidate()
                            }
                        }
                        Tool.ERASER -> {
                            eraserActive = true
                            erX = ev.x
                            erY = ev.y
                            eraseAt(erX, erY)
                            invalidate()
                        }
                        else -> {
                            ex = ev.x
                            ey = ev.y
                            invalidate()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!multiTouch) {
                        when (tool) {
                            Tool.PEN -> {
                                if (liveXs.isNotEmpty()) {
                                    val xs = FloatArray(liveXs.size) { liveXs[it] }
                                    val ys = FloatArray(liveYs.size) { liveYs[it] }
                                    currentPage().commitAdd(Item.Stroke(xs, ys, activeColor(), activeWidthPx()))
                                }
                            }
                            Tool.ERASER -> Unit
                            else -> {
                                if (shapeActive && hypot(ex - sx, ey - sy) >= 4f) {
                                    currentPage().commitAdd(
                                        Item.Shape(tool, sx, sy, ex, ey, activeColor(), activeWidthPx())
                                    )
                                }
                            }
                        }
                    }
                    clearLive()
                    multiTouch = false
                    swipeFired = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        private fun avgY(ev: MotionEvent): Float {
            var sum = 0f
            for (i in 0 until ev.pointerCount) sum += ev.getY(i)
            return sum / max(1, ev.pointerCount)
        }

        private fun appendLivePoint(x: Float, y: Float): Boolean {
            if (liveXs.isEmpty()) {
                liveXs.add(x)
                liveYs.add(y)
                return true
            }
            val lx = liveXs[liveXs.lastIndex]
            val ly = liveYs[liveYs.lastIndex]
            if (hypot(x - lx, y - ly) >= minStep) {
                liveXs.add(x)
                liveYs.add(y)
                return true
            }
            return false
        }

        private fun rebuildLivePath() {
            livePath.reset()
            if (liveXs.isEmpty()) return
            livePath.moveTo(liveXs[0], liveYs[0])
            for (i in 1 until liveXs.size) {
                val px = liveXs[i - 1]
                val py = liveYs[i - 1]
                val cx = liveXs[i]
                val cy = liveYs[i]
                livePath.quadTo(px, py, (px + cx) * 0.5f, (py + cy) * 0.5f)
            }
            livePath.lineTo(liveXs[liveXs.lastIndex], liveYs[liveYs.lastIndex])
        }

        private fun clearLive() {
            liveXs.clear()
            liveYs.clear()
            livePath.reset()
            shapeActive = false
            eraserActive = false
        }

        private fun eraseAt(x: Float, y: Float) {
            val page = currentPage()
            val remaining = page.items.filterNot { hitTest(it, x, y, eraserRadius + it.widthPx * 0.5f) }
            if (remaining.size != page.items.size) {
                page.commitReplace(remaining)
            }
        }

        private fun drawItem(canvas: Canvas, item: Item) {
            when (item) {
                is Item.Stroke -> {
                    if (item.xs.size == 1) {
                        fillPaint.color = item.color
                        canvas.drawCircle(item.xs[0], item.ys[0], item.widthPx * 0.55f, fillPaint)
                    } else {
                        strokePaint.color = item.color
                        strokePaint.strokeWidth = item.widthPx
                        canvas.drawPath(item.path, strokePaint)
                    }
                }
                is Item.Shape -> drawShape(canvas, item.kind, item.x1, item.y1, item.x2, item.y2, item.color, item.widthPx)
            }
        }

        private fun drawShape(canvas: Canvas, kind: Tool, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, w: Float) {
            strokePaint.color = color
            strokePaint.strokeWidth = w
            when (kind) {
                Tool.LINE -> canvas.drawLine(x1, y1, x2, y2, strokePaint)
                Tool.RECT -> {
                    rectTmp.set(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
                    canvas.drawRect(rectTmp, strokePaint)
                }
                Tool.OVAL -> {
                    rectTmp.set(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
                    canvas.drawOval(rectTmp, strokePaint)
                }
                Tool.ARROW -> {
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                    val len = hypot(x2 - x1, y2 - y1)
                    if (len > 12f) {
                        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                        val head = min(32f, max(14f, len * 0.22f))
                        val spread = Math.toRadians(28.0)
                        canvas.drawLine(
                            x2, y2,
                            (x2 - head * cos(angle - spread)).toFloat(),
                            (y2 - head * sin(angle - spread)).toFloat(),
                            strokePaint
                        )
                        canvas.drawLine(
                            x2, y2,
                            (x2 - head * cos(angle + spread)).toFloat(),
                            (y2 - head * sin(angle + spread)).toFloat(),
                            strokePaint
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    companion object {
        private fun buildPath(xs: FloatArray, ys: FloatArray): Path {
            val p = Path()
            if (xs.isEmpty()) return p
            p.moveTo(xs[0], ys[0])
            if (xs.size == 1) {
                p.lineTo(xs[0] + 0.1f, ys[0] + 0.1f)
                return p
            }
            for (i in 1 until xs.size) {
                val px = xs[i - 1]
                val py = ys[i - 1]
                val cx = xs[i]
                val cy = ys[i]
                p.quadTo(px, py, (px + cx) * 0.5f, (py + cy) * 0.5f)
            }
            p.lineTo(xs[xs.lastIndex], ys[ys.lastIndex])
            return p
        }

        private fun hitTest(item: Item, px: Float, py: Float, threshold: Float): Boolean = when (item) {
            is Item.Stroke -> {
                if (item.xs.isEmpty()) false
                else if (item.xs.size == 1) hypot(px - item.xs[0], py - item.ys[0]) <= threshold
                else {
                    var hit = false
                    for (i in 1 until item.xs.size) {
                        if (distSeg(px, py, item.xs[i - 1], item.ys[i - 1], item.xs[i], item.ys[i]) <= threshold) {
                            hit = true
                            break
                        }
                    }
                    hit
                }
            }
            is Item.Shape -> when (item.kind) {
                Tool.LINE, Tool.ARROW -> distSeg(px, py, item.x1, item.y1, item.x2, item.y2) <= threshold
                Tool.RECT -> {
                    val l = min(item.x1, item.x2)
                    val r = max(item.x1, item.x2)
                    val t = min(item.y1, item.y2)
                    val b = max(item.y1, item.y2)
                    distSeg(px, py, l, t, r, t) <= threshold ||
                        distSeg(px, py, r, t, r, b) <= threshold ||
                        distSeg(px, py, r, b, l, b) <= threshold ||
                        distSeg(px, py, l, b, l, t) <= threshold
                }
                Tool.OVAL -> {
                    val cx = (item.x1 + item.x2) * 0.5f
                    val cy = (item.y1 + item.y2) * 0.5f
                    val rx = abs(item.x2 - item.x1) * 0.5f
                    val ry = abs(item.y2 - item.y1) * 0.5f
                    if (rx < 2f || ry < 2f) hypot(px - cx, py - cy) <= threshold
                    else {
                        val d = hypot((px - cx) / rx, (py - cy) / ry)
                        abs(d - 1f) * ((rx + ry) * 0.5f) <= threshold
                    }
                }
                else -> false
            }
        }

        private fun distSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            if (lenSq <= 0.0001f) return hypot(px - ax, py - ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }
    }
}
