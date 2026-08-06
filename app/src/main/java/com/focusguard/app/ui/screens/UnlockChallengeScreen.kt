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
import com.focusguard.app.challenge.ChallengeQuestion
import com.focusguard.app.data.Settings
import kotlinx.coroutines.launch

/**
 * 解锁挑战答题界面。
 *
 * 关键设计（针对"锁机无法答题"问题）：
 *
 * 1. **本地题目立即渲染**：进入界面就用本地生成的题目，
 *    绝不先等网络。之前的实现调用 AI 出题（最长 40 秒超时），
 *    API 异常（如 401）或网络差时用户会永远卡在"AI 正在出题中…"，
 *    根本无法答题解锁——这是致命缺陷。
 *
 * 2. **AI 题目作为可选升级**：本地题目显示后，后台异步尝试取 AI 题目；
 *    成功则替换（用户尚未作答时），失败则完全无感知。
 *
 * 3. **容错判分**：使用 [ChallengeGenerator.isAnswerCorrect]，
 *    容忍全角逗号、千分位、空格、单位后缀等差异。
 *    旧实现用严格字符串相等，答对了也可能被判错。
 */
@Composable
fun UnlockChallengeScreen(
    onUnlocked: () -> Unit,
    requiredCorrect: Int = 2
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val generator = remember { ChallengeGenerator() }
    val scope = rememberCoroutineScope()

    val targetCorrectCount = remember { requiredCorrect.coerceAtLeast(1) }

    // 立刻用本地题目初始化——保证界面一进来就能答题
    var currentQuestion by remember { mutableStateOf(generator.generateLocalQuestion()) }
    var userAnswer by remember { mutableStateOf("") }
    var currentCorrectCount by remember { mutableIntStateOf(0) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var aiUpgrading by remember { mutableStateOf(false) }
    /** 题目序号，用于取消过期的 AI 请求结果。 */
    var questionSeq by remember { mutableIntStateOf(0) }

    /** 换新题：先本地出题立即可答，再异步尝试 AI 题目替换。 */
    fun nextQuestion() {
        questionSeq += 1
        val seq = questionSeq
        userAnswer = ""
        currentQuestion = generator.generateLocalQuestion()

        // AI 题目仅在配置了密钥时尝试，且失败完全静默
        if (settings.apiKey.isNotBlank()) {
            aiUpgrading = true
            scope.launch {
                val aiQuestion = runCatching {
                    generator.generateQuestion(
                        baseUrl = settings.apiBaseUrl,
                        apiKey = settings.apiKey,
                        modelName = settings.modelName
                    )
                }.getOrNull()
                // 序号变了说明用户已经换题/答完，丢弃这次结果
                if (seq == questionSeq) {
                    if (aiQuestion != null && aiQuestion.question.isNotBlank() &&
                        userAnswer.isBlank()
                    ) {
                        currentQuestion = aiQuestion
                    }
                    aiUpgrading = false
                }
            }
        }
    }

    // 首次进入也尝试升级为 AI 题目（本地题目已经可答，不阻塞）
    LaunchedEffect(Unit) {
        if (settings.apiKey.isNotBlank()) {
            aiUpgrading = true
            val seq = questionSeq
            val aiQuestion = runCatching {
                generator.generateQuestion(
                    baseUrl = settings.apiBaseUrl,
                    apiKey = settings.apiKey,
                    modelName = settings.modelName
                )
            }.getOrNull()
            if (seq == questionSeq && aiQuestion != null &&
                aiQuestion.question.isNotBlank() && userAnswer.isBlank()
            ) {
                currentQuestion = aiQuestion
            }
            aiUpgrading = false
        }
    }

    fun submit() {
        val question = currentQuestion
        val correct = generator.isAnswerCorrect(userAnswer, question.answer)
        if (correct) {
            currentCorrectCount += 1
            if (currentCorrectCount >= targetCorrectCount) {
                onUnlocked()
            } else {
                feedbackMessage = "回答正确！还需 ${targetCorrectCount - currentCorrectCount} 题"
                isError = false
                nextQuestion()
            }
        } else {
            // 答错：给出正确答案与解析，进度不清零（避免过度惩罚导致永远解不开）
            feedbackMessage = "回答错误。正确答案：${question.answer}" +
                if (question.explanation.isNotBlank()) "\n解析：${question.explanation}" else ""
            isError = true
            scope.launch {
                kotlinx.coroutines.delay(2600L)
                feedbackMessage = null
                nextQuestion()
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

            Text(
                text = "答对 $targetCorrectCount 题即可解锁。答错会给出正确答案并自动换题，进度不会清零。",
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
                        if (aiUpgrading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF7C4DFF)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "尝试获取 AI 题目…",
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.35f)
                                )
                            }
                        }
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
                enabled = userAnswer.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7C4DFF),
                    disabledContainerColor = Color(0xFF2A2A2E)
                )
            ) {
                Text("提交答案", fontSize = 17.sp)
            }

            OutlinedButton(
                onClick = { feedbackMessage = null; nextQuestion() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("换一题", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
