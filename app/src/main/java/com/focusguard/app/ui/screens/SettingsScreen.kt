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
    var apiFormat by remember { mutableStateOf(settings.apiFormat) }
    var aiCustomPrompt by remember { mutableStateOf(settings.aiCustomPrompt) }
    var intervalMinutes by remember { mutableStateOf(settings.intervalMinutes.toString()) }
    var confidenceThreshold by remember { mutableStateOf(settings.confidenceThreshold) }
    var consecutiveViolations by remember { mutableStateOf(settings.consecutiveViolations.toString()) }
    var whitelist by remember { mutableStateOf(settings.whitelist) }
    var enforcementMode by remember { mutableStateOf(settings.enforcementMode) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var dailyCallLimit by remember { mutableStateOf(settings.dailyCallLimit.toString()) }

    // AI 检出娱乐后的锁机设置
    var aiLockMinutes by remember { mutableIntStateOf(settings.lockMinutesOnViolation) }
    var aiLockStrength by remember { mutableIntStateOf(settings.aiLockStrength) }
    var aiAlertEnabled by remember { mutableStateOf(settings.aiAlertEnabled) }
    var aiAlertDelaySeconds by remember { mutableIntStateOf(settings.aiAlertDelaySeconds) }

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
                placeholder = { Text("https://api.moonshot.cn/v1") },
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
                label = { Text("模型名称") },
                placeholder = { Text("moonshot-v1-8k-vision-preview") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))

            // ── 厂商预设一键填充 ────────────────────────
            Text(
                "厂商预设（点击自动填入地址与模型）",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(4.dp))
            val presets = listOf(
                "OpenAI" to Triple("openai", "https://api.openai.com/v1", "gpt-4o-mini"),
                "Kimi" to Triple("openai", "https://api.moonshot.cn/v1", "moonshot-v1-8k-vision-preview"),
                "GLM 智谱" to Triple("openai", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-plus"),
                "通义千问" to Triple("openai", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-plus"),
                "DeepSeek" to Triple("openai", "https://api.deepseek.com/v1", "deepseek-chat"),
                "Claude" to Triple("anthropic", "https://api.anthropic.com", "claude-3-5-sonnet-latest"),
                "Gemini" to Triple("gemini", "https://generativelanguage.googleapis.com", "gemini-1.5-flash")
            )
            presets.chunked(2).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowPresets.forEach { (name, cfg) ->
                        OutlinedButton(
                            onClick = {
                                apiBaseUrl = cfg.second
                                modelName = cfg.third
                                apiFormat = cfg.first
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(name, fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "GLM/千问/DeepSeek 为 OpenAI 兼容格式；Claude 走 /v1/messages；Gemini 走 generateContent",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.4f)
            )

            Spacer(Modifier.height(8.dp))

            // ── Kimi 一键填充 ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2332))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Kimi 视觉模型推荐配置",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF8AB4F8)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "API 地址：https://api.moonshot.cn/v1\n" +
                            "可用模型：kimi-k3 / moonshot-v1-8k-vision-preview / moonshot-v1-32k-vision-preview / kimi-k2.6 / kimi-k2.7-code\n" +
                            "注意：Kimi 的 temperature 为固定值，应用已适配（不传该参数）",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            apiBaseUrl = "https://api.moonshot.cn/v1"
                            modelName = "moonshot-v1-8k-vision-preview"
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("一键填入 Kimi 配置", color = Color(0xFF8AB4F8), fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = aiCustomPrompt, onValueChange = { aiCustomPrompt = it },
                label = { Text("AI 提醒风格（可选）") },
                placeholder = { Text("例如：检测到玩游戏时，用妈妈的口吻调侃我两句，给我点情绪价值") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                minLines = 3
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "附加到 AI 检测提示词末尾，可让提醒更有趣、更有温度",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.45f)
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

        // ── AI 检出娱乐后的锁机设置 ────────────────────────────────
        SettingsSection(title = "AI 锁机设置", icon = Icons.Default.Lock) {
            Text(
                text = "AI 判定娱乐并达到连续次数后，按下面的配置自动锁机",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))

            // 锁机时长
            Text("锁机时长：$aiLockMinutes 分钟", fontSize = 13.sp, color = Color.White)
            Slider(
                value = aiLockMinutes.toFloat(),
                onValueChange = { aiLockMinutes = it.toInt() },
                valueRange = 5f..120f,
                steps = 22
            )

            Spacer(Modifier.height(8.dp))

            // 解锁强度
            Text("解锁强度", fontSize = 13.sp, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (1..4).forEach { level ->
                    FilterChip(
                        selected = aiLockStrength == level,
                        onClick = { aiLockStrength = level },
                        label = { Text("$level 级", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (aiLockStrength) {
                    1 -> "1 级：答对 1 道题即可解锁"
                    2 -> "2 级：需连续答对 5 道高难度题"
                    3 -> "3 级：朋友辅助——需朋友解密凯撒密文告知密码"
                    else -> "4 级：无法提前解锁，只能等时间结束"
                },
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(10.dp))

            // 锁机前提醒
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("锁机前弹出提醒", fontSize = 14.sp, color = Color.White)
                    Text(
                        text = "像微信那样弹横幅提示，给你主动收手的机会",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
                Switch(checked = aiAlertEnabled, onCheckedChange = { aiAlertEnabled = it })
            }

            if (aiAlertEnabled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (aiAlertDelaySeconds == 0) {
                        "宽限时间：立即锁机"
                    } else {
                        "宽限时间：$aiAlertDelaySeconds 秒"
                    },
                    fontSize = 13.sp,
                    color = Color.White
                )
                Slider(
                    value = aiAlertDelaySeconds.toFloat(),
                    onValueChange = { aiAlertDelaySeconds = it.toInt() },
                    valueRange = 0f..120f,
                    steps = 23
                )
                Text(
                    text = "宽限期内切回学习/工作应用即可免除本次锁机",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
        }

        // ── 界面主题 ──────────────────────────────────────────────
        SettingsSection(title = "界面主题", icon = Icons.Default.Palette) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (0..3).forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { themeMode = mode },
                        label = {
                            Text(
                                com.focusguard.app.ui.theme.ThemeModes.labelOf(mode),
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "切换后点击保存生效",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.45f)
            )
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

        // ── 调试与导出 ────────────────────────────────────────────
        SettingsSection(title = "调试", icon = Icons.Default.BugReport) {
            var exportMsg by remember { mutableStateOf<String?>(null) }
            Button(
                onClick = {
                    val logStore = com.focusguard.app.data.LogStore(context)
                    val sb = StringBuilder()
                    sb.append("===== 设备信息 =====\n")
                    sb.append("品牌型号：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                    sb.append("系统：Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
                    sb.append("===== 配置（密钥已脱敏） =====\n")
                    sb.append("API 地址：${settings.apiBaseUrl}\n")
                    sb.append("模型：${settings.modelName}\n")
                    sb.append("检测间隔：${settings.intervalMinutes} 分钟\n")
                    sb.append("执法模式：${settings.enforcementMode.name}\n")
                    sb.append("Token 节约：${if (settings.tokenSavingEnabled) "开" else "关"}\n")
                    sb.append("===== Token 统计 =====\n")
                    sb.append("今日调用：${tokenBudget.callsToday} 次\n")
                    sb.append("今日节约：${tokenBudget.savedCallsToday} 次\n")
                    sb.append("===== AI 调用诊断（最近 ${com.focusguard.app.ai.AiClient.exportDiagnostics().lines().count()} 条） =====\n")
                    sb.append(com.focusguard.app.ai.AiClient.exportDiagnostics())
                    sb.append("\n")
                    sb.append("===== 检测日志 =====\n")
                    sb.append(logStore.exportText())

                    // 写入文件并分享
                    val file = java.io.File(context.cacheDir, "focusguard_export_${System.currentTimeMillis()}.txt")
                    file.writeText(sb.toString())
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        ))
                        putExtra(android.content.Intent.EXTRA_TEXT, "专注卫士诊断信息（也可在聊天中直接复制以下内容）：\n\n${sb.toString()}")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "导出诊断日志"))
                        exportMsg = "已生成诊断日志，请选择分享方式"
                    }.onFailure {
                        exportMsg = "导出失败：${it.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导出诊断日志", fontSize = 15.sp)
            }
            exportMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = Color(0xFF4CAF50))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "包含设备信息、配置（密钥脱敏）、Token 统计与检测日志，排查问题时可分享给开发者",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.45f)
            )
        }

        // ── 保存按钮 ──────────────────────────────────────────────
        Button(
            onClick = {
                settings.apiBaseUrl = apiBaseUrl
                settings.apiKey = apiKey
                settings.modelName = modelName
                settings.apiFormat = apiFormat
                settings.aiCustomPrompt = aiCustomPrompt
                settings.intervalMinutes = intervalMinutes.toIntOrNull() ?: 3
                settings.confidenceThreshold = confidenceThreshold
                settings.consecutiveViolations = consecutiveViolations.toIntOrNull() ?: 2
                settings.whitelist = whitelist
                settings.enforcementMode = enforcementMode
                settings.themeMode = themeMode
                settings.tokenSavingEnabled = tokenSavingEnabled
                settings.screenHashDedupEnabled = screenHashDedup
                settings.screenTextPrefilterEnabled = screenTextPrefilter
                settings.decisionCacheEnabled = decisionCacheEnabled
                settings.adaptiveIntervalEnabled = adaptiveInterval
                settings.dailyCallLimit = dailyCallLimit.toIntOrNull() ?: 120
                tokenBudget.dailyCallLimit = settings.dailyCallLimit
                // AI 检出娱乐后的锁机配置
                settings.lockMinutesOnViolation = aiLockMinutes.coerceIn(1, 480)
                settings.aiLockStrength = aiLockStrength
                settings.aiAlertEnabled = aiAlertEnabled
                settings.aiAlertDelaySeconds = aiAlertDelaySeconds.coerceIn(0, 120)
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
