package com.focusguard.app.enforce

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.access.GuardAccessibilityService
import com.focusguard.app.usage.UsageRuleStore
import kotlinx.coroutines.delay

/**
 * 应用硬封锁界面。
 *
 * 与 [BlockActivity] 的区别：
 * - BlockActivity 是一次性提示，用户点掉即可继续
 * - AppBlockActivity 是持续封锁，只要目标应用还超限就一直挡在前面，
 *   返回键无效，唯一出路是回桌面
 *
 * 之所以用 Activity 而不是悬浮窗：Activity 能可靠地覆盖住目标应用的
 * 全部内容（包括视频等使用 SurfaceView 的界面），悬浏窗在部分机型上
 * 会被视频层盖住，达不到"无法查看任何内容"的要求。
 */
class AppBlockActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AppBlockActivity"
        private const val EXTRA_PACKAGE = "package_name"
        private const val EXTRA_LABEL = "app_label"
        private const val EXTRA_USED_MINUTES = "used_minutes"
        private const val EXTRA_LIMIT_MINUTES = "limit_minutes"
        private const val EXTRA_BLOCK_UNTIL = "block_until"

        /**
         * 拉起应用封锁页。
         *
         * @param blockUntil 临时封锁截止时间戳（毫秒）。>0 时显示
         *   「封锁至 HH:mm + 剩余倒计时」；=0 时显示旧的"今日已用/上限"样式。
         */
        fun show(
            context: Context,
            packageName: String,
            appLabel: String,
            usedMinutes: Int,
            limitMinutes: Int,
            blockUntil: Long = 0L
        ) {
            val intent = Intent(context, AppBlockActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, appLabel)
                putExtra(EXTRA_USED_MINUTES, usedMinutes)
                putExtra(EXTRA_LIMIT_MINUTES, limitMinutes)
                putExtra(EXTRA_BLOCK_UNTIL, blockUntil)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            context.startActivity(intent)
        }
    }

    private var blockedPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上也能显示，防止用户息屏再唤醒绕过
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 保持屏幕常亮，避免息屏后封锁页被系统回收
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        blockedPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { blockedPackage }
        val used = intent.getIntExtra(EXTRA_USED_MINUTES, 0)
        val limit = intent.getIntExtra(EXTRA_LIMIT_MINUTES, 0)
        val blockUntil = intent.getLongExtra(EXTRA_BLOCK_UNTIL, 0L)

        setContent {
            if (blockUntil > 0L) {
                // 临时封锁（仅锁该软件）：显示封锁截止时间与倒计时
                AppBlockedUntilScreen(
                    appLabel = label,
                    blockUntil = blockUntil,
                    onGoHome = { goHome() }
                )
            } else {
                AppBlockScreen(
                    appLabel = label,
                    usedMinutes = used,
                    limitMinutes = limit,
                    resetHint = "计时于每日 0 点自动归零",
                    onGoHome = { goHome() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 复用同一实例时刷新数据，避免显示上一个应用的信息
        setIntent(intent)
        recreate()
    }

    @Deprecated("Back is intentionally disabled while blocked")
    override fun onBackPressed() {
        // 封锁期间返回键直接回桌面，而不是退回被封锁的应用
        goHome()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 注意：这里不能直接 finish。
        // 早期实现一 finish 就把封锁页销毁，用户按 Home 再点回被封锁的应用
        // 就能正常使用（这正是"超过最高时间后仍可使用"的直接原因）。
        // 现在交给 LockGuardService 巡检：用户若再打开被封锁应用，
        // 守护服务会在 1 秒内重新拉起本页面。
        Log.d(TAG, "用户离开封锁页，守护服务将在其重新打开被封应用时拦截")
    }

    /**
     * 封锁页失焦（下拉通知栏等）时不做处理，
     * 顶回逻辑统一由守护服务负责，避免与系统窗口争抢焦点。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            GuardAccessibilityService.instance?.dismissNotificationShade()
        }
    }

    private fun goHome() {
        val service = GuardAccessibilityService.instance
        if (service != null) {
            service.performHome()
        } else {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
        finish()
    }
}

/** 「仅锁该软件」临时封锁页：显示封锁截止时间与剩余倒计时。 */
@Composable
private fun AppBlockedUntilScreen(
    appLabel: String,
    blockUntil: Long,
    onGoHome: () -> Unit
) {
    var remainingSeconds by remember { mutableIntStateOf(0) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(blockUntil) {
        while (true) {
            delay(1000L)
            nowMillis = System.currentTimeMillis()
            remainingSeconds = ((blockUntil - nowMillis) / 1000).coerceAtLeast(0).toInt()
            if (remainingSeconds <= 0) {
                // 封锁到期：本页使命完成，回桌面
                onGoHome()
                break
            }
        }
    }

    val h = remainingSeconds / 3600
    val m = (remainingSeconds % 3600) / 60
    val s = remainingSeconds % 60
    val timeText = if (h > 0) "%02d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)

    val untilFormat = remember {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF17151C), Color(0xFF1F1B24), Color(0xFF262029))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF3A2E34)
            ) {
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = Color(0xFFC6786F),
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Text(
                text = "该应用已被封锁",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEDE8E4),
                textAlign = TextAlign.Center
            )

            Text(
                text = appLabel,
                fontSize = 18.sp,
                color = Color(0xFFB9AFA8),
                textAlign = TextAlign.Center
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF241F27))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "封锁截止",
                        fontSize = 12.sp,
                        color = Color(0xFF8A8078)
                    )
                    Text(
                        text = untilFormat.format(java.util.Date(blockUntil)),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC6786F)
                    )
                    Text(
                        text = "剩余 $timeText",
                        fontSize = 14.sp,
                        color = Color(0xFFB9AFA8)
                    )
                }
            }

            Text(
                text = "在此期间打开该应用会被挡住，退出后使用其他应用不受影响。",
                fontSize = 14.sp,
                color = Color(0xFF8A8078),
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )

            Button(
                onClick = onGoHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3E4A5C)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("返回桌面", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun AppBlockScreen(
    appLabel: String,
    usedMinutes: Int,
    limitMinutes: Int,
    resetHint: String,
    onGoHome: () -> Unit
) {
    // 轻微的呼吸动画，避免界面显得像卡死的黑屏
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1600)
            pulse = !pulse
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF17151C),
                        Color(0xFF1F1B24),
                        Color(0xFF262029)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF3A2E34),
                modifier = Modifier.size(if (pulse) 104.dp else 100.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = Color(0xFFC6786F),
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Text(
                text = "已达使用上限",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEDE8E4),
                textAlign = TextAlign.Center
            )

            Text(
                text = appLabel,
                fontSize = 18.sp,
                color = Color(0xFFB9AFA8),
                textAlign = TextAlign.Center
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF241F27))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    LimitRow("今日已用", formatMinutes(usedMinutes), Color(0xFFC6786F))
                    LimitRow("设定上限", formatMinutes(limitMinutes), Color(0xFF8E9AAF))

                    val progress = if (limitMinutes > 0) {
                        (usedMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f)
                    } else 1f

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = Color(0xFFC6786F),
                        trackColor = Color(0xFF3A3340)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Color(0xFF8A8078),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = resetHint,
                            fontSize = 13.sp,
                            color = Color(0xFF8A8078)
                        )
                    }
                }
            }

            Text(
                text = "该应用今日已被封锁，内容不可查看。\n如需调整，请在专注卫士中修改限额。",
                fontSize = 14.sp,
                color = Color(0xFF8A8078),
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )

            Button(
                onClick = onGoHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3E4A5C)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("返回桌面", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun LimitRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = Color(0xFF8A8078))
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

private fun formatMinutes(minutes: Int): String = when {
    minutes <= 0 -> "0 分钟"
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}
