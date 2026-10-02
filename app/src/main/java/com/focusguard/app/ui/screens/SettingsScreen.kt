package com.focusguard.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
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
fun SettingsScreen(
    onSave: () -> Unit,
    onOpenTextKeywords: () -> Unit = {}
) {
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
    var dailyCallLimit by remember { mutableStateOf(settings.dailyCallLimit.toString()) }

    // AI 检出娱乐后的锁机设置
    var aiLockMinutes by remember { mutableIntStateOf(settings.lockMinutesOnViolation) }
    var aiLockStrength by remember { mutableIntStateOf(settings.aiLockStrength) }
    var aiAlertEnabled by remember { mutableStateOf(settings.aiAlertEnabled) }
    var aiAlertDelaySeconds by remember { mutableIntStateOf(settings.aiAlertDelaySeconds) }

    // 智能检测模式
    var smartScheduleEnabled by remember { mutableStateOf(settings.smartScheduleEnabled) }

    // 仅锁该软件时长 / 自定义箴言
    var appBlockMinutes by remember { mutableIntStateOf(settings.appBlockMinutes) }
    var customMottos by remember { mutableStateOf(settings.customMottos) }

    // 降低限制方向修改的答题验证（增强限制无需答题）
    var showVerifyDialog by remember { mutableStateOf(false) }

    // Token 节约系统开关
    var tokenSavingEnabled by remember { mutableStateOf(settings.tokenSavingEnabled) }
    var screenHashDedup by remember { mutableStateOf(settings.screenHashDedupEnabled) }
    var screenTextPrefilter by remember { mutableStateOf(settings.screenTextPrefilterEnabled) }
    var decisionCacheEnabled by remember { mutableStateOf(settings.decisionCacheEnabled) }
    var adaptiveInterval by remember { mutableStateOf(settings.adaptiveIntervalEnabled) }

    // 屏幕文字特征关键词（完整词表，默认内置，可增删改）
    var studyKeywords by remember { mutableStateOf(settings.studyKeywords) }
    var entertainmentKeywords by remember { mutableStateOf(settings.entertainmentKeywords) }

    // 隐私保护（敏感应用跳过截屏/文字读取/上传）
    var privacyProtectEnabled by remember { mutableStateOf(settings.privacyProtectEnabled) }
    var sensitiveApps by remember { mutableStateOf(settings.sensitiveApps) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        // ═══════ 分组一：AI 检测 ═══════
        SettingsGroupHeader("AI 检测")

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
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(4.dp))
            val presets = listOf(
                "OpenAI" to Triple("openai", "https://api.openai.com/v1", "gpt-4o-mini"),
                "Kimi" to Triple("openai", "https://api.moonshot.cn/v1", "moonshot-v1-8k-vision-preview"),
                "GLM 智谱" to Triple("openai", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash"),
                "通义千问" to Triple("openai", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-max"),
                "Agnes" to Triple("openai", "https://apihub.agnes-ai.com/v1", "agnes-2.5-flash"),
                "Claude" to Triple("anthropic", "https://api.anthropic.com", "claude-3-5-sonnet-latest"),
                "Gemini" to Triple("gemini", "https://generativelanguage.googleapis.com", "gemini-3.1-flash-lite"),
                "自定义 API" to Triple("openai", "", "")
            )
            presets.chunked(2).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowPresets.forEach { (name, cfg) ->
                        OutlinedButton(
                            onClick = {
                                if (cfg.second.isBlank()) {
                                    // 点击「自定义 API」：清空三个输入框
                                    apiFormat = "openai"
                                    apiBaseUrl = ""
                                    apiKey = ""
                                    modelName = ""
                                } else {
                                    // 点击预设厂商：把预设填入输入框，但保留用户之前存在 custom 里的自定义配置
                                    apiFormat = cfg.first
                                    apiBaseUrl = cfg.second
                                    modelName = cfg.third
                                }
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
                text = "GLM/千问/DeepSeek 为 OpenAI 兼容格式；Claude 走 /v1/messages；Gemini 走 generateContent；自定义 API 默认按 OpenAI 兼容协议发送",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = aiCustomPrompt, onValueChange = { aiCustomPrompt = it },
                label = { Text("AI 提醒风格（可选）") },
                placeholder = { Text("例如：检测到娱乐时，用猫娘的口吻撒娇提醒我休息（留空则不设置风格）") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                minLines = 3
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "附加到 AI 检测提示词末尾，可让提醒更有趣、更有温度",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
        }

        // ── 隐私保护 ──────────────────────────────────────────────
        SettingsSection(title = "隐私保护", icon = Icons.Default.VerifiedUser) {
            TokenSavingToggle(
                title = "敏感应用保护",
                subtitle = "检测到银行/支付/密码管理等敏感应用时，本轮不截屏、不读取屏幕文字、不上传任何内容",
                icon = Icons.Default.VisibilityOff,
                checked = privacyProtectEnabled,
                onCheckedChange = { privacyProtectEnabled = it },
                highlight = true
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = sensitiveApps, onValueChange = { sensitiveApps = it },
                label = { Text("自定义敏感应用（可选）") },
                placeholder = { Text("包名或应用名片段，逗号分隔，例如：notion, 密码本") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                minLines = 2
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "命中敏感应用时仅记录一条「隐私保护」日志，判定为中性、不触发任何执法。API 密钥已使用系统级 AES 加密存储，不会以明文写入应用数据。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
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
            Text("置信度阈值: ${(confidenceThreshold * 100).toInt()}%", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
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
        // ── 智能检测调度 ──────────────────────────────────────────
        SettingsSection(title = "智能检测调度", icon = Icons.Default.AutoAwesome) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("智能模式（秒级动态间隔）", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        text = "根据风险自动收紧/放宽检测节奏，比固定间隔更准也更省 token",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                    )
                }
                Switch(
                    checked = smartScheduleEnabled,
                    onCheckedChange = { smartScheduleEnabled = it }
                )
            }

            if (smartScheduleEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "智能模式：根据上面设置的检测时间进行智能调整",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已关闭：使用固定间隔（上方「检测间隔」分钟数）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                )
            }
        }

        SettingsSection(title = "Token 节约", icon = Icons.Default.Savings) {
            // 今日统计摘要
            val callsToday = tokenBudget.callsToday
            val savedToday = tokenBudget.savedCallsToday
            val savedPct = tokenBudget.savedPercentToday()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TokenStatChip("今日调用", "$callsToday 次", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                TokenStatChip("节约", "$savedToday 次", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                TokenStatChip("节约率", "$savedPct%", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
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
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
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
                color = if (tokenSavingEnabled) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
            )
        }

        // ── 屏幕文字特征关键词（自定义） ──────────────────────────
        SettingsSection(title = "屏幕文字特征词", icon = Icons.Default.TextFields) {
            Text(
                text = "「屏幕文字预过滤」命中这些词时直接判定（不截图、不调 AI）。\n" +
                    "完整词表（默认内置，可增删改）在独立页面编辑，避免占用本页空间。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenTextKeywords,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "编辑特征词（${studyKeywords.lines().count { it.isNotBlank() }} + " +
                        "${entertainmentKeywords.lines().count { it.isNotBlank() }} 个词）",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }
        }

        // ═══════ 分组二：执法与锁机 ═══════
        SettingsGroupHeader("执法与锁机")

        SettingsSection(title = "答题年级", icon = Icons.Default.School) {
            com.focusguard.app.ui.components.GradeSettingCard()
        }

        // ── 执法模式 ──────────────────────────────────────────────
        SettingsSection(title = "执法模式", icon = Icons.Default.Gavel) {
            EnforcementModeSelector(selected = enforcementMode, onSelect = { enforcementMode = it })
        }

        // ── AI 检出娱乐后的锁机设置 ────────────────────────────────
        // ── 系统级锁机（Dhizuku Lock Task）状态 ──────────────
        SettingsSection(title = "系统级锁机（Lock Task）", icon = Icons.Default.Security) {
            val dhizukuReady = com.focusguard.app.enhance.DhizukuEnhancer.isReady()
            val lockTaskOn = com.focusguard.app.enhance.LockTaskEnhancer.lockTaskActive
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            lockTaskOn -> "已生效 · 锁机期间系统级封锁"
                            dhizukuReady -> "Dhizuku 已连接 · 锁机时自动进入"
                            else -> "未启用（将使用普通模式）"
                        },
                        fontSize = 14.sp,
                        color = when {
                            lockTaskOn -> MaterialTheme.colorScheme.tertiary
                            dhizukuReady -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        }
                    )
                    Text(
                        text = if (dhizukuReady) {
                            "Lock Task 生效后，Home / 上滑 / 最近任务全部失效，无法退出"
                        } else {
                            "安装并激活 Dhizuku 后，锁机将无法被任何手势退出。\n" +
                                "未生效原因：${com.focusguard.app.enhance.DhizukuEnhancer.lastError.ifBlank { "未连接" }}"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                    )
                }
            }
        }

        SettingsSection(title = "AI 锁机设置", icon = Icons.Default.Lock) {
            Text(
                text = "AI 判定娱乐并达到连续次数后，按下面的配置自动锁机",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))

            // 锁机时长
            Text("锁机时长：$aiLockMinutes 分钟", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
            Slider(
                value = aiLockMinutes.toFloat(),
                onValueChange = { aiLockMinutes = it.toInt() },
                valueRange = 5f..120f,
                steps = 22
            )

            Spacer(Modifier.height(8.dp))

            // 解锁强度
            Text("解锁强度", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (1..4).forEach { level ->
                    FilterChip(shape = RoundedCornerShape(10.dp),
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
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
            Spacer(Modifier.height(10.dp))

            // 锁机前提醒
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("锁机前弹出提醒", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        text = "像微信那样弹横幅提示，给你主动收手的机会",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
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
                    color = MaterialTheme.colorScheme.onBackground
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
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                )
            }
        }

        // ── 仅锁该软件时长 ────────────────────────────────────────
        SettingsSection(title = "仅锁该软件时长", icon = Icons.Default.Block) {
            Text(
                text = if (enforcementMode == Settings.EnforcementMode.APP_BLOCK) {
                    "执法模式为「仅锁该软件」：判定娱乐后该应用封锁 $appBlockMinutes 分钟"
                } else {
                    "切换执法模式为「仅锁该软件」后生效：判定娱乐后该应用封锁 $appBlockMinutes 分钟"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(10.dp))
            Slider(
                value = appBlockMinutes.toFloat(),
                onValueChange = { appBlockMinutes = it.toInt() },
                valueRange = 5f..240f,
                steps = 46
            )
            Text(
                text = "封锁期内打开该应用会被全屏挡住，退出后其他应用不受影响",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
        }

        // ── 自定义锁机箴言 ────────────────────────────────────────
        SettingsSection(title = "自定义锁机箴言", icon = Icons.Default.FormatQuote) {
            OutlinedTextField(
                value = customMottos,
                onValueChange = { customMottos = it },
                label = { Text("每行一条，留空使用内置箴言") },
                placeholder = { Text("例如：\n自律给我自由\n拒绝拖延，立刻行动") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "锁机页面会随机展示你写的句子",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
        }

        // ═══════ 分组三：外观与习惯 ═══════
        SettingsGroupHeader("外观与习惯")

        // ── 界面主题 ──────────────────────────────────────────────
        SettingsSection(title = "界面主题", icon = Icons.Default.Palette) {
            com.focusguard.app.ui.components.ThemePicker()
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

        // ═══════ 分组四：系统与调试 ═══════
        SettingsGroupHeader("系统与调试")

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
                colors = ButtonDefaults.buttonColors(containerColor = com.focusguard.app.ui.theme.cardContainer())
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导出诊断日志", fontSize = 15.sp)
            }
            exportMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "包含设备信息、配置（密钥脱敏）、Token 统计与检测日志，排查问题时可分享给开发者",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )
        }

        // ── 自动保存 ─────────────────────────────────────────────
        // 任一设置变化即持久化，无需手动点保存（onChange 即生效）。
        fun saveAll() {
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
            // 仅锁该软件时长 + 自定义箴言
            settings.studyKeywords = studyKeywords
            settings.entertainmentKeywords = entertainmentKeywords
            settings.smartScheduleEnabled = smartScheduleEnabled
            settings.appBlockMinutes = appBlockMinutes.coerceIn(1, 480)
            settings.customMottos = customMottos
            // 隐私保护
            settings.privacyProtectEnabled = privacyProtectEnabled
            settings.sensitiveApps = sensitiveApps
        }

        // ── 方向判定：修改是否在「降低对自己的限制」 ──────────────
        // 增强限制（锁得更久/更容易触发/更难解锁）→ 直接保存；
        // 降低限制（缩短锁机/更难触发/更容易解锁）→ 需答题验证（防被监管对象篡改）。
        // 判定改为「收紧白名单」：只有明确是收紧（或纯外观）的改动可直接保存，
        // 其余任何改动一律视为放宽、需答题。旧实现逐项列举放宽项，漏掉了
        // 白名单 / 敏感应用 / 置信度 / API 配置 / 提示词 / 调用上限等，
        // 改这些即可让 AI 检测失效。
        fun isLoosening(): Boolean {
            val newInterval = intervalMinutes.toIntOrNull() ?: settings.intervalMinutes
            val newViolations = consecutiveViolations.toIntOrNull() ?: settings.consecutiveViolations
            val newCallLimit = dailyCallLimit.toIntOrNull() ?: settings.dailyCallLimit
            val newAlertDelay = aiAlertDelaySeconds.coerceIn(0, 120)
            val enforceRank = mapOf(
                Settings.EnforcementMode.WARN to 0,
                Settings.EnforcementMode.APP_BLOCK to 1,
                Settings.EnforcementMode.LOCK to 2
            )
            fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

            // 数值类：只允许朝「更严」方向变化
            val numericOk =
                aiLockMinutes >= settings.lockMinutesOnViolation &&
                    appBlockMinutes >= settings.appBlockMinutes &&
                    newViolations <= settings.consecutiveViolations &&
                    aiLockStrength >= settings.aiLockStrength &&
                    (enforceRank[enforcementMode] ?: 2) >= (enforceRank[settings.enforcementMode] ?: 2) &&
                    newInterval <= settings.intervalMinutes &&
                    newAlertDelay <= settings.aiAlertDelaySeconds &&
                    confidenceThreshold <= settings.confidenceThreshold &&
                    newCallLimit >= settings.dailyCallLimit &&
                    (smartScheduleEnabled || !settings.smartScheduleEnabled) &&
                    // 开启「先提醒再锁机」= 多一段宽限 = 放宽
                    (!aiAlertEnabled || settings.aiAlertEnabled)

            // 列表类：白名单 / 敏感应用只能删、不能加；娱乐词只能加、学习词只能删
            val listsOk =
                lines(settings.whitelist).containsAll(lines(whitelist)) &&
                    lines(settings.sensitiveApps).containsAll(lines(sensitiveApps)) &&
                    lines(entertainmentKeywords).containsAll(lines(settings.entertainmentKeywords)) &&
                    lines(settings.studyKeywords).containsAll(lines(studyKeywords))

            // 开关类：关闭隐私保护 = 更严；开启省 token 系列 = 检测变少 = 放宽
            val togglesOk =
                (!privacyProtectEnabled || settings.privacyProtectEnabled) &&
                    (!tokenSavingEnabled || settings.tokenSavingEnabled) &&
                    (!screenHashDedup || settings.screenHashDedupEnabled) &&
                    (!screenTextPrefilter || settings.screenTextPrefilterEnabled) &&
                    (!decisionCacheEnabled || settings.decisionCacheEnabled) &&
                    (!adaptiveInterval || settings.adaptiveIntervalEnabled)

            // 检测通道：任何改动都可能让检测失效，一律需答题
            val channelOk =
                apiBaseUrl == settings.apiBaseUrl &&
                    apiKey == settings.apiKey &&
                    modelName == settings.modelName &&
                    apiFormat == settings.apiFormat &&
                    aiCustomPrompt == settings.aiCustomPrompt

            // 主题 / 箴言属于外观，不影响限制
            return !(numericOk && listsOk && togglesOk && channelOk)
        }

        /** 放宽验证被取消：界面值回滚到已保存的设置（旧实现不回滚，界面显示与实际不符）。 */
        fun revertUnsaved() {
            apiBaseUrl = settings.apiBaseUrl
            apiKey = settings.apiKey
            modelName = settings.modelName
            apiFormat = settings.apiFormat
            aiCustomPrompt = settings.aiCustomPrompt
            intervalMinutes = settings.intervalMinutes.toString()
            confidenceThreshold = settings.confidenceThreshold
            consecutiveViolations = settings.consecutiveViolations.toString()
            whitelist = settings.whitelist
            enforcementMode = settings.enforcementMode
            tokenSavingEnabled = settings.tokenSavingEnabled
            screenHashDedup = settings.screenHashDedupEnabled
            screenTextPrefilter = settings.screenTextPrefilterEnabled
            decisionCacheEnabled = settings.decisionCacheEnabled
            adaptiveInterval = settings.adaptiveIntervalEnabled
            dailyCallLimit = settings.dailyCallLimit.toString()
            aiLockMinutes = settings.lockMinutesOnViolation
            aiLockStrength = settings.aiLockStrength
            aiAlertEnabled = settings.aiAlertEnabled
            aiAlertDelaySeconds = settings.aiAlertDelaySeconds
            studyKeywords = settings.studyKeywords
            entertainmentKeywords = settings.entertainmentKeywords
            smartScheduleEnabled = settings.smartScheduleEnabled
            appBlockMinutes = settings.appBlockMinutes
            privacyProtectEnabled = settings.privacyProtectEnabled
            sensitiveApps = settings.sensitiveApps
        }

        // ── 自动保存 ─────────────────────────────────────────────
        // 任一设置变化即持久化（onChange 即生效；含初始组合的一次写入，无害）。
        // 防抖提示：停止操作 2 秒后弹出「已自动保存」；
        // 方向为「降低限制」时不直接保存，改为弹答题验证。
        var isFirstSave by remember { mutableStateOf(true) }
        LaunchedEffect(
            apiBaseUrl, apiKey, modelName, apiFormat, aiCustomPrompt,
            intervalMinutes, confidenceThreshold, consecutiveViolations, whitelist,
            enforcementMode, tokenSavingEnabled, screenHashDedup,
            screenTextPrefilter, decisionCacheEnabled, adaptiveInterval, dailyCallLimit,
            aiLockMinutes, aiLockStrength, aiAlertEnabled, aiAlertDelaySeconds,
            appBlockMinutes, customMottos, smartScheduleEnabled,
            studyKeywords, entertainmentKeywords,
            privacyProtectEnabled, sensitiveApps
        ) {
            if (isFirstSave) {
                // 首次组合：只保存不提示（避免一进页面就弹"已保存"）
                isFirstSave = false
                saveAll()
                return@LaunchedEffect
            }
            // 防抖：状态变化后等 2 秒（期间再变化会取消重启），无变化才处理
            kotlinx.coroutines.delay(2000)
            if (isLoosening() && com.focusguard.app.data.LockState(context).isLocked) {
                // 锁机中（含暂停）：放宽类改动一律拒绝，答题也不行
                revertUnsaved()
                saveAll()
                Toast.makeText(context, "锁机期间不能放宽限制，已恢复原设置", Toast.LENGTH_LONG).show()
            } else if (isLoosening()) {
                // 降低限制：弹答题验证（通过后才保存）
                if (!showVerifyDialog) showVerifyDialog = true
            } else {
                saveAll()
                try {
                    Toast.makeText(context, "已自动保存", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    // Toast 失败不影响
                }
            }
        }

        // ── 降低限制的答题验证对话框（答错即换题、错 2 次冷却 5 分钟、换题 5 次） ──
        if (showVerifyDialog) {
            com.focusguard.app.ui.components.VerifyDialog(
                title = "降低限制需先答题",
                description = "你正在降低对自己的限制（如缩短锁机、调大间隔、修改白名单或检测配置）。为防止限制被随意解除，请先答对一道题。",
                confirmText = "验证并保存",
                onPassed = {
                    showVerifyDialog = false
                    saveAll()
                    Toast.makeText(context, "已自动保存", Toast.LENGTH_SHORT).show()
                },
                onCancel = {
                    showVerifyDialog = false
                    revertUnsaved()
                    Toast.makeText(context, "已恢复原设置", Toast.LENGTH_SHORT).show()
                }
            )
        }

        Text(
            text = "设置自动保存，改动即时生效",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

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
            tint = (if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                .copy(alpha = contentAlpha),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = if (highlight) 15.sp else 14.sp,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = contentAlpha)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f * contentAlpha)
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onBackground,
                checkedTrackColor = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primaryContainer
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
            Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        }
    }
}

@Composable
/** 设置分组标题（分隔不同类别，让设置页更清晰）。 */
private fun SettingsGroupHeader(title: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
    )
}

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)), colors = CardDefaults.cardColors(
            containerColor = com.focusguard.app.ui.theme.cardContainer()
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
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
                Settings.EnforcementMode.LOCK -> "全局锁机" to Icons.Default.Lock
                Settings.EnforcementMode.APP_BLOCK -> "仅锁该软件" to Icons.Default.Block
                Settings.EnforcementMode.WARN -> "仅提醒" to Icons.Default.Notifications
            }
            val isSelected = mode == selected

            FilterChip(shape = RoundedCornerShape(10.dp),
                selected = isSelected,
                onClick = { onSelect(mode) },
                label = { Text(label, fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = when (selected) {
            Settings.EnforcementMode.LOCK -> "检测到娱乐后全屏锁机，需答题或等时间结束才能继续使用手机"
            Settings.EnforcementMode.APP_BLOCK -> "检测到娱乐后只封锁该应用：打开即被挡住，退出后其他应用不受影响，封锁时长见下方设置"
            Settings.EnforcementMode.WARN -> "只弹横幅提醒，不锁机"
        },
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    )
}
