package com.focusguard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LogStore

@Composable
fun LogScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val logStore = remember { LogStore(context) }
    var logs by remember { mutableStateOf(logStore.getAllLogs()) }

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
            Text(
                text = "检测日志",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            TextButton(
                onClick = {
                    logStore.clearLogs()
                    logs = emptyList()
                }
            ) {
                Text("清空", color = Color(0xFFF44336))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { log ->
                    LogEntryItem(
                        timestamp = log.timestamp,
                        time = log.getTimeFormatted(),
                        date = log.getDateFormatted(),
                        classification = log.classification,
                        reason = log.reason,
                        confidence = log.confidence,
                        action = log.action
                    )
                }
            }
        }
    }
}

@Composable
fun LogEntryItem(
    timestamp: Long,
    time: String,
    date: String,
    classification: String,
    reason: String,
    confidence: Float,
    action: String
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
    val actionLabel = when (action) {
        "LOCK" -> "锁机"
        "EXIT" -> "退出"
        "WARN" -> "警告"
        else -> "无"
    }
    val actionColor = when (action) {
        "LOCK" -> Color(0xFFF44336)
        "EXIT" -> Color(0xFFFF9800)
        "WARN" -> Color(0xFFFFC107)
        else -> Color(0xFF9E9E9E)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF263238)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = time,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = date,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.3f)
                    )
                }
                Row {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = color
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(confidence * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = color.copy(alpha = 0.7f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = reason,
                fontSize = 14.sp,
                color = Color.White
            )
            if (action != "NONE" && action.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Text(
                        text = "执法: ",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = actionLabel,
                        fontSize = 12.sp,
                        color = actionColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
