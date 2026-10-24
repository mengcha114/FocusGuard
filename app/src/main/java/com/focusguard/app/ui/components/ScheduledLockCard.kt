package com.focusguard.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LockState
import com.focusguard.app.data.ScheduledLock
import com.focusguard.app.data.ScheduledLockRule
import com.focusguard.app.data.Settings

/**
 * 设置页「定时锁机」卡片。
 *
 * 规则由守护服务每 300ms 巡检触发（不依赖精确闹钟权限）。**新增/编辑立刻生效**；
 * 「关闭」「删除」属于放宽限制 ⇒ 需要答题（和防破解开关同一条规则），锁机期间直接拒绝。
 */
@Composable
fun ScheduledLockCard() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var rules by remember { mutableStateOf(settings.scheduledLocks) }
    var editing by remember { mutableStateOf<ScheduledLockRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingLoosen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    fun persist(list: List<ScheduledLockRule>) {
        rules = list
        settings.scheduledLocks = list
    }

    /** 关闭/删除：锁机期间拒绝，未锁机则先答题。 */
    fun loosen(apply: () -> Unit) {
        if (LockState(context).isLocked) {
            hint = "锁机期间不能关闭或删除定时锁机"
            return
        }
        pendingLoosen = apply
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "到点由守护服务直接锁机（不需要精确闹钟权限，也不怕闹钟被系统清理）。" +
                "已锁机时会自动延长，不会被更短的锁覆盖。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (rules.isEmpty()) {
            Text(
                "还没有规则。比如「每天 21:30 锁 60 分钟」。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        rules.forEach { rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = com.focusguard.app.ui.theme.cardContainer()
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${dayText(rule.days)} ${"%02d:%02d".format(rule.hour, rule.minute)}" +
                                    " · 锁 ${rule.minutes} 分钟",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "下次：" + ScheduledLock.nextTriggerText(System.currentTimeMillis(), rule) +
                                    " · 强度 ${rule.strength}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                if (on) {
                                    persist(rules.map { if (it.id == rule.id) it.copy(enabled = true) else it })
                                } else {
                                    loosen {
                                        persist(rules.map { if (it.id == rule.id) it.copy(enabled = false) else it })
                                    }
                                }
                            }
                        )
                    }
                    Row {
                        TextButton(onClick = { editing = rule }) { Text("编辑", fontSize = 12.sp) }
                        TextButton(onClick = { loosen { persist(rules.filterNot { it.id == rule.id }) } }) {
                            Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        hint?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { hint = null }) { Text("知道了", fontSize = 12.sp) }
        }
        Button(
            onClick = { adding = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text("新增定时锁机", fontSize = 14.sp) }

        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
        Text(
            "关闭或删除需要答题（属于放宽限制）；锁机期间不能改。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (adding || editing != null) {
        ScheduledLockEditDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { rule ->
                val list = if (editing == null) rules + rule
                else rules.map { if (it.id == rule.id) rule else it }
                persist(list)
                adding = false
                editing = null
            }
        )
    }

    pendingLoosen?.let { apply ->
        VerifyDialog(
            title = "关闭定时锁机需要先答题",
            description = "定时锁机是锁机计划，关闭它属于放宽限制。本题由应用本地题库按你的年级出题，" +
                "与 AI 无关；答错立即换题，错 2 次要等 5 分钟。",
            confirmText = "验证并关闭",
            onPassed = {
                val action = pendingLoosen
                pendingLoosen = null
                action?.invoke()
            },
            onCancel = { pendingLoosen = null }
        )
    }
}

private fun dayText(days: Set<Int>): String = when {
    days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "工作日"
    days == setOf(6, 7) -> "周末"
    else -> {
        val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        days.sorted().joinToString("") { names[it - 1] }
    }
}

@Composable
private fun ScheduledLockEditDialog(
    initial: ScheduledLockRule?,
    onDismiss: () -> Unit,
    onSave: (ScheduledLockRule) -> Unit
) {
    val base = initial ?: ScheduledLockRule()
    var days by remember { mutableStateOf(base.days) }
    var hourText by remember { mutableStateOf("%02d".format(base.hour)) }
    var minuteText by remember { mutableStateOf("%02d".format(base.minute)) }
    var minutesText by remember { mutableStateOf(base.minutes.toString()) }
    var strength by remember { mutableStateOf(base.strength) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增定时锁机" else "编辑定时锁机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("生效日期", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    arrayOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, label ->
                        val d = i + 1
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days) days - d else days + d },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.take(2); error = null },
                        label = { Text("时") },
                        singleLine = true,
                        modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.take(2); error = null },
                        label = { Text("分") },
                        singleLine = true,
                        modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.take(4); error = null },
                        label = { Text("锁机分钟") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Text("解锁强度", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { s ->
                        FilterChip(
                            selected = strength == s,
                            onClick = { strength = s },
                            label = { Text("$s", fontSize = 12.sp) }
                        )
                    }
                }
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val h = hourText.toIntOrNull()
                val m = minuteText.toIntOrNull()
                val mins = minutesText.toIntOrNull()
                if (days.isEmpty()) { error = "至少选一天"; return@Button }
                if (h == null || h !in 0..23) { error = "小时要在 0~23"; return@Button }
                if (m == null || m !in 0..59) { error = "分钟要在 0..59"; return@Button }
                if (mins == null || mins < 5) { error = "锁机时长至少 5 分钟"; return@Button }
                onSave(
                    base.copy(
                        days = days, hour = h, minute = m,
                        minutes = mins.coerceAtMost(24 * 60), strength = strength, enabled = true
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
