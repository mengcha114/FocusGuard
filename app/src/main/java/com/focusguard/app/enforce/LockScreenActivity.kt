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
 * 全局强制锁机界面（勒索式全屏覆盖）。
 *
 * 防退出机制（多层）：
 * 1. 返回键拦截
 * 2. 失焦轮询顶回：每 400ms 检查窗口焦点，失焦即重新置顶（不依赖无障碍）
 * 3. 无障碍服务窗口拦截：切换到其他应用时顶回（无障碍存在时生效）
 * 4. 答题时（UnlockChallengeActivity 在前台）暂停顶回，避免输入法循环
 * 5. 锁机状态持久化，强杀/重启进程后依然锁定
 */
class LockScreenActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LockScreenActivity"

        /** 当前锁机页实例，供答题页解锁后通知收尾。 */
        @Volatile
        var instance: LockScreenActivity? = null
            private set

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

    /** 答题成功后是解锁（false）还是申请暂停（true）。 */
    private var pendingPause = false

    /** 失焦轮询：间隔 400ms，不依赖无障碍，只要失焦就顶回。 */
    private val focusPollingRunnable = object : Runnable {
        override fun run() {
            if (!isDestroyed && lockState.shouldBlockNow && !UnlockChallengeActivity.active) {
                // 输入法弹出时（强度 3 输密码）窗口焦点仍在，不受影响；
                // 但部分 ROM 上输入法弹出会造成瞬时失焦，这里再补一次输入法判断兜底
                val imeActive = try {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).isAcceptingText
                } catch (e: Exception) {
                    false
                }
                if (!hasWindowFocus() && !imeActive) {
                    Log.d(TAG, "轮询发现窗口失焦，执行顶回")
                    // 通知栏被拉下来时先收起
                    com.focusguard.app.access.GuardAccessibilityService.instance
                        ?.dismissNotificationShade()
                    reassert(this@LockScreenActivity)
                }
            }
            if (!isDestroyed) {
                window.decorView.postDelayed(this, 400L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockState = LockState(this)
        instance = this

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
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
                onStartChallenge = { count ->
                    pendingPause = count < 0
                    UnlockChallengeActivity.show(this, kotlin.math.abs(count).coerceAtLeast(1))
                },
                onUnlocked = {
                    lockState.releaseLock()
                    finish()
                }
            )
        }

        // 启动失焦轮询（不依赖无障碍的防退出兜底）
        window.decorView.postDelayed(focusPollingRunnable, 400L)
    }

    /** 答题页答对全部题目后调用：暂停申请则开始暂停，否则解锁。 */
    fun onUnlockedExternally() {
        if (pendingPause) {
            pendingPause = false
            lockState.startPause()
            Log.d(TAG, "答题成功，获得一次暂停（${lockState.pauseMinutes} 分钟）")
        } else {
            lockState.releaseLock()
            finish()
        }
    }

    @Deprecated("Back blocked during lock")
    override fun onBackPressed() {
        // 拦截返回键——锁机期间任何退出手段都无效
    }

    override fun onResume() {
        super.onResume()
        if (!lockState.isLocked) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }
}

@Composable
private fun LockScreenContent(
    lockState: LockState,
    onStartChallenge: (Int) -> Unit,
    onUnlocked: () -> Unit
) {
    var remainingSeconds by remember { mutableIntStateOf(lockState.remainingSeconds) }
    var isWorkPhase by remember { mutableStateOf(lockState.pomodoroIsWorkPhase) }
    var phaseSeconds by remember { mutableIntStateOf(lockState.pomodoroRemainingSeconds) }
    var pauseSeconds by remember { mutableIntStateOf(lockState.pauseRemainingSeconds) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val isPomodoro = lockState.lockSource == "POMODORO"

    // 随机一条励志语录（每次进入锁机页不同）
    val motto = remember { MotivationalQuotes.random() }

    // 倒计时 + 番茄钟阶段推进 + 暂停倒计时 + 时钟
    LaunchedEffect(Unit) {
        while (lockState.isLocked) {
            kotlinx.coroutines.delay(1000L)
            nowMillis = System.currentTimeMillis()
            remainingSeconds = lockState.remainingSeconds
            pauseSeconds = lockState.pauseRemainingSeconds

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
            val isPausing = lockState.isPaused
            val accent = when {
                isPausing -> Color(0xFF4CAF50)
                isPomodoro && !isWorkPhase -> Color(0xFF4CAF50)
                else -> Color(0xFF7C4DFF)
            }

            // ── 日期时间 ──────────────────────────────
            val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val dateFormat = java.text.SimpleDateFormat("yyyy年M月d日 EEEE", java.util.Locale.getDefault())
            Text(
                text = timeFormat.format(java.util.Date(nowMillis)),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = dateFormat.format(java.util.Date(nowMillis)),
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(28.dp))

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "专注卫士",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    isPausing -> "暂停中 · 可自由使用"
                    isPomodoro && isWorkPhase -> "番茄钟专注阶段 · 设备已锁定"
                    isPomodoro -> "番茄钟休息阶段 · 可自由使用"
                    else -> "设备已锁定，请专心工作学习"
                },
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            // ── 励志语录 ──────────────────────────────
            Surface(
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "「$motto」",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // 倒计时：暂停中显示暂停剩余，番茄钟显示当前阶段剩余，普通锁机显示总剩余
            val shownSeconds = when {
                isPausing -> pauseSeconds
                isPomodoro -> phaseSeconds
                else -> remainingSeconds
            }
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
                    color = if (isPausing) Color(0xFF81C784) else Color(0xFFFF6B6B),
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

            when (lockState.unlockStrength) {
                4 -> {
                    // 强度 4：无法解锁，只能等时间结束
                    Text(
                        text = "本锁机不可提前解锁，请等待时间结束",
                        fontSize = 13.sp,
                        color = Color(0xFFC6786F),
                        textAlign = TextAlign.Center
                    )
                }
                3 -> {
                    // 强度 3：朋友辅助（凯撒密码）
                    FriendUnlockSection(
                        cipher = lockState.friendCipher,
                        shift = lockState.friendShift,
                        onVerified = onUnlocked
                    )
                }
                2 -> {
                    Text(
                        text = "需连续答对 5 道高难度题才能解锁",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { onStartChallenge(5) },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("开始挑战（5 题）", color = Color(0xFFD0BCFF))
                    }
                }
                else -> {
                    Text(
                        text = "答对 1 道高难度计算题即可解锁",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { onStartChallenge(1) },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("挑战答题解锁", color = Color(0xFFD0BCFF))
                    }
                }
            }

            // ── 暂停申请（答题获得暂停时长） ──────────
            if (lockState.pauseEnabled && !lockState.isPaused) {
                Spacer(Modifier.height(24.dp))
                if (lockState.canPause) {
                    Text(
                        text = "可以申请暂停：剩余 ${lockState.pauseQuota - lockState.pauseUsed} 次，每次 ${lockState.pauseMinutes} 分钟",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onStartChallenge(-1) }, // -1 标记为暂停申请
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("答题申请暂停", color = Color(0xFF8AB4F8))
                    }
                } else {
                    Text(
                        text = "暂停次数已用完",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}

/** 强度 3：朋友辅助解锁——显示凯撒密文与偏移，输入解密后的密码。 */
@Composable
private fun FriendUnlockSection(
    cipher: String,
    shift: Int,
    onVerified: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var input by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showHint by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "朋友辅助解锁",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFD0BCFF)
        )

        Surface(
            color = Color(0xFF241F27),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "密文",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = cipher.ifBlank { "——" },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 6.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "偏移量：$shift",
                    fontSize = 14.sp,
                    color = Color(0xFF8AB4F8)
                )
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it; errorMsg = null },
            label = { Text("朋友解密后的密码") },
            placeholder = { Text("输入明文密码") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        errorMsg?.let {
            Text(it, color = Color(0xFFC6786F), fontSize = 12.sp)
        }

        TextButton(onClick = { showHint = !showHint }) {
            Text(
                text = if (showHint) "收起解密说明" else "怎么解密？",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }

        if (showHint) {
            Text(
                text = "把密文中的每个字母按偏移量向前移动 $shift 位即得密码。\n" +
                    "例：偏移 3 时，D→A，E→B，F→C。数字保持不变。\n" +
                    "把密文发给朋友，朋友可通过在线工具解密，如：\n" +
                    "https://www.lddgo.net/encrypt/caesar-cipher",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                lineHeight = 18.sp
            )
        }

        Button(
            onClick = {
                if (com.focusguard.app.data.LockState(context).verifyFriendPassword(input)) {
                    onVerified()
                } else {
                    errorMsg = "密码错误，请核对解密结果"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F378B))
        ) {
            Text("输入密码解锁", fontSize = 15.sp)
        }
    }
}

/** 励志语录库：锁机页随机展示一条。 */
private object MotivationalQuotes {

    private val quotes = listOf(
        "自律给我自由",
        "现在的努力，是为了以后更好的自己",
        "把专注当成习惯，优秀就会成为自然",
        "每一个不起舞的日子，都是对生命的辜负",
        "坚持一下，你比自己想象的更强大",
        "今天的不开心就到此为止，明天依然光芒万丈",
        "你的时间花在哪里，人生的花就开在哪里",
        "别让未来的你，讨厌现在放纵的自己",
        "努力是会上瘾的，尤其是尝到甜头之后",
        "优秀的人不是天生优秀，而是比常人更自律",
        "读书是为了遇见更好的自己",
        "熬过无人问津的日子，才有诗和远方",
        "自律的顶端是享受孤独",
        "你现在偷的懒，都会变成以后打脸的巴掌",
        "与其仰望别人，不如点亮自己",
        "脚踏实地，才能仰望星空",
        "奋斗的路上，每一步都算数",
        "把每一件简单的事做好，就是不简单",
        "专注当下，未来自然来",
        "坚持做难而正确的事"
    )

    fun random(): String = quotes[kotlin.random.Random.nextInt(quotes.size)]
}
