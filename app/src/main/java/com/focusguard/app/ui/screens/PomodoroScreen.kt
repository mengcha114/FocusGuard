package com.focusguard.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun PomodoroScreen() {
    var isRunning by remember { mutableStateOf(false) }
    var isWorkPhase by remember { mutableStateOf(true) } // true: 25min, false: 5min
    var timeLeftSeconds by remember { mutableStateOf(25 * 60) }
    var completedSessions by remember { mutableStateOf(0) }

    LaunchedEffect(isRunning, timeLeftSeconds) {
        if (isRunning && timeLeftSeconds > 0) {
            delay(1000L)
            timeLeftSeconds--
        } else if (isRunning && timeLeftSeconds == 0) {
            isRunning = false
            if (isWorkPhase) {
                completedSessions++
                isWorkPhase = false
                timeLeftSeconds = 5 * 60
            } else {
                isWorkPhase = true
                timeLeftSeconds = 25 * 60
            }
        }
    }

    val totalTime = if (isWorkPhase) 25 * 60 else 5 * 60
    val progress = (totalTime - timeLeftSeconds).toFloat() / totalTime

    val minutes = timeLeftSeconds / 60
    val seconds = timeLeftSeconds % 60
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "番茄钟",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isWorkPhase) "专注阶段 (25 分钟)" else "休息阶段 (5 分钟)",
                fontSize = 16.sp,
                color = if (isWorkPhase) Color(0xFF7C4DFF) else Color(0xFF4CAF50),
                fontWeight = FontWeight.Medium
            )
        }

        // Circular Timer Display
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(280.dp)
        ) {
            val strokeColor = if (isWorkPhase) Color(0xFF7C4DFF) else Color(0xFF4CAF50)
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = strokeColor.copy(alpha = 0.15f),
                    style = Stroke(width = 16.dp.toPx())
                )
                drawArc(
                    color = strokeColor,
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = timeFormatted,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isRunning) "专注中..." else "准备就绪",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        // Session Count
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "今日已完成 $completedSessions 个番茄钟",
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
        }

        // Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { isRunning = !isRunning },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFFF9800) else Color(0xFF7C4DFF)
                )
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRunning) "暂停" else "开始", fontSize = 18.sp)
            }

            OutlinedButton(
                onClick = {
                    isRunning = false
                    timeLeftSeconds = if (isWorkPhase) 25 * 60 else 5 * 60
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("重置", fontSize = 18.sp)
            }
        }
    }
}
