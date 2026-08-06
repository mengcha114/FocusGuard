package com.focusguard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LockState
import com.focusguard.app.data.Settings
import com.focusguard.app.enforce.LockScreenActivity
import com.focusguard.app.util.PermissionChecker

/**
 * 锁机配置页。
 *
 * 锁机指的是全局全屏、用户无法退出的强制锁定。
 * 番茄钟是锁机的一种模式：专注阶段锁定、休息阶段自动放开。
 */
enum class LockMode(val label: String, val description: String) {
    PLAIN("持续锁机", "整段时间内持续锁定，直到时间结束或答题解锁"),
    POMODORO("番茄钟模式", "专注 25 分钟锁定 + 休息 5 分钟放开，循环进行")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerLockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lockState = remember { LockState(context) }
    val settings = remember { Settings(context) }

    var selectedMinutes by remember { mutableIntStateOf(30) }
    var selectedMode by remember { mutableStateOf(LockMode.PLAIN) }
    var pomodoroRounds by remember { mutableIntStateOf(4) }

    val accessibilityOn = PermissionChecker.isAccessibilityEnabled(context)
    val overlayOn = PermissionChecker.canDrawOverlays(context)
    val ready = accessibilityOn && overlayOn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text("强制锁机", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // ── 权限提示 ──────────────────────────────────
        if (!ready) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2E34))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFC6786F),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "锁机需要以下权限才能生效",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        if (!accessibilityOn) {
                            Text(
                                "· 无障碍服务：用于拦截切换到其他应用",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                        if (!overlayOn) {
                            Text(
                                "· 悬浮窗：用于在其他应用之上显示锁定界面",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // ── 锁机模式 ──────────────────────────────────
        Text("锁机模式", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        LockMode.entries.forEach { mode ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedMode == mode) Color(0xFF332D41) else Color(0xFF1F1F23)
                ),
                onClick = { selectedMode = mode }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFD0BCFF))
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            mode.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            mode.description,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }

        // ── 时长 / 轮数 ───────────────────────────────
        if (selectedMode == LockMode.PLAIN) {
            Text("锁机时长", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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
        } else {
            Text("番茄钟轮数", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(2, 4, 6, 8).forEach { rounds ->
                    FilterChip(
                        selected = pomodoroRounds == rounds,
                        onClick = { pomodoroRounds = rounds },
                        label = { Text("$rounds 轮") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(
                text = "共 ${pomodoroRounds * 25} 分钟专注 + ${pomodoroRounds * 5} 分钟休息",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }

        // ── 规则说明 ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F23))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("锁机规则", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "1. 锁定期间全屏覆盖，返回键与 Home 键均无效\n" +
                        "2. 切换到任何其他应用都会被立即顶回锁定界面\n" +
                        "3. 强杀进程也无法绕过——锁机状态已持久化\n" +
                        "4. 需要提前解锁必须答对 ${settings.unlockQuestionCount} 道高难度计算题",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    lineHeight = 19.sp
                )
            }
        }

        // ── 开始按钮 ──────────────────────────────────
        Button(
            onClick = {
                val totalMinutes = if (selectedMode == LockMode.PLAIN) {
                    selectedMinutes
                } else {
                    // 番茄钟：专注 + 休息合计，休息阶段由锁屏页内部放开
                    pomodoroRounds * 30
                }
                lockState.startLock(totalMinutes, selectedMode.name)
                if (selectedMode == LockMode.POMODORO) {
                    lockState.pomodoroRunning = true
                    lockState.pomodoroIsWorkPhase = true
                    lockState.pomodoroRoundsLeft = pomodoroRounds
                    lockState.pomodoroEnd = System.currentTimeMillis() + 25 * 60_000L
                }
                LockScreenActivity.show(context)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = ready,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF7C4DFF),
                disabledContainerColor = Color(0xFF2A2A2E)
            )
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    !ready -> "请先完成上方权限"
                    selectedMode == LockMode.PLAIN -> "开始锁机（$selectedMinutes 分钟）"
                    else -> "开始番茄钟锁机（$pomodoroRounds 轮）"
                },
                fontSize = 17.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
