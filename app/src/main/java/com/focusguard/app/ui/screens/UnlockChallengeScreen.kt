package com.focusguard.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.challenge.ChallengeGenerator
import kotlinx.coroutines.launch

/**
 * 解锁挑战答题界面（纯本地题库，零网络依赖）。
 *
 * 关键设计：
 * 1. 题目全部由 [ChallengeGenerator] 本地即时生成，进入界面立刻可答，
 *    不再有"AI 正在出题中…"的等待与失败风险。
 * 2. 难度随目标题数自动提升：单题解锁用中等难度，
 *    连对 5 题模式用困难难度，避免"5 道简单题"形同虚设。
 * 3. 判分走归一化比对，容忍千分位、全角标点、单位后缀。
 */
@Composable
fun UnlockChallengeScreen(
    onUnlocked: () -> Unit,
    requiredCorrect: Int = 2
) {
    val context = LocalContext.current
    val generator = remember { ChallengeGenerator(context) }
    val scope = rememberCoroutineScope()
    // 答题页为沉浸深色界面，跟随主题但浅色回退深色·墨（见 DESIGN.md §3.2）
    val palette = remember(context) {
        com.focusguard.app.ui.theme.FocusColors.paletteForLockScreen(
            com.focusguard.app.data.Settings(context).themeMode,
            context
        )
    }

    val targetCorrectCount = remember { requiredCorrect.coerceAtLeast(1) }
    // 题数越多，单题难度越高：1 题=中等，>=3 题=困难
    val difficulty = remember { if (targetCorrectCount >= 3) 3 else 2 }

    // 换题计数持久化在 LockState：退出答题页重进不重置（锁机结束才归零）
    val lockState = remember { com.focusguard.app.data.LockState(context) }

    var currentQuestion by remember { mutableStateOf(generator.generate(difficulty)) }
    var userAnswer by remember { mutableStateOf("") }
    var currentCorrectCount by remember { mutableIntStateOf(0) }
    var refreshCount by remember { mutableIntStateOf(lockState.challengeRefreshCount) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    // 通用版没有学段概念，显示模式名而不是兜底的学段
    val gradeLabel = remember {
        if (com.focusguard.app.challenge.ChallengeMode.needsGrade())
            com.focusguard.app.data.GradeStore(context).effective.label
        else "通用题库"
    }
    // 作答时限：每题独立计时，超时算错并换题
    var secondsLeft by remember { mutableIntStateOf(currentQuestion.timeLimitSec) }
    // 答错冷却：免费次数用完后每次答错需等待 5 分钟（单调时钟，退出重进不重置）
    var cooldownSec by remember { mutableIntStateOf(((lockState.cooldownRemainingMs + 999) / 1000).toInt()) }
    var freeWrongLeft by remember { mutableIntStateOf(lockState.freeWrongLeft) }
    val cooling = cooldownSec > 0

    fun nextQuestion() {
        userAnswer = ""
        feedbackMessage = null
        currentQuestion = generator.generate(difficulty)
        secondsLeft = currentQuestion.timeLimitSec
    }

    /** 答错 / 超时统一处理：计数，次数用完则进入冷却。 */
    fun onWrong(msg: String) {
        generator.easeDifficulty = true
        val enteredCooldown = lockState.recordWrongAnswer()
        freeWrongLeft = lockState.freeWrongLeft
        feedbackMessage = msg
        isError = true
        switching = true
        scope.launch {
            kotlinx.coroutines.delay(2800L)
            if (enteredCooldown) {
                cooldownSec = ((lockState.cooldownRemainingMs + 999) / 1000).toInt()
            } else {
                nextQuestion()
            }
            switching = false
        }
    }

    // 冷却倒计时：结束后换一道新题
    LaunchedEffect(cooldownSec > 0) {
        if (cooldownSec <= 0) return@LaunchedEffect
        while (true) {
            cooldownSec = ((lockState.cooldownRemainingMs + 999) / 1000).toInt()
            if (cooldownSec <= 0) break
            feedbackMessage = "连续答错（${com.focusguard.app.data.LockState.FREE_WRONG_ANSWERS} 次），请等待 %d:%02d 后继续答题"
                .format(cooldownSec / 60, cooldownSec % 60)
            isError = true
            kotlinx.coroutines.delay(1000L)
        }
        nextQuestion()
    }

    LaunchedEffect(currentQuestion, cooling) {
        if (cooling) return@LaunchedEffect
        secondsLeft = currentQuestion.timeLimitSec
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1000L)
            if (!switching) secondsLeft--
        }
        if (!switching) onWrong("超时，算作答错。正确答案：${currentQuestion.answer}")
    }

    fun submit() {
        if (switching || cooling || lockState.isInCooldown) return
        val question = currentQuestion
        val correct = generator.isAnswerCorrect(userAnswer, question.answer)
        if (correct) {
            lockState.recordCorrectAnswer()
            generator.easeDifficulty = false
            freeWrongLeft = lockState.freeWrongLeft
            currentCorrectCount += 1
            if (currentCorrectCount >= targetCorrectCount) {
                onUnlocked()
            } else {
                feedbackMessage = "回答正确！还需 ${targetCorrectCount - currentCorrectCount} 题"
                isError = false
                switching = true
                scope.launch {
                    kotlinx.coroutines.delay(900L)
                    nextQuestion()
                    switching = false
                }
            }
        } else {
            onWrong(buildString {
                append("回答错误。正确答案：${question.answer}")
                if (question.explanation.isNotBlank()) {
                    append("\n解析：${question.explanation}")
                }
            })
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 标题与进度 ────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        tint = palette.accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "解锁挑战",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.text
                    )
                }
                Text(
                    "进度 $currentCorrectCount / $targetCorrectCount",
                    color = palette.success,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            LinearProgressIndicator(
                progress = { currentCorrectCount.toFloat() / targetCorrectCount },
                modifier = Modifier.fillMaxWidth(),
                color = palette.success,
                trackColor = palette.line.copy(alpha = 0.5f)
            )

            Text(
                text = "答对 $targetCorrectCount 题即可解锁。答错会给出答案与解析并自动换题，进度不清零。",
                fontSize = 12.sp,
                color = palette.haze
            )

            // ── 题目卡片 ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = BorderStroke(1.dp, palette.line.copy(alpha = 0.6f)), colors = CardDefaults.cardColors(containerColor = palette.card)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "题目",
                            fontSize = 13.sp,
                            color = palette.accent,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (cooling) "冷却 %d:%02d".format(cooldownSec / 60, cooldownSec % 60)
                                else "$gradeLabel · ${secondsLeft}s · 可错 $freeWrongLeft",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (secondsLeft <= 15) palette.error else palette.haze
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = currentQuestion.question,
                        fontSize = 18.sp,
                        color = palette.text,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 26.sp
                    )
                }
            }

            // ── 答案显示区（自绘键盘） ──────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = palette.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.line)
            ) {
                Box(
                    modifier = Modifier.padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = userAnswer.ifEmpty { "请点击下方键盘输入答案" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (userAnswer.isEmpty()) palette.faint else palette.text
                    )
                }
            }

            // ── 自绘键盘 ──────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 如果是选择题，上方直接呈现 A B C D 大按键
                if (currentQuestion.options.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val multi = currentQuestion.answer.length > 1
                        currentQuestion.options.indices.map { ('A' + it).toString() }.forEach { optKey ->
                            Button(
                                onClick = {
                                    if (!switching && !cooling) {
                                        if (multi) {
                                            userAnswer = if (optKey in userAnswer) userAnswer.replace(optKey, "")
                                            else (userAnswer + optKey).toCharArray().sorted().joinToString("")
                                        } else {
                                            userAnswer = optKey
                                            submit()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (optKey in userAnswer) palette.accent else palette.card,
                                    contentColor = if (optKey in userAnswer) palette.bg else palette.text
                                )
                            ) {
                                Text(optKey, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                val numRows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("-", "0", ".")
                )
                numRows.forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowKeys.forEach { key ->
                            Button(
                                onClick = {
                                    if (!switching && !cooling && userAnswer.length < 24) {
                                        userAnswer += key
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.surface,
                                    contentColor = palette.text
                                )
                            ) {
                                Text(key, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 功能行：清空、退格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            if (!switching) userAnswer = ""
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.surface,
                            contentColor = palette.text
                        )
                    ) {
                        Text("清空", fontSize = 14.sp)
                    }
                    Button(
                        onClick = {
                            if (!switching && userAnswer.isNotEmpty()) {
                                userAnswer = userAnswer.dropLast(1)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.surface,
                            contentColor = palette.text
                        )
                    ) {
                        Text("⌫", fontSize = 18.sp)
                    }
                }
            }

            // ── 反馈 ──────────────────────────────────
            feedbackMessage?.let { msg ->
                Surface(
                    color = (if (isError) palette.error else palette.success).copy(alpha = 0.18f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            if (isError) Icons.Default.Close else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isError) palette.error else palette.success,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg,
                            color = palette.text,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            // ── 操作按钮 ──────────────────────────────
            Button(
                onClick = { submit() },
                enabled = userAnswer.isNotBlank() && !switching && !cooling,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.bg,
                    disabledContainerColor = palette.surface,
                    disabledContentColor = palette.faint
                )
            ) {
                Text(if (switching) "准备下一题…" else "提交答案", fontSize = 17.sp)
            }

            OutlinedButton(
                onClick = {
                    if (refreshCount < 5) {
                        refreshCount++
                        // 持久化：退出答题页重进不重置
                        lockState.recordChallengeRefresh()
                        nextQuestion()
                    }
                },
                enabled = !switching && !cooling && refreshCount < 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (refreshCount >= 5) "已达换题上限(5/5)" else "换一题 (${refreshCount}/5)",
                    color = if (refreshCount >= 5) palette.faint else palette.haze,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
