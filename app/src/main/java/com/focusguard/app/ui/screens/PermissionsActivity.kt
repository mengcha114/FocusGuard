package com.focusguard.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 权限状态页（**只展示 + 跳系统设置，不做任何自动授权**）。
 *
 * 面向普通用户：只列锁机真正依赖的几项，缺哪项一眼能看到，点一下跳到系统设置。
 * 刻意保持极简（纯 Column + 文本 + 文本按钮），减少渲染崩溃面；
 * 之前带卡片/一键授权的版本点开就崩，且拿不到堆栈。
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val items = remember(refresh) { probePermissions(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "权限状态",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回", fontSize = 13.sp) }
        }

        val missing = items.count { it.required && it.granted != true }
        Text(
            if (missing == 0) "必需权限已就绪" else "还有 $missing 项必需权限未开启",
            fontSize = 12.sp,
            color = if (missing == 0) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(6.dp))

        items.forEach { item ->
            Text(
                item.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                when (item.granted) {
                    true -> "已开启"
                    false -> "未开启"
                    null -> "状态未知"
                } + " · " + item.desc,
                fontSize = 11.sp,
                color = if (item.granted == true) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.granted != true) {
                TextButton(
                    onClick = {
                        runCatching { item.open(context) }
                        refresh++
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) { Text("去开启", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { refresh++ }, modifier = Modifier.align(Alignment.End)) {
            Text("刷新", fontSize = 12.sp)
        }
    }
}

private class PermItem(
    val title: String,
    val desc: String,
    /** true=已开启 false=未开启 null=读不到 */
    val granted: Boolean?,
    val required: Boolean,
    val open: (Context) -> Unit
)

/** 一次性探测所有相关权限；每一项都单独兜底，任何一项失败都不影响整页。 */
private fun probePermissions(context: Context): List<PermItem> {
    val pkg = context.packageName
    val out = ArrayList<PermItem>(6)

    out.add(
        PermItem(
            title = "悬浮窗（显示在其他应用上层）",
            desc = "锁机界面靠它显示",
            granted = runCatching { Settings.canDrawOverlays(context) }.getOrNull(),
            required = true
        ) {
            it.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )

    out.add(
        PermItem(
            title = "无障碍服务",
            desc = "识别前台应用、代点系统弹窗",
            granted = runCatching {
                com.focusguard.app.access.GuardAccessibilityService.instance != null
            }.getOrNull(),
            required = true
        ) {
            it.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )

    out.add(
        PermItem(
            title = "使用情况访问",
            desc = "判断当前前台应用",
            granted = runCatching {
                val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    pkg
                ) == android.app.AppOpsManager.MODE_ALLOWED
            }.getOrNull(),
            required = true
        ) {
            it.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )

    out.add(
        PermItem(
            title = "通知",
            desc = "常驻守护通知与执法提醒",
            granted = runCatching {
                context.getSystemService(android.app.NotificationManager::class.java)
                    .areNotificationsEnabled()
            }.getOrNull(),
            required = true
        ) {
            it.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )

    out.add(
        PermItem(
            title = "忽略电池优化",
            desc = "不加白名单容易被后台冻结",
            granted = runCatching {
                context.getSystemService(android.os.PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(pkg)
            }.getOrNull(),
            required = false
        ) {
            it.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        out.add(
            PermItem(
                title = "精确闹钟",
                desc = "定时锁机到点能否准时触发",
                granted = runCatching {
                    context.getSystemService(android.app.AlarmManager::class.java)
                        .canScheduleExactAlarms()
                }.getOrNull(),
                required = false
            ) {
                it.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
    }

    return out
}
