package com.focusguard.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LockState
import com.focusguard.app.enforce.LockScreenActivity
import com.focusguard.app.ui.components.*
import com.focusguard.app.util.PermissionChecker
import kotlinx.coroutines.delay

/**
 * 锁机配置页。
 *
 * 锁机指的是全局全屏、用户无法退出的强制锁定。
 * 番茄钟是锁机的一种模式：专注阶段锁定、休息阶段自动放开。
 */
enum class LockMode(val label: String, val description: String) {
    PLAIN("持续锁机", "整段时间持续锁定"),
    POMODORO("番茄钟", "专注锁定 · 休息放开")
}

/** 番茄钟预设：专注 / 休息（分钟）。 */
private val pomodoroPresets = listOf(25 to 5, 50 to 10, 90 to 20)

/** 当前设备的防护等级（决定锁机能否被绕过）。 */
enum class ProtectionLevel(val label: String, val detail: String) {
    SYSTEM("系统级", "Dhizuku Lock Task：手势、最近任务、强行停止全部失效"),
    OVERLAY("普通模式", "全屏锁机界面 + 无障碍拦截；国产 ROM 一键清理仍可能终止守护"),
    BASIC("基础", "缺少悬浮窗权限，锁机页可能被手势切走")
}

fun protectionLevel(context: android.content.Context): ProtectionLevel = when {
    com.focusguard.app.enhance.DhizukuEnhancer.shouldPreferActivity(context) -> ProtectionLevel.SYSTEM
    PermissionChecker.canDrawOverlays(context) -> ProtectionLevel.OVERLAY
    else -> ProtectionLevel.BASIC
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerLockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lockState = remember { LockState(context) }

    var selectedMinutes by remember { mutableIntStateOf(30) }
    var selectedMode by remember { mutableStateOf(LockMode.PLAIN) }
    var pomodoroRounds by remember { mutableIntStateOf(4) }
    var workMinutes by remember { mutableIntStateOf(25) }
    var breakMinutes by remember { mutableIntStateOf(5) }
    var customWork by remember { mutableStateOf("") }
    var customBreak by remember { mutableStateOf("") }
    var customMinutes by remember { mutableStateOf("") }
    var unlockStrength by remember { mutableIntStateOf(1) }
    var pauseEnabled by remember { mutableStateOf(false) }
    var pauseQuota by remember { mutableIntStateOf(3) }
    var pauseMinutes by remember { mutableIntStateOf(5) }
    var showConfirm by remember { mutableStateOf(false) }

    // 锁机中（含暂停 / 番茄钟休息）：配置页只读，新锁机只能延长当前锁机
    var activeRemaining by remember { mutableIntStateOf(lockState.remainingSeconds) }
    LaunchedEffect(Unit) {
        while (true) {
            activeRemaining = lockState.remainingSeconds
            delay(1000)
        }
    }
    val lockActive = activeRemaining > 0

    // 软件锁机只需无障碍：锁机时拦截所有切换到其他应用的尝试
    val accessibilityOn = PermissionChecker.isAccessibilityEnabled(context)
    val ready = accessibilityOn && !lockActive
    val protection = remember { protectionLevel(context) }

    val totalMinutes = if (selectedMode == LockMode.PLAIN) selectedMinutes
    else pomodoroRounds * (workMinutes + breakMinutes)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("强制锁机", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "防护等级 · ${protection.label}",
                    fontSize = 12.sp,
                    color = when (protection) {
                        ProtectionLevel.SYSTEM -> MaterialTheme.colorScheme.primary
                        ProtectionLevel.OVERLAY -> MaterialTheme.colorScheme.onSurfaceVariant
                        ProtectionLevel.BASIC -> MaterialTheme.colorScheme.error
                    }
                )
            }
        }

        // ── 锁机进行中 ──
        FxExpand(visible = lockActive) {
            FxNotice(
                text = "当前锁机剩余 ${formatDuration(activeRemaining)}。锁机期间不能新开或缩短锁机，配置已锁定。",
                icon = Icons.Default.Lock
            )
        }

        // ── 权限提示 ──
        FxExpand(visible = !accessibilityOn) {
            FxNotice(
                text = "需要开启无障碍服务才能锁机：用于拦截切换到其他应用",
                tone = MaterialTheme.colorScheme.error,
                icon = Icons.Default.Info
            )
        }
        FxExpand(visible = protection == ProtectionLevel.BASIC && accessibilityOn) {
            FxNotice(
                text = "未授予悬浮窗权限，普通模式无法启用，锁机页可能被手势切走。建议在权限页授予悬浮窗权限，或配置 Dhizuku 获得系统级锁机。",
                tone = MaterialTheme.colorScheme.error,
                icon = Icons.Default.Warning
            )
        }

        // ── 锁机模式 ──
        FxSection(title = "锁机模式", icon = Icons.Default.Timer) {
            FxOptionRow(
                options = LockMode.entries,
                selected = selectedMode,
                label = { it.label },
                caption = { it.description },
                onSelect = { selectedMode = it },
                enabled = !lockActive
            )

            AnimatedContent(
                targetState = selectedMode,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "modeContent"
            ) { mode ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode == LockMode.PLAIN) {
                        Text("时长", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FxOptionRow(
                            options = listOf(15, 30, 45, 60),
                            selected = selectedMinutes,
                            label = { "$it 分" },
                            onSelect = { selectedMinutes = it; customMinutes = "" },
                            enabled = !lockActive
                        )
                        FxOptionRow(
                            options = listOf(90, 120, 180, 240),
                            selected = selectedMinutes,
                            label = { if (it % 60 == 0) "${it / 60} 小时" else "${it / 60.0} 小时" },
                            onSelect = { selectedMinutes = it; customMinutes = "" },
                            enabled = !lockActive
                        )
                        MinutesField(
                            value = customMinutes,
                            label = "自定义时长（1–720 分钟）",
                            enabled = !lockActive,
                            onValue = { v, parsed ->
                                customMinutes = v
                                if (parsed != null) selectedMinutes = parsed.coerceIn(1, 720)
                            }
                        )
                    } else {
                        Text("专注 / 休息", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FxOptionRow(
                            options = pomodoroPresets,
                            selected = workMinutes to breakMinutes,
                            label = { "${it.first} / ${it.second}" },
                            caption = { "分钟" },
                            onSelect = {
                                workMinutes = it.first; breakMinutes = it.second
                                customWork = ""; customBreak = ""
                            },
                            enabled = !lockActive
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MinutesField(
                                value = customWork,
                                label = "专注 5–180",
                                enabled = !lockActive,
                                modifier = Modifier.weight(1f),
                                onValue = { v, parsed ->
                                    customWork = v
                                    if (parsed != null) workMinutes = parsed.coerceIn(5, 180)
                                }
                            )
                            MinutesField(
                                value = customBreak,
                                label = "休息 1–60",
                                enabled = !lockActive,
                                modifier = Modifier.weight(1f),
                                onValue = { v, parsed ->
                                    customBreak = v
                                    if (parsed != null) breakMinutes = parsed.coerceIn(1, 60)
                                }
                            )
                        }
                        Text("轮数", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FxOptionRow(
                            options = listOf(2, 4, 6, 8),
                            selected = pomodoroRounds,
                            label = { "$it 轮" },
                            onSelect = { pomodoroRounds = it },
                            enabled = !lockActive
                        )
                        Text(
                            "共 ${pomodoroRounds * workMinutes} 分钟专注 + ${pomodoroRounds * breakMinutes} 分钟休息，阶段切换时会通知你",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── 解锁强度 ──
        FxSection(title = "解锁强度", icon = Icons.Default.Shield) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "答题", 2 to "连对5题").forEach { (level, label) ->
                    FxOption(
                        label = label, caption = "强度 $level",
                        selected = unlockStrength == level,
                        onClick = { unlockStrength = level },
                        enabled = !lockActive,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3 to "朋友辅助", 4 to "不可解锁").forEach { (level, label) ->
                    FxOption(
                        label = label, caption = "强度 $level",
                        selected = unlockStrength == level,
                        onClick = {
                            unlockStrength = level
                            if (level == LockState.STRENGTH_LOCKED_FOREVER) pauseEnabled = false
                        },
                        enabled = !lockActive,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            AnimatedContent(
                targetState = unlockStrength,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "strengthHint"
            ) { level ->
                Text(
                    text = when (level) {
                        1 -> "答对 1 道题即可提前解锁"
                        2 -> "必须连续答对 5 道题才能解锁"
                        3 -> "锁机页显示加密代码，朋友解密出密码后输入解锁"
                        else -> "完全无法提前解锁，也不能暂停，只能等时间结束"
                    },
                    fontSize = 12.sp,
                    color = if (level == 4) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 暂停设置（强度 4 不可用） ──
        val pauseAllowed = unlockStrength < LockState.STRENGTH_LOCKED_FOREVER
        FxSection(
            title = "中途暂停",
            subtitle = if (pauseAllowed) "每次暂停需答对 1 道题换取" else "强度 4 不允许暂停",
            icon = Icons.Default.PauseCircle
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("允许暂停", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(
                    checked = pauseEnabled && pauseAllowed,
                    onCheckedChange = { pauseEnabled = it },
                    enabled = pauseAllowed && !lockActive
                )
            }
            FxExpand(visible = pauseEnabled && pauseAllowed) {
                Text("暂停次数", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FxOptionRow(
                    options = listOf(1, 2, 3, 5),
                    selected = pauseQuota,
                    label = { "$it 次" },
                    onSelect = { pauseQuota = it },
                    enabled = !lockActive
                )
                Text("每次时长", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FxOptionRow(
                    options = listOf(3, 5, 10, 15),
                    selected = pauseMinutes,
                    label = { "$it 分" },
                    onSelect = { pauseMinutes = it },
                    enabled = !lockActive
                )
            }
        }

        FxPrimaryButton(
            text = when {
                lockActive -> "锁机进行中"
                !accessibilityOn -> "请先开启无障碍服务"
                selectedMode == LockMode.PLAIN -> "开始锁机 · ${formatDuration(totalMinutes * 60)}"
                else -> "开始番茄钟 · $pomodoroRounds 轮"
            },
            icon = Icons.Default.Lock,
            enabled = ready,
            onClick = { showConfirm = true }
        )

        Spacer(Modifier.height(12.dp))
    }

    if (showConfirm) {
        ConfirmLockDialog(
            totalMinutes = totalMinutes,
            mode = selectedMode,
            strength = unlockStrength,
            pause = if (pauseEnabled && unlockStrength < 4) "$pauseQuota 次 × $pauseMinutes 分钟" else "不允许",
            protection = protection,
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                if (selectedMode == LockMode.PLAIN) {
                    lockState.startLock(selectedMinutes, selectedMode.name, unlockStrength)
                } else {
                    lockState.startPomodoro(pomodoroRounds, workMinutes, breakMinutes, unlockStrength)
                }
                lockState.configurePause(pauseEnabled, pauseQuota, pauseMinutes)
                // 先启动守护服务与看门狗，再拉起锁机页：
                // 守护服务是防破解主防线，必须在锁机页出现前就位
                com.focusguard.app.service.LockGuardService.start(context)
                com.focusguard.app.service.GuardWatchdogWorker.schedule(context)
                LockScreenActivity.show(context)
            }
        )
    }
}

@Composable
private fun MinutesField(
    value: String,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValue: (String, Int?) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }.take(3)
            onValue(digits, digits.toIntOrNull()?.takeIf { it > 0 })
        },
        label = { Text(label, fontSize = 12.sp) },
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/**
 * 开始前确认：汇总全部参数，3 秒倒计时后才能确认（防误触；强度 4 不可撤销）。
 */
@Composable
private fun ConfirmLockDialog(
    totalMinutes: Int,
    mode: LockMode,
    strength: Int,
    pause: String,
    protection: ProtectionLevel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("确认开始锁机") },
        text = {
            // 小屏/大字体下内容会超出弹窗高度（防护说明 + 强度 4 提示最长），
            // 加滚动避免文字被裁掉
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    formatDuration(totalMinutes * 60),
                    fontSize = 40.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                SummaryRow("模式", mode.label)
                SummaryRow(
                    "解锁", when (strength) {
                        1 -> "答对 1 题"; 2 -> "连对 5 题"; 3 -> "朋友辅助"; else -> "不可解锁"
                    }
                )
                SummaryRow("暂停", pause)
                SummaryRow("防护", protection.label)
                Text(protection.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (strength == LockState.STRENGTH_LOCKED_FOREVER) {
                    FxNotice(
                        text = "强度 4 开始后无法以任何方式提前结束。",
                        tone = MaterialTheme.colorScheme.error,
                        icon = Icons.Default.Warning
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = countdown == 0, shape = RoundedCornerShape(14.dp)) {
                Text(if (countdown > 0) "确认（$countdown）" else "确认锁机")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun SummaryRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 秒数 → 「X 小时 Y 分」/「Y 分 Z 秒」。 */
fun formatDuration(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 && m > 0 -> "$h 小时 $m 分"
        h > 0 -> "$h 小时"
        totalSeconds >= 600 || s == 0 -> "$m 分钟"
        else -> "$m 分 $s 秒"
    }
}
