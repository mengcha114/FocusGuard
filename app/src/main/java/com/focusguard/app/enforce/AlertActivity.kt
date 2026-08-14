package com.focusguard.app.enforce

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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

/**
 * 全屏提醒页（Full-Screen Intent 目标）。
 *
 * ## 为什么需要它
 * Heads-up 横幅在**全屏应用**（横屏视频、游戏等）下会被系统抑制，
 * 只显示一个小图标甚至完全不出现。Full-Screen Intent 是 Android 官方
 * 提供的"锁屏/全屏也要打扰用户"的通道：通知携带 fullScreenIntent，
 * 系统直接以全屏方式拉起本页——无论用户在横屏看视频还是打游戏，
 * 提醒一定会出现在眼前。
 *
 * ## 行为
 * - 半透明深色背景 + 居中提醒卡片（标题 / 消息 / 倒计时说明）
 * - 自动消失：无操作 8 秒后自动关闭（不打扰）
 * - 按钮「知道了」立即关闭；「打开应用」跳转主界面
 * - 关闭后回到用户之前的应用（本页不留痕）
 */
class AlertActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AlertActivity"
        private const val EXTRA_TITLE = "alert_title"
        private const val EXTRA_MESSAGE = "alert_message"
        private const val EXTRA_COUNTDOWN = "alert_countdown"

        fun show(context: Context, title: String, message: String, countdownSeconds: Int = 0) {
            try {
                val intent = Intent(context, AlertActivity::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_MESSAGE, message)
                    putExtra(EXTRA_COUNTDOWN, countdownSeconds)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "拉起全屏提醒失败：${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上也要能显示提醒
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "设置窗口标志失败：${e.message}")
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "专注卫士提醒"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val countdown = intent.getIntExtra(EXTRA_COUNTDOWN, 0)

        setContent {
            AlertCard(
                title = title,
                message = message,
                countdownSeconds = countdown,
                onDismiss = { finish() },
                onOpenApp = {
                    try {
                        startActivity(
                            Intent(
                                this,
                                com.focusguard.app.MainActivity::class.java
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "打开应用失败：${e.message}")
                    }
                    finish()
                }
            )
        }

        // 8 秒后自动关闭，避免一直占住屏幕
        android.os.Handler(mainLooper).postDelayed({
            if (!isFinishing) finish()
        }, 8_000L)
    }
}

@Composable
private fun AlertCard(
    title: String,
    message: String,
    countdownSeconds: Int,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = remember(context) {
        com.focusguard.app.ui.theme.FocusColors.paletteForLockScreen(
            com.focusguard.app.data.Settings(context).themeMode,
            context
        )
    }
    // 轻遮罩（约 35% 黑）：能看到原画面但明显变暗，不强制全屏打断
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x59000000))
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                // 图标：强调色圆角块
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .background(palette.accent, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = palette.bg,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text,
                    textAlign = TextAlign.Center
                )

                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = palette.text.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }

                if (countdownSeconds > 0) {
                    // 倒计时徽章
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = palette.accent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "$countdownSeconds 秒后将自动锁机，现在切回学习即可避免",
                            fontSize = 12.sp,
                            color = palette.accent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.bg
                    )
                ) {
                    Text("知道了", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }

                TextButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "打开专注卫士",
                        color = palette.haze,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
