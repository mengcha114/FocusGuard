package com.focusguard.app.enforce

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.focusguard.app.service.LockGuardService

/**
 * 全局强制锁机界面（全屏覆盖）。
 *
 * ## 职责边界（重要）
 * 本 Activity **只负责显示**。防退出的守护职责已完全移交给
 * [LockGuardService]（独立前台服务）。原因：
 *
 * - Activity 会被最近任务划掉、被系统内存回收、被 ROM 清理，
 *   把守护逻辑放在这里等于把门锁挂在门板上——门被拆了锁就没了。
 * - 早期实现在 `onDestroy` 里用已销毁的 Activity Context 重新
 *   `startActivity` 自己，形成"拉起→销毁→再拉起"的循环，
 *   这正是"破解后再打开软件直接闪退"的根因。
 *
 * 现在：Activity 被销毁 → 前台服务在下一个巡检周期（1.2s 内）
 * 用 applicationContext 重新拉起，安全且必然生效。
 */
class LockScreenActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LockScreenActivity"

        /** 当前锁机页实例，供答题页与守护服务查询。 */
        @Volatile
        var instance: LockScreenActivity? = null
            private set

        /** 锁机页是否在前台可见（守护服务据此判断是否需要拉起）。 */
        @Volatile
        var foreground: Boolean = false
            private set

        /**
         * 显示锁机页。
         *
         * 必须使用 applicationContext 调用，避免持有已销毁的 Activity。
         */
        fun show(context: Context) {
            try {
                val intent = Intent(context.applicationContext, LockScreenActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                context.applicationContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "拉起锁机页失败：${e.message}")
            }
        }

        /** 兼容旧调用点：语义与 [show] 相同（都是置顶而非重建）。 */
        fun reassert(context: Context) = show(context)
    }

    private lateinit var lockState: LockState

    /** 答题成功后是解锁（false）还是换取一次暂停（true）。 */
    private var pendingPause = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockState = LockState(this)
        instance = this

        // 锁屏上也能显示、并点亮屏幕
        try {
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
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.w(TAG, "设置窗口标志失败：${e.message}")
        }

        // 全屏沉浸：隐藏状态栏与导航栏，阻止下拉通知栏
        applyImmersiveMode()

        // 锁机已到期 → 直接退出
        if (!lockState.isLocked) {
            Log.d(TAG, "锁机已到期，关闭锁机页")
            finish()
            return
        }

        // 确保守护服务在运行（负责防退出巡检）
        LockGuardService.ensureRunning(applicationContext)

        setContent {
            LockScreenContent(
                lockState = lockState,
                onStartChallenge = { count -> startChallenge(count) },
                onUnlocked = {
                    // 先退出系统级 Lock Task，再释放锁机
                    com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
                    lockState.releaseLock()
                    LockGuardService.stop(applicationContext)
                    finish()
                }
            )
        }
    }

    /**
     * 发起答题。
     *
     * @param count 正数=需答对的题数（解锁）；-1=申请暂停
     */
    private fun startChallenge(count: Int) {
        pendingPause = count < 0
        val required = kotlin.math.abs(count).coerceAtLeast(1)
        val launched = UnlockChallengeActivity.show(applicationContext, required)
        if (!launched) {
            pendingPause = false
            Toast.makeText(this, "无法打开答题界面，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    /** 答题页答对全部题目后回调：暂停申请则开始暂停，否则解锁。 */
    fun onUnlockedExternally() {
        if (pendingPause) {
            pendingPause = false
            lockState.startPause()
            Log.d(TAG, "答题成功，获得 ${lockState.pauseMinutes} 分钟暂停")
            // 暂停期间退出系统级 Lock Task，让用户自由使用；
            // 守护服务会在暂停结束后自动把锁机页拉回来并重新进入 Lock Task。
            com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
            moveTaskToBack(true)
        } else {
            Log.d(TAG, "答题成功，解除锁机")
            com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
            lockState.releaseLock()
            LockGuardService.stop(applicationContext)
            finish()
        }
    }

    private fun applyImmersiveMode() {
        try {
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            Log.w(TAG, "进入沉浸模式失败：${e.message}")
        }
    }

    @Deprecated("Back blocked during lock")
    override fun onBackPressed() {
        // 拦截返回键：锁机期间任何退出手段都无效
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (!lockState.isLocked) {
            finish()
            return
        }
        // 每次回到前台重新应用沉浸模式（部分 ROM 会重置系统栏状态）
        applyImmersiveMode()

        // Dhizuku 增强：应封锁时进入系统级 Lock Task（Home/上滑/最近任务全部失效）；
        // 暂停/番茄钟休息阶段退出 Lock Task，让用户自由使用。
        if (lockState.shouldBlockNow) {
            com.focusguard.app.enhance.LockTaskEnhancer.enter(this)
        } else {
            com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
    }

    /**
     * 用户尝试上滑/按 Home 离开锁机页时的即时拦截。
     *
     * 这里不调 startActivity（后台 Activity 启动受平台限制，且会闪烁），
     * 而是让前台守护服务立刻巡检拉起。配合 LockGuardService 的
     * TYPE_APPLICATION_OVERLAY 覆盖层，锁机页被切走后 1 秒内
     * 覆盖层会盖住整个屏幕（含桌面/小窗），用户无法操作任何内容。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d(TAG, "检测到上滑/Home 手势，锁机页即将离开前台")
        // 尽力触发守护巡检（无需等待下一个 600ms tick）
        try {
            LockGuardService.ensureRunning(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "触发守护巡检失败：${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 失焦通常意味着通知栏被拉下 → 立即收起。
        // 注意：这里只做"收通知栏"，不做顶回。
        // 顶回统一由守护服务处理，避免与输入法争抢焦点导致闪退。
        if (!hasFocus && lockState.shouldBlockNow && !UnlockChallengeActivity.active) {
            com.focusguard.app.access.GuardAccessibilityService.instance
                ?.dismissNotificationShade()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        foreground = false
        // 兜底退出 Lock Task（正常路径已在 unlock/暂停时退出；
        // 若因锁机到期 finish 等路径遗漏，这里保证系统不被锁死）
        com.focusguard.app.enhance.LockTaskEnhancer.exit(this)
        Log.d(TAG, "锁机页已销毁（守护服务会在需要时重新拉起）")
        // 这里绝不自行 startActivity：
        // 用已销毁的 Activity 作为 Context 拉起自己会造成崩溃循环。
        // 恢复工作交由 LockGuardService 的巡检完成。
    }
}

// ── UI ────────────────────────────────────────────────────────────────

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
    var isPausing by remember { mutableStateOf(lockState.isPaused) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pauseLeft by remember { mutableIntStateOf(lockState.pauseQuota - lockState.pauseUsed) }

    val isPomodoro = lockState.lockSource == "POMODORO"
    val motto = remember { MotivationalQuotes.random() }

    // 统一的每秒刷新：时钟、倒计时、番茄钟阶段、暂停状态
    LaunchedEffect(Unit) {
        // 追踪番茄钟工作/休息阶段切换 → 同步进出系统级 Lock Task
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        var lastWorkPhase = lockState.pomodoroIsWorkPhase

        while (lockState.isLocked) {
            kotlinx.coroutines.delay(1000L)
            nowMillis = System.currentTimeMillis()
            remainingSeconds = lockState.remainingSeconds
            pauseSeconds = lockState.pauseRemainingSeconds
            isPausing = lockState.isPaused
            pauseLeft = lockState.pauseQuota - lockState.pauseUsed

            if (isPomodoro) {
                phaseSeconds = lockState.pomodoroRemainingSeconds
                isWorkPhase = lockState.pomodoroIsWorkPhase
                if (phaseSeconds <= 0) {
                    val finished = lockState.advancePomodoroPhase()
                    if (finished) break
                    isWorkPhase = lockState.pomodoroIsWorkPhase
                    phaseSeconds = lockState.pomodoroRemainingSeconds
                }
                // 阶段切换：休息→工作 重新进入 Lock Task；工作→休息 释放
                if (isWorkPhase != lastWorkPhase && activity != null) {
                    if (isWorkPhase) {
                        com.focusguard.app.enhance.LockTaskEnhancer.enter(activity)
                    } else {
                        com.focusguard.app.enhance.LockTaskEnhancer.exit(activity)
                    }
                    lastWorkPhase = isWorkPhase
                }
            }
        }
        onUnlocked()
    }

    val accent = when {
        isPausing -> Color(0xFF4CAF50)
        isPomodoro && !isWorkPhase -> Color(0xFF4CAF50)
        else -> Color(0xFF7C4DFF)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── 时钟与日期 ──────────────────────────────
            val timeFormat = remember {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            }
            val dateFormat = remember {
                java.text.SimpleDateFormat("yyyy年M月d日 EEEE", java.util.Locale.getDefault())
            }
            Text(
                text = timeFormat.format(java.util.Date(nowMillis)),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = dateFormat.format(java.util.Date(nowMillis)),
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.55f)
            )

            Spacer(Modifier.height(26.dp))

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "专注卫士",
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    isPausing -> "暂停中 · 可自由使用"
                    isPomodoro && isWorkPhase -> "番茄钟专注阶段 · 设备已锁定"
                    isPomodoro -> "番茄钟休息阶段 · 可自由使用"
                    else -> "设备已锁定，请专心工作学习"
                },
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(22.dp))

            // ── 励志语录 ────────────────────────────────
            Surface(
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "「$motto」",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            Spacer(Modifier.height(22.dp))

            // ── 倒计时 ──────────────────────────────────
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
                Column(
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isPausing) "暂停剩余" else "锁定剩余",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = timeText,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPausing) Color(0xFF81C784) else Color(0xFFFF6B6B)
                    )
                }
            }

            if (isPomodoro) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "剩余 ${lockState.pomodoroRoundsLeft} 轮 · 今日已完成 ${lockState.pomodoroCompletedToday} 个",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(32.dp))

            // ── 解锁区（暂停中隐藏，避免重复操作） ──────
            if (!isPausing) {
                when (lockState.unlockStrength) {
                    4 -> Text(
                        text = "本次锁机不可提前解锁，请等待时间结束",
                        fontSize = 13.sp,
                        color = Color(0xFFC6786F),
                        textAlign = TextAlign.Center
                    )
                    3 -> FriendUnlockSection(
                        cipher = lockState.friendCipher,
                        shift = lockState.friendShift,
                        onVerified = onUnlocked
                    )
                    2 -> UnlockButtonWithHint(
                        hint = "需连续答对 5 道高难度题才能解锁",
                        buttonText = "开始挑战（5 题）",
                        onClick = { onStartChallenge(5) }
                    )
                    else -> UnlockButtonWithHint(
                        hint = "答对 1 道高难度计算题即可解锁",
                        buttonText = "挑战答题解锁",
                        onClick = { onStartChallenge(1) }
                    )
                }

                // ── 暂停申请 ────────────────────────────
                if (lockState.pauseEnabled) {
                    Spacer(Modifier.height(22.dp))
                    if (lockState.canPause) {
                        Text(
                            text = "可申请暂停：剩余 $pauseLeft 次，每次 ${lockState.pauseMinutes} 分钟",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.45f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onStartChallenge(-1) },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("答题申请暂停", color = Color(0xFF8AB4F8))
                        }
                    } else {
                        Text(
                            text = "暂停次数已用完（共 ${lockState.pauseQuota} 次）",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnlockButtonWithHint(hint: String, buttonText: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = hint,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.45f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(14.dp)) {
            Text(buttonText, color = Color(0xFFD0BCFF))
        }
    }
}

/** 强度 3：朋友辅助解锁——显示密文与偏移量，输入解密后的密码。 */
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
                Text("密文", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = cipher.ifBlank { "——" },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 6.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("偏移量：$shift", fontSize = 14.sp, color = Color(0xFF8AB4F8))
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

        errorMsg?.let { Text(it, color = Color(0xFFC6786F), fontSize = 12.sp) }

        TextButton(onClick = { showHint = !showHint }) {
            Text(
                text = if (showHint) "收起解密说明" else "怎么解密？",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }

        if (showHint) {
            Text(
                text = "把密文交给朋友，让朋友用在线工具解密后把结果告给你。\n" +
                    "推荐工具：https://www.lddgo.net/encrypt/caesar-cipher\n" +
                    "解密方式选「凯撒密码解密」，偏移量填 $shift。\n" +
                    "也可手动推算：每个字母向前移动 $shift 位（偏移 3 时 D→A），数字不变。",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                lineHeight = 18.sp
            )
        }

        Button(
            onClick = {
                if (LockState(context).verifyFriendPassword(input)) {
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
        "坚持做难而正确的事",
        "时间不会辜负每一个认真生活的人"
    )

    fun random(): String = quotes[kotlin.random.Random.nextInt(quotes.size)]
}
