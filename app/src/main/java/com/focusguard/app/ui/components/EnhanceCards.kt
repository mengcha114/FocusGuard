package com.focusguard.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.enhance.DhizukuEnhancer
import com.focusguard.app.enhance.LockPolicies
import com.focusguard.app.enhance.ShizukuEnhancer

/**
 * 设置页的加固 / 增强 / 关于卡片。
 * 这些项直接读写各自的偏好，不参与「放宽限制」的答题验证（它们都是加紧限制）。
 */
@Composable
fun LockHardeningCard() {
    val context = LocalContext.current
    var freeze by remember { mutableStateOf(LockPolicies.isFreezeEnabled(context)) }
    var blockReset by remember { mutableStateOf(LockPolicies.isBlockResetEnabled(context)) }
    val dzReady = remember { DhizukuEnhancer.isReady() }
    val canFreeze = dzReady || ShizukuEnhancer.isReady()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchRow(
            title = "锁机期间冻结娱乐应用",
            hint = if (canFreeze) {
                "锁机时冻结游戏与视频类应用（含 AI 判定与你手动标记的），解锁后自动解冻；社交类不冻结"
            } else {
                "需要 Dhizuku 或 Shizuku 授权后才能使用"
            },
            checked = freeze && canFreeze,
            enabled = canFreeze
        ) { on -> freeze = on; LockPolicies.setFreezeEnabled(context, on) }

        SwitchRow(
            title = "锁机期间禁止恢复出厂设置",
            hint = if (dzReady) "需 Dhizuku，仅锁机期间生效" else "需要 Dhizuku 授权",
            checked = blockReset && dzReady,
            enabled = dzReady
        ) { on -> blockReset = on; LockPolicies.setBlockResetEnabled(context, on) }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    hint: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            if (hint != null) {
                Text(hint, fontSize = 11.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Shizuku / Dhizuku 增强状态：逐项显示是否生效，附一键修复。 */
@Composable
fun ShizukuStatusCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val online = remember(tick) { ShizukuEnhancer.isAvailable() }
    val granted = remember(tick) { ShizukuEnhancer.isPermissionGranted() }
    val dzReady = remember(tick) { DhizukuEnhancer.isReady() }
    val a11y = remember(tick) { com.focusguard.app.util.PermissionChecker.isAccessibilityEnabled(context) }
    val usage = remember(tick) { com.focusguard.app.util.PermissionChecker.isUsageStatsGranted(context) }
    // 锁机期冻结 + AI 对话手动冻结一起显示
    val frozen = remember(tick) { LockPolicies.allFrozen(context).size }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow("Shizuku 服务", online, if (online) "已连接" else "未安装或未启动")
        StatusRow("Shizuku 授权", granted, if (granted) "已授权本应用" else "未授权")
        StatusRow("Dhizuku（系统级锁机）", dzReady, DhizukuEnhancer.lastError.ifBlank { "已就绪" })
        StatusRow("无障碍服务", a11y, if (a11y) "已开启" else "未开启（锁机拦截失效）")
        StatusRow("使用情况访问", usage, if (usage) "已授权" else "未授权（无法识别前台应用）")
        // 强制解冻：由用户选路径。挂起是按施加者记录的，选对了才解得开。
        fun runUnfreeze(by: String?) {
            Thread {
                val ok = LockPolicies.forceUnfreeze(context.applicationContext, by)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "已解冻" else "仍未解开：请确认 Dhizuku 已就绪 / Shizuku 已启动，再试一次",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    tick++
                }
            }.start()
        }
        var showUnfreezeDialog by remember { mutableStateOf(false) }
        if (frozen > 0) {
            Text(
                "冻结中：$frozen 个应用。正常应在锁机结束后自动解冻；" +
                    "若一直没解开，点下面「强制解冻」并选择路径",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = { showUnfreezeDialog = true }) { Text("强制解冻") }
            if (showUnfreezeDialog) {
                AlertDialog(
                    onDismissRequest = { showUnfreezeDialog = false },
                    title = { Text("强制解冻 $frozen 个应用") },
                    text = {
                        Text(
                            "挂起状态是按「施加者」记录的：当初用 Dhizuku 冻结的，就得用 Dhizuku 解" +
                                "（Shizuku 的 pm unsuspend 会返回成功但解不开）。不确定就选「两条都试」。"
                        )
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                showUnfreezeDialog = false; runUnfreeze("dhizuku")
                            }) { Text("用 Dhizuku") }
                            TextButton(onClick = {
                                showUnfreezeDialog = false; runUnfreeze("shizuku")
                            }) { Text("用 Shizuku") }
                            TextButton(onClick = {
                                showUnfreezeDialog = false; runUnfreeze(null)
                            }) { Text("两条都试") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUnfreezeDialog = false }) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
            }
        }
        // 记录之外的兜底：系统里真正处于挂起状态的应用（旧版本可能把记录清掉了）
        val stillSuspended = remember(tick) { LockPolicies.suspendedOnDevice(context) }
        if (stillSuspended.isNotEmpty()) {
            Text(
                "检测到 ${stillSuspended.size} 个应用仍处于系统挂起状态（冻结记录可能已丢失）。" +
                    "点「全部解冻」一次性解开",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = {
                Thread {
                    val (freed, left) = LockPolicies.forceUnfreezeAllSuspended(context.applicationContext)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context,
                            if (left == 0) "已解冻 $freed 个应用"
                            else "解开 $freed 个，仍有 $left 个：请确认 Dhizuku / Shizuku 可用后重试",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        tick++
                    }
                }.start()
            }) { Text("全部解冻") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    Thread { ShizukuEnhancer.selfHeal(context.applicationContext) }.start()
                    tick++
                },
                enabled = granted
            ) { Text("一键修复权限") }
            OutlinedButton(onClick = { tick++ }) { Text("刷新状态") }
        }
    }
}

@Composable
private fun StatusRow(title: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
