package com.focusguard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.Settings
import com.focusguard.app.token.TokenBudget

@Composable
fun SettingsScreen(onSave: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val tokenBudget = remember { TokenBudget(context) }

    var apiBaseUrl by remember { mutableStateOf(settings.apiBaseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var modelName by remember { mutableStateOf(settings.modelName) }
    var intervalMinutes by remember { mutableStateOf(settings.intervalMinutes.toString()) }
    var confidenceThreshold by remember { mutableStateOf(settings.confidenceThreshold) }
    var consecutiveViolations by remember { mutableStateOf(settings.consecutiveViolations.toString()) }
    var whitelist by remember { mutableStateOf(settings.whitelist) }
    var enforcementMode by remember { mutableStateOf(settings.enforcementMode) }
    var dailyCallLimit by remember { mutableStateOf(settings.dailyCallLimit.toString()) }

    // Token 节约系统开关
    var tokenSavingEnabled by remember { mutableStateOf(settings.tokenSavingEnabled) }
    var screenHashDedup by remember { mutableStateOf(settings.screenHashDedupEnabled) }
    var screenTextPrefilter by remember { mutableStateOf(settings.screenTextPrefilterEnabled) }
    var decisionCacheEnabled by remember { mutableStateOf(settings.decisionCacheEnabled) }
    var adaptiveInterval by remember { mutableStateOf(settings.adaptiveIntervalEnabled) }

    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)

        // ── API 设置 ──────────────────────────────────────────────
        SettingsSection(title = "API 设置", icon = Icons.Default.Api) {
            OutlinedTextField(
                value = apiBaseUrl, onValueChange = { apiBaseUrl = it },
                label = { Text("API 地址") },
                placeholder = { Text("https://api.openai.com/v1") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API 密钥") }, placeholder = { Text("sk-...") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = modelName, onValueChange = { modelName = it },
                label = { Text("模型名称") }, placeholder = { Text("gpt-4o-mini") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
        }

        // ── 检测设置 ──────────────────────────────────────────────
        SettingsSection(title = "检测设置", icon = Icons.Default.Search) {
            OutlinedTextField(
                value = intervalMinutes, onValueChange = { intervalMinutes = it },
                label = { Text("基础检测间隔（分钟）") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("置信度阈值: ${(confidenceThreshold * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
            Slider(
                value = confidenceThreshold, onValueChange = { confidenceThreshold = it },
                valueRange = 0.3f..0.95f, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = consecutiveViolations, onValueChange = { consecutiveViolations = it },
                label = { Text("连续违规触发次数") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
        }

        // ── Token 节约系统 ────────────────────────────────────────
        SettingsSection(title = "Token 节约", icon = Icons.Default.Savings) {
            // 今日统计摘要
            val callsToday = tokenBudget.callsToday
            val savedToday = tokenBudget.savedCallsToday
            val savedPct = tokenBudget.savedPercentToday()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TokenStatChip("今日调用", "$callsToday 次", Color(0xFF7C4DFF), Modifier.weight(1f))
                TokenStatChip("节约", "$savedToday 次", Color(0xFF4CAF50), Modifier.weight(1f))
                TokenStatChip("节约率", "$savedPct%", Color(0xFFFF9800), Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))

            // 总开关
            TokenSavingToggle(
                title = "Token 节约系统",
                subtitle = "关闭后每次检测均直接调用 AI，消耗更多 token",
                icon = Icons.Default.TrendingDown,
                checked = tokenSavingEnabled,
                highlight = true,
                onCheckedChange = {
                    tokenSavingEnabled = it
                    // 子开关跟随总开关状态同步刷新
                    if (!it) {
                        screenHashDedup = false
                        screenTextPrefilter = false
                        decisionCacheEnabled = false
                        adaptiveInterval = false
                    } else {
                        screenHashDedup = true
                        screenTextPrefilter = true
                        decisionCacheEnabled = true
                        adaptiveInterval = true
                    }
                }
            )

            Spacer(Modifier.height(4.dp))

            // 子开关（总开关关闭时置灰）
            TokenSavingToggle(
                title = "画面去重",
                subtitle = "截图相似时复用上次判定，节省约 40% 调用",
                icon = Icons.Default.Compare,
                checked = screenHashDedup,
                enabled = tokenSavingEnabled,
                onCheckedChange = { screenHashDedup = it }
            )
            TokenSavingToggle(
                title = "屏幕文字预过滤",
                subtitle = "关键词规则命中后不截图、不调 AI（需无障碍权限）",
                icon = Icons.Default.TextFields,
                checked = screenTextPrefilter,
                enabled = tokenSavingEnabled,
                onCheckedChange = { screenTextPrefilter = it }
            )
            TokenSavingToggle(
                title = "判定结果缓存",
                subtitle = "历史相似画面复用大模型结论（TTL 6 小时）",
                icon = Icons.Default.Memory,
                checked = decisionCacheEnabled,
                enabled = tokenSavingEnabled,
                onCheckedChange = { decisionCacheEnabled = it }
            )
            TokenSavingToggle(
                title = "自适应检测间隔",
                subtitle = "专注状态稳定时自动放宽间隔（最多 4×），发现娱乐立即收紧",
                icon = Icons.Default.Speed,
                checked = adaptiveInterval,
                enabled = tokenSavingEnabled,
                onCheckedChange = { adaptiveInterval = it }
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = dailyCallLimit,
                onValueChange = { dailyCallLimit = it },
                label = { Text("每日最大 AI 调用次数（0 = 不限制）") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = tokenSavingEnabled
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "达到上限后退化为本地规则判定，功能不中断",
                fontSize = 11.sp,
                color = if (tokenSavingEnabled) Color.White.copy(alpha = 0.45f)
                        else Color.White.copy(alpha = 0.2f)
            )
        }

        // ── 执法模式 ──────────────────────────────────────────────
        SettingsSection(title = "执法模式", icon = Icons.Default.Gavel) {
            EnforcementModeSelector(selected = enforcementMode, onSelect = { enforcementMode = it })
        }

        // ── 白名单 ────────────────────────────────────────────────
        SettingsSection(title = "白名单", icon = Icons.Default.PlaylistAdd) {
            OutlinedTextField(
                value = whitelist, onValueChange = { whitelist = it },
                label = { Text("白名单应用/场景（逗号分隔）") },
                placeholder = { Text("例如: 学习强国, 得到, B站课程") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                minLines = 3
            )
        }

        // ── 保存按钮 ──────────────────────────────────────────────
        Button(
            onClick = {
                settings.apiBaseUrl = apiBaseUrl
                settings.apiKey = apiKey
                settings.modelName = modelName
                settings.intervalMinutes = intervalMinutes.toIntOrNull() ?: 3
                settings.confidenceThreshold = confidenceThreshold
                settings.consecutiveViolations = consecutiveViolations.toIntOrNull() ?: 2
                settings.whitelist = whitelist
                settings.enforcementMode = enforcementMode
                settings.tokenSavingEnabled = tokenSavingEnabled
                settings.screenHashDedupEnabled = screenHashDedup
                settings.screenTextPrefilterEnabled = screenTextPrefilter
                settings.decisionCacheEnabled = decisionCacheEnabled
                settings.adaptiveIntervalEnabled = adaptiveInterval
                settings.dailyCallLimit = dailyCallLimit.toIntOrNull() ?: 120
                tokenBudget.dailyCallLimit = settings.dailyCallLimit
                saved = true
                onSave()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
        ) {
            Text("保存设置", fontSize = 18.sp)
        }

        if (saved) {
            Text(
                text = "已保存",
                color = Color(0xFF4CAF50),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** Token 节约系统的单项开关。 */
@Composable
private fun TokenSavingToggle(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    highlight: Boolean = false
) {
    val contentAlpha = if (enabled) 1f else 0.35f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = (if (highlight) Color(0xFF4CAF50) else Color(0xFFD0BCFF))
                .copy(alpha = contentAlpha),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = if (highlight) 15.sp else 14.sp,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
                color = Color.White.copy(alpha = contentAlpha)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f * contentAlpha)
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = if (highlight) Color(0xFF388E3C) else Color(0xFF4F378B)
            )
        )
    }
}

/** Token 统计小卡片。 */
@Composable
private fun TokenStatChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(2.dp))
            Text(text = label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.55f))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF263238)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF7C4DFF),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnforcementModeSelector(
    selected: Settings.EnforcementMode,
    onSelect: (Settings.EnforcementMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Settings.EnforcementMode.entries.forEach { mode ->
            val (label, icon) = when (mode) {
                Settings.EnforcementMode.LOCK -> "强制锁机" to Icons.Default.Lock
                Settings.EnforcementMode.EXIT -> "强制退出" to Icons.Default.ExitToApp
                Settings.EnforcementMode.WARN -> "仅警告" to Icons.Default.Notifications
            }
            val isSelected = mode == selected

            FilterChip(
                selected = isSelected,
                onClick = { onSelect(mode) },
                label = { Text(label) },
                leadingIcon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
