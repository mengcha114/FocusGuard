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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "权限状态",
                fontSize = 28.sp,
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

        items.forEach { item -> PermissionCard(item) { runCatching { item.open(context) }; refresh++ } }

        // ── 增强能力：Shizuku / Dhizuku ─────────────────────
        Text(
            "增强能力（可选）",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        EnhanceCard(
            title = "Shizuku",
            granted = runCatching { com.focusguard.app.enhance.ShizukuEnhancer.isReady() }.getOrNull(),
            desc = "强停应用、授权 appops 等 shell 操作",
            actionText = "打开 Shizuku 授权",
            onAction = {
                // 只负责把用户送到 Shizuku 里启动服务；授权弹窗由 Shizuku 应用自己给，
                // 这里直接调 requestPermission 在某些状态下会崩（实测闪退）。
                openPkg(context, "moe.shizuku.privileged.api")
                refresh++
            },
            secondaryText = "打开 Shizuku",
            onSecondary = { openPkg(context, "moe.shizuku.privileged.api") }
        )
        EnhanceCard(
            title = "Dhizuku（设备所有者）",
            granted = runCatching { com.focusguard.app.enhance.DhizukuEnhancer.isReady() }.getOrNull(),
            desc = "冻结/隐藏应用、系统级锁机。授权后必须重启应用才生效",
            actionText = "申请 Dhizuku 授权",
            onAction = {
                // 用 Dhizuku 官方申请接口弹出授权对话框；之前只是启动它的 App，
                // 用户看到的是"直接调用了软件"而不是授权页。
                runCatching {
                    com.focusguard.app.enhance.DhizukuEnhancer.requestPermission(context) { ok ->
                        if (ok) refresh++
                    }
                }.onFailure {
                    // 没装 Dhizuku 时退回应用详情
                    openPkg(context, "com.rosan.dhizuku")
                }
                refresh++
            },
            secondaryText = "重启应用",
            onSecondary = { restartApp(context) }
        )
        Text(
            "Dhizuku 授权完成后请点「重启应用」：Dhizuku 的连接在进程启动时就建立，重启前不会生效。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(onClick = { refresh++ }, modifier = Modifier.align(Alignment.End)) {
            Text("刷新", fontSize = 12.sp)
        }
    }
}

@Composable
private fun PermissionCard(item: PermItem, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = com.focusguard.app.ui.theme.cardContainer(),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    when (item.granted) {
                        true -> "已开启"
                        false -> "未开启"
                        null -> "未知"
                    },
                    fontSize = 12.sp,
                    color = if (item.granted == true) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.granted != true) {
                TextButton(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) { Text("去开启", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun EnhanceCard(
    title: String,
    granted: Boolean?,
    desc: String,
    actionText: String,
    onAction: () -> Unit,
    secondaryText: String,
    onSecondary: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = com.focusguard.app.ui.theme.cardContainer(),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (granted == true) "已就绪" else if (granted == false) "未授权" else "未知",
                    fontSize = 12.sp,
                    color = if (granted == true) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction, shape = RoundedCornerShape(12.dp)) {
                    Text(actionText, fontSize = 12.sp)
                }
                OutlinedButton(onClick = onSecondary, shape = RoundedCornerShape(12.dp)) {
                    Text(secondaryText, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 打开指定包；没装就跳到应用详情，避免点了没反应。 */
private fun openPkg(context: Context, pkg: String) {
    runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        val intent = launch ?: Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg")
        )
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** 进程内重启界面 + 让 Dhizuku 连接缓存失效（与 MainActivity 里那套一致）。 */
private fun restartApp(context: Context) {
    runCatching {
        com.focusguard.app.enhance.DhizukuEnhancer.resetForRetry()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        context.startActivity(
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
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
