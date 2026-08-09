package com.focusguard.app.ui.screens

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
    val generator = remember { ChallengeGenerator() }
    val scope = rememberCoroutineScope()

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

    fun nextQuestion() {
        userAnswer = ""
        feedbackMessage = null
        currentQuestion = generator.generate(difficulty)
    }

    fun submit() {
        if (switching) return
        val question = currentQuestion
        val correct = generator.isAnswerCorrect(userAnswer, question.answer)
        if (correct) {
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
            feedbackMessage = buildString {
                append("回答错误。正确答案：${question.answer}")
                if (question.explanation.isNotBlank()) {
                    append("\n解析：${question.explanation}")
                }
            }
            isError = true
            switching = true
            scope.launch {
                kotlinx.coroutines.delay(2800L)
                nextQuestion()
                switching = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
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
                        tint = Color(0xFF7C4DFF)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "解锁挑战",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    "进度 $currentCorrectCount / $targetCorrectCount",
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            LinearProgressIndicator(
                progress = { currentCorrectCount.toFloat() / targetCorrectCount },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF4CAF50),
                trackColor = Color.White.copy(alpha = 0.08f)
            )

            Text(
                text = "答对 $targetCorrectCount 题即可解锁。答错会给出答案与解析并自动换题，进度不清零。",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f)
            )

            // ── 题目卡片 ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))
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
                            color = Color(0xFF7C4DFF),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (difficulty) {
                                3 -> "难度：困难"
                                else -> "难度：中等"
                            },
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.35f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = currentQuestion.question,
                        fontSize = 18.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 26.sp
                    )
                }
            }

            // ── 答案输入 ──────────────────────────────
            OutlinedTextField(
                value = userAnswer,
                onValueChange = { userAnswer = it },
                label = { Text("输入你的答案") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                enabled = !switching,
                keyboardOptions = KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text
                )
            )

            // ── 反馈 ──────────────────────────────────
            feedbackMessage?.let { msg ->
                Surface(
                    color = if (isError) {
                        Color(0xFFD32F2F).copy(alpha = 0.2f)
                    } else {
                        Color(0xFF388E3C).copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            if (isError) Icons.Default.Close else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isError) Color(0xFFF44336) else Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            // ── 操作按钮 ──────────────────────────────
            Button(
                onClick = { submit() },
                enabled = userAnswer.isNotBlank() && !switching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7C4DFF),
                    disabledContainerColor = Color(0xFF2A2A2E)
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
                enabled = !switching && refreshCount < 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (refreshCount >= 5) "已达换题上限(5/5)" else "换一题 (${refreshCount}/5)",
                    color = if (refreshCount >= 5) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
