package com.focusguard.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 * 权限设置页。
 *
 * 锁机、检测、自动恢复全都依赖一组系统授权，但这些授权散落在系统设置各处，
 * 用户很难自己找全；而且系统/ROM 会在清理或更新后悄悄收回（悬浮窗权限被收回
 * 会让锁机界面显示不出来）。这里集中列出 + 显示真实状态 + 一键跳转，
 * 有 Shizuku 时还能一键补授权。
 */
class PermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 整体兜底：这个页面要读一堆系统状态（AppOps / 通知 / Dhizuku 等），
        // 任一项在个别 ROM 上抛异常就会整页闪退。这里捕获并写进检测日志，
        // 下次能直接从日志看到原因，而不是只看到"闪退了"。
        runCatching {
            setContent {
                com.focusguard.app.ui.theme.FocusGuardTheme {
                    PermissionsScreen(onBack = { finish() })
                }
            }
        }.onFailure { e ->
            android.util.Log.e("PermissionsActivity", "权限页渲染失败", e)
            runCatching {
                com.focusguard.app.data.LogStore(applicationContext).addLog(
                    com.focusguard.app.data.DetectionLog(
                        classification = "NEUTRAL",
                        confidence = 1f,
                        reason = "权限页打开失败：${e.javaClass.simpleName}: ${e.message}",
                        action = "NONE",
                        source = "ERROR",
                        appLabel = "权限设置"
                    )
                )
            }
            finish()
        }
    }
}

private data class PermRow(
    val title: String,
    val desc: String,
    val granted: Boolean,
    val action: (() -> Unit)?,
    val required: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var shizukuReady by remember { mutableStateOf(false) }

    val rows = remember(refresh) { buildRows(context, onChanged = { refresh++ }) }
    LaunchedEffect(refresh) {
        shizukuReady = com.focusguard.app.enhance.ShizukuEnhancer.isReady()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回", fontSize = 13.sp) }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "锁机与检测依赖下面这些授权。系统或管理器可能在清理、更新后自动收回，建议定期回来检查。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (shizukuReady) {
                Button(
                    onClick = {
                        com.focusguard.app.enhance.ShizukuEnhancer.selfHeal(context)
                        com.focusguard.app.enhance.ShizukuEnhancer.ensureOverlayPermission(context)
                        refresh++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("用 Shizuku 一键补全授权", fontSize = 14.sp) }
            } else {
                Text(
                    "Shizuku 未就绪：只能逐项手动开启。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            val missing = rows.count { it.required && !it.granted }
            Text(
                if (missing == 0) "✅ 必需权限已全部就绪" else "⚠️ 还有 $missing 项必需权限未开启",
                fontSize = 12.sp,
                color = if (missing == 0) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error
            )

            rows.forEach { row -> PermissionCard(row) }

            TextButton(onClick = { refresh++ }, modifier = Modifier.align(Alignment.End)) {
                Text("刷新状态", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PermissionCard(row: PermRow) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (row.granted) "已开启" else "未开启",
                    fontSize = 12.sp,
                    color = if (row.granted) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(row.desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!row.granted && row.action != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { row.action.invoke() }) { Text("去开启", fontSize = 13.sp) }
            }
        }
    }
}

private fun buildRows(
    context: android.content.Context,
    onChanged: () -> Unit
): List<PermRow> = runCatching { buildRowsInner(context, onChanged) }.getOrElse { e ->
    android.util.Log.w("PermissionsActivity", "读取权限状态失败：${e.message}")
    listOf(
        PermRow(
            title = "权限状态读取失败",
            desc = "${e.javaClass.simpleName}: ${e.message}",
            granted = false,
            action = null
        )
    )
}

private fun buildRowsInner(
    context: android.content.Context,
    onChanged: () -> Unit
): List<PermRow> {
    val pkg = context.packageName
    val rows = mutableListOf<PermRow>()

    val overlay = Settings.canDrawOverlays(context)
    rows += PermRow(
        title = "悬浮窗（显示在其他应用上层）",
        desc = "锁机页靠它显示。被收回后锁机会显示不出来，本次会自动跳过锁机以免冻结应用后没有解锁入口。",
        granted = overlay,
        action = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            onChanged()
        }
    )

    val a11y = com.focusguard.app.access.GuardAccessibilityService.instance != null
    rows += PermRow(
        title = "无障碍服务",
        desc = "用于识别前台应用、代点系统弹窗、拦截返回/下拉等绕过手段。",
        granted = a11y,
        action = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            onChanged()
        }
    )

    val usage = runCatching {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            pkg
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    rows += PermRow(
        title = "使用情况访问",
        desc = "用来判断当前前台是哪个应用，检测与执法都依赖它。",
        granted = usage,
        action = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            onChanged()
        }
    )

    val notif = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    }.getOrDefault(false)
    rows += PermRow(
        title = "通知",
        desc = "常驻守护通知与执法提醒都靠它；关闭后前台服务可能被系统更快回收。",
        granted = notif,
        action = {
            runCatching {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
            onChanged()
        }
    )

    val battery = runCatching {
        context.getSystemService(android.os.PowerManager::class.java)
            .isIgnoringBatteryOptimizations(pkg)
    }.getOrDefault(false)
    rows += PermRow(
        title = "忽略电池优化",
        desc = "不加入白名单时，后台守护容易被系统冻结或杀掉。",
        granted = battery,
        required = false,
        action = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            onChanged()
        }
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarm = runCatching {
            context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
        }.getOrDefault(false)
        rows += PermRow(
            title = "精确闹钟",
            desc = "定时锁机到点能否准时触发依赖它。",
            granted = alarm,
            required = false,
            action = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                onChanged()
            }
        )
    }

    val shizuku = runCatching { com.focusguard.app.enhance.ShizukuEnhancer.isReady() }
        .getOrDefault(false)
    rows += PermRow(
        title = "Shizuku（shell 通道）",
        desc = "用于强停应用、授权 appops、改成自动时间等需要 shell 的操作。",
        granted = shizuku,
        required = false,
        action = null
    )

    val dhizuku = runCatching { com.focusguard.app.enhance.DhizukuEnhancer.isReady() }
        .getOrDefault(false)
    rows += PermRow(
        title = "Dhizuku（设备所有者）",
        desc = "用于冻结/隐藏应用、LockTask 系统级锁机等设备所有者能力。",
        granted = dhizuku,
        required = false,
        action = null
    )

    return rows
}
