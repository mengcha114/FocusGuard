package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

/** 备忘录卡片：显眼展示未完成事项，点击弹出编辑对话框。 */
@Composable
private fun MemoCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val memoStore = remember { com.focusguard.app.data.MemoStore(context) }
    var memos by remember { mutableStateOf(memoStore.getAll()) }
    var showEdit by remember { mutableStateOf(false) }
    var newMemo by remember { mutableStateOf("") }

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
                        imageVector = Icons.Default.Edit,
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
                    text = if (memos.isEmpty()) "暂无待办" else "共 ${memos.size} 条",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }

            Spacer(Modifier.height(10.dp))

            if (memos.isEmpty()) {
                Text(
                    text = "添加待办事项，AI 锁机提醒时会引用它们督促你",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            } else {
                memos.take(2).forEachIndexed { index, memo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF7C4DFF).copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = memo,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    if (index == 1 && memos.size > 2) {
                        Text(
                            text = "…还有 ${memos.size - 2} 条",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.35f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { showEdit = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("管理备忘录", color = Color(0xFFD0BCFF), fontSize = 13.sp)
            }
        }
    }

    // ── 备忘录编辑对话框 ─────────────────────────────
    if (showEdit) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("备忘录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 现有条目（可删除）
                    if (memos.isEmpty()) {
                        Text(
                            text = "还没有待办事项",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    } else {
                        memos.forEachIndexed { index, memo ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "• $memo",
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        memoStore.removeAt(index)
                                        memos = memoStore.getAll()
                                    }
                                ) {
                                    Text("删除", color = Color(0xFFF44336), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newMemo,
                        onValueChange = { newMemo = it },
                        label = { Text("新增待办事项") },
                        placeholder = { Text("例如：完成数学作业第三章") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newMemo.isNotBlank()) {
                            memoStore.add(newMemo)
                            newMemo = ""
                            memos = memoStore.getAll()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F378B))
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showEdit = false }) {
                    Text("完成", color = Color.White.copy(alpha = 0.5f))
                }
            },
            containerColor = Color(0xFF241F27),
            shape = RoundedCornerShape(20.dp)
        )
    }
}
