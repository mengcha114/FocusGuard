package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.ai.AiClient
import com.focusguard.app.ai.ChatMessage
import com.focusguard.app.data.LogStore
import com.focusguard.app.data.Settings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 对话消息（本地展示用）。 */
private data class ChatMsg(val role: String, val text: String, val time: String)

/**
 * AI 对话页。
 *
 * 两个 Tab：
 * 1. **AI 对话**：与当前配置的模型聊天（openai/anthropic/gemini 协议自适应）。
 *    **检测时 AI 给出的提醒会作为 AI 消息自动进入对话**（来源=AI 视觉
 *    的日志 reason），让用户能在对话里看到 AI 之前说过的话，并继续聊下去。
 * 2. **检测日志**：保留原有的完整检测日志（含 AI 诊断与崩溃日志）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings(context) }
    val aiClient = remember { AiClient() }
    val logStore = remember { LogStore(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) } // 0=AI 对话 1=检测日志

    // 会话消息：初始加载检测日志里 AI 给出的提醒（按时间正序）
    fun loadAiReminders(): List<ChatMsg> = logStore.getAllLogs()
        .filter { it.source == "AI_VISION" && it.reason.isNotBlank() }
        .map { ChatMsg("ai", it.reason, it.getTimeFormatted()) }
        .reversed()

    var messages by remember { mutableStateOf(loadAiReminders()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141416))
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (tab == 0) "AI 对话" else "检测日志",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (tab == 0) {
                    Text(
                        text = "与 AI 聊天 · 检测提醒也会出现在这里",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
            }
            // Tab 切换（TabRow：空间充足，图标与文字互不遮挡）
            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = Color(0xFFD0BCFF)
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("对话", fontSize = 13.sp)
                        }
                    }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.List,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("日志", fontSize = 13.sp)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (tab == 1) {
            // ── 检测日志（保留原有完整功能） ──────────────
            Box(modifier = Modifier.weight(1f)) {
                LogScreen()
            }
        } else {
            // ── AI 对话 ─────────────────────────────────
            // 消息列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "还没有消息\n检测到娱乐时 AI 的提醒会自动出现在这里",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.35f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                items(messages) { msg ->
                    ChatBubble(msg)
                }
                if (sending) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF8B7CF6)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "AI 思考中…",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            // 输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("问 AI 点什么…") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 3,
                    enabled = !sending
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty() || sending) return@Button
                        val now = SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date())
                        messages = messages + ChatMsg("user", text, now)
                        input = ""
                        sending = true
                        scope.launch {
                            try {
                                val history = messages.map {
                                    ChatMessage(
                                        if (it.role == "user") "user" else "assistant",
                                        it.text
                                    )
                                }
                                // 系统提示词：复用设置里的提醒风格 + 注入备忘录 + 锁机工具协议
                                val memoList = com.focusguard.app.data.MemoStore(context).getAll()
                                val systemText = buildString {
                                    append("你是专注卫士的 AI 助手，回答简短、友好、有耐心，使用中文。")
                                    if (settings.aiCustomPrompt.isNotBlank()) {
                                        append("\n用户设定的提醒风格：")
                                        append(settings.aiCustomPrompt)
                                        append("（与检测娱乐时的提醒保持一致）")
                                    }
                                    append(
                                        "\n你拥有 lock_phone 工具：当用户请求锁机、自律、管住自己、限制使用手机时，" +
                                            "在你的回复末尾单独输出一行 __LOCK__:<分钟数>（例如 __LOCK__:30 表示锁机 30 分钟），" +
                                            "应用会自动执行锁机。其余情况不要输出该标记。"
                                    )
                                    if (memoList.isNotEmpty()) {
                                        append("\n用户的备忘录（用户询问待办时可查看并提醒）：\n- ")
                                        append(memoList.joinToString("\n- "))
                                    }
                                }
                                val fullHistory = buildList {
                                    add(ChatMessage("system", systemText))
                                    addAll(history)
                                }
                                val reply = aiClient.chat(
                                    messages = fullHistory,
                                    baseUrl = settings.apiBaseUrl,
                                    apiKey = settings.apiKey,
                                    modelName = settings.modelName,
                                    apiFormat = settings.apiFormat
                                )
                                // 解析 AI 的锁机工具调用（__LOCK__:分钟数）
                                val lockResult = com.focusguard.app.enforce.LockToolExecutor
                                    .tryExecute(context, reply)
                                val displayReply = if (lockResult != null) {
                                    reply.replace(Regex("""__LOCK__:\d+"""), "")
                                        .trim() + "\n\n🔒 已执行锁机 $lockResult 分钟"
                                } else {
                                    reply
                                }
                                messages = messages + ChatMsg(
                                    "ai", displayReply,
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                )
                            } finally {
                                sending = false
                            }
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                    enabled = !sending
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/** 聊天气泡：用户右对齐紫色，AI 左对齐深色。 */
@Composable
private fun ChatBubble(msg: ChatMsg) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.8f else 0.9f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) Color(0xFF4F378B) else Color(0xFF262031)
            ) {
                Text(
                    text = msg.text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = msg.time,
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.3f)
            )
        }
    }
}


