package com.focusguard.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LogStore

/**
 * 检测日志界面。
 *
 * 修复要点：日志正文此前被折行截断（仅显示两行）。
 * 现在默认显示 3 行摘要，点击任意条目**完整展开**，
 * 展开态下正文不限行数、可长按复制，并额外显示
 * 来源、应用名、置信度等完整字段。
 */
@Composable
fun LogScreen() {
    val context = LocalContext.current
    val logStore = remember { LogStore(context) }
    var logs by remember { mutableStateOf(logStore.getAllLogs()) }
    var showDiagnostics by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "检测日志",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "共 ${logs.size} 条 · 点击条目展开完整信息",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
            Row {
                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                    Text(
                        if (showDiagnostics) "隐藏诊断" else "AI 诊断",
                        color = Color(0xFF8AB4F8),
                        fontSize = 13.sp
                    )
                }
                TextButton(
                    onClick = {
                        logStore.clearLogs()
                        logs = emptyList()
                    }
                ) {
                    Text("清空", color = Color(0xFFF44336), fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── AI 调用诊断（原始请求/响应，排查问题用） ──
        AnimatedVisibility(
            visible = showDiagnostics,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2332))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "AI 调用诊断（最近 40 次）",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF8AB4F8)
                        )
                        IconButton(
                            onClick = {
                                copyToClipboard(
                                    context,
                                    com.focusguard.app.ai.AiClient.exportDiagnostics()
                                )
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制",
                                tint = Color(0xFF8AB4F8),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = com.focusguard.app.ai.AiClient.exportDiagnostics(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.75f),
                        lineHeight = 15.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── 崩溃日志（闪退排查用，有记录才显示） ──
        val crashLog = remember { com.focusguard.app.FocusGuardApp.readCrashLog(context) }
        if (crashLog.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1F1F))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "崩溃日志（最近 ${
                                crashLog.split("===== ").size - 1
                            } 次闪退）",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF8A80)
                        )
                        IconButton(
                            onClick = { copyToClipboard(context, crashLog) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制",
                                tint = Color(0xFFFF8A80),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = crashLog,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.8f),
                        lineHeight = 15.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无检测记录",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 16.sp
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs, key = { it.timestamp }) { log ->
                    LogEntryItem(log = log, context = context)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LogEntryItem(log: DetectionLog, context: Context) {
    var expanded by remember { mutableStateOf(false) }

    val color = when (log.classification) {
        "STUDY_WORK" -> Color(0xFF4CAF50)
        "ENTERTAINMENT" -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }
    val label = when (log.classification) {
        "STUDY_WORK" -> "学习/工作"
        "ENTERTAINMENT" -> "娱乐"
        else -> "中性"
    }
    val actionLabel = when (log.action) {
        "LOCK" -> "锁机"
        "EXIT" -> "退出"
        "WARN" -> "警告"
        "APP_BLOCK" -> "应用封锁"
        else -> "无"
    }
    val actionColor = when (log.action) {
        "LOCK" -> Color(0xFFF44336)
        "EXIT" -> Color(0xFFFF9800)
        "WARN" -> Color(0xFFFFC107)
        "APP_BLOCK" -> Color(0xFFC6786F)
        else -> Color(0xFF9E9E9E)
    }
    val sourceLabel = when (log.source) {
        "WHITELIST" -> "白名单"
        "APP_CATEGORY" -> "应用分类"
        "SCREEN_TEXT" -> "屏幕文字"
        "SCREEN_UNCHANGED" -> "画面未变"
        "CACHE_HIT" -> "缓存命中"
        "BLANK_SCREEN" -> "空白画面"
        "BUDGET_EXCEEDED" -> "配额用尽"
        "AI_VISION" -> "AI 视觉"
        "ERROR" -> "错误"
        else -> log.source
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // ── 头部：时间 + 分类 + 置信度 ──────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = log.getTimeFormatted(),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = log.getDateFormatted(),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.3f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = color
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${(log.confidence * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = color.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 正文：折叠 3 行 / 展开完整 ──────────────
            Text(
                text = log.reason.ifBlank { "（无说明）" },
                fontSize = 13.sp,
                color = Color.White,
                lineHeight = 19.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 3
            )

            // ── 展开区：完整字段 ──────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))

                    DetailRow("判定来源", sourceLabel)
                    if (log.appLabel.isNotBlank()) {
                        DetailRow("应用", log.appLabel)
                    }
                    DetailRow("执法动作", actionLabel, actionColor)
                    DetailRow("置信度", "${(log.confidence * 100).toInt()}%")
                    DetailRow("完整时间", "${log.getDateFormatted()} ${log.getTimeFormatted()}")

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(
                                context,
                                buildString {
                                    append("[${log.getDateFormatted()} ${log.getTimeFormatted()}] ")
                                    append("$label ${(log.confidence * 100).toInt()}% ")
                                    append("来源=$sourceLabel 动作=$actionLabel ")
                                    append("应用=${log.appLabel.ifBlank { "-" }}\n")
                                    append("原因：${log.reason}")
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("复制这条日志", fontSize = 12.sp)
                    }
                }
            }

            // 折叠态下若有执法动作，仍显示一个小标记
            if (!expanded && log.action != "NONE" && log.action.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "执法：",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                    Text(
                        text = actionLabel,
                        fontSize = 11.sp,
                        color = actionColor,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "来源：$sourceLabel",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.45f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = valueColor.copy(alpha = 0.9f),
            fontWeight = FontWeight.Medium
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FocusGuard 日志", text))
        android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT)
            .show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "复制失败：${e.message}", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}
