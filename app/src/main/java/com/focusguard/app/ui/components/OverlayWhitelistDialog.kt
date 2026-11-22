package com.focusguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.ui.theme.dialogGlassColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String)

/**
 * 悬浮窗豁免名单选择器（锁机期间仍允许弹出悬浮窗的应用）。
 *
 * 只列出「有启动图标」的应用，避免把系统组件也列进来。
 * 名单改动本身由调用方先过答题验证（[VerifyDialog]），这里只负责选择。
 */
@Composable
fun OverlayWhitelistDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var all by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var picked by remember { mutableStateOf(initial) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
                    .mapNotNull { ri ->
                        val info = ri.activityInfo ?: return@mapNotNull null
                        val pkg = info.packageName ?: return@mapNotNull null
                        if (pkg == context.packageName) return@mapNotNull null
                        AppEntry(pkg, runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg))
                    }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label }
            }.getOrDefault(emptyList())
        }
        all = list
        loading = false
    }

    val filtered = remember(all, query) {
        if (query.isBlank()) all
        else all.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("悬浮窗豁免", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "锁机期间盖在锁机页上方的应用会被立即冻结。勾选的应用不参与冻结，仍可弹出悬浮窗。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "已选 ${picked.size} 个",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.height(4.dp))
                if (loading) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("没有匹配的应用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(filtered, key = { it.pkg }) { app ->
                            val checked = app.pkg in picked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable {
                                        picked = if (checked) picked - app.pkg else picked + app.pkg
                                    }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 自绘勾选圈：系统 Checkbox 观感和整体不一致
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (checked) MaterialTheme.colorScheme.primary
                                            else androidx.compose.ui.graphics.Color.Transparent
                                        )
                                        .border(
                                            1.5.dp,
                                            if (checked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (checked) {
                                        Text("✓", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, fontSize = 14.sp)
                                    Text(
                                        app.pkg,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { picked = emptySet() }) { Text("清空", fontSize = 13.sp) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { onConfirm(picked) },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("保存", fontSize = 14.sp) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        // 与 App 的卡片语言一致：卡片底色 + 22dp 圆角，不用系统默认对话框外观
        containerColor = com.focusguard.app.ui.theme.cardContainer(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 0.dp
    )
}
