package com.focusguard.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.focusguard.app.privacy.PrivacyStats

/**
 * 隐私透明度：今日上传次数、跳过次数与最近一次跳过原因。
 * 只显示计数与脱敏后的短句，不展示任何屏幕内容。
 */
@Composable
fun PrivacyStatsRow() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val snap = remember(tick) { PrivacyStats.snapshot(context) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
            Text(
                "今日已上传 ${snap.uploads} 次 · 隐私跳过 ${snap.skips} 次",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val marked = remember(tick) { com.focusguard.app.privacy.SensitiveLearning.packages(context) }
            if (marked.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AI 标记的敏感应用 ${marked.size} 个：${marked.joinToString("、").take(60)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = androidx.compose.ui.Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        com.focusguard.app.privacy.SensitiveLearning.clear(context)
                        tick++
                    }) { Text("清空", fontSize = 12.sp) }
                }
            }
            if (snap.lastSkip.isNotBlank()) {
                Text(
                    "最近跳过：${snap.lastSkip}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = { tick++ }) { Text("刷新", fontSize = 12.sp) }
    }
}
