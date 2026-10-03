package com.focusguard.app.enforce

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.challenge.ChallengeGenerator
import com.focusguard.app.challenge.ChallengeQuestion
import com.focusguard.app.data.AttemptGuard
import kotlinx.coroutines.delay

/**
 * 应用封锁界面：悬浮窗与封锁 Activity **共用同一套**，外观与答题规则完全一致。
 *
 * 答题直接内嵌在这里（不用 AlertDialog）：悬浮窗没有 Activity 窗口，弹不出对话框，
 * 此前只能「点答题 → 跳出 Activity」，体验割裂。现在答题全程留在同一层界面里。
 */
@Composable
fun AppBlockContent(
    appLabel: String,
    usedMinutes: Int,
    limitMinutes: Int,
    blockUntil: Long,
    onUnlocked: () -> Unit,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    val palette = remember(context) {
        com.focusguard.app.ui.theme.FocusColors.paletteForLockScreen(
            com.focusguard.app.data.Settings(context).themeMode, context
        )
    }
    var showQuiz by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(blockUntil) {
        while (blockUntil > 0L) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }
    val temp = blockUntil > 0L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (showQuiz) {
                InlineUnlockQuiz(
                    appLabel = appLabel,
                    palette = palette,
                    onPassed = onUnlocked,
                    onCancel = { showQuiz = false }
                )
            } else {
                BlockSummary(
                    appLabel = appLabel,
                    usedMinutes = usedMinutes,
                    limitMinutes = limitMinutes,
                    blockUntil = blockUntil,
                    now = now,
                    temp = temp,
                    palette = palette,
                    onChallenge = { showQuiz = true },
                    onGoHome = onGoHome
                )
            }
        }
    }
}

/** 封锁摘要：图标 + 应用名 + 倒计时/用量卡 + 两个操作。 */
@Composable
private fun BlockSummary(
    appLabel: String,
    usedMinutes: Int,
    limitMinutes: Int,
    blockUntil: Long,
    now: Long,
    temp: Boolean,
    palette: com.focusguard.app.ui.theme.FocusColors.Palette,
    onChallenge: () -> Unit,
    onGoHome: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = palette.error.copy(alpha = 0.12f),
        modifier = Modifier.size(96.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (temp) Icons.Default.Lock else Icons.Default.Block,
                contentDescription = null,
                tint = palette.error,
                modifier = Modifier.size(46.dp)
            )
        }
    }
    Text(
        text = if (temp) "应用已锁定" else "今日已达使用上限",
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.text,
        textAlign = TextAlign.Center
    )
    Text(
        text = appLabel,
        fontSize = 16.sp,
        color = palette.haze,
        textAlign = TextAlign.Center
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, palette.line.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = palette.card)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (temp) {
                val left = ((blockUntil - now) / 1000).coerceAtLeast(0)
                val h = left / 3600
                val m = (left % 3600) / 60
                val s = left % 60
                Text("剩余时间", fontSize = 13.sp, color = palette.haze)
                Text(
                    text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent
                )
                Text(
                    "时间到会自动解除，期间打开该应用都会被挡住",
                    fontSize = 12.sp,
                    color = palette.haze,
                    textAlign = TextAlign.Center
                )
            } else {
                Text("今日已用", fontSize = 13.sp, color = palette.haze)
                Text(
                    text = "$usedMinutes / $limitMinutes 分钟",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.error
                )
                LinearProgressIndicator(
                    progress = {
                        if (limitMinutes > 0) (usedMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f) else 1f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = palette.error,
                    trackColor = palette.line
                )
                Text("每日 0 点自动重置", fontSize = 12.sp, color = palette.haze)
            }
        }
    }

    Button(
        onClick = onChallenge,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.accent,
            contentColor = palette.bg
        )
    ) {
        Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("答题解封", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
    OutlinedButton(
        onClick = onGoHome,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text)
    ) {
        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("返回桌面", fontSize = 15.sp)
    }
}

/**
 * 内嵌答题解封（不弹对话框）。
 *
 * 规则与其他答题路径一致：错 1 次立即换题、错 2 次冷却 5 分钟（[AttemptGuard]），
 * 每题有时限，超时算答错；选择题显示选项文字（A. 星期一），填空题用自绘键盘输入。
 */
@Composable
private fun InlineUnlockQuiz(
    appLabel: String,
    palette: com.focusguard.app.ui.theme.FocusColors.Palette,
    onPassed: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val generator = remember { ChallengeGenerator(context) }
    val guard = remember { AttemptGuard(context, AttemptGuard.SCOPE_VERIFY) }

    var question by remember { mutableStateOf<ChallengeQuestion>(generator.generate(2)) }
    var answer by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    var cooldownSec by remember { mutableIntStateOf(guard.cooldownSeconds) }
    var wrongLeft by remember { mutableIntStateOf(guard.wrongLeft) }
    var refreshLeft by remember { mutableIntStateOf(guard.refreshLeft) }
    var secondsLeft by remember { mutableIntStateOf(question.timeLimitSec) }
    val cooling = cooldownSec > 0
    val multi = question.answer.length > 1

    fun nextQuestion(exclude: String? = question.kind) {
        question = generator.generate(2, excludeTopic = exclude)
        answer = ""
        secondsLeft = question.timeLimitSec
    }

    fun recordWrong(timeout: Boolean) {
        val enteredCooldown = guard.recordWrong()
        wrongLeft = guard.wrongLeft
        feedbackIsError = true
        feedback = when {
            timeout && enteredCooldown ->
                "超时且连续答错 ${AttemptGuard.MAX_WRONG} 次，需等待 5 分钟。正确答案：${question.answer}"
            timeout ->
                "超时，算作答错，已换一题（再错 $wrongLeft 次需等待 5 分钟）。正确答案：${question.answer}"
            enteredCooldown ->
                "连续答错 ${AttemptGuard.MAX_WRONG} 次，需等待 5 分钟。正确答案：${question.answer}"
            else ->
                "回答错误，已换一题（再错 $wrongLeft 次需等待 5 分钟）。上题答案：${question.answer}"
        }
        cooldownSec = guard.cooldownSeconds
        nextQuestion()
    }

    fun submit(value: String) {
        if (cooling || value.isBlank()) return
        if (generator.isAnswerCorrect(value, question.answer)) {
            guard.recordCorrect()
            guard.resetSession()
            onPassed()
            return
        }
        recordWrong(timeout = false)
    }

    // 冷却倒计时结束后出新题
    LaunchedEffect(cooling) {
        if (!cooling) return@LaunchedEffect
        while (true) {
            cooldownSec = guard.cooldownSeconds
            if (cooldownSec <= 0) break
            delay(1000L)
        }
        wrongLeft = guard.wrongLeft
        feedback = null
        nextQuestion()
    }
    // 每题时限
    LaunchedEffect(question, cooling) {
        if (cooling) return@LaunchedEffect
        secondsLeft = question.timeLimitSec
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
        recordWrong(timeout = true)
    }

    Text(
        text = "答题解封",
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.text
    )
    Text(
        text = "答对后可继续使用「$appLabel」，并把今日已用时长回退一段",
        fontSize = 13.sp,
        color = palette.haze,
        textAlign = TextAlign.Center
    )
    Text(
        text = if (cooling) "冷却中 ${guard.cooldownText()} · 冷却结束后自动出新题"
        else "可错 $wrongLeft 次 · 可换题 $refreshLeft 次 · 剩余 ${secondsLeft}s",
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = if (cooling) palette.error else palette.accent
    )

    if (!cooling) {
        Text(
            text = question.question,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = palette.text
        )
        if (question.options.isNotEmpty()) {
            com.focusguard.app.ui.components.OptionList(
                options = question.options,
                selected = answer,
                multi = multi,
                enabled = true,
                containerColor = palette.card,
                contentColor = palette.text,
                selectedColor = palette.accent,
                selectedContentColor = palette.bg
            ) { letter ->
                if (multi) {
                    answer = if (letter in answer) answer.replace(letter, "")
                    else (answer + letter).toCharArray().sorted().joinToString("")
                } else {
                    answer = letter
                    submit(letter)
                }
            }
            if (multi) {
                Button(
                    onClick = { submit(answer) },
                    enabled = answer.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent, contentColor = palette.bg
                    )
                ) { Text("提交答案") }
            }
        } else {
            // 填空题：自绘键盘（悬浮窗里系统输入法不可靠）
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = palette.surface,
                border = BorderStroke(1.dp, palette.line)
            ) {
                Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = answer.ifEmpty { "用下方键盘输入答案" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (answer.isEmpty()) palette.faint else palette.text
                    )
                }
            }
            listOf(
                listOf("1", "2", "3"), listOf("4", "5", "6"),
                listOf("7", "8", "9"), listOf("-", "0", ".")
            ).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { key ->
                        Button(
                            onClick = { if (answer.length < 24) answer += key },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.card, contentColor = palette.text
                            )
                        ) { Text(key, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { answer = answer.dropLast(1) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.card, contentColor = palette.text
                    )
                ) { Text("⌫", fontSize = 16.sp) }
                Button(
                    onClick = { submit(answer) },
                    enabled = answer.isNotEmpty(),
                    modifier = Modifier.weight(2f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent, contentColor = palette.bg
                    )
                ) { Text("提交答案", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    feedback?.let {
        Text(
            text = it,
            fontSize = 12.sp,
            color = if (feedbackIsError) palette.error else palette.accent,
            textAlign = TextAlign.Center
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = {
                if (guard.tryRefresh()) {
                    refreshLeft = guard.refreshLeft
                    feedback = null
                    nextQuestion()
                }
            },
            enabled = !cooling && refreshLeft > 0,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, palette.line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text)
        ) { Text("换一题", fontSize = 14.sp) }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, palette.line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.haze)
        ) { Text("先不答", fontSize = 14.sp) }
    }
}
