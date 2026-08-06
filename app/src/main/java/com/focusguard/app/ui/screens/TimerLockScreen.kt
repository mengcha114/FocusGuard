package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerLockScreen(onBack: () -> Unit) {
    var selectedMinutes by remember { mutableStateOf(30) }
    var isLocked by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(isLocked, remainingSeconds) {
        if (isLocked && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        } else if (isLocked && remainingSeconds == 0) {
            isLocked = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "定时锁机",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        if (isLocked) {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            val timeText = String.format("%02d:%02d", minutes, seconds)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F).copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFFF44336)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("设备锁定中", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(timeText, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("需要提前解锁？请点击下方完成 AI 挑战答题", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("选择锁机时长", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        FilterChip(
                            selected = selectedMinutes == mins,
                            onClick = { selectedMinutes = mins },
                            label = { Text("$mins 分钟") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(90, 120, 180, 240).forEach { mins ->
                        FilterChip(
                            selected = selectedMinutes == mins,
                            onClick = { selectedMinutes = mins },
                            label = { Text("${mins / 60} 小时") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("规则说明：", fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("1. 锁机期间无法开启受限应用\n2. 紧急情况下可解答 AI 逻辑/数学题解锁\n3. 答错将自动换题并给出正确答案", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }

        Button(
            onClick = {
                if (!isLocked) {
                    isLocked = true
                    remainingSeconds = selectedMinutes * 60
                } else {
                    // Trigger challenge unlock
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isLocked) Color(0xFFD32F2F) else Color(0xFF7C4DFF)
            )
        ) {
            Icon(if (isLocked) Icons.Default.LockOpen else Icons.Default.Lock, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isLocked) "挑战答题提前解锁" else "开启强制锁机 ($selectedMinutes 分钟)", fontSize = 18.sp)
        }
    }
}
