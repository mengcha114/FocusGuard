package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LogStore
import com.focusguard.app.data.Settings

@Composable
fun HomeScreen(
    serviceRunning: Boolean,
    onStartGuard: () -> Unit,
    onStopGuard: () -> Unit,
    onTestDetection: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val logStore = remember { LogStore(context) }
    val settings = remember { Settings(context) }

    val todayChecks = remember { logStore.getTodayCheckCount() }
    val focusScore = remember { logStore.getTodayFocusScore() }
    val violations = remember { logStore.getTodayViolations().size }
    val recentLogs = remember { logStore.getAllLogs().take(5) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Text(
                text = "专注卫士",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Status card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (serviceRunning) Color(0xFF1B5E20) else Color(0xFF37474F)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (serviceRunning) Icons.Default.Shield else Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (serviceRunning) "守护中" else "已暂停",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (serviceRunning) "AI 正在监控您的专注状态" else "点击下方按钮开始守护",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )

                    // ── 守护健康诊断 ──────────────────────────
                    // 排查"守护开着但不出日志"：显示巡检心跳与上次 AI 检测时间。
                    // 心跳超过 60 秒未更新说明检测循环已死，会红字告警。
                    if (serviceRunning) {
                        var healthTick by remember { mutableIntStateOf(0) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                kotlinx.coroutines.delay(2000L)
                                healthTick++
                            }
                        }
                        val health = remember(healthTick) {
                            com.focusguard.app.service.MonitorService.healthText()
                        }
                        val alive = remember(healthTick) {
                            com.focusguard.app.service.MonitorService.isLoopAlive()
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = health,
                            fontSize = 11.sp,
                            color = if (alive) Color.White.copy(alpha = 0.55f)
                                    else Color(0xFFFFCDD2)
                        )
                    }
                }
            }
        }


        // Stats row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "今日检测",
                    value = todayChecks.toString(),
                    icon = Icons.Default.Analytics,
                    color = Color(0xFF1976D2),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "专注指数",
                    value = "$focusScore%",
                    icon = Icons.Default.Star,
                    color = Color(0xFF388E3C),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "违规次数",
                    value = violations.toString(),
                    icon = Icons.Default.Warning,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Action buttons（放在显眼位置，不被备忘录压到下面）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (serviceRunning) {
                    Button(
                        onClick = onStopGuard,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD32F2F)
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("停止守护", fontSize = 16.sp)
                    }
                } else {
                    Button(
                        onClick = onStartGuard,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF388E3C)
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("开始守护", fontSize = 16.sp)
                    }
                }

                OutlinedButton(
                    onClick = onTestDetection,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFF7C4DFF), Color(0xFF448AFF))
                        )
                    )
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("测试识别", fontSize = 16.sp)
                }
            }
        }

        // ── 备忘录 ──────────────────────────────────────────────
        item {
            MemoCard()
        }

        // Recent logs
        item {
            Text(
                text = "最近检测",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        if (recentLogs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF263238)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无检测记录",
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        } else {
            items(recentLogs.size) { index ->
                val log = recentLogs[index]
                LogItem(
                    time = log.getTimeFormatted(),
                    classification = log.classification,
                    reason = log.reason,
                    confidence = log.confidence
                )
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun LogItem(
    time: String,
    classification: String,
    reason: String,
    confidence: Float
) {
    val color = when (classification) {
        "STUDY_WORK" -> Color(0xFF4CAF50)
        "ENTERTAINMENT" -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }
    val label = when (classification) {
        "STUDY_WORK" -> "学习/工作"
        "ENTERTAINMENT" -> "娱乐"
        else -> "中性"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF263238)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = time,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = reason,
                    fontSize = 14.sp,
                    color = Color.White,
                    maxLines = 2
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                Text(
                    text = "${(confidence * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = color.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * 备忘录卡片：首页显眼位置展示未完成待办。
 *
 * 支持：勾选完成、优先级色标、截止时间提示（逾期红色）、AI 添加标记。
 * 「管理」进入完整编辑面板（新增/改优先级/设截止/删除/清理已完成）。
 */
@Composable
private fun MemoCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val memoStore = remember { MemoStore(context) }
    var items by remember { mutableStateOf(memoStore.getAll()) }
    var showManage by remember { mutableStateOf(false) }

    val pending = items.filter { !it.done }
    val doneCount = items.size - pending.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2438))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Checklist,
                        contentDescription = null,
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "备忘录",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
                Text(
                    text = if (items.isEmpty()) "暂无待办"
                    else "待办 ${pending.size} · 已完成 $doneCount",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }

            Spacer(Modifier.height(10.dp))

            if (pending.isEmpty()) {
                Text(
                    text = if (items.isEmpty()) {
                        "添加待办事项，AI 提醒和锁机页都会引用它们督促你"
                    } else {
                        "全部完成了，做得不错"
                    },
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            } else {
                pending.take(3).forEach { item ->
                    MemoRow(
                        item = item,
                        onToggle = {
                            memoStore.toggleDone(item.id)
                            items = memoStore.getAll()
                        }
                    )
                }
                if (pending.size > 3) {
                    Text(
                        text = "…还有 ${pending.size - 3} 条",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showManage = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("管理备忘录", color = Color(0xFFD0BCFF), fontSize = 13.sp)
            }
        }
    }

    if (showManage) {
        MemoManageDialog(
            memoStore = memoStore,
            onDismiss = {
                showManage = false
                items = memoStore.getAll()
            }
        )
    }
}

/** 单条待办：勾选框 + 优先级色标 + 文本 + 截止/AI 标记。 */
@Composable
private fun MemoRow(item: MemoItem, onToggle: () -> Unit) {
    val priorityColor = when (item.priority) {
        2 -> Color(0xFFEF5350)
        1 -> Color(0xFFFFB74D)
        else -> Color(0xFF7C4DFF)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.size(22.dp)) {
            Icon(
                imageVector = if (item.done) Icons.Default.CheckCircle
                else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (item.done) "标记未完成" else "标记完成",
                tint = if (item.done) Color(0xFF66BB6A) else priorityColor,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.text,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = if (item.done) 0.35f else 0.85f),
                textDecoration = if (item.done) {
                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                } else null,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            val tags = buildList {
                if (item.priority > 0) add(item.priorityLabel())
                item.dueText()?.let { add(it) }
                if (item.fromAi) add("AI 添加")
            }
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString(" · "),
                    fontSize = 10.sp,
                    color = if (item.overdue) Color(0xFFEF5350)
                    else Color.White.copy(alpha = 0.4f)
                )
            }
        }
    }
}

/** 备忘录管理面板：新增（含优先级/截止）、勾选、删除、清理已完成。 */
@Composable
private fun MemoManageDialog(memoStore: MemoStore, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf(memoStore.getAll()) }
    var newText by remember { mutableStateOf("") }
    var newPriority by remember { mutableIntStateOf(0) }
    var newDue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备忘录", fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = "还没有待办事项",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                } else {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                MemoRow(
                                    item = item,
                                    onToggle = {
                                        memoStore.toggleDone(item.id)
                                        items = memoStore.getAll()
                                    }
                                )
                            }
                            IconButton(
                                onClick = {
                                    memoStore.remove(item.id)
                                    items = memoStore.getAll()
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "删除",
                                    tint = Color(0xFFF44336),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                    if (items.any { it.done }) {
                        TextButton(
                            onClick = {
                                memoStore.clearDone()
                                items = memoStore.getAll()
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "清理已完成",
                                color = Color(0xFF8AB4F8),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    label = { Text("新增待办") },
                    placeholder = { Text("例如：完成数学作业第三章") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Text("优先级", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "普通", 1 to "重要", 2 to "紧急").forEach { (p, label) ->
                        FilterChip(
                            selected = newPriority == p,
                            onClick = { newPriority = p },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                OutlinedTextField(
                    value = newDue,
                    onValueChange = { newDue = it },
                    label = { Text("截止（可选）") },
                    placeholder = { Text("30m / 2h / 今天 / 明天") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newText.isNotBlank()) {
                        memoStore.add(
                            text = newText,
                            priority = newPriority,
                            dueAt = MemoStore.parseDueText(newDue)
                        )
                        newText = ""
                        newPriority = 0
                        newDue = ""
                        items = memoStore.getAll()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F378B))
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = Color.White.copy(alpha = 0.6f))
            }
        },
        containerColor = Color(0xFF241F27),
        shape = RoundedCornerShape(20.dp)
    )
}
