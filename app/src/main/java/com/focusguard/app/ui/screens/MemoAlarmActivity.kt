package com.focusguard.app.ui.screens

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.MemoPrefs
import com.focusguard.app.data.MemoStore
import com.focusguard.app.service.MemoReminder
import com.focusguard.app.ui.theme.AppBackground
import com.focusguard.app.ui.theme.FocusColors
import com.focusguard.app.ui.theme.FocusGuardTheme
import com.focusguard.app.ui.theme.ThemeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待办全屏提醒页（锁机同款沉浸样式）。
 *
 * 由 [MemoReminder] 的全屏通知 / 屏幕点亮时的直接启动拉起：
 * 锁屏上越过 keyguard、点亮屏幕，显示大号时钟与待办内容，
 * 用户必须选择「已完成」或「稍后提醒」。
 */
class MemoAlarmActivity : ComponentActivity() {

    private var memoId = 0L
    private var memoText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memoId = intent.getLongExtra(MemoReminder.EXTRA_ID, 0L)
        memoText = intent.getStringExtra(MemoReminder.EXTRA_TEXT).orEmpty()

        // 锁屏上硬压制：与锁机页保持一致
        runCatching {
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
        }

        // 返回键等同「稍后提醒」，避免误触后提醒彻底消失
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = snooze()
        })

        setContent {
            FocusGuardTheme(
                themeMode = ThemeState.mode,
                accentOverride = ThemeState.accent
            ) {
                val palette = FocusColors.paletteFor(
                    ThemeState.mode,
                    this,
                    ThemeState.accent
                )
                MemoAlarmScreen(
                    text = memoText.ifBlank { "待办到期" },
                    palette = palette,
                    repeatMinutes = MemoPrefs(this).reminderRepeatMinutes,
                    onDone = { complete() },
                    onSnooze = { snooze() }
                )
            }
        }
    }

    private fun complete() {
        if (memoId > 0L) {
            MemoStore(this).markDone(memoId)
            MemoReminder.cancel(this, memoId)
            MemoReminder.dismissNotification(this, memoId)
        }
        finishAndRemoveTask()
    }

    private fun snooze() {
        if (memoId > 0L) {
            val minutes = MemoPrefs(this).reminderRepeatMinutes.takeIf { it > 0 } ?: 10
            MemoReminder.snooze(this, memoId, memoText, minutes)
            MemoReminder.dismissNotification(this, memoId)
        }
        finishAndRemoveTask()
    }
}

@Composable
private fun MemoAlarmScreen(
    text: String,
    palette: FocusColors.Palette,
    repeatMinutes: Int,
    onDone: () -> Unit,
    onSnooze: () -> Unit
) {
    val now = Date()
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
    val date = SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(now)
    val snoozeMinutes = repeatMinutes.takeIf { it > 0 } ?: 10

    Box(Modifier.fillMaxSize()) {
        AppBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = time,
                fontSize = 58.sp,
                fontWeight = FontWeight.Light,
                color = palette.text
            )
            Text(
                text = date,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                color = palette.haze
            )

            Spacer(Modifier.height(40.dp))

            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.14f))
                    .border(1.dp, palette.accent.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(34.dp)
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "待办提醒",
                fontSize = 11.sp,
                letterSpacing = 3.sp,
                color = palette.accent
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                fontSize = 25.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = palette.text
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.bg
                )
            ) {
                Text("已完成", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    palette.accent.copy(alpha = 0.45f)
                ),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text)
            ) {
                Text("稍后提醒（$snoozeMinutes 分钟）", fontSize = 15.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "未完成将每 $snoozeMinutes 分钟再次提醒",
                fontSize = 11.sp,
                color = palette.faint
            )
        }
    }
}
