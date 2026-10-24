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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
 * 设计目标：**一眼看懂、两步设置完**。
 * - 顶部一句状态摘要（有几个计划、下次什么时候）；
 * - 每条计划显示成一张卡：大字号时间 + 「每天 · 锁 1 小时」；
 * - 新增时用「每天 / 工作日 / 周末」+「30 分钟 / 1 小时 / 2 小时…」预设点选，
 *   不用自己算分钟数；下方实时预览「将于 今天 21:30 开始锁 1 小时」。
 *
 * 安全规则：新增/编辑直接生效（收紧）；关闭/删除需答题；**锁机期间一律不能改**。
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
    val locked = remember { LockState(context).isLocked }

    fun persist(list: List<ScheduledLockRule>) {
        rules = list
        settings.scheduledLocks = list
    }

    fun loosen(apply: () -> Unit) {
        if (locked) {
            hint = "锁机期间不能修改定时锁机计划"
            return
        }
        pendingLoosen = apply
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── 顶部状态摘要：一眼知道"有几个、下次什么时候" ──
        val enabled = rules.filter { it.enabled }
        val nextRule = enabled.mapNotNull { r ->
            ScheduledLock.nextTriggerInMs(System.currentTimeMillis(), r)?.let { d -> d to r }
        }.minByOrNull { it.first }?.second
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    when {
                        enabled.isEmpty() -> "还没有启用的计划"
                        else -> "已启用 ${enabled.size} 个计划"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (nextRule != null) {
                        "下次自动锁机：" + ScheduledLock.nextTriggerText(System.currentTimeMillis(), nextRule)
                    } else {
                        "点下面「新增计划」，选好时间点保存就行"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 每条计划：大字号时间 + 通俗副标题 + 开关 ──
        rules.forEach { rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = com.focusguard.app.ui.theme.cardContainer()
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%02d:%02d".format(rule.hour, rule.minute),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (rule.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                dayText(rule.days),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "锁 ${lengthText(rule.minutes)}  ·  解锁强度 ${rule.strength}",
                                fontSize = 12.sp,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "下次：" + ScheduledLock.nextTriggerText(System.currentTimeMillis(), rule),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { editing = rule }, enabled = !locked) {
                            Text("修改", fontSize = 12.sp)
                        }
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
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (locked) "锁机期间不能修改计划" else "＋ 新增计划", fontSize = 14.sp)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
        Text(
            "到点会自动锁机，锁机时答对题可以提前解锁。关闭或删除计划需要答题（避免被随手关掉）；" +
                "锁机期间不能修改。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
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
            description = "关闭或删除计划属于放宽限制。本题由应用本地题库按你的年级出题，与 AI 无关；" +
                "答错立即换题，错 2 次要等 5 分钟。",
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

/** 星期集合 → 人话。 */
private fun dayText(days: Set<Int>): String = when {
    days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "工作日（周一~周五）"
    days == setOf(6, 7) -> "周末（周六、周日）"
    else -> {
        val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        days.sorted().joinToString("、") { names[it - 1] }
    }
}

/** 分钟 → 人话。 */
private fun lengthText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "$h 小时 $m 分钟"
        h > 0 -> "$h 小时"
        else -> "$m 分钟"
    }
}

@Composable
private fun ScheduledLockEditDialog(
    initial: ScheduledLockRule?,
    onDismiss: () -> Unit,
    onSave: (ScheduledLockRule) -> Unit
) {
    val base = initial ?: ScheduledLockRule()
    // 预设一到就用预设；"自定义" 才自己勾星期
    var dayPreset by remember {
        mutableStateOf(
            when (base.days) {
                setOf(1, 2, 3, 4, 5, 6, 7) -> "每天"
                setOf(1, 2, 3, 4, 5) -> "工作日"
                setOf(6, 7) -> "周末"
                else -> "自定义"
            }
        )
    }
    var days by remember { mutableStateOf(base.days) }
    var hourText by remember { mutableStateOf("%02d".format(base.hour)) }
    var minuteText by remember { mutableStateOf("%02d".format(base.minute)) }
    var minutes by remember { mutableStateOf(base.minutes) }
    var strength by remember { mutableStateOf(base.strength) }
    var error by remember { mutableStateOf<String?>(null) }

    // 实时预览：改什么立刻看到"将于 XX 开始，锁 YY"
    val previewHour = hourText.toIntOrNull() ?: base.hour
    val previewMinute = minuteText.toIntOrNull() ?: base.minute
    val preview = if (days.isEmpty()) {
        "请至少选择一天"
    } else {
        val tmp = base.copy(days = days, hour = previewHour.coerceIn(0, 23),
            minute = previewMinute.coerceIn(0, 59), minutes = minutes)
        "将于 " + ScheduledLock.nextTriggerText(System.currentTimeMillis(), tmp) +
            " 开始，锁 " + lengthText(minutes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增定时锁机" else "修改定时锁机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. 多久一次", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                listOf("每天", "工作日", "周末", "自定义").chunked(2).forEach { row -> 
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { preset ->
                            FilterChip(
                                selected = dayPreset == preset,
                                onClick = {
                                    dayPreset = preset
                                    days = when (preset) {
                                        "每天" -> setOf(1, 2, 3, 4, 5, 6, 7)
                                        "工作日" -> setOf(1, 2, 3, 4, 5)
                                        "周末" -> setOf(6, 7)
                                        else -> days
                                    }
                                    error = null
                                },
                                label = { Text(preset, fontSize = 12.sp) }
                            )
                        }
                    }
                }
                if (dayPreset == "自定义") {
                    // 7 个按钮必须换行：此前挤一行，"日"被挤出屏幕 ⇒ 周日选不到（用户反馈）
                    listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四",
                        5 to "五", 6 to "六", 7 to "日").chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (d, label) ->
                                FilterChip(
                                    selected = d in days,
                                    onClick = { days = if (d in days) days - d else days + d },
                                    label = { Text("周$label", fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                Text("2. 几点开始", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter { c -> c.isDigit() }.take(2); error = null },
                        label = { Text("时") },
                        singleLine = true,
                        modifier = Modifier.width(84.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(" : ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter { c -> c.isDigit() }.take(2); error = null },
                        label = { Text("分") },
                        singleLine = true,
                        modifier = Modifier.width(84.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Text("3. 锁多久", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                listOf(30 to "30 分", 60 to "1 小时", 90 to "1.5 小时",
                    120 to "2 小时", 180 to "3 小时", 240 to "4 小时").chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (m, label) ->
                            FilterChip(
                                selected = minutes == m,
                                onClick = { minutes = m; error = null },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }

                Text("4. 解锁强度（越大越难解）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { s ->
                        FilterChip(
                            selected = strength == s,
                            onClick = { strength = s },
                            label = { Text("强度 $s", fontSize = 12.sp) }
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                ) {
                    Text(
                        preview,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val h = hourText.toIntOrNull()
                val m = minuteText.toIntOrNull()
                if (days.isEmpty()) { error = "至少选一天"; return@Button }
                if (h == null || h !in 0..23) { error = "小时填 0~23，例如 21"; return@Button }
                if (m == null || m !in 0..59) { error = "分钟填 0~59，例如 30"; return@Button }
                onSave(
                    base.copy(
                        days = days, hour = h, minute = m,
                        minutes = minutes.coerceAtLeast(5), strength = strength, enabled = true
                    )
                )
            }) { Text(if (initial == null) "保存计划" else "保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
