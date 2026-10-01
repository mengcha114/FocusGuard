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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import com.focusguard.app.ui.theme.animationsEnabled
import com.focusguard.app.ui.theme.inkCard
import com.focusguard.app.ui.theme.pressScale

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

    val todayChecks = remember { logStore.getTodayCheckCount() }
    val focusScore = remember { logStore.getTodayFocusScore() }
    val violations = remember { logStore.getTodayViolations().size }
    val recentLogs = remember { logStore.getAllLogs().take(5) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StaggerIn(0) {
                Column {
                    Text(
                        text = greeting(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "专注卫士",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        item { StaggerIn(1) { HeroStatusCard(serviceRunning, focusScore) } }

        item {
            StaggerIn(2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard("今日检测", todayChecks, "次", Icons.Default.Analytics,
                        MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    StatCard("专注指数", focusScore, "%", Icons.Default.Star,
                        MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                    StatCard("违规", violations, "次", Icons.Default.Warning,
                        MaterialTheme.colorScheme.error, Modifier.weight(1f))
                }
            }
        }

        item {
            StaggerIn(3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Button(
                        onClick = if (serviceRunning) onStopGuard else onStartGuard,
                        interactionSource = interaction,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(56.dp)
                            .then(Modifier.pressScaleCompat(interaction)),
                        shape = RoundedCornerShape(18.dp),
                        colors = if (serviceRunning) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                            contentColor = MaterialTheme.colorScheme.error
                        ) else ButtonDefaults.buttonColors()
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = serviceRunning,
                            label = "guardBtn"
                        ) { running ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (running) "停止守护" else "开始守护", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    val testInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    OutlinedButton(
                        onClick = onTestDetection,
                        interactionSource = testInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .then(Modifier.pressScaleCompat(testInteraction)),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("测试识别", fontSize = 15.sp)
                    }
                }
            }
        }

        item { StaggerIn(4) { MemoCard(onOpenMemo = onOpenMemo) } }

        item {
            StaggerIn(5) {
                Text(
                    text = "最近检测",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (recentLogs.isEmpty()) {
            item {
                StaggerIn(6) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(Modifier.inkCardCompat())
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Insights, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("暂无检测记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("开始守护后，这里会显示 AI 的判断", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }
        } else {
            items(recentLogs.size) { index ->
                val log = recentLogs[index]
                StaggerIn(6 + index) {
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
}

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..10 -> "早上好，今天也专注一点"
        in 11..13 -> "中午好，记得休息眼睛"
        in 14..17 -> "下午好，保持节奏"
        in 18..22 -> "晚上好，收个好尾"
        else -> "夜深了，早点休息"
    }
}

private fun Modifier.pressScaleCompat(
    interaction: androidx.compose.foundation.interaction.MutableInteractionSource
): Modifier = this.pressScale(interaction)

@Composable
private fun Modifier.inkCardCompat(): Modifier = this.inkCard(corner = 20.dp)

/**
 * 入场编排：按序号错开 40ms 淡入 + 上移 16dp（只播放一次，系统关闭动画时直接显示）。
 */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    val on = animationsEnabled()
    val progress = remember { androidx.compose.animation.core.Animatable(if (on) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (on && progress.value < 1f) {
            kotlinx.coroutines.delay(40L * index.coerceAtMost(8))
            progress.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 16.dp.toPx()
        }
    ) { content() }
}

/** 主状态卡：专注指数环 + 守护状态脉冲点 + 健康诊断。 */
@Composable
private fun HeroStatusCard(serviceRunning: Boolean, focusScore: Int) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (serviceRunning) scheme.primary else scheme.onSurfaceVariant
    val on = animationsEnabled()

    // 专注指数环：从 0 平滑填充到目标值
    val ring = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(focusScore) {
        val target = (focusScore / 100f).coerceIn(0f, 1f)
        if (on) ring.animateTo(target, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        else ring.snapTo(target)
    }
    // 守护中脉冲
    val pulse = if (serviceRunning && on) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse").animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1600),
                androidx.compose.animation.core.RepeatMode.Restart
            ),
            label = "pulseV"
        ).value
    } else 0f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.20f),
                        scheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                )
            )
            .then(Modifier.inkCardCompatBorder())
            .padding(20.dp)
    ) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2
                val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(scheme.outline.copy(alpha = 0.35f), -90f, 360f, false, tl, arc,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                drawArc(
                    Brush.sweepGradient(listOf(scheme.tertiary, scheme.primary, scheme.tertiary)),
                    -90f, 360f * ring.value, false, tl, arc,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${(ring.value * 100).toInt()}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    color = scheme.onSurface
                )
                Text("专注", fontSize = 10.sp, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (serviceRunning) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .graphicsLayer {
                                    scaleX = 0.5f + pulse
                                    scaleY = 0.5f + pulse
                                    alpha = 1f - pulse
                                }
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(accent.copy(alpha = 0.5f))
                        )
                    }
                    Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent))
                }
                Spacer(Modifier.width(6.dp))
                androidx.compose.animation.AnimatedContent(serviceRunning, label = "statusText") { running ->
                    Text(
                        if (running) "守护中" else "未开启",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (serviceRunning) "AI 正在关注你的专注状态" else "点击「开始守护」让 AI 帮你保持专注",
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            // ── 守护健康诊断：心跳超过 60 秒未更新说明检测循环已死 ──
            if (serviceRunning) {
                var healthTick by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(2000L)
                        healthTick++
                    }
                }
                val health = remember(healthTick) { com.focusguard.app.service.MonitorService.healthText() }
                val alive = remember(healthTick) { com.focusguard.app.service.MonitorService.isLoopAlive() }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = health,
                    fontSize = 11.sp,
                    color = if (alive) scheme.onSurfaceVariant.copy(alpha = 0.8f) else scheme.error
                )
            }
        }
    }
}

@Composable
private fun Modifier.inkCardCompatBorder(): Modifier = this.border(
    1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(24.dp)
)

/** 统计卡：数值从 0 计数增长。 */
@Composable
fun StatCard(
    title: String,
    value: Int,
    unit: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    val on = animationsEnabled()
    val shown = remember { androidx.compose.animation.core.Animatable(if (on) 0f else value.toFloat()) }
    LaunchedEffect(value) {
        if (on) shown.animateTo(value.toFloat(), androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        else shown.snapTo(value.toFloat())
    }
    Column(
        modifier = modifier
            .then(Modifier.inkCardCompat())
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = shown.value.toInt().toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(2.dp))
            Text(unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(
            text = title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
