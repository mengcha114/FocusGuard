package com.focusguard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LockState
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

    var selectedMinutes by remember { mutableIntStateOf(30) }
    var selectedMode by remember { mutableStateOf(LockMode.PLAIN) }
    var pomodoroRounds by remember { mutableIntStateOf(4) }
    var customMinutes by remember { mutableStateOf("") }
    var unlockStrength by remember { mutableIntStateOf(1) }
    var pauseEnabled by remember { mutableStateOf(false) }
    var pauseQuota by remember { mutableIntStateOf(3) }
    var pauseMinutes by remember { mutableIntStateOf(5) }

    // 软件锁机只需无障碍：锁机时拦截所有切换到其他应用的尝试
    val accessibilityOn = PermissionChecker.isAccessibilityEnabled(context)
    val ready = accessibilityOn

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
                            "需要开启无障碍服务才能锁机",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "锁机为全屏软件覆盖，无障碍用于拦截切换到其他应用",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
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

        // ── 解锁强度 ──────────────────────────────────
        Text("解锁强度", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        val strengthOptions = listOf(
            1 to "答题解锁",
            2 to "连对5题",
            3 to "朋友辅助",
            4 to "不可解锁"
        )        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            strengthOptions.forEach { (level, label) ->
                FilterChip(
                    selected = unlockStrength == level,
                    onClick = { unlockStrength = level },
                    label = { Text("强度$level $label", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = when (unlockStrength) {
                1 -> "答对 1 道高难度题即可提前解锁"
                2 -> "必须连续答对 5 道题才能解锁"
                3 -> "锁机页显示加密代码，朋友用解密工具算出密码后输入解锁"
                else -> "完全无法提前解锁，只能等时间结束"
            },
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.55f)
        )

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
            Spacer(Modifier.height(8.dp))
            // ── 自定义时长 ──────────────────────────────
            OutlinedTextField(
                value = customMinutes,
                onValueChange = { input ->
                    customMinutes = input
                    val parsed = input.trim().toIntOrNull()
                    if (parsed != null && parsed > 0) {
                        selectedMinutes = parsed
                    }
                },
                label = { Text("自定义时长（分钟）") },
                placeholder = { Text("输入任意分钟数，如 25") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                text = "输入后自动选中该时长",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f)
            )
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

        // ── 暂停设置 ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F23))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("允许中途暂停", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(
                            "每次暂停需答对 1 道题获取",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                    Switch(
                        checked = pauseEnabled,
                        onCheckedChange = { pauseEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF4F378B)
                        )
                    )
                }

                if (pauseEnabled) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(12.dp))

                    Text("暂停次数", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3, 5).forEach { n ->
                            FilterChip(
                                selected = pauseQuota == n,
                                onClick = { pauseQuota = n },
                                label = { Text("$n 次", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("每次暂停时长", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(3, 5, 10, 15).forEach { mins ->
                            FilterChip(
                                selected = pauseMinutes == mins,
                                onClick = { pauseMinutes = mins },
                                label = { Text("$mins 分钟", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
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
                        "4. 解锁方式取决于所选强度（1-4 级）",
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
                lockState.unlockStrength = unlockStrength
                lockState.pauseEnabled = pauseEnabled
                lockState.pauseQuota = pauseQuota
                lockState.pauseMinutes = pauseMinutes
                if (unlockStrength == 3) {
                    // 强度 3：预先生成凯撒密文，锁机页直接展示
                    lockState.setupFriendChallenge()
                }                if (selectedMode == LockMode.POMODORO) {
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
