package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.challenge.ChallengeGenerator
import com.focusguard.app.challenge.ChallengeQuestion
import com.focusguard.app.data.Settings
import kotlinx.coroutines.launch

@Composable
fun UnlockChallengeScreen(onUnlocked: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings(context) }
    val generator = remember { ChallengeGenerator() }
    val scope = rememberCoroutineScope()

    var currentQuestion by remember { mutableStateOf<ChallengeQuestion?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var userAnswer by remember { mutableStateOf("") }
    var targetCorrectCount by remember { mutableStateOf(2) }
    var currentCorrectCount by remember { mutableStateOf(0) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun loadNextQuestion() {
        isLoading = true
        userAnswer = ""
        scope.launch {
            currentQuestion = generator.generateQuestion(
                baseUrl = settings.apiBaseUrl,
                apiKey = settings.apiKey,
                modelName = settings.modelName
            )
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadNextQuestion()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp)
    ) {
        if (isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color(0xFF7C4DFF))
                Spacer(modifier = Modifier.height(16.dp))
                Text("AI 正在出题中...", color = Color.White)
            }
        } else {
            val q = currentQuestion
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = Color(0xFF7C4DFF))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("解锁挑战", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Text("进度: $currentCorrectCount / $targetCorrectCount", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }

                    Text("答对高难度思维/计算题方可解锁，答错将自动换题并给出正确答案。", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))

                    q?.let { question ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text("题目：", fontSize = 14.sp, color = Color(0xFF7C4DFF), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(question.question, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium)
                            }
                        }

                        OutlinedTextField(
                            value = userAnswer,
                            onValueChange = { userAnswer = it },
                            label = { Text("输入你的答案") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        feedbackMessage?.let { msg ->
                            Surface(
                                color = if (isError) Color(0xFFD32F2F).copy(alpha = 0.2f) else Color(0xFF388E3C).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isError) Icons.Default.Close else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (isError) Color(0xFFF44336) else Color(0xFF4CAF50)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(msg, color = Color.White, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        val q = currentQuestion ?: return@Button
                        val isCorrect = userAnswer.trim().equals(q.answer.trim(), ignoreCase = true)
                        if (isCorrect) {
                            currentCorrectCount++
                            feedbackMessage = "回答正确！"
                            isError = false
                            if (currentCorrectCount >= targetCorrectCount) {
                                onUnlocked()
                            } else {
                                loadNextQuestion()
                            }
                        } else {
                            feedbackMessage = "回答错误！正确答案是：${q.answer}\n解析：${q.explanation}"
                            isError = true
                            // Delay then auto switch question
                            scope.launch {
                                kotlinx.coroutines.delay(2500L)
                                feedbackMessage = null
                                loadNextQuestion()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                ) {
                    Text("提交答案", fontSize = 18.sp)
                }
            }
        }
    }
}
