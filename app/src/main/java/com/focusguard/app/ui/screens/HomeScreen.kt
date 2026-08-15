package com.focusguard.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LogStore
import com.focusguard.app.data.MemoItem
import com.focusguard.app.data.MemoStore
import com.focusguard.app.data.Settings

@Composable
fun HomeScreen(
    serviceRunning: Boolean,
    onStartGuard: () -> Unit,
    onStopGuard: () -> Unit,
    onTestDetection: () -> Unit,
    onOpenMemo: () -> Unit
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
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Status card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)), colors = CardDefaults.cardColors(
                    containerColor = if (serviceRunning) {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
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
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (serviceRunning) "守护中" else "已暂停",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (serviceRunning) "AI 正在监控您的专注状态" else "点击下方按钮开始守护",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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
                            color = if (alive) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.error
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
                    color = MaterialTheme.colorScheme.tertiary,
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
                        shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
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
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            MaterialTheme.colorScheme.primary
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
            MemoCard(onOpenMemo = onOpenMemo)
        }

        // Recent logs
        item {
            Text(
                text = "最近检测",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (recentLogs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
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
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
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
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)), colors = CardDefaults.cardColors(
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
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
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
        "STUDY_WORK" -> MaterialTheme.colorScheme.tertiary
        "ENTERTAINMENT" -> Color(0xFFF44336)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (classification) {
        "STUDY_WORK" -> "学习/工作"
        "ENTERTAINMENT" -> "娱乐"
        else -> "中性"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
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
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = reason,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
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
 * 点击卡片进入独立备忘录页（完整编辑 / 统计热力图 / 导入 / 外观）。
 * 从其他页返回首页时自动刷新（AI 对话里新增的待办立即显示）。
 */
@Composable
private fun MemoCard(onOpenMemo: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val memoStore = remember { MemoStore(context) }
    var items by remember { mutableStateOf(memoStore.getAll()) }

    // 回前台刷新：AI 对话/锁机页勾选的待办，返回首页立即同步
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                items = memoStore.getAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pending = items.filter { !it.done }
    val doneCount = items.size - pending.size

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenMemo),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "备忘录",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = if (items.isEmpty()) "暂无待办"
                    else "待办 ${pending.size} · 已完成 $doneCount",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
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
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
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
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenMemo,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("打开备忘录", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }
    }
}

/** 单条待办：勾选框 + 优先级色标 + 文本 + 截止/AI 标记。 */
@Composable
private fun MemoRow(item: MemoItem, onToggle: () -> Unit) {
    val priorityColor = when (item.priority) {
        2 -> Color(0xFFEF5350)
        1 -> Color(0xFFFFB74D)
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (item.done) Icons.Default.CheckCircle
                else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (item.done) "标记未完成" else "标记完成",
                tint = if (item.done) Color(0xFF66BB6A) else priorityColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (item.done) 0.35f else 0.85f),
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
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
    }
}
