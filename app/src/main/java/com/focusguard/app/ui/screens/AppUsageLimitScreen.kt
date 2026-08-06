package com.focusguard.app.ui.screens

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.detection.AppCategory
import com.focusguard.app.detection.AppClassifier
import com.focusguard.app.usage.AppUsageRule
import com.focusguard.app.usage.UsageRuleStore

/**
 * 应用使用时长限额管理页。
 *
 * 两种规则：
 * - 检测触发时长：使用超过 N 分钟后，立刻启动 AI 检测（低耗 token 的早期预警）
 * - 强制封锁时长：使用超过 M 分钟后，全屏封锁该应用直至次日
 */
@Composable
fun AppUsageLimitScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { UsageRuleStore(context) }
    var rules by remember { mutableStateOf(store.allRules()) }
    var showAddSheet by remember { mutableStateOf(false) }

    // 当日已用时长（来自 store）
    val usageSeconds = remember(rules) {
        rules.associate { it.packageName to store.getTodaySeconds(it.packageName) }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
                }
                Text(
                    text = "应用时长管理",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加规则", tint = Color(0xFFD0BCFF))
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = Color(0xFF4F378B),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        },
        containerColor = Color(0xFF141416)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (rules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("尚未为任何应用设置时长限制", color = Color.White.copy(alpha = 0.4f))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点击右下角 + 添加",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.25f)
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rules, key = { it.packageName }) { rule ->
                        val usedSec = usageSeconds[rule.packageName] ?: 0L
                        UsageLimitRuleCard(
                            rule = rule,
                            usedMinutes = (usedSec / 60).toInt(),
                            appLabel = getAppLabel(context.packageManager, rule.packageName),
                            onDelete = {
                                store.removeRule(rule.packageName)
                                rules = store.allRules()
                            },
                            onResetToday = {
                                store.resetToday(rule.packageName)
                                rules = store.allRules() // triggers recompose with 0
                            }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddSheet) {
        AddUsageRuleSheet(
            onConfirm = { rule ->
                store.setRule(rule)
                rules = store.allRules()
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false }
        )
    }
}

// ── 规则卡片 ─────────────────────────────────────────────────────────

@Composable
private fun UsageLimitRuleCard(
    rule: AppUsageRule,
    usedMinutes: Int,
    appLabel: String,
    onDelete: () -> Unit,
    onResetToday: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F23))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // ── 标题行 ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = rule.packageName,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── 今日进度条 ──────────────────────────────
            val maxMinutes = rule.hardBlockMinutes ?: rule.triggerMinutes ?: 1
            val progress = (usedMinutes.toFloat() / maxMinutes).coerceIn(0f, 1f)
            val barColor = when {
                rule.hardBlockMinutes != null && usedMinutes >= rule.hardBlockMinutes -> Color(0xFFC6786F)
                rule.triggerMinutes != null && usedMinutes >= rule.triggerMinutes -> Color(0xFFFF9800)
                else -> Color(0xFF7C4DFF)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "今日 ${formatMin(usedMinutes)}",
                    fontSize = 12.sp,
                    color = barColor
                )
                rule.hardBlockMinutes?.let {
                    Text(text = "上限 ${formatMin(it)}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f))
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = Color.White.copy(alpha = 0.08f)
            )

            // ── 规则摘要 Chip ────────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rule.triggerMinutes?.let {
                    RuleChip(label = "检测阈值 ${formatMin(it)}", color = Color(0xFFFF9800))
                }
                rule.hardBlockMinutes?.let {
                    RuleChip(label = "封锁上限 ${formatMin(it)}", color = Color(0xFFC6786F))
                }
            }
        }

        // ── 展开操作区 ──────────────────────────────────
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onResetToday) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("重置今日计时", fontSize = 13.sp)
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC6786F))
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("删除规则", fontSize = 13.sp)
                }
            }
        }
    }
}

// ── 添加规则底部表单 ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddUsageRuleSheet(
    onConfirm: (AppUsageRule) -> Unit,
    onDismiss: () -> Unit
) {
    var packageInput by remember { mutableStateOf("") }
    var triggerInput by remember { mutableStateOf("") }
    var hardBlockInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1B1F),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "添加使用时长规则",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            OutlinedTextField(
                value = packageInput,
                onValueChange = { packageInput = it; errorMsg = null },
                label = { Text("应用包名") },
                placeholder = { Text("com.example.app") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = triggerInput,
                    onValueChange = { triggerInput = it; errorMsg = null },
                    label = { Text("检测阈值（分钟）") },
                    placeholder = { Text("如 30") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = hardBlockInput,
                    onValueChange = { hardBlockInput = it; errorMsg = null },
                    label = { Text("封锁上限（分钟）") },
                    placeholder = { Text("如 60") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // 规则说明
            Surface(
                color = Color(0xFF292929),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RuleHintRow(
                        icon = Icons.Default.Notifications,
                        color = Color(0xFFFF9800),
                        text = "检测阈值：超过后立刻启动 AI 内容识别"
                    )
                    RuleHintRow(
                        icon = Icons.Default.Block,
                        color = Color(0xFFC6786F),
                        text = "封锁上限：超过后全屏封锁，无法查看内容（次日解封）"
                    )
                    RuleHintRow(
                        icon = Icons.Default.Info,
                        color = Color.White.copy(alpha = 0.4f),
                        text = "两项均可单独使用，组合时封锁值须 ≥ 检测值"
                    )
                }
            }

            if (errorMsg != null) {
                Text(text = errorMsg!!, color = Color(0xFFC6786F), fontSize = 13.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("取消") }

                Button(
                    onClick = {
                        val pkg = packageInput.trim()
                        val trigger = triggerInput.trim().toIntOrNull()
                        val hard = hardBlockInput.trim().toIntOrNull()

                        when {
                            pkg.isEmpty() -> errorMsg = "请填写应用包名"
                            trigger == null && hard == null ->
                                errorMsg = "至少填写一项阈值"
                            trigger != null && trigger <= 0 ->
                                errorMsg = "检测阈值必须大于 0"
                            hard != null && hard <= 0 ->
                                errorMsg = "封锁上限必须大于 0"
                            trigger != null && hard != null && hard < trigger ->
                                errorMsg = "封锁上限必须 ≥ 检测阈值（$trigger 分钟）"
                            else -> {
                                onConfirm(
                                    AppUsageRule(
                                        packageName = pkg,
                                        triggerMinutes = trigger,
                                        hardBlockMinutes = hard
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F378B))
                ) { Text("确认") }
            }
        }
    }
}

// ── 公共小组件 ───────────────────────────────────────────────────────

@Composable
private fun RuleChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun RuleHintRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    text: String
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(15.dp)
                .padding(top = 1.dp)
        )
        Text(text = text, fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f))
    }
}

private fun getAppLabel(pm: PackageManager, packageName: String): String = try {
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (e: Exception) {
    packageName
}

private fun formatMin(minutes: Int): String = when {
    minutes <= 0 -> "0 分钟"
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 时 ${minutes % 60} 分"
}
