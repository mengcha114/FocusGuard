package com.focusguard.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
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
import com.focusguard.app.detection.InstalledApp
import com.focusguard.app.ui.components.AppPickerDialog
import com.focusguard.app.usage.AppUsageRule
import com.focusguard.app.usage.UsageRuleStore

/**
 * 应用使用时长限额管理页。
 *
 * - 通过应用选择器挑选应用，无需手动输入包名
 * - 检测触发时长：使用超过 N 分钟后启动 AI 检测
 * - 强制封锁时长：使用超过 M 分钟后全屏封锁（次日解封）
 */
@Composable
fun AppUsageLimitScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { UsageRuleStore(context) }
    var rules by remember { mutableStateOf(store.allRules()) }
    var showPicker by remember { mutableStateOf(false) }
    var pickedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

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
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加规则", tint = Color(0xFFD0BCFF))
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showPicker = true },
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
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
                            "点击右下角 + 从应用列表中选择",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.25f)
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rules, key = { it.packageName }) { rule ->
                        UsageLimitRuleCard(
                            rule = rule,
                            usedMinutes = ((usageSeconds[rule.packageName] ?: 0L) / 60).toInt(),
                            packageName = rule.packageName,
                            onDelete = {
                                store.removeRule(rule.packageName)
                                rules = store.allRules()
                            },
                            onResetToday = {
                                store.resetToday(rule.packageName)
                                rules = store.allRules()
                            }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    // ── 选择应用 ──────────────────────────────────────
    if (showPicker) {
        AppPickerDialog(
            title = "选择要限时的应用",
            selectedPackages = rules.map { it.packageName }.toSet(),
            onPick = { app ->
                showPicker = false
                if (store.getRule(app.packageName) != null) {
                    errorMsg = "「${app.label}」已有时长规则"
                } else {
                    pickedApp = app
                }
            },
            onDismiss = { showPicker = false }
        )
    }

    // ── 设置阈值 ──────────────────────────────────────
    pickedApp?.let { app ->
        AddRuleDialog(
            appLabel = app.label,
            packageName = app.packageName,
            onConfirm = { trigger, hardBlock ->
                store.setRule(
                    AppUsageRule(
                        packageName = app.packageName,
                        triggerMinutes = trigger,
                        hardBlockMinutes = hardBlock
                    )
                )
                rules = store.allRules()
                pickedApp = null
            },
            onDismiss = { pickedApp = null }
        )
    }

    // ── 错误提示 ──────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            snackbarHostState.showSnackbar(it)
            errorMsg = null
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ── 规则卡片 ─────────────────────────────────────────────────────────

@Composable
private fun UsageLimitRuleCard(
    rule: AppUsageRule,
    usedMinutes: Int,
    packageName: String,
    onDelete: () -> Unit,
    onResetToday: () -> Unit
) {
    val context = LocalContext.current
    val appLabel = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrDefault(packageName)
    }
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
                        text = packageName,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

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
                Text(text = "今日 ${formatMin(usedMinutes)}", fontSize = 12.sp, color = barColor)
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
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重置今日计时", fontSize = 13.sp)
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC6786F))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除规则", fontSize = 13.sp)
                }
            }
        }
    }
}

// ── 设置阈值对话框 ───────────────────────────────────────────────────

@Composable
private fun AddRuleDialog(
    appLabel: String,
    packageName: String,
    onConfirm: (trigger: Int?, hardBlock: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var triggerInput by remember { mutableStateOf("") }
    var hardBlockInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1B1F),
        title = {
            Column {
                Text("为「$appLabel」设置时长", color = Color.White, fontSize = 18.sp)
                Text(packageName, color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = triggerInput,
                    onValueChange = { triggerInput = it; errorMsg = null },
                    label = { Text("检测阈值（分钟）") },
                    placeholder = { Text("超过后启动 AI 检测，可留空") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = hardBlockInput,
                    onValueChange = { hardBlockInput = it; errorMsg = null },
                    label = { Text("封锁上限（分钟）") },
                    placeholder = { Text("超过后全屏封锁，次日解封，可留空") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                errorMsg?.let {
                    Text(it, color = Color(0xFFC6786F), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trigger = triggerInput.trim().toIntOrNull()
                    val hard = hardBlockInput.trim().toIntOrNull()
                    when {
                        trigger == null && hard == null ->
                            errorMsg = "至少填写一项阈值"
                        trigger != null && trigger <= 0 ->
                            errorMsg = "检测阈值必须大于 0"
                        hard != null && hard <= 0 ->
                            errorMsg = "封锁上限必须大于 0"
                        trigger != null && hard != null && hard < trigger ->
                            errorMsg = "封锁上限必须 ≥ 检测阈值（$trigger 分钟）"
                        else -> onConfirm(trigger, hard)
                    }
                }
            ) {
                Text("确认", color = Color(0xFFD0BCFF))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color.White.copy(alpha = 0.6f))
            }
        }
    )
}

// ── 公共小组件 ───────────────────────────────────────────────────────

@Composable
private fun RuleChip(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun formatMin(minutes: Int): String = when {
    minutes <= 0 -> "0 分钟"
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 时 ${minutes % 60} 分"
}
