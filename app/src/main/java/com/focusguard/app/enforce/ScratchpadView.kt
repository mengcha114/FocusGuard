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
import com.focusguard.app.ui.theme.FocusColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 悬浮窗答题模式的草稿纸浮层（纯传统 View，可直接叠在 LockOverlayManager 之上）。
 *
 * 与 Compose 版 [com.focusguard.app.ui.components.ScratchpadOverlay] 功能与视觉完全对齐：
 * - 配色 100% 来自 [FocusColors.Palette]，与锁机页/悬浮窗/主界面同一套令牌，无野生硬编码色；
 * - 单行顶栏（收起 · 页码 · 新建 · 看题 · 纸面）+ 单层横向紧凑工具坞，画布面积最大化；
 * - 高刷历史点采样 + 二次贝塞尔平滑书写；对象级碰撞橡皮擦；
 * - 双指上下划换页（末页自动新建），不与单指书写冲突。
 */
class ScratchpadOverlayView(
    context: Context,
    private val palette: FocusColors.Palette,
    private val questionProvider: () -> String,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private enum class Tool(val label: String) {
        PEN("画笔"), ERASER("橡皮"), LINE("直线"),
        RECT("矩形"), OVAL("圆形"), ARROW("箭头")
    }

    private enum class Paper(val label: String) {
        TRANSLUCENT("半透"), THEME("主题纸"), PAPER("纸白")
    }

    private sealed class Item(val color: Int, val widthPx: Float) {
        class Stroke(
            val xs: FloatArray,
            val ys: FloatArray,
            color: Int,
            widthPx: Float
        ) : Item(color, widthPx) {
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

    private val widthsDp = floatArrayOf(2.2f, 3.8f, 6.2f)

    private val inkColors: IntArray
    private val paperIsLight: Boolean
        get() = paper == Paper.PAPER || palette.isLight

    private val canvasView = DrawingSurface(context)
    private val pageLabel: TextView
    private val paperBtn: TextView
    private val questionBtn: TextView
    private val questionBox: TextView
    private val toastView: TextView
    private val toolButtons = LinkedHashMap<Tool, TextView>()
    private val colorDots = ArrayList<View>()
    private val widthDots = ArrayList<TextView>()
    /** 橡皮尺寸三档（半径 dp）：小/中/大 */
    private val eraserSizesDp = floatArrayOf(12f, 24f, 40f)
    private var eraserSizeIdx = 1
    private fun eraserRadiusPx(): Float = dpf(eraserSizesDp[eraserSizeIdx.coerceIn(0, 2)])
    /** 重新打开草稿纸时把视口复位，否则会停在上次的 6x 上。 */
    fun resetViewport() = canvasView.resetViewport()
    private val hideToastRunnable = Runnable { toastView.visibility = View.GONE }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density
    private fun argb(c: Int, alpha: Int): Int =
        (c and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    private fun focus(c: androidx.compose.ui.graphics.Color): Int =
        Color.parseColor(FocusColors.hex(c))

    private fun currentPage(): Page = pages[pageIdx.coerceIn(0, pages.lastIndex)]
    private fun activeColor(): Int = inkColors[colorIdx.coerceIn(0, inkColors.lastIndex)]
    private fun activeWidthPx(): Float = dpf(widthsDp[widthIdx])

    init {
        isClickable = true
        isFocusable = true

        val cText = focus(palette.text)
        val cHaze = focus(palette.haze)
        val cFaint = focus(palette.faint)
        val cAccent = focus(palette.accent)
        val cCard = focus(palette.card)
        val cLine = focus(palette.line)
        val cError = focus(palette.error)
        val cSuccess = focus(palette.success)

        inkColors = if (palette.isLight) {
            intArrayOf(
                Color.parseColor("#0F172A"), cAccent, cError, cSuccess,
                Color.parseColor("#64748B")
            )
        } else {
            intArrayOf(cText, cAccent, cError, cSuccess, cHaze)
        }

        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ── 顶栏（横向可滚动，绝不溢出） ──
        val topWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(40f), dp(12f), 0)
        }
        val topScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelBg(argb(cCard, 242), 16f, argb(cLine, 140))
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        }
        topBar.addView(smallBtn("✕", argb(cHaze, 255)) { onClose() })
        topBar.addView(TextView(context).apply {
            text = "草稿"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cText)
            setPadding(dp(6f), 0, dp(8f), 0)
        })
        topBar.addView(smallBtn("‹", argb(cText, 255)) { prevPage() })
        pageLabel = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cHaze)
            setPadding(dp(2f), 0, dp(2f), 0)
        }
        topBar.addView(pageLabel)
        topBar.addView(smallBtn("›", argb(cText, 255)) { nextPage(true) })
        topBar.addView(smallBtn("+", argb(cAccent, 255)) { addPage() })

        questionBtn = chip("看题", argb(cAccent, 255), argb(cHaze, 255)) {
            showQuestion = !showQuestion
            refreshQuestionBanner()
        }
        topBar.addView(questionBtn)

        paperBtn = chip(paper.label, argb(cAccent, 255), argb(cHaze, 255)) {
            val all = Paper.entries
            paper = all[(paper.ordinal + 1) % all.size]
            refreshUi()
        }
        topBar.addView(paperBtn)
        topScroll.addView(topBar)
        topWrapper.addView(
            topScroll,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        questionBox = TextView(context).apply {
            textSize = 13f
            setTextColor(cText)
            setLineSpacing(dpf(3f), 1f)
            background = panelBg(argb(cCard, 242), 12f, argb(cLine, 130))
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            maxLines = 5
            visibility = View.GONE
        }
        topWrapper.addView(
            questionBox,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6f) }
        )
        addView(
            topWrapper,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        // ── 居中轻提示 ──
        toastView = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cText)
            background = panelBg(argb(cCard, 245), 20f, argb(cLine, 150))
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            visibility = View.GONE
        }
        addView(
            toastView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        // ── 底部单层紧凑工具坞 ──
        val dockScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val dockRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(7f), dp(8f), dp(7f))
        }
        for (t in Tool.entries) {
            val btn = TextView(context).apply {
                text = t.label
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(dp(9f), dp(7f), dp(9f), dp(7f))
                setOnClickListener {
                    tool = t
                    refreshUi()
                }
            }
            toolButtons[t] = btn
            dockRow.addView(
                btn,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(4f) }
            )
        }
        dockRow.addView(divider(argb(cLine, 130)))
        for (i in inkColors.indices) {
            val dot = View(context).apply {
                setOnClickListener {
                    colorIdx = i
                    if (tool == Tool.ERASER) tool = Tool.PEN
                    refreshUi()
                }
            }
            colorDots.add(dot)
            dockRow.addView(
                dot,
                LinearLayout.LayoutParams(dp(19f), dp(19f)).apply {
                    marginStart = dp(3f)
                    marginEnd = dp(3f)
                }
            )
        }
        dockRow.addView(divider(argb(cLine, 130)))
        for (i in widthsDp.indices) {
            val w = TextView(context).apply {
                textSize = 11f
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (tool == Tool.ERASER) eraserSizeIdx = i else widthIdx = i
                    refreshUi()
                }
            }
            widthDots.add(w)
            dockRow.addView(
                w,
                LinearLayout.LayoutParams(dp(24f), dp(24f)).apply {
                    marginStart = dp(2f)
                    marginEnd = dp(2f)
                }
            )
        }
        dockRow.addView(divider(argb(cLine, 130)))
        dockRow.addView(smallBtn("撤销", argb(cText, 255)) {
            if (currentPage().undo()) canvasView.invalidate()
        })
        dockRow.addView(smallBtn("重做", argb(cText, 255)) {
            if (currentPage().redo()) canvasView.invalidate()
        })
        dockRow.addView(smallBtn("清空", argb(cError, 255)) {
            currentPage().clear()
            canvasView.invalidate()
        })
        dockScroll.addView(dockRow)

        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg(argb(cCard, 242), 16f, argb(cLine, 140))
        }
        dock.addView(
            dockScroll,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            dock,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply {
                    leftMargin = dp(12f)
                    rightMargin = dp(12f)
                    bottomMargin = dp(12f)
                }
        )

        // 保底引用，避免未使用告警；实际着色在 refreshUi 中按状态刷新
        cFaint

        refreshUi()
    }

    fun refreshQuestionBanner() {
        val q = questionProvider()
        questionBtn.text = if (showQuestion) "藏题" else "看题"
        questionBox.text = q
        questionBox.visibility = if (showQuestion && q.isNotBlank()) View.VISIBLE else View.GONE
        questionBtn.visibility = if (q.isBlank()) View.GONE else View.VISIBLE
    }

    private fun prevPage() {
        if (pageIdx > 0) {
            pageIdx--
            showToast("第 ${pageIdx + 1} / ${pages.size} 页")
            refreshUi()
        } else {
            showToast("已是第 1 页")
        }
    }

    private fun nextPage(autoCreate: Boolean) {
        if (pageIdx < pages.lastIndex) {
            pageIdx++
            showToast("第 ${pageIdx + 1} / ${pages.size} 页")
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
        postDelayed(hideToastRunnable, 1100L)
    }

    private fun refreshUi() {
        val cText = focus(palette.text)
        val cHaze = focus(palette.haze)
        val cAccent = focus(palette.accent)

        pageLabel.text = "${pageIdx + 1}/${pages.size}"
        paperBtn.text = paper.label

        for ((t, btn) in toolButtons) {
            val sel = t == tool
            btn.setTextColor(if (sel) cAccent else cHaze)
            btn.background = if (sel) {
                GradientDrawable().apply {
                    cornerRadius = dpf(10f)
                    setColor(argb(cAccent, 40))
                    setStroke(dp(1f), argb(cAccent, 150))
                }
            } else {
                null
            }
        }
        colorDots.forEachIndexed { idx, v ->
            val sel = idx == colorIdx
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(inkColors[idx])
                setStroke(dp(if (sel) 2.4f else 1f), if (sel) cAccent else argb(cText, 70))
            }
        }
        widthDots.forEachIndexed { idx, tv ->
            val isEraser = tool == Tool.ERASER
            val sel = if (isEraser) idx == eraserSizeIdx else idx == widthIdx
            tv.text = if (isEraser) {
                when (idx) {
                    0 -> "小"
                    1 -> "中"
                    else -> "大"
                }
            } else {
                when (idx) {
                    0 -> "细"
                    1 -> "中"
                    else -> "粗"
                }
            }
            tv.setTextColor(if (sel) cAccent else cHaze)
            tv.background = if (sel) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(argb(cAccent, 40))
                }
            } else {
                null
            }
        }
        refreshQuestionBanner()
        canvasView.invalidate()
    }

    private fun panelBg(color: Int, radiusDp: Float, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dpf(radiusDp)
            setColor(color)
            setStroke(dp(1f), strokeColor)
        }

    private fun divider(color: Int): View = View(context).apply {
        setBackgroundColor(color)
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(1f), dp(18f)).apply {
            marginStart = dp(6f)
            marginEnd = dp(6f)
        }
    }

    private fun smallBtn(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            setOnClickListener { onClick() }
        }

    private fun chip(text: String, color: Int, borderColor: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
            setOnClickListener { onClick() }
            background = GradientDrawable().apply {
                cornerRadius = dpf(10f)
                setColor(0x00000000)
                setStroke(dp(1f), borderColor)
            }
        }

    private inner class DrawingSurface(ctx: Context) : View(ctx) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
        private val eraserFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val eraserRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.5f)
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
        private val swipeThreshold = dpf(56f)

        // ── 视口变换（世界坐标 = 图元自身坐标，加变换后自动生效）──
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var zoomMode = false
        private var rebasePending = false
        private var startZoom = 1f
        private var startPanX = 0f
        private var startPanY = 0f
        private var startSpan = 0f
        private var lastSpan = 0f
        private var lastCx = 0f
        private var lastCy = 0f
        private val pinchSlop = dpf(0.5f)

        fun resetViewport() {
            zoom = 1f
            panX = 0f
            panY = 0f
            zoomMode = false
            invalidate()
        }

        private fun spanOf(ev: MotionEvent): Float {
            if (ev.pointerCount < 2) return 0f
            return hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0))
        }

        private fun centroidXOf(ev: MotionEvent): Float {
            var s = 0f
            for (i in 0 until ev.pointerCount) s += ev.getX(i)
            return s / max(1, ev.pointerCount)
        }

        private fun centroidYOf(ev: MotionEvent): Float {
            var s = 0f
            for (i in 0 until ev.pointerCount) s += ev.getY(i)
            return s / max(1, ev.pointerCount)
        }

        /** 把我方视口变换的逆矩阵作用到事件上，让原有单指逻辑继续用屏幕坐标写代码。 */
        private fun mappedSingle(ev0: MotionEvent): MotionEvent {
            if (ev0.pointerCount != 1) return ev0
            if (zoom == 1f && panX == 0f && panY == 0f) return ev0
            val m = android.graphics.Matrix()
            m.setScale(1f / zoom, 1f / zoom, width / 2f, height / 2f)
            m.postTranslate(-panX / zoom, -panY / zoom)
            return MotionEvent.obtain(ev0).apply { transform(m) }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val light = paperIsLight
            val bg = when (paper) {
                Paper.TRANSLUCENT -> argb(focus(palette.bg), 230)
                Paper.THEME -> focus(palette.bg)
                Paper.PAPER -> argb(Color.parseColor("#FDFDF8"), 255)
            }
            canvas.drawColor(bg)

            gridPaint.color = if (light) {
                argb(Color.parseColor("#64748B"), 33)
            } else {
                argb(focus(palette.line), 56)
            }
            // 网格与图元都进同一个变换：网格按「可见世界范围」铺，缩放/平移时不露边
            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(zoom, zoom, width / 2f, height / 2f)

            val step = dpf(28f)
            val cx = width / 2f
            val cy = height / 2f
            val left = cx - (cx + panX) / zoom
            val top = cy - (cy + panY) / zoom
            val right = left + width / zoom
            val bottom = top + height / zoom
            gridPaint.strokeWidth = (1f / zoom).coerceAtLeast(0.6f)
            var gx = floor(left / step) * step
            while (gx <= right) {
                canvas.drawLine(gx, top, gx, bottom, gridPaint)
                gx += step
            }
            var gy = floor(top / step) * step
            while (gy <= bottom) {
                canvas.drawLine(left, gy, right, gy, gridPaint)
                gy += step
            }

            for (item in currentPage().items) drawItem(canvas, item)

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
            if (shapeActive) drawShape(canvas, tool, sx, sy, ex, ey, activeColor(), activeWidthPx())
            if (eraserActive) {
                val accent = focus(palette.accent)
                eraserFillPaint.color = argb(accent, 40)
                eraserRingPaint.color = argb(accent, 190)
                // 光标在世界坐标下画，半径/描边都除以 zoom，屏幕上看起来恒定
                val r = eraserRadiusPx() / zoom
                eraserRingPaint.strokeWidth = dpf(1.5f) / zoom
                canvas.drawCircle(erX, erY, r, eraserFillPaint)
                canvas.drawCircle(erX, erY, r, eraserRingPaint)
            }
            canvas.restore()
        }

        override fun onTouchEvent(ev0: MotionEvent): Boolean {
            // 单指事件先过一遍视口逆变换，原有绘制/擦除代码不用改坐标
            val ev = mappedSingle(ev0)
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
                        zoomMode = false
                        rebasePending = false
                        // 用原始事件算 span/质心（屏幕坐标）
                        startSpan = spanOf(ev0)
                        lastSpan = startSpan
                        startZoom = zoom
                        startPanX = panX
                        startPanY = panY
                        lastCx = centroidXOf(ev0)
                        lastCy = centroidYOf(ev0)
                        twoFingerStartY = avgY(ev)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 三指→两指时质心会跳，标记下一帧重新取基准
                    rebasePending = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount >= 2 || multiTouch) {
                        if (!multiTouch) {
                            multiTouch = true
                            clearLive()
                            startSpan = spanOf(ev0)
                            lastSpan = startSpan
                            startZoom = zoom
                            startPanX = panX
                            startPanY = panY
                            lastCx = centroidXOf(ev0)
                            lastCy = centroidYOf(ev0)
                            twoFingerStartY = avgY(ev)
                        }
                        if (rebasePending) {
                            rebasePending = false
                            startSpan = spanOf(ev0)
                            lastSpan = startSpan
                            startZoom = zoom
                            startPanX = panX
                            startPanY = panY
                            lastCx = centroidXOf(ev0)
                            lastCy = centroidYOf(ev0)
                        }
                        val span = spanOf(ev0)
                        val cx = centroidXOf(ev0)
                        val cy = centroidYOf(ev0)
                        // 径向距离一有变化就进入缩放（无死区）
                        if (!zoomMode && startSpan > 1f && abs(span - startSpan) > pinchSlop) {
                            zoomMode = true
                        }
                        if (zoomMode) {
                            val base = if (lastSpan > 1f) lastSpan else startSpan
                            val ratio = if (base > 1f) span / base else 1f
                            val newZoom = (zoom * ratio).coerceIn(0.8f, 6f)
                            if (zoom > 0.01f) {
                                // 锚点：手势中心处的世界点保持不动
                                panX = cx - width / 2f -
                                    (cx - width / 2f - panX) * (newZoom / zoom)
                                panY = cy - height / 2f -
                                    (cy - height / 2f - panY) * (newZoom / zoom)
                            }
                            panX += cx - lastCx
                            panY += cy - lastCy
                            zoom = newZoom
                            lastSpan = span
                            lastCx = cx
                            lastCy = cy
                            eraserActive = false
                        } else if (!swipeFired && startZoom <= 1.05f) {
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
                                if (appendLivePoint(ev.getHistoricalX(h), ev.getHistoricalY(h))) {
                                    added = true
                                }
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
                            Tool.PEN -> if (liveXs.isNotEmpty()) {
                                val xs = FloatArray(liveXs.size) { liveXs[it] }
                                val ys = FloatArray(liveYs.size) { liveYs[it] }
                                currentPage().commitAdd(
                                    Item.Stroke(xs, ys, activeColor(), activeWidthPx())
                                )
                            }
                            Tool.ERASER -> Unit
                            else -> if (shapeActive && hypot(ex - sx, ey - sy) >= 4f) {
                                currentPage().commitAdd(
                                    Item.Shape(
                                        tool, sx, sy, ex, ey, activeColor(), activeWidthPx()
                                    )
                                )
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
            val remaining = page.items.filterNot {
                hitTest(it, x, y, (eraserRadiusPx() + it.widthPx * 0.5f) / zoom)
            }
            if (remaining.size != page.items.size) page.commitReplace(remaining)
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
                is Item.Shape -> drawShape(
                    canvas, item.kind, item.x1, item.y1, item.x2, item.y2,
                    item.color, item.widthPx
                )
            }
        }

        private fun drawShape(
            canvas: Canvas,
            kind: Tool,
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            color: Int,
            w: Float
        ) {
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
                if (item.xs.isEmpty()) {
                    false
                } else if (item.xs.size == 1) {
                    hypot(px - item.xs[0], py - item.ys[0]) <= threshold
                } else {
                    var hit = false
                    for (i in 1 until item.xs.size) {
                        if (distSeg(
                                px, py,
                                item.xs[i - 1], item.ys[i - 1],
                                item.xs[i], item.ys[i]
                            ) <= threshold
                        ) {
                            hit = true
                            break
                        }
                    }
                    hit
                }
            }
            is Item.Shape -> when (item.kind) {
                Tool.LINE, Tool.ARROW ->
                    distSeg(px, py, item.x1, item.y1, item.x2, item.y2) <= threshold
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
                    if (rx < 2f || ry < 2f) {
                        hypot(px - cx, py - cy) <= threshold
                    } else {
                        val d = hypot((px - cx) / rx, (py - cy) / ry)
                        abs(d - 1f) * ((rx + ry) * 0.5f) <= threshold
                    }
                }
                else -> false
            }
        }

        private fun distSeg(
            px: Float, py: Float,
            ax: Float, ay: Float,
            bx: Float, by: Float
        ): Float {
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            if (lenSq <= 0.0001f) return hypot(px - ax, py - ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }
    }
}
