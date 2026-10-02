package com.focusguard.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.challenge.AttemptGuard
import com.focusguard.app.challenge.ChallengeGenerator
import com.focusguard.app.challenge.ChallengeQuestion
import kotlinx.coroutines.delay

/**
 * 答题验证弹窗（设置放宽 / 停止守护 / 应用管控共用）。
 *
 * 规则由 [AttemptGuard]（SCOPE_VERIFY）统一：答错立即换题、连续错 2 次冷却 5 分钟、
 * 换一题最多 5 次。选择题显示 A/B/C/D 按钮，填空题显示输入框。
 * 内容区可滚动（长题干、横屏都能完整显示），按钮始终可见。
 */
@Composable
fun VerifyDialog(
    title: String,
    description: String,
    confirmText: String,
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
    val cooling = cooldownSec > 0

    fun nextQuestion(excludeTopic: String? = question.kind) {
        question = generator.generate(2, excludeTopic = excludeTopic)
        answer = ""
    }

    // 冷却倒计时：结束后换一道新题
    LaunchedEffect(cooling) {
        if (!cooling) return@LaunchedEffect
        while (true) {
            cooldownSec = guard.cooldownSeconds
            if (cooldownSec <= 0) break
            delay(1000)
        }
        wrongLeft = guard.wrongLeft
        feedback = null
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
        val enteredCooldown = guard.recordWrong()
        wrongLeft = guard.wrongLeft
        feedbackIsError = true
        feedback = if (enteredCooldown) {
            "连续答错 ${AttemptGuard.MAX_WRONG} 次，需等待 5 分钟。正确答案：${question.answer}"
        } else {
            "回答错误，已换一题（再错 $wrongLeft 次需等待 5 分钟）。上题答案：${question.answer}"
        }
        cooldownSec = guard.cooldownSeconds
        // 答错立即换题，杜绝同一道题反复试到蒙对
        nextQuestion()
    }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

    AlertDialog(
        onDismissRequest = { /* 必须明确选择：验证或取消 */ },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = if (cooling) "冷却中 ${guard.cooldownText()} · 冷却结束后自动出新题"
                    else "可错 $wrongLeft 次 · 可换题 $refreshLeft 次",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (cooling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                if (!cooling) {
                    Text(
                        text = question.question,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (question.options.isNotEmpty()) {
                        // 选择题：按选项数显示字母按钮，点一下即提交
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            question.options.indices.forEach { i ->
                                val letter = ('A' + i).toString()
                                OutlinedButton(
                                    onClick = { submit(letter) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text(letter, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it.take(24) },
                            label = { Text("你的答案") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
                feedback?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = if (feedbackIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        if (guard.tryRefresh()) {
                            refreshLeft = guard.refreshLeft
                            feedback = null
                            nextQuestion()
                        }
                    },
                    enabled = !cooling && refreshLeft > 0
                ) { Text("换一题") }
                if (question.options.isEmpty()) {
                    Button(
                        onClick = { submit(answer) },
                        enabled = !cooling && answer.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(confirmText) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
