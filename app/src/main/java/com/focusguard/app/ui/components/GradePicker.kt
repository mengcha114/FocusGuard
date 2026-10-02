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
    var selectedGrade by remember { mutableStateOf<GradeStore.Grade?>(null) }
    var selectedStream by remember { mutableStateOf(GradeStore.Stream.ALL) }
    var confirming by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(5) }
    LaunchedEffect(confirming) {
        if (confirming) {
            countdown = 5
            while (countdown > 0) { delay(1000); countdown-- }
        }
    }

    val needsStream = (selectedGrade?.level ?: 0) >= 3

    AlertDialog(
        onDismissRequest = { if (!mandatory) onDismiss() },
        icon = { Icon(Icons.Default.School, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (store.isChosen) "调高答题学段与选科" else "选择你的答题学段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "解锁、暂停与修改设置时需答题。以高思维量数学题为主，辅以选科真题。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FxNotice(
                    text = "确认后学段只能调高不能调低，锁机中不可更改。杜绝一眼出答案，请认真作答。",
                    tone = MaterialTheme.colorScheme.error,
                    icon = Icons.Default.Warning
                )
                options.forEach { g ->
                    FxOption(
                        label = g.label,
                        caption = g.hint,
                        selected = selectedGrade == g,
                        onClick = { selectedGrade = g; confirming = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 高中或大学阶段展示选科/专业方向单选项
                if (needsStream) {
                    Spacer(Modifier.height(4.dp))
                    Text("选科/专业偏向：", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    GradeStore.Stream.entries.forEach { s ->
                        FxOption(
                            label = s.label,
                            caption = s.hint,
                            selected = selectedStream == s,
                            onClick = { selectedStream = s },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            val g = selectedGrade
            if (!confirming) {
                Button(onClick = { confirming = true }, enabled = g != null, shape = RoundedCornerShape(14.dp)) {
                    Text("下一步")
                }
            } else {
                Button(
                    onClick = {
                        if (g != null && store.set(g, selectedStream, LockState(context).isLocked)) onDone()
                    },
                    enabled = countdown == 0 && g != null,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    val label = "${g?.label}${if (needsStream) " · ${selectedStream.label.split(' ')[0]}" else ""}"
                    Text(if (countdown > 0) "确认「$label」（$countdown）" else "确认「$label」")
                }
            }
        },
        dismissButton = if (mandatory) null else {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

/** 设置页卡片：显示当前年级与选科方向，可调高（锁机中禁用）。 */
@Composable
fun GradeSettingCard() {
    val context = LocalContext.current
    val store = remember { GradeStore(context) }
    var grade by remember { mutableStateOf(store.grade) }
    var stream by remember { mutableStateOf(store.stream) }
    var show by remember { mutableStateOf(false) }
    val locked = remember { LockState(context).isLocked }
    val canRaise = store.selectable().isNotEmpty() && !locked

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            val streamSuffix = if ((grade?.level ?: 0) >= 3) " (${stream.label.split(' ')[0]})" else ""
            Text((grade?.label ?: "未选择") + streamSuffix, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                when {
                    locked -> "锁机中不能修改"
                    grade == GradeStore.Grade.COLLEGE -> "已是最高学段，高思维量题目为主"
                    else -> "只能调高，不可调低；杜绝低龄秒答题"
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
            onDone = {
                grade = store.grade
                stream = store.stream
                show = false
            },
            onDismiss = { show = false }
        )
    }
}
