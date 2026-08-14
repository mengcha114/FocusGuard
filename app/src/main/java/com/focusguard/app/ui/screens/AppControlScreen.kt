package com.focusguard.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.app.detection.AppCategory
import com.focusguard.app.detection.AppCategoryStore
import com.focusguard.app.detection.AppClassifier
import com.focusguard.app.detection.AppInventory
import com.focusguard.app.detection.InstalledApp
import com.focusguard.app.data.AppBlockStore
import com.focusguard.app.usage.AppUsageRule
import com.focusguard.app.usage.UsageRuleStore
import kotlinx.coroutines.launch

/**
 * 应用管控页。
 *
 * 点击任意应用直接进入编辑弹窗，一个弹窗里完成全部设置：
 * - 应用类型（自动识别 / 游戏 / 学习办公 / 视频 / 短视频 / 社交 / 系统）
 * - 允许使用时间（超过后开始 AI 检测）
 * - 最多使用时间（超过后全屏封锁）
 */
@Composable
fun AppControlScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var filterText by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editingApp by remember { mutableStateOf<InstalledApp?>(null) }

    // 读取已安装应用（含自动识别出的分类）
    LaunchedEffect(Unit) {
        loading = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val store = AppCategoryStore(context)
            apps = AppInventory.listLaunchableApps(context, store)
        }
        loading = false
    }

    val filteredApps = remember(apps, selectedTab, filterText) {
        apps.filter { app ->
            val matchesSearch = filterText.isBlank() ||
                app.label.contains(filterText, ignoreCase = true) ||
                app.packageName.contains(filterText, ignoreCase = true)
            val matchesTab = when (selectedTab) {
                1 -> app.category == AppCategory.GAME || app.category == AppCategory.SHORT_VIDEO
                2 -> app.category == AppCategory.STUDY
                3 -> AppClassifier.needsAiDetection(app.category)
                else -> true
            }
            matchesSearch && matchesTab
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("应用管控", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "点击应用即可设置类型与使用时长，纯游戏自动拦截，视频/社交由 AI 动态识别",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f)
        )

        // ── 搜索 ──────────────────────────────────────
        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = { Text("搜索应用...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        // ── 分类 Tab ──────────────────────────────────
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = Color.White
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("全部 (${apps.size})", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("游戏/娱乐", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("学习/办公", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                Text("需 AI 识别", modifier = Modifier.padding(12.dp))
            }
        }

        // ── 应用列表 ──────────────────────────────────
        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text("正在读取应用列表…", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppControlRow(
                        app = app,
                        hasRule = remember(app.packageName) {
                            UsageRuleStore(context).getRule(app.packageName) != null
                        },
                        onClick = { editingApp = app }
                    )
                }
            }
        }
    }

    // ── 编辑弹窗：类型 + 时长一体 ────────────────────
    val scope = rememberCoroutineScope()
    editingApp?.let { app ->
        AppEditSheet(
            app = app,
            onDismiss = { editingApp = null },
            onSaved = { category, rule ->
                val categoryStore = AppCategoryStore(context)
                val ruleStore = UsageRuleStore(context)

                // 限额改动后必须解除既有封锁，否则旧封锁在新限额下仍然生效
                // （用户反馈"修改限额后软件仍被封锁"的根因）：
                // ① 清掉 AI 执法下发的临时封锁截止时间
                // ② 归零今日累计，让新限额从干净状态重新计时
                // ③ 若封锁页正挂在前台，通知它自行退出
                AppBlockStore(context).clear(app.packageName)
                ruleStore.resetToday(app.packageName)
                com.focusguard.app.enforce.AppBlockActivity
                    .dismissIfShowing(app.packageName)
                if (category == null) {
                    categoryStore.clearUserOverride(app.packageName)
                } else {
                    categoryStore.setUserOverride(app.packageName, category)
                }
                if (rule == null) {
                    ruleStore.removeRule(app.packageName)
                } else {
                    ruleStore.setRule(rule)
                    // 设了硬封锁上限就必须启动守护服务，
                    // 否则超限后没人负责拉起封锁页（用户反馈"超时后仍可使用"的根因）
                    if (rule.hardBlockMinutes != null) {
                        com.focusguard.app.service.LockGuardService.start(context)
                        com.focusguard.app.service.GuardWatchdogWorker.schedule(context)
                    }
                }
                AppInventory.invalidate()
                editingApp = null
                // 重新加载以刷新分类显示
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val store = AppCategoryStore(context)
                        apps = AppInventory.listLaunchableApps(context, store, forceRefresh = true)
                    }
                }
            }
        )
    }
}

/** 应用行：图标 + 名称 + 分类标签 + 时长规则标记。 */
@Composable
private fun AppControlRow(app: InstalledApp, hasRule: Boolean, onClick: () -> Unit) {
    val (statusLabel, statusColor) = when (app.category) {
        AppCategory.GAME -> "游戏" to Color(0xFFF44336)
        AppCategory.STUDY -> "学习" to MaterialTheme.colorScheme.tertiary
        AppCategory.SYSTEM -> "系统" to Color(0xFF9E9E9E)
        AppCategory.VIDEO -> "视频" to MaterialTheme.colorScheme.tertiary
        AppCategory.SHORT_VIDEO -> "短视频" to MaterialTheme.colorScheme.tertiary
        AppCategory.SOCIAL -> "社交" to MaterialTheme.colorScheme.tertiary
        AppCategory.UNKNOWN -> "未知" to Color(0xFF90A4AE)
    }

    val iconBitmap: ImageBitmap? = remember(app.packageName) {
        runCatching { app.icon?.toBitmap(48, 48)?.asImageBitmap() }.getOrNull()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        app.label.take(1),
                        fontSize = 18.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = app.packageName,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1
                )
            }

            if (hasRule) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "限时",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
            }

            Surface(
                color = statusColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/** 应用编辑底部弹窗：类型 + 允许使用时间 + 最多使用时间。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppEditSheet(
    app: InstalledApp,
    onDismiss: () -> Unit,
    onSaved: (category: AppCategory?, rule: AppUsageRule?) -> Unit
) {
    val context = LocalContext.current
    val categoryStore = remember { AppCategoryStore(context) }
    val ruleStore = remember { UsageRuleStore(context) }

    // 初始值：用户手动设置优先，否则取自动识别分类
    val initialCategory = remember {
        categoryStore.getUserOverride(app.packageName) ?: app.category
    }
    var selectedCategory by remember {
        mutableStateOf<AppCategory?>(initialCategory)
    }
    val existingRule = remember { ruleStore.getRule(app.packageName) }
    val initialAllow = remember { existingRule?.triggerMinutes?.toString() ?: "" }
    val initialMax = remember { existingRule?.hardBlockMinutes?.toString() ?: "" }
    var allowMinutes by remember { mutableStateOf(initialAllow) }
    var maxMinutes by remember { mutableStateOf(initialMax) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 是否有未保存的更改：任一字段与初始值不同即为"脏"。
    // 无更改 → 保存按钮灰色（禁用感）；有更改 → 亮色可保存；改回原值自动恢复灰色。
    val isDirty = selectedCategory != initialCategory ||
        allowMinutes != initialAllow ||
        maxMinutes != initialMax

    // 防篡改答题验证（首次免费，之后每次保存规则都要答题）
    var showVerify by remember { mutableStateOf(false) }
    var verifyQuestion by remember {
        mutableStateOf<com.focusguard.app.challenge.ChallengeQuestion?>(null)
    }
    var verifyAnswer by remember { mutableStateOf("") }
    var verifyError by remember { mutableStateOf<String?>(null) }
    var pendingRule by remember { mutableStateOf<AppUsageRule?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = app.label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = app.packageName,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.45f)
            )

            // ── 应用类型 ──────────────────────────────
            Text("应用类型", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f))
            val options = listOf(
                null to "自动识别",
                AppCategory.GAME to "游戏",
                AppCategory.STUDY to "学习/办公",
                AppCategory.VIDEO to "视频",
                AppCategory.SHORT_VIDEO to "短视频",
                AppCategory.SOCIAL to "社交",
                AppCategory.SYSTEM to "系统"
            )
            options.chunked(4).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { (cat, label) ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // ── 使用时长 ──────────────────────────────
            Text("使用时长限制（可选）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f))

            OutlinedTextField(
                value = allowMinutes,
                onValueChange = { allowMinutes = it; errorMsg = null },
                label = { Text("允许使用时间（分钟）") },
                placeholder = { Text("如 30，超过后开始 AI 检测") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = maxMinutes,
                onValueChange = { maxMinutes = it; errorMsg = null },
                label = { Text("最多使用时间（分钟）") },
                placeholder = { Text("如 60，超过后全屏封锁") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                text = "留空表示不限制；最多使用时间必须 ≥ 允许使用时间",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f)
            )

            errorMsg?.let {
                Text(it, color = Color(0xFFC6786F), fontSize = 12.sp)
            }

            Spacer(Modifier.height(4.dp))

            // ── 操作按钮 ──────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("取消") }
                Button(
                    onClick = {
                        val trigger = allowMinutes.trim().toIntOrNull()
                        val hard = maxMinutes.trim().toIntOrNull()
                        when {
                            trigger != null && trigger <= 0 ->
                                errorMsg = "允许使用时间必须大于 0"
                            hard != null && hard <= 0 ->
                                errorMsg = "最多使用时间必须大于 0"
                            trigger != null && hard != null && hard < trigger ->
                                errorMsg = "最多使用时间必须 ≥ 允许使用时间"
                            else -> {
                                val rule = if (trigger != null || hard != null) {
                                    AppUsageRule(
                                        packageName = app.packageName,
                                        triggerMinutes = trigger,
                                        hardBlockMinutes = hard
                                    )
                                } else null
                                // 防篡改：首次配置免费，之后每次保存需答题验证
                                val settings = com.focusguard.app.data.Settings(context)
                                if (settings.settingsEditCount > 0) {
                                    pendingRule = rule
                                    verifyQuestion =
                                        com.focusguard.app.challenge.ChallengeGenerator().generate(2)
                                    verifyAnswer = ""
                                    verifyError = null
                                    showVerify = true
                                } else {
                                    settings.settingsEditCount = settings.settingsEditCount + 1
                                    onSaved(selectedCategory, rule)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = isDirty,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = Color.White.copy(alpha = 0.4f)
                    )
                ) { Text("保存") }
            }
        }

        // ── 修改规则答题验证对话框 ────────────────────────
        if (showVerify) {
            AlertDialog(
                onDismissRequest = { showVerify = false },
                title = { Text("修改规则需先答题") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "为防止限制被随意篡改，请先回答一道题：",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Text(
                            text = verifyQuestion?.question ?: "",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        OutlinedTextField(
                            value = verifyAnswer,
                            onValueChange = { verifyAnswer = it; verifyError = null },
                            label = { Text("你的答案") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        verifyError?.let {
                            Text(it, fontSize = 12.sp, color = Color(0xFFF44336))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val q = verifyQuestion
                            val gen = com.focusguard.app.challenge.ChallengeGenerator()
                            if (q != null && gen.isAnswerCorrect(verifyAnswer, q.answer)) {
                                com.focusguard.app.data.Settings(context).settingsEditCount =
                                    com.focusguard.app.data.Settings(context).settingsEditCount + 1
                                showVerify = false
                                verifyAnswer = ""
                                onSaved(selectedCategory, pendingRule)
                            } else {
                                verifyError = "回答错误，请重试"
                            }
                        }
                    ) { Text("验证并保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showVerify = false }) {
                        Text("取消", color = Color.White.copy(alpha = 0.5f))
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}
