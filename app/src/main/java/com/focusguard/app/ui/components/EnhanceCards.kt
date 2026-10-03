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
import com.focusguard.app.challenge.ChallengeMode
import com.focusguard.app.data.GradeStore
import com.focusguard.app.data.Settings
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
    val settings = remember { Settings(context) }
    var freeze by remember { mutableStateOf(LockPolicies.isFreezeEnabled(context)) }
    var blockReset by remember { mutableStateOf(LockPolicies.isBlockResetEnabled(context)) }
    var countOff by remember { mutableStateOf(settings.countOffTimeInLock) }
    val dzReady = remember { DhizukuEnhancer.isReady() }
    val canFreeze = dzReady || ShizukuEnhancer.isReady()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchRow(
            title = "关机 / 安全模式时间计入锁机",
            hint = "锁机期间重启、进安全模式或被杀，回来后这段时间补回锁机（只增不减，改时间也不会缩短）",
            checked = countOff
        ) { on -> countOff = on; settings.countOffTimeInLock = on }

        SwitchRow(
            title = "锁机期间冻结娱乐应用",
            hint = if (canFreeze) {
                "冻结你在「应用管控」里标为游戏/视频的应用（图标变灰、点开提示已暂停）；" +
                    "解锁后自动解冻。若解冻失败，重启手机或再次进入本应用即可恢复。"
            } else {
                "需要 Dhizuku 或 Shizuku 授权后才能使用"
            },
            checked = freeze && canFreeze,
            enabled = canFreeze
        ) { on -> freeze = on; LockPolicies.setFreezeEnabled(context, on) }

        SwitchRow(
            title = "锁机期间禁止恢复出厂设置",
            hint = if (dzReady) "需 Dhizuku；这是影响很大的限制，仅在锁机期间生效，默认关闭"
            else "需要 Dhizuku 授权后才能使用",
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
    val frozen = remember(tick) { LockPolicies.suspendedPackages(context).size }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow("Shizuku 服务", online, if (online) "已连接" else "未安装或未启动")
        StatusRow("Shizuku 授权", granted, if (granted) "已授权本应用" else "未授权")
        StatusRow("Dhizuku（系统级锁机）", dzReady, DhizukuEnhancer.lastError.ifBlank { "已就绪" })
        StatusRow("无障碍服务", a11y, if (a11y) "已开启" else "未开启（锁机拦截失效）")
        StatusRow("使用情况访问", usage, if (usage) "已授权" else "未授权（无法识别前台应用）")
        if (frozen > 0) {
            Text("当前冻结中的娱乐应用：$frozen 个（锁机结束后会自动解冻）",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
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
        Text(
            "Shizuku 可以提供：防后台冻结、自动授予权限、锁机中写回无障碍、" +
                "锁机中冻结娱乐应用、以及在重启后主动拉起 Dhizuku。",
            fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

/** 关于：当前版本、题库出处（MIT）、两版差异与 Dhizuku 授权提醒。 */
@Composable
fun AboutCard() {
    val context = LocalContext.current
    val grade = remember { GradeStore(context) }
    var bankCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        bankCount = runCatching {
            com.focusguard.app.challenge.QuestionBank.all(context).size
        }.getOrDefault(-1)
    }
    val edu = ChallengeMode.needsGrade()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (edu) "当前版本：学段版（真题库）" else "当前版本：通用版（繁复计算题）",
            fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            if (edu) "题库：数学与理综文综真题${
                if (bankCount >= 0) "（已加载 $bankCount 题）" else "（加载失败，已回退到计算题）"
            }；出处 TAL-SCQ5K（好未来）与 AGIEval（微软），均为 MIT 许可。年级按题目真实来源判定，已筛除过易与过难。当前年级：${grade.grade?.label ?: "未选择"}"
            else "题目为程序生成的繁复多步计算，无需联网、无需选择年级，答案必为整数。",
            fontSize = 12.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (edu) {
            Text(
                "通用版是另一个独立安装包（应用名带「通用」），两个版本可同时安装、互不影响。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Dhizuku 的设备所有者授权按应用包名生效：如果你同时安装两个版本，另一个版本需要单独在 Dhizuku 里授权一次。",
            fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
