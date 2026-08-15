package com.focusguard.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.focusguard.app.data.*
import com.focusguard.app.service.MemoReminder
import com.focusguard.app.ui.theme.FocusColors
import java.text.SimpleDateFormat
import java.time.*
import java.util.Date
import java.util.Locale

/**
 * 分享/通知跳转过来的「待导入文本」桥。
 * MainActivity 收到 ACTION_SEND 或 EXTRA_OPEN_MEMO 时写入，本页消费后清空。
 */
object MemoImportBridge {
    @Volatile
    var pendingText: String? = null
}

/** 编辑器打开目标：新增或编辑。 */
sealed interface EditorTarget {
    object New : EditorTarget
    data class Edit(val item: MemoItem) : EditorTarget
}

/**
 * 独立备忘录页面 —— 待办列表 + 完成热力图统计 + 个性化外观 + 第三方导入。
 *
 * 视觉遵循 DESIGN.md「纸墨时间」：细边框玻璃卡、琥珀唯一强调、
 * 每屏至多一个实底主按钮（FAB）、eyebrow 标签风格。
 */
@Composable
fun MemoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val memoStore = remember { MemoStore(context) }
    val memoPrefs = remember { MemoPrefs(context) }
    val settings = remember { Settings(context) }
    val palette = remember(settings.themeMode) {
        FocusColors.paletteFor(settings.themeMode, context)
    }

    var items by remember { mutableStateOf(memoStore.getAll()) }
    var tab by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var showStyle by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var styleTick by remember { mutableIntStateOf(0) } // 外观变化强制重组

    /** 数据变更统一入口：刷新列表 + 重排到期提醒。 */
    fun refresh() {
        items = memoStore.getAll()
        MemoReminder.sync(context)
    }

    // 回到前台（从其他页返回）时刷新——首页/AI 对话里改的待办立即同步
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 分享导入桥：MainActivity 写入的文本，本页启动时消费
    LaunchedEffect(Unit) {
        MemoImportBridge.pendingText?.let { text ->
            MemoImportBridge.pendingText = null
            showImport = true
            ImportPreviewHolder.text = text
        }
    }

    // 自定义样式解析
    val style = remember(styleTick, memoPrefs.cardStyle, memoPrefs.textColor, memoPrefs.fontScale) {
        MemoStyle.resolve(memoPrefs, palette)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = palette.text)
                }
                Text(
                    text = "备忘录",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.text
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showImport = true }) {
                    Icon(Icons.Default.FileDownload, "导入", tint = palette.haze)
                }
                IconButton(onClick = { showStyle = true }) {
                    Icon(Icons.Default.Palette, "外观", tint = palette.haze)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editor = EditorTarget.New },
                shape = RoundedCornerShape(14.dp),
                containerColor = palette.accent,
                contentColor = palette.bg
            ) {
                Icon(Icons.Default.Add, "新增待办")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // ── Tab 切换 ───────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.card.copy(alpha = 0.6f))
                    .border(1.dp, palette.line.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(3.dp)
            ) {
                listOf("待办" to 0, "完成统计" to 1).forEach { (label, index) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (tab == index) palette.accentDeep.copy(alpha = 0.35f) else Color.Transparent)
                            .clickable { tab = index }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (tab == index) palette.text else palette.haze
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (tab == 0) {
                TodoTab(
                    items = items,
                    palette = palette,
                    style = style,
                    exactAlarm = MemoReminder.canScheduleExact(context),
                    onToggle = { id ->
                        memoStore.toggleDone(id)
                        refresh()
                    },
                    onEdit = { item ->
                        editor = EditorTarget.Edit(item)
                    },
                    onOpenExactAlarmSetting = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    },
                    onOpenStats = { tab = 1 },
                    onOpenStyle = { showStyle = true }
                )
            } else {
                StatsTab(palette = palette, memoStore = memoStore)
            }
        }
    }

    // ── 新增 / 编辑对话框 ────────────────────────────────
    editor?.let { target ->
        MemoEditorDialog(
            initial = (target as? EditorTarget.Edit)?.item,
            palette = palette,
            onDismiss = { editor = null },
            onSave = { id, text, priority, dueAt ->
                if (id == null) memoStore.add(text, priority, dueAt)
                else memoStore.update(id, text, priority, dueAt)
                refresh()
                editor = null
            },
            onDelete = { id ->
                memoStore.remove(id)
                MemoReminder.cancel(context, id)
                refresh()
                editor = null
            }
        )
    }

    // ── 外观面板 ───────────────────────────────────────
    if (showStyle) {
        StylePanelDialog(
            palette = palette,
            prefs = memoPrefs,
            exactAlarm = MemoReminder.canScheduleExact(context),
            onChanged = {
                styleTick++
                refresh()
            },
            onDismiss = { showStyle = false },
            onOpenExactAlarmSetting = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
        )
    }

    // ── 导入面板 ───────────────────────────────────────
    if (showImport) {
        ImportDialog(
            palette = palette,
            onDismiss = { showImport = false; ImportPreviewHolder.text = null },
            onImport = { lines ->
                val added = memoStore.addBatch(lines)
                refresh()
                Toast.makeText(context, "已导入 $added 条待办", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/** 导入预览文本暂存（文件/剪贴板/分享三条路径共用）。 */
object ImportPreviewHolder {
    @Volatile
    var text: String? = null
}

/** 备忘录个性化样式解析结果。 */
data class MemoStyle(
    val cardBg: Color,
    val cardLine: Color,
    val text: Color,
    val sub: Color,
    val fontSize: Float
) {
    companion object {
        fun resolve(prefs: MemoPrefs, palette: FocusColors.Palette): MemoStyle {
            val (bg, defaultText) = when (prefs.cardStyle) {
                MemoPrefs.CARD_INK -> Color(0xFF151B22) to Color(0xFFEDE6D6)
                MemoPrefs.CARD_PAPER -> Color(0xFFF0E9DA) to Color(0xFF1D1A14)
                MemoPrefs.CARD_AMBER_NIGHT -> Color(0xFF221A10) to Color(0xFFEDE6D6)
                else -> palette.card.copy(alpha = 0.6f) to palette.text
            }
            val lightCard = prefs.cardStyle == MemoPrefs.CARD_PAPER
            val text = when (prefs.textColor) {
                MemoPrefs.TEXT_PAPER -> Color(0xFFEDE6D6)
                MemoPrefs.TEXT_AMBER -> Color(0xFFE2A65D)
                MemoPrefs.TEXT_SAGE -> Color(0xFF8AAE8C)
                else -> defaultText
            }
            return MemoStyle(
                cardBg = bg,
                cardLine = if (lightCard) Color(0xFFD6CDB9).copy(alpha = 0.8f) else palette.line,
                text = text,
                sub = if (lightCard) Color(0xFF6B6455) else palette.haze,
                fontSize = prefs.itemFontSize()
            )
        }
    }
}

// ══════════════════════════ 待办 Tab ══════════════════════════

@Composable
private fun TodoTab(
    items: List<MemoItem>,
    palette: FocusColors.Palette,
    style: MemoStyle,
    exactAlarm: Boolean,
    onToggle: (Long) -> Unit,
    onEdit: (MemoItem) -> Unit,
    onOpenExactAlarmSetting: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenStyle: () -> Unit
) {
    val pending = items.filter { !it.done }
    val done = items.filter { it.done }
    val today = LocalDate.now()

    val todayDue = pending.count { it.dueDate == today && !it.overdue }
    val todayDone = done.count {
        it.completedAt > 0 && Instant.ofEpochMilli(it.completedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate() == today
    }

    val groups = buildList {
        val overdue = pending.filter { it.overdue }
        val todayList = pending.filter { it.dueDate == today && !it.overdue }
        val tomorrowList = pending.filter { it.dueDate == today.plusDays(1) }
        val futureList = pending.filter { (it.dueDate ?: today.minusDays(1)).isAfter(today.plusDays(1)) }
        val noDue = pending.filter { it.dueAt <= 0 }
        if (overdue.isNotEmpty()) add("已逾期" to overdue)
        if (todayList.isNotEmpty()) add("今天" to todayList)
        if (tomorrowList.isNotEmpty()) add("明天" to tomorrowList)
        if (futureList.isNotEmpty()) add("更晚" to futureList)
        if (noDue.isNotEmpty()) add("待安排" to noDue)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 精确提醒降级提示
        if (!exactAlarm && pending.any { it.dueAt > 0 }) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.accent.copy(alpha = 0.12f))
                        .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenExactAlarmSetting)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.NotificationsActive, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("精确提醒未开启", fontSize = 12.sp, color = palette.text, fontWeight = FontWeight.Medium)
                        Text("到期提醒可能延迟，点击前往系统设置开启", fontSize = 10.sp, color = palette.haze)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = palette.haze, modifier = Modifier.size(16.dp))
                }
            }
        }

        // 概览卡
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(style.cardBg)
                    .border(1.dp, style.cardLine.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OverviewCell("待办中", pending.size.toString(), style, palette)
                OverviewCell("今日到期", todayDue.toString(), style, palette)
                OverviewCell("今日完成", todayDone.toString(), style, palette)
            }
        }

        if (items.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Checklist, null, tint = palette.faint, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("还没有待办", fontSize = 14.sp, color = palette.haze)
                    Text("点右下角添加，AI 提醒与锁机页都会引用它们", fontSize = 11.sp, color = palette.faint)
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = onOpenStyle) {
                        Text("个性化外观", fontSize = 12.sp, color = palette.accent)
                    }
                }
            }
        }

        groups.forEach { (title, groupItems) ->
            item(key = "header_$title") {
                SectionHeader(
                    title = title,
                    count = groupItems.size,
                    titleColor = if (title == "已逾期") palette.error else palette.haze
                )
            }
            items(count = groupItems.size, key = { "item_${groupItems[it].id}" }) { i ->
                MemoItemRow(
                    item = groupItems[i],
                    palette = palette,
                    style = style,
                    onToggle = { onToggle(groupItems[i].id) },
                    onClick = { onEdit(groupItems[i]) }
                )
            }
        }

        if (done.isNotEmpty()) {
            item(key = "header_done") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(title = "已完成", count = done.size, titleColor = palette.faint)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { /* 清除由统计页管理 */ onOpenStats() }) {
                        Text("查看统计", fontSize = 11.sp, color = palette.accent)
                    }
                }
            }
            items(count = done.size, key = { "done_${done[it].id}" }) { i ->
                MemoItemRow(
                    item = done[i],
                    palette = palette,
                    style = style,
                    onToggle = { onToggle(done[i].id) },
                    onClick = { onEdit(done[i]) }
                )
            }
            item {
                Spacer(Modifier.height(72.dp)) // FAB 遮挡留白
            }
        } else {
            item {
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun OverviewCell(label: String, value: String, style: MemoStyle, palette: FocusColors.Palette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            color = style.text
        )
        Text(text = label, fontSize = 10.sp, color = style.sub.copy(alpha = 0.8f))
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, titleColor: Color) {
    Text(
        text = title.uppercase() + " · $count",
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Medium,
        color = titleColor
    )
}

/** 单条待办行：勾选框 + 内容（自定义字号/颜色）+ 标签 + 截止。 */
@Composable
private fun MemoItemRow(
    item: MemoItem,
    palette: FocusColors.Palette,
    style: MemoStyle,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val priorityColor = when (item.priority) {
        2 -> palette.error
        1 -> palette.accent
        else -> palette.faint
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(style.cardBg)
            .border(1.dp, style.cardLine.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (item.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (item.done) "标记未完成" else "标记完成",
                tint = if (item.done) palette.success else if (item.priority == 2) priorityColor else style.sub,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.text,
                fontSize = style.fontSize.sp,
                lineHeight = (style.fontSize + 4).sp,
                color = style.text.copy(alpha = if (item.done) 0.4f else 0.92f),
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val tags = buildList {
                if (item.priority > 0) add(item.priorityLabel())
                if (item.overdue) add("逾期")
                else if (item.dueAt > 0) add(item.dueText())
                if (item.fromAi) add("AI")
                if (item.done && item.completedAt > 0) {
                    add(
                        SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                            .format(Date(item.completedAt)) + " 完成"
                    )
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tags.joinToString(" · "),
                    fontSize = (style.fontSize - 4).coerceAtLeast(9f).sp,
                    color = if (item.overdue) palette.error else style.sub
                )
            }
        }
    }
}

// ══════════════════════════ 统计 Tab ══════════════════════════

@Composable
private fun StatsTab(palette: FocusColors.Palette, memoStore: MemoStore) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    val dayCounts = remember(tick) { memoStore.completionsByDay(98) }
    val streak = remember(tick) { memoStore.completionStreak() }
    val total = remember(tick) { memoStore.historyCount() }
    val today = LocalDate.now()
    val todayCount = dayCounts[today] ?: 0
    val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekCount = dayCounts.filterKeys { !it.isBefore(weekStart) && !it.isAfter(today) }
        .values.sum()
    val selectedItems = remember(selectedDay, tick) {
        selectedDay?.let { memoStore.historyForDay(it) } ?: emptyList()
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 统计卡
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCell("累计完成", total.toString(), palette, Modifier.weight(1f))
                StatCell("连续天数", streak.toString(), palette, Modifier.weight(1f))
                StatCell("今日", todayCount.toString(), palette, Modifier.weight(1f))
                StatCell("本周", weekCount.toString(), palette, Modifier.weight(1f))
            }
        }

        // 热力图
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.card.copy(alpha = 0.6f))
                    .border(1.dp, palette.line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "完成热力图",
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        color = palette.haze,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    Text("最近 14 周", fontSize = 10.sp, color = palette.faint)
                }
                Spacer(Modifier.height(12.dp))
                if (total == 0) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.GridOn, null, tint = palette.faint, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("还没有完成记录", fontSize = 12.sp, color = palette.haze)
                        Spacer(Modifier.height(2.dp))
                        Text("完成一条待办，日历就亮起一格", fontSize = 10.sp, color = palette.faint)
                    }
                } else {
                    CompletionHeatmap(
                        dayCounts = dayCounts,
                        selectedDay = selectedDay,
                        palette = palette,
                        onSelect = { selectedDay = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("少", fontSize = 9.sp, color = palette.faint)
                        Spacer(Modifier.width(4.dp))
                        listOf(0.35f, 0.55f, 0.75f, 1f).forEach { a ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 1.5.dp)
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(palette.accent.copy(alpha = a))
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("多", fontSize = 9.sp, color = palette.faint)
                    }
                }
            }
        }

        // 选中日详情
        selectedDay?.let { day ->
            item(key = "day_detail") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.card.copy(alpha = 0.6f))
                        .border(1.dp, palette.line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${day.monthValue}月${day.dayOfMonth}日",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.text
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "完成 ${selectedItems.size} 项",
                            fontSize = 11.sp,
                            color = palette.success
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Default.Close,
                            "收起",
                            tint = palette.faint,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { selectedDay = null }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (selectedItems.isEmpty()) {
                        Text("这一天没有完成记录", fontSize = 12.sp, color = palette.faint)
                    } else {
                        selectedItems.forEach { item ->
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(palette.accent)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = item.text,
                                    fontSize = 13.sp,
                                    color = palette.text.copy(alpha = 0.9f),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = SimpleDateFormat("HH:mm", Locale.getDefault())
                                        .format(Date(item.completedAt)),
                                    fontSize = 10.sp,
                                    color = palette.faint
                                )
                            }
                        }
                    }
                }
            }
        }

        // 操作
        item {
            if (total > 0) {
                OutlinedButton(
                    onClick = {
                        memoStore.clearHistory()
                        selectedDay = null
                        tick++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.error.copy(alpha = 0.6f))
                ) {
                    Text("清空完成历史", fontSize = 12.sp, color = palette.error)
                }
            }
        }

        item {
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, palette: FocusColors.Palette, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card.copy(alpha = 0.6f))
            .border(1.dp, palette.line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            color = palette.accent
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, fontSize = 10.sp, color = palette.haze)
    }
}

/**
 * GitHub 风格完成热力图：14 周 × 7 天，琥珀色阶。
 * 点击某天查看当天完成内容；今天带描边高亮。
 */
@Composable
private fun CompletionHeatmap(
    dayCounts: Map<LocalDate, Int>,
    selectedDay: LocalDate?,
    palette: FocusColors.Palette,
    onSelect: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    // 窗口起点：包含今天的周往前推 13 周，列对齐到周日
    val windowStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(13)
    val weeks = (0 until 14).map { w ->
        (0 until 7).map { d -> windowStart.plusDays(w * 7L + d) }
    }

    fun cellColor(day: LocalDate): Color {
        val count = dayCounts[day] ?: 0
        if (day.isAfter(today)) return Color.Transparent
        if (count == 0) return palette.line.copy(alpha = 0.4f)
        val alpha = when {
            count >= 7 -> 1f
            count >= 5 -> 0.8f
            count >= 3 -> 0.6f
            else -> 0.38f
        }
        return palette.accent.copy(alpha = alpha)
    }

    Column {
        // 月份标签行
        Row(modifier = Modifier.fillMaxWidth()) {
            weeks.forEachIndexed { w, days ->
                val monthLabel = days.firstOrNull { it.dayOfMonth == 1 }
                    ?.let { "${it.monthValue}月" }
                    ?: if (w == 0) {
                        "${windowStart.monthValue}月"
                    } else null
                Box(modifier = Modifier.width(19.dp)) {
                    if (monthLabel != null) {
                        Text(
                            text = monthLabel,
                            fontSize = 8.sp,
                            color = palette.faint,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // 网格
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            weeks.forEach { days ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    days.forEach { day ->
                        if (!day.isAfter(today)) {
                            val selected = selectedDay == day
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(cellColor(day))
                                    .then(
                                        if (selected) Modifier.border(
                                            1.dp, palette.text.copy(alpha = 0.85f),
                                            RoundedCornerShape(4.dp)
                                        ) else if (day == today) Modifier.border(
                                            1.dp, palette.accent,
                                            RoundedCornerShape(4.dp)
                                        ) else Modifier
                                    )
                                    .clickable { onSelect(day) }
                            )
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 星期提示
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("一", fontSize = 8.sp, color = palette.faint)
            Spacer(Modifier.width(4.dp))
            Text("三", fontSize = 8.sp, color = palette.faint)
            Spacer(Modifier.width(4.dp))
            Text("五", fontSize = 8.sp, color = palette.faint)
            Spacer(Modifier.width(4.dp))
            Text("日", fontSize = 8.sp, color = palette.faint)
            Spacer(Modifier.width(6.dp))
            Text("（列 = 周，行 = 周日→周六）", fontSize = 8.sp, color = palette.faint)
        }
    }
}

// ══════════════════════════ 编辑器对话框 ══════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoEditorDialog(
    initial: MemoItem?,
    palette: FocusColors.Palette,
    onDismiss: () -> Unit,
    onSave: (id: Long?, text: String, priority: Int, dueAt: Long) -> Unit,
    onDelete: (id: Long) -> Unit
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var priority by remember { mutableIntStateOf(initial?.priority ?: 0) }
    var dueAt by remember { mutableStateOf(initial?.dueAt ?: 0L) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val today = LocalDate.now()

    fun quickLabel(ts: Long): String? = when {
        ts <= 0L -> null
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate() == today -> "今天"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增待办" else "编辑待办", fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("事项内容") },
                    placeholder = { Text("例如：完成数学作业第三章") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3
                )

                Text("优先级", fontSize = 12.sp, color = palette.haze)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "普通", 1 to "重要", 2 to "紧急").forEach { (p, label) ->
                        FilterChip(
                            shape = RoundedCornerShape(10.dp),
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text("截止时间", fontSize = 12.sp, color = palette.haze)
                // 快捷项
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val quickOptions = listOf(
                        "今天" to (today.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                        "明天" to (today.plusDays(1).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                        "后天" to (today.plusDays(2).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    )
                    quickOptions.forEach { (label, ts) ->
                        FilterChip(
                            shape = RoundedCornerShape(10.dp),
                            selected = dueAt == ts,
                            onClick = { dueAt = if (dueAt == ts) 0L else ts },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val customSelected = dueAt > 0 && quickLabel(dueAt) == null
                    FilterChip(
                        shape = RoundedCornerShape(10.dp),
                        selected = customSelected,
                        onClick = { showDatePicker = true },
                        label = { Text("自定义时间", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    if (dueAt > 0) {
                        OutlinedButton(
                            onClick = { dueAt = 0L },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("清除", fontSize = 11.sp, color = palette.haze)
                        }
                    }
                }
                // 当前选择回显
                Text(
                    text = when {
                        dueAt <= 0 -> "未设置截止"
                        else -> "截止：" + SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault())
                            .format(Date(dueAt))
                    },
                    fontSize = 11.sp,
                    color = if (dueAt > 0 && dueAt < System.currentTimeMillis()) palette.error else palette.accent
                )

                if (initial != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { onDelete(initial.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除这条待办", fontSize = 12.sp, color = palette.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) onSave(initial?.id, text.trim(), priority, dueAt)
                },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.bg
                ),
                shape = RoundedCornerShape(10.dp)
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = palette.haze)
            }
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(14.dp)
    )

    // ── 日期选择 → 时间选择 两段式 ───────────────────────
    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = if (dueAt > 0) dueAt else null
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { dateMillis ->
                            showDatePicker = false
                            showTimePicker = true
                            PickedDateTime.dateMillis = dateMillis
                        }
                    }
                ) { Text("选时间") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = dateState, showModeToggle = false)
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间", fontSize = 16.sp) },
            text = {
                TimePicker(state = timeState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dateMillis = PickedDateTime.dateMillis ?: System.currentTimeMillis()
                        // DatePicker 返回 UTC 午夜 → 转成本地日期后组合时分
                        val date = Instant.ofEpochMilli(dateMillis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        dueAt = date.atTime(timeState.hour, timeState.minute)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        showTimePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

/** 日期→时间两段选择的中间暂存。 */
object PickedDateTime {
    @Volatile
    var dateMillis: Long? = null
}

// ══════════════════════════ 外观面板 ══════════════════════════

@Composable
private fun StylePanelDialog(
    palette: FocusColors.Palette,
    prefs: MemoPrefs,
    exactAlarm: Boolean,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
    onOpenExactAlarmSetting: () -> Unit
) {
    var fontScale by remember { mutableIntStateOf(prefs.fontScale) }
    var textColor by remember { mutableIntStateOf(prefs.textColor) }
    var cardStyle by remember { mutableIntStateOf(prefs.cardStyle) }
    var lead by remember { mutableIntStateOf(prefs.reminderLeadMinutes) }

    fun save() {
        prefs.fontScale = fontScale
        prefs.textColor = textColor
        prefs.cardStyle = cardStyle
        prefs.reminderLeadMinutes = lead
        onChanged()
    }

    AlertDialog(
        onDismissRequest = { save(); onDismiss() },
        title = { Text("备忘录外观", fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 字号
                SectionLabel("文字大小", palette)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        MemoPrefs.FONT_SMALL to "小",
                        MemoPrefs.FONT_NORMAL to "标准",
                        MemoPrefs.FONT_LARGE to "大"
                    ).forEach { (v, label) ->
                        FilterChip(
                            shape = RoundedCornerShape(10.dp),
                            selected = fontScale == v,
                            onClick = { fontScale = v },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 文字颜色
                SectionLabel("文字颜色", palette)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val colors = listOf(
                        MemoPrefs.TEXT_AUTO to ("跟随卡片" to Color.Transparent),
                        MemoPrefs.TEXT_PAPER to ("暖纸白" to Color(0xFFEDE6D6)),
                        MemoPrefs.TEXT_AMBER to ("琥珀" to Color(0xFFE2A65D)),
                        MemoPrefs.TEXT_SAGE to ("灰绿" to Color(0xFF8AAE8C))
                    )
                    colors.forEach { (v, pair) ->
                        val (label, c) = pair
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(palette.card)
                                    .border(
                                        if (textColor == v) 2.dp else 1.dp,
                                        if (textColor == v) palette.accent else palette.line,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable { textColor = v },
                                contentAlignment = Alignment.Center
                            ) {
                                if (v == MemoPrefs.TEXT_AUTO) {
                                    Text("A", fontSize = 13.sp, color = palette.text, fontWeight = FontWeight.Bold)
                                } else {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(label, fontSize = 9.sp, color = palette.haze)
                        }
                    }
                }

                // 卡片背景
                SectionLabel("卡片背景", palette)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cards = listOf(
                        MemoPrefs.CARD_AUTO to ("跟随主题" to palette.card.copy(alpha = 0.6f)),
                        MemoPrefs.CARD_INK to ("墨" to Color(0xFF151B22)),
                        MemoPrefs.CARD_PAPER to ("暖纸" to Color(0xFFF0E9DA)),
                        MemoPrefs.CARD_AMBER_NIGHT to ("琥珀夜" to Color(0xFF221A10))
                    )
                    cards.forEach { (v, pair) ->
                        val (label, c) = pair
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(c)
                                    .border(
                                        if (cardStyle == v) 2.dp else 1.dp,
                                        if (cardStyle == v) palette.accent else palette.line,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable { cardStyle = v }
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(label, fontSize = 9.sp, color = palette.haze)
                        }
                    }
                }

                // 提醒提前量
                SectionLabel("到期提醒", palette)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "准时", 5 to "提前5分", 15 to "提前15分").forEach { (v, label) ->
                        FilterChip(
                            shape = RoundedCornerShape(10.dp),
                            selected = lead == v,
                            onClick = { lead = v },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (!exactAlarm) {
                    Text(
                        text = "精确闹钟权限未开启，提醒可能延迟",
                        fontSize = 10.sp,
                        color = palette.error,
                        modifier = Modifier.clickable(onClick = onOpenExactAlarmSetting)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { save(); onDismiss() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.bg
                ),
                shape = RoundedCornerShape(10.dp)
            ) { Text("完成") }
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun SectionLabel(text: String, palette: FocusColors.Palette) {
    Text(
        text = text,
        fontSize = 10.sp,
        letterSpacing = 1.5.sp,
        color = palette.haze,
        fontWeight = FontWeight.Medium
    )
}

// ══════════════════════════ 导入面板 ══════════════════════════

@Composable
private fun ImportDialog(
    palette: FocusColors.Palette,
    onDismiss: () -> Unit,
    onImport: (List<String>) -> Unit
) {
    val context = LocalContext.current
    // 分享桥文本：外部（分享/通知点击）进入时，随 dialog 首次组合读入
    var preview by remember { mutableStateOf(ImportPreviewHolder.text) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "无法读取文件", Toast.LENGTH_SHORT).show()
            } else {
                preview = text
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入待办", fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "从其他备忘录/便签应用导入：复制文本后粘贴，或选择导出的 txt/csv 文件。" +
                        "每行一条；行内可用 | 分隔优先级与截止，如「写数学作业|紧急|明天」。",
                    fontSize = 11.sp,
                    color = palette.haze,
                    lineHeight = 16.sp
                )
                OutlinedButton(
                    onClick = {
                        val clip = runCatching {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        }.getOrNull()
                        if (clip.isNullOrBlank()) {
                            Toast.makeText(context, "剪贴板没有文本", Toast.LENGTH_SHORT).show()
                        } else {
                            preview = clip
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("从剪贴板粘贴", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("text/*", "application/csv")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("从文件导入（txt / csv）", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = palette.haze)
            }
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(14.dp)
    )

    // 预览确认
    preview?.let { raw ->
        val lines = remember(raw) {
            raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("确认导入 ${lines.size} 条", fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    lines.take(8).forEach {
                        Text("· $it", fontSize = 12.sp, color = palette.text.copy(alpha = 0.85f), lineHeight = 18.sp)
                    }
                    if (lines.size > 8) {
                        Text("……还有 ${lines.size - 8} 条", fontSize = 11.sp, color = palette.faint)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("与现有待办重复的行会自动跳过", fontSize = 10.sp, color = palette.faint)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImport(lines)
                        preview = null
                        ImportPreviewHolder.text = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.bg
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { preview = null; ImportPreviewHolder.text = null }) {
                    Text("取消", color = palette.haze)
                }
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(14.dp)
        )
    }
}
