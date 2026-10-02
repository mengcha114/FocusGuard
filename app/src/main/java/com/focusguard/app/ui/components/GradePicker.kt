package com.focusguard.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.GradeStore
import com.focusguard.app.data.LockState
import kotlinx.coroutines.delay

/**
 * 年级选择。
 * - [mandatory] = true：首次进入应用时全屏弹出，不能关闭，必须选择；
 * - 已选择后只列出更高的年级（调低入口不存在），锁机中不可修改。
 * 确认前有 5 秒冷静期，防止误触。
 */
@Composable
fun GradePickerDialog(mandatory: Boolean, onDone: () -> Unit, onDismiss: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { GradeStore(context) }
    val options = remember { store.selectable() }
    var selected by remember { mutableStateOf<GradeStore.Grade?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(5) }
    LaunchedEffect(confirming) {
        if (confirming) {
            countdown = 5
            while (countdown > 0) { delay(1000); countdown-- }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!mandatory) onDismiss() },
        icon = { Icon(Icons.Default.School, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (store.isChosen) "调高答题年级" else "选择你的答题年级") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "解锁、暂停和修改设置时需要答题，题目难度按年级出。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FxNotice(
                    text = "确认后只能调高、不能调低，锁机期间也不能修改。请如实选择。",
                    tone = MaterialTheme.colorScheme.error,
                    icon = Icons.Default.Warning
                )
                options.forEach { g ->
                    FxOption(
                        label = g.label,
                        caption = g.hint,
                        selected = selected == g,
                        onClick = { selected = g; confirming = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            val g = selected
            if (!confirming) {
                Button(onClick = { confirming = true }, enabled = g != null, shape = RoundedCornerShape(14.dp)) {
                    Text("下一步")
                }
            } else {
                Button(
                    onClick = {
                        if (g != null && store.set(g, LockState(context).isLocked)) onDone()
                    },
                    enabled = countdown == 0 && g != null,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (countdown > 0) "确认「${g?.label}」（$countdown）" else "确认「${g?.label}」")
                }
            }
        },
        dismissButton = if (mandatory) null else {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

/** 设置页卡片：显示当前年级，可调高（锁机中禁用）。 */
@Composable
fun GradeSettingCard() {
    val context = LocalContext.current
    val store = remember { GradeStore(context) }
    var grade by remember { mutableStateOf(store.grade) }
    var show by remember { mutableStateOf(false) }
    val locked = remember { LockState(context).isLocked }
    val canRaise = store.selectable().isNotEmpty() && !locked

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(grade?.label ?: "未选择", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                when {
                    locked -> "锁机中不能修改"
                    grade == GradeStore.Grade.COLLEGE -> "已是最高年级"
                    else -> "只能调高，不能调低"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = { show = true }, enabled = canRaise) { Text(if (grade == null) "选择" else "调高") }
    }
    if (show) {
        GradePickerDialog(
            mandatory = false,
            onDone = { grade = store.grade; show = false },
            onDismiss = { show = false }
        )
    }
}
