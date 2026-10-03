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
    // 回到前台时重新判断可用性：去 Shizuku/Dhizuku 授权完回来，这里要立刻反映出来
    var refreshKey by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshKey++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val dzReady = remember(refreshKey) { DhizukuEnhancer.isReady() }
    val adminActive = remember(refreshKey) {
        com.focusguard.app.enhance.AdminEnhancer.isActive(context)
    }
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

        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

        val settings = remember { com.focusguard.app.data.Settings(context) }

        // 不在最近任务里显示（防划掉杀进程）
        var hideRecents by remember { mutableStateOf(settings.hideFromRecents) }
        SwitchRow(
            title = "不在最近任务里显示",
            hint = "防止用户顺手把专注卫士从最近任务划掉（划掉 = 杀进程，守护与锁机都会中断）。" +
                "关掉后本应用会正常出现在最近任务里。默认开（下次打开应用生效）",
            checked = hideRecents,
            enabled = true
        ) { value ->
            hideRecents = value
            settings.hideFromRecents = value
            android.widget.Toast.makeText(
                context, "已自动保存（下次打开应用生效）", android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // 没有 Shizuku/Dhizuku 的用户：用系统「强行停止」把被锁应用真正停掉
        var forceStop by remember { mutableStateOf(settings.forceStopUnlocked) }
        SwitchRow(
            title = "无授权时用「强行停止」停掉被锁应用",
            hint = "没有 Shizuku/Dhizuku 授权时（也无法冻结应用），由无障碍代点系统的" +
                "「强行停止」把被锁应用真停掉；过程在封锁页后面完成。有授权时优先冻结。默认开",
            checked = forceStop,
            enabled = true
        ) { value ->
            forceStop = value
            settings.forceStopUnlocked = value
            android.widget.Toast.makeText(
                context, "已自动保存（锁机期间生效）", android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // 重启后不必手动授权录屏：由无障碍替用户点掉系统授权弹窗
        var autoGrant by remember { mutableStateOf(settings.autoGrantProjection) }
        SwitchRow(
            title = "重启后自动授权屏幕录制",
            hint = "屏幕录制的授权重启后必然失效。打开后由无障碍服务替你点掉系统的" +
                "「立即开始录制」弹窗，不用手动确认；不打开则等你自己点一下。默认开",
            checked = autoGrant,
            enabled = true
        ) { value ->
            autoGrant = value
            settings.autoGrantProjection = value
            android.widget.Toast.makeText(
                context, "已自动保存（锁机期间生效）", android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        Text(
            "锁机期间的系统加固（逐项即时保存、**不需要答题**；只在锁机期间生效，锁机结束自动撤销）",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Text(
            "默认开启",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        LockPolicies.Hardening.entries.filter { it.defaultOn }.forEach { item ->
            var on by remember(item.key) {
                mutableStateOf(LockPolicies.isHardeningEnabled(context, item))
            }
            val usable = dzReady || (item.adminCapable && adminActive)
            SwitchRow(
                title = item.label,
                hint = if (usable) item.hint else item.hint + "（需要 Dhizuku 就绪，当前不生效）",
                checked = on,
                enabled = true
            ) { value ->
                on = value
                LockPolicies.setHardeningEnabled(context, item, value)
                // 加固/防破解开关都是**收紧**方向：即时生效、不需要答题
                // （此前没有任何反馈，用户以为没保存）
                android.widget.Toast.makeText(
                    context, "已自动保存（锁机期间生效）", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        Text(
            "可选加固（默认关闭，按需打开）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        LockPolicies.Hardening.entries.filterNot { it.defaultOn }.forEach { item ->
            var on by remember(item.key) {
                mutableStateOf(LockPolicies.isHardeningEnabled(context, item))
            }
            val usable = dzReady || (item.adminCapable && adminActive)
            SwitchRow(
                title = item.label,
                hint = if (usable) item.hint else item.hint + "（需要 Dhizuku 就绪，当前不生效）",
                checked = on,
                enabled = true
            ) { value ->
                on = value
                LockPolicies.setHardeningEnabled(context, item, value)
                // 加固/防破解开关都是**收紧**方向：即时生效、不需要答题
                // （此前没有任何反馈，用户以为没保存）
                android.widget.Toast.makeText(
                    context, "已自动保存（锁机期间生效）", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
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
    // 锁机期冻结 + AI 对话手动冻结一起显示。
    // 枚举已安装应用、取应用标签都是 IO，放到后台线程算，避免卡住设置页组合线程。
    var frozenCount by remember { mutableIntStateOf(0) }
    var suspendedList by remember { mutableStateOf<List<String>>(emptyList()) }
    var crackCount by remember { mutableIntStateOf(0) }
    var crackNames by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tick) {
        val app = context.applicationContext
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val matched = com.focusguard.app.enhance.CrackGuard.matched(app)
            val pm = app.packageManager
            Triple(
                LockPolicies.allFrozen(app).size,
                LockPolicies.suspendedOnDevice(app),
                matched.map { pkg ->
                    runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                }.sorted()
            )
        }
        frozenCount = result.first
        suspendedList = result.second
        crackCount = result.third.size
        crackNames = result.third
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow("Shizuku 服务", online, if (online) "已连接" else "未安装或未启动")
        StatusRow("Shizuku 授权", granted, if (granted) "已授权本应用" else "未授权")
        StatusRow("Dhizuku（系统级锁机）", dzReady, DhizukuEnhancer.lastError.ifBlank { "已就绪" })
        StatusRow("无障碍服务", a11y, if (a11y) "已开启" else "未开启（锁机拦截失效）")
        StatusRow("使用情况访问", usage, if (usage) "已授权" else "未授权（无法识别前台应用）")
        // 系统自带设备管理员：免 Dhizuku 的「立即锁屏 / 禁相机 / 密码解锁」，激活期间系统还会禁止卸载本应用
        val overlayOk = remember(tick) { android.provider.Settings.canDrawOverlays(context) }
        StatusRow(
            "悬浮窗权限",
            overlayOk,
            if (overlayOk) "已授权" else "未授权（应用封锁只能退回「踢回桌面 + 封锁页」）"
        )
        val adminActive = remember(tick) { com.focusguard.app.enhance.AdminEnhancer.isActive(context) }
        StatusRow(
            "系统设备管理员",
            adminActive,
            if (adminActive) "已激活（期间系统禁止卸载本应用）" else "未激活"
        )
        if (!adminActive) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        com.focusguard.app.enhance.AdminEnhancer
                            .activationIntent(context)
                            .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                }
            }) { Text("申请设备管理员") }
        }
        if (crackCount > 0) {
            Text(
                "识别到 $crackCount 个破解/自动化工具（锁机期间按加固开关冻结或隐藏）：" +
                    crackNames.joinToString("、").take(80),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            "锁机加固：已启用 ${LockPolicies.enabledHardeningCount(context)} 项" +
                "（在「加固」卡里逐项开关；需要 Dhizuku，未就绪时会在就绪后自动生效）",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // AI 对话下达的冻结属于「放宽需答题」的范围：设置页解冻同样要先答题，
        // 否则这里的「强制解冻 / 全部解冻」就成了绕过答题的后门。
        fun needsQuiz(): Boolean = runCatching {
            LockPolicies.hasPersistentFreeze(context.applicationContext)
        }.getOrDefault(false)
        var pendingUnfreezeAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        fun guardedUnfreeze(action: () -> Unit) {
            if (needsQuiz()) pendingUnfreezeAction = action else action()
        }

        // 强制解冻：由用户选路径。挂起是按施加者记录的，选对了才解得开。
        fun runUnfreeze(by: String?) {
            guardedUnfreeze {
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
        }
        var showUnfreezeDialog by remember { mutableStateOf(false) }
        if (frozenCount > 0) {
            Text(
                "冻结中：$frozenCount 个应用。正常应在锁机结束后自动解冻；" +
                    "若一直没解开，点下面「强制解冻」并选择路径",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = { showUnfreezeDialog = true }) { Text("强制解冻") }
            if (showUnfreezeDialog) {
                AlertDialog(
                    onDismissRequest = { showUnfreezeDialog = false },
                    title = { Text("强制解冻 $frozenCount 个应用") },
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
        if (suspendedList.isNotEmpty()) {
            Text(
                "检测到 ${suspendedList.size} 个应用仍处于系统挂起状态（冻结记录可能已丢失）。" +
                    "点「全部解冻」一次性解开",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = {
                guardedUnfreeze {
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
                }
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

        // AI 对话下达的冻结：解除必须先答题（与设置里「放宽限制」同一套规则）
        pendingUnfreezeAction?.let { action ->
            com.focusguard.app.ui.components.VerifyDialog(
                title = "解冻需要先答题",
                description = "冻结是 AI 对话下达的（或你手动标过），解除需要先答对一道题。" +
                    "本题由应用本地题库按你的年级「" +
                    com.focusguard.app.data.GradeStore(context).effective.label +
                    "」出题，与 AI 无关；答错立即换题，错 2 次要等 5 分钟。",
                confirmText = "验证并解冻",
                onPassed = {
                    pendingUnfreezeAction = null
                    action()
                },
                onCancel = { pendingUnfreezeAction = null }
            )
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
