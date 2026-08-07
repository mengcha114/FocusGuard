package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.focusguard.app.data.Settings
import com.focusguard.app.util.PermissionChecker

data class PermissionItem(
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val isGranted: Boolean,
    val isRequired: Boolean = true
)

@Composable
fun PermissionSetupScreen(
    onFinish: () -> Unit,
    onRequestPermission: (String) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 每次回到前台都重新查询系统真实权限状态
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = remember(refreshKey) {
        listOf(
            PermissionItem(
                key = "usage_stats",
                title = "应用使用情况",
                description = "识别前台应用与统计使用时长，请在列表中找到「专注卫士」并开启",
                icon = Icons.Default.QueryStats,
                isGranted = PermissionChecker.isUsageStatsGranted(context)
            ),
            PermissionItem(
                key = "overlay",
                title = "悬浮窗",
                description = "在应用之上显示警告与提示",
                icon = Icons.Default.Layers,
                isGranted = PermissionChecker.canDrawOverlays(context),
                isRequired = false
            ),
            PermissionItem(
                key = "accessibility",
                title = "无障碍服务",
                description = "锁机拦截、强制退出应用、读屏省 token，请在列表中开启「专注卫士」",
                icon = Icons.Default.Accessibility,
                isGranted = PermissionChecker.isAccessibilityEnabled(context)
            ),
            PermissionItem(
                key = "screen_capture",
                title = "屏幕录制",
                description = "AI 视觉识别的基础。点击「开始守护」时会弹出系统授权框",
                icon = Icons.Default.Screenshot,
                isGranted = settings.screenCaptureGranted,
                isRequired = false
            ),
            PermissionItem(
                key = "notification",
                title = "通知",
                description = "显示守护状态与检测结果",
                icon = Icons.Default.Notifications,
                isGranted = PermissionChecker.isNotificationGranted(context),
                isRequired = false
            ),
            PermissionItem(
                key = "battery",
                title = "忽略电池优化",
                description = "避免后台服务被系统杀掉",
                icon = Icons.Default.BatteryChargingFull,
                isGranted = PermissionChecker.isBatteryOptimizationIgnored(context),
                isRequired = false
            ),
            // ── 高级增强（可选，Shizuku/Dhizuku） ──
            PermissionItem(
                key = "shizuku",
                title = "Shizuku 权限自愈（可选）",
                description = "免 Root 提权。授权后自动开启「使用情况访问」与电池优化白名单，权限被 ROM 重置也能自愈",
                icon = Icons.Default.Bolt,
                isGranted = com.focusguard.app.enhance.ShizukuEnhancer.isReady(),
                isRequired = false
            ),
            PermissionItem(
                key = "dhizuku",
                title = "Dhizuku 系统级防退出（可选·最强）",
                description = "授权后锁机进入系统 Lock Task 模式：Home/上滑/最近任务全部被系统禁用，任何手势都无法退出",
                icon = Icons.Default.Security,
                isGranted = com.focusguard.app.enhance.DhizukuEnhancer.isReady(),
                isRequired = false
            )
        )
    }

    val required = permissions.filter { it.isRequired }
    val grantedCount = required.count { it.isGranted }
    val allGranted = required.all { it.isGranted }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141416))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "专注卫士",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "完成必需权限后即可开始守护",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.65f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { if (required.isEmpty()) 1f else grantedCount.toFloat() / required.size },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFD0BCFF),
                trackColor = Color.White.copy(alpha = 0.08f)
            )
            Text(
                text = "已完成 $grantedCount / ${required.size} 项必需权限",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.45f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            permissions.forEach { perm ->
                PermissionCard(item = perm, onRequest = { onRequestPermission(perm.key) })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = allGranted,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4F378B),
                    disabledContainerColor = Color(0xFF2A2A2E)
                )
            ) {
                Text(
                    text = if (allGranted) "完成设置" else "请先完成必需权限",
                    fontSize = 17.sp,
                    color = if (allGranted) Color.White else Color.White.copy(alpha = 0.4f)
                )
            }

            TextButton(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("稍后设置，先进入应用", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionItem, onRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isGranted) Color(0xFF1E3222) else Color(0xFF1F1F23)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            color = if (item.isGranted) Color(0xFF3D6B45) else Color(0xFF332D41),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.isGranted) Icons.Default.Check else item.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        if (item.isRequired) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "必需",
                                fontSize = 10.sp,
                                color = Color(0xFFF2B8B5)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.description,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
            }
            if (!item.isGranted) {
                TextButton(onClick = onRequest) {
                    Text("授权", color = Color(0xFFD0BCFF))
                }
            }
        }
    }
}
