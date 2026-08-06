package com.focusguard.app.enforce

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
import com.focusguard.app.data.LockState

/**
 * 全局强制锁机界面。
 *
 * 功能：
 * - 覆盖整个屏幕，无法被用户关掉
 * - 返回键 / Home 键均被拦截（通过无障碍阻止回桌面）
 * - 只有答对挑战题才能解锁
 * - 进程被杀后重启 Activity 仍会根据 LockState 判断是否继续锁
 */
class LockScreenActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LockScreenActivity"

        fun show(context: Context) {
            val intent = Intent(context, LockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            context.startActivity(intent)
        }

        /** 防破解顶回：不携带 CLEAR_TASK，避免每次顶回都销毁重建自己造成闪烁。 */
        fun reassert(context: Context) {
            val intent = Intent(context, LockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            context.startActivity(intent)
        }
    }

    private lateinit var lockState: LockState

    /** 是否正在显示答题界面（输入法可能弹出，此时不能顶回）。 */
    private var challengeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockState = LockState(this)

        // 锁屏上也能显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // 全屏沉浸：隐藏状态栏/导航栏，防下拉通知栏
        try {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            Log.w(TAG, "进入沉浸模式失败：${e.message}")
        }

        // 如果锁机已到期则直接退出
        if (!lockState.isLocked) {
            finish()
            return
        }

        setContent {
            LockScreenContent(
                lockState = lockState,
                challengeVisible = challengeVisible,
                onChallengeVisibilityChange = { challengeVisible = it },
                onUnlocked = {
                    lockState.releaseLock()
                    finish()
                }
            )
        }
    }

    @Deprecated("Back blocked during lock")
    override fun onBackPressed() {
        // 拦截返回键——锁机期间任何退出手段都无效
    }

    /** 输入法是否可见（可见时说明用户在答题，不是试图逃跑）。 */
    private fun isImeVisible(): Boolean {
        return try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.isAcceptingText
        } catch (e: Exception) {
            false
        }
    }

    /** 窗口失焦（用户按 Home / 最近任务 / 通知栏）时立即顶回前台。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && lockState.shouldBlockNow && !challengeVisible && !isImeVisible()) {
            // 通知栏被拉下来时先收起（部分机型窗口失焦即代表通知栏弹出）
            com.focusguard.app.access.GuardAccessibilityService.instance
                ?.dismissNotificationShade()
            // 延迟一点再置顶，避免与系统转场动画冲突导致闪屏
            android.os.Handler(mainLooper).postDelayed({
                if (lockState.shouldBlockNow && !challengeVisible) {
                    reassert(this)
                }
            }, 150L)
        }
    }

    override fun onUserLeaveHint() {
        // 用户试图离开（如按 Home）时重新置顶自身。
        // 番茄钟休息阶段允许离开；答题界面（输入法弹出）时不顶回。
        super.onUserLeaveHint()
        if (lockState.shouldBlockNow && !challengeVisible) {
            reassert(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!lockState.isLocked) {
            finish()
        }
    }
}

@Composable
private fun LockScreenContent(
    lockState: LockState,
    challengeVisible: Boolean,
    onChallengeVisibilityChange: (Boolean) -> Unit,
    onUnlocked: () -> Unit
) {
    var remainingSeconds by remember { mutableIntStateOf(lockState.remainingSeconds) }
    var isWorkPhase by remember { mutableStateOf(lockState.pomodoroIsWorkPhase) }
    var phaseSeconds by remember { mutableIntStateOf(lockState.pomodoroRemainingSeconds) }
    var showChallenge by remember { mutableStateOf(false) }

    val isPomodoro = lockState.lockSource == "POMODORO"

    // 倒计时 + 番茄钟阶段推进
    LaunchedEffect(Unit) {
        while (lockState.isLocked) {
            kotlinx.coroutines.delay(1000L)
            remainingSeconds = lockState.remainingSeconds

            if (isPomodoro) {
                phaseSeconds = lockState.pomodoroRemainingSeconds
                isWorkPhase = lockState.pomodoroIsWorkPhase
                if (phaseSeconds <= 0) {
                    val finished = lockState.advancePomodoroPhase()
                    if (finished) break
                    isWorkPhase = lockState.pomodoroIsWorkPhase
                    phaseSeconds = lockState.pomodoroRemainingSeconds
                }
            }
        }
        onUnlocked()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D0D12), Color(0xFF17151C))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val accent = if (isPomodoro && !isWorkPhase) Color(0xFF4CAF50) else Color(0xFF7C4DFF)

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "专注卫士",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    isPomodoro && isWorkPhase -> "番茄钟专注阶段 · 设备已锁定"
                    isPomodoro -> "番茄钟休息阶段 · 可自由使用"
                    else -> "设备已锁定，请专心工作学习"
                },
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            // 倒计时：番茄钟显示当前阶段剩余，普通锁机显示总剩余
            val shownSeconds = if (isPomodoro) phaseSeconds else remainingSeconds
            val h = shownSeconds / 3600
            val m = (shownSeconds % 3600) / 60
            val s = shownSeconds % 60
            val timeText = if (h > 0) {
                "%02d:%02d:%02d".format(h, m, s)
            } else {
                "%02d:%02d".format(m, s)
            }

            Surface(color = Color(0xFF1F1B24), shape = RoundedCornerShape(20.dp)) {
                Text(
                    text = timeText,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPomodoro && !isWorkPhase) Color(0xFF81C784) else Color(0xFFFF6B6B),
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
                )
            }

            if (isPomodoro) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "剩余 ${lockState.pomodoroRoundsLeft} 轮 · 今日已完成 ${lockState.pomodoroCompletedToday} 个",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(48.dp))

            Text(
                text = "如需提前解锁，请答对高难度计算题",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showChallenge = true },
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("挑战答题解锁", color = Color(0xFFD0BCFF))
            }
        }
    }

    if (showChallenge) {
        // 通知 Activity：答题界面可见，输入法弹出时不顶回
        LaunchedEffect(Unit) { onChallengeVisibilityChange(true) }
        DisposableEffect(Unit) {
            onDispose { onChallengeVisibilityChange(false) }
        }
        // 直接复用 UnlockChallengeScreen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            com.focusguard.app.ui.screens.UnlockChallengeScreen(
                onUnlocked = onUnlocked
            )
        }
    }
}
