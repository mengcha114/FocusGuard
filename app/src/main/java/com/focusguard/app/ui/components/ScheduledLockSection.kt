package com.focusguard.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.ScheduledLock
import com.focusguard.app.data.ScheduledLockRule
import com.focusguard.app.data.Settings

/**
 * 定时锁机：计划列表 + 新增/修改/删除（**设置页和锁机模式页共用同一份**）。
 *
 * 排版遵循 DESIGN：inkCard 内 16dp 内边距、行间 1dp 细线、时间用大字号、说明用 11sp 灰字；
 * 选项一律用项目自有的 [FxOptionRow]。安全规则：新增/修改直接生效（收紧）；
 * 关闭/删除要答题；[locked] 为真（锁机中）时全部只读（新增也禁）。
 */
@Composable
fun ScheduledLockSection(locked: Boolean) {
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

    fun loosen(apply: () -> Unit) {
        if (locked) {
            hint = "锁机期间不能修改定时锁机计划"
            return
        }
        pendingLoosen = apply
    }

    val enabled = rules.filter { it.enabled }
    val nextRule = enabled.mapNotNull { r ->
        ScheduledLock.nextTriggerInMs(System.currentTimeMillis(), r)?.let { d -> d to r }
    }.minByOrNull { it.first }?.second

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ── 状态摘要 ──
        FxNotice(
            text = if (nextRule != null) {
                "已启用 ${enabled.size} 个计划，下次自动锁机：" +
                    ScheduledLock.nextTriggerText(System.currentTimeMillis(), nextRule)
            } else {
                "还没有计划。比如：每天 21:30 锁 1 小时，到点自动锁机、答题可提前解锁"
            },
            icon = Icons.Default.Alarm
        )

        // ── 计划列表 ──
        if (rules.isEmpty()) {
            Text(
                "点下面「新增计划」开始设置",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        rules.forEachIndexed { index, rule ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "%02d:%02d".format(rule.hour, rule.minute),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        color = if (rule.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        dayText(rule.days).uppercase(),
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "锁 ${lengthText(rule.minutes)} · 强度 ${rule.strength}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "下次：" + ScheduledLock.nextTriggerText(System.currentTimeMillis(), rule),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editing = rule }, enabled = !locked) {
                        androidx.compose.material3.Icon(
                            Icons.Default.Edit, contentDescription = "修改",
                            modifier = Modifier.width(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { loosen { persist(rules.filterNot { it.id == rule.id }) } }) {
                        androidx.compose.material3.Icon(
                            Icons.Default.Delete, contentDescription = "删除",
                            modifier = Modifier.width(18.dp),
                            tint = if (locked) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Switch(
                        checked = rule.enabled,
                        enabled = !locked,
                        onCheckedChange = { on ->
                            if (on) persist(rules.map { if (it.id == rule.id) it.copy(enabled = true) else it })
                            else loosen {
                                persist(rules.map { if (it.id == rule.id) it.copy(enabled = false) else it })
                            }
                        }
                    )
                }
            }
        }

        hint?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { adding = true },
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            androidx.compose.material3.Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (locked) "锁机期间不能修改计划" else "新增计划", fontSize = 14.sp)
        }

        Text(
            "到点会自动锁机；锁机时答对题可以提前解锁。关闭或删除计划需要答题，锁机期间不能修改。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (adding || editing != null) {
        ScheduledLockEditDialog(
            initial = editing,
            locked = locked,
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

/** 设置页用的薄包装（布局由外层的设置卡片提供）。 */
@Composable
fun ScheduledLockCard() {
    val context = LocalContext.current
    ScheduledLockSection(locked = com.focusguard.app.data.LockState(context).isLocked)
}

/** 星期集合 → 人话。 */
private fun dayText(days: Set<Int>): String = when {
    days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "工作日"
    days == setOf(6, 7) -> "周末"
    else -> {
        val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        days.sorted().joinToString(" ") { names[it - 1] }
    }
}

/** 分钟 → 人话。 */
private fun lengthText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "$h 小时 $m 分"
        h > 0 -> "$h 小时"
        else -> "$m 分钟"
    }
}

@Composable
private fun ScheduledLockEditDialog(
    initial: ScheduledLockRule?,
    locked: Boolean,
    onDismiss: () -> Unit,
    onSave: (ScheduledLockRule) -> Unit
) {
    val base = initial ?: ScheduledLockRule()
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
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

    val pH = hourText.toIntOrNull() ?: base.hour
    val pM = minuteText.toIntOrNull() ?: base.minute
    val preview = if (days.isEmpty()) "请至少选择一天" else {
        val tmp = base.copy(days = days, hour = pH.coerceIn(0, 23),
            minute = pM.coerceIn(0, 59), minutes = minutes)
        "将于 " + ScheduledLock.nextTriggerText(System.currentTimeMillis(), tmp) +
            " 开始，锁 " + lengthText(minutes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增定时锁机" else "修改定时锁机") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("多久一次", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FxOptionRow(
                    options = listOf("每天", "工作日", "周末", "自定义"),
                    selected = dayPreset,
                    label = { it },
                    onSelect = {
                        dayPreset = it
                        days = when (it) {
                            "每天" -> setOf(1, 2, 3, 4, 5, 6, 7)
                            "工作日" -> setOf(1, 2, 3, 4, 5)
                            "周末" -> setOf(6, 7)
                            else -> days
                        }
                        error = null
                    },
                    enabled = !locked
                )
                if (dayPreset == "自定义") {
                    FxOptionRow(
                        options = listOf(1, 2, 3, 4, 5, 6, 7),
                        selected = null,
                        label = { "周" + arrayOf("一", "二", "三", "四", "五", "六", "日")[it - 1] },
                        onSelect = { d -> days = if (d in days) days - d else days + d },
                        enabled = !locked
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

                Text("几点开始", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter { c -> c.isDigit() }.take(2); error = null },
                        label = { Text("时") }, singleLine = true,
                        modifier = Modifier.width(84.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(" : ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter { c -> c.isDigit() }.take(2); error = null },
                        label = { Text("分") }, singleLine = true,
                        modifier = Modifier.width(84.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

                Text("锁多久", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FxOptionRow(
                    options = listOf(30, 60, 90),
                    selected = minutes,
                    label = { if (it % 60 == 0) "${it / 60} 小时" else "${it / 60.0} 小时" },
                    onSelect = { minutes = it; error = null },
                    enabled = !locked
                )
                FxOptionRow(
                    options = listOf(120, 180, 240),
                    selected = minutes,
                    label = { "${it / 60} 小时" },
                    onSelect = { minutes = it; error = null },
                    enabled = !locked
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

                Text("解锁强度（越大越难解）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FxOptionRow(
                    options = listOf(1, 2, 3, 4),
                    selected = strength,
                    label = { "强度 $it" },
                    onSelect = { strength = it },
                    enabled = !locked
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(preview, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp))
                }
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = hourText.toIntOrNull()
                    val m = minuteText.toIntOrNull()
                    if (days.isEmpty()) { error = "至少选一天"; return@Button }
                    if (h == null || h !in 0..23) { error = "小时填 0~23，例如 21"; return@Button }
                    if (m == null || m !in 0..59) { error = "分钟填 0~59，例如 30"; return@Button }
                    onSave(base.copy(days = days, hour = h, minute = m,
                        minutes = minutes.coerceAtLeast(5), strength = strength, enabled = true))
                },
                enabled = !locked
            ) { Text(if (initial == null) "保存计划" else "保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
