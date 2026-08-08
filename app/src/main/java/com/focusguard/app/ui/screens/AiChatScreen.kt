package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
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
import dev.jeziellago.compose.markdowntext.MarkdownText
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
 *    - **流式输出**：回复逐字显示（ChatGPT 式），Markdown 渲染
 *    - **检测时 AI 的提醒**自动进入对话（来源=AI 视觉的日志 reason）
 *    - **锁机工具**：AI 输出 `__LOCK__:<分钟数>` 即触发锁机
 *    - 对话历史持久化，切页不丢
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

    // 对话历史存储：手动对话（用户消息+AI回复）持久化，切页不丢
    val chatHistory = remember { com.focusguard.app.data.ChatHistoryStore(context) }

    // 会话消息：持久化的手动对话历史 + 检测日志里 AI 给出的提醒（按时间正序）
    fun loadAiReminders(): List<ChatMsg> = logStore.getAllLogs()
        .filter { it.source == "AI_VISION" && it.reason.isNotBlank() }
        .map { ChatMsg("ai", it.reason, it.getTimeFormatted()) }
        .reversed()

    var messages by remember {
        mutableStateOf(
            chatHistory.getMessages()
                .map { ChatMsg(it.role, it.text, it.time) } + loadAiReminders()
        )
    }
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
            if (tab == 0 && messages.isNotEmpty()) {
                TextButton(onClick = {
                    chatHistory.clear()
                    messages = loadAiReminders()
                }) {
                    Text("清空", color = Color(0xFFF44336), fontSize = 12.sp)
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
                    ChatBubble(
                        msg = msg,
                        onCopy = {
                            val cm = context.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE
                            ) as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("AI 对话", msg.text)
                            )
                        }
                    )
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
                        chatHistory.addMessage("user", text, now)
                        input = ""
                        sending = true
                        scope.launch {
                            try {
                                // 占位 AI 消息（打字动画期间显示跳动点）
                                val placeholder = ChatMsg("ai", "…", now)
                                messages = messages + placeholder

                                val history = messages.filter { it !== placeholder }.map {
                                    ChatMessage(
                                        if (it.role == "user") "user" else "assistant",
                                        it.text
                                    )
                                }
                                // 系统提示词：复用设置里的提醒风格 + 注入备忘录 + 工具协议
                                val memoStore =
                                    com.focusguard.app.data.MemoStore(context)
                                                                val memoSummary = memoStore.promptSummary(limit = 10)
                                val systemText = buildString {
                                    val prompt = settings.aiCustomPrompt.trim()
                                    if (prompt.isNotBlank()) {
                                        append("【核心人设/角色指令】你必须严格使用以下人设口吻回答一切问题：")
                                        append(prompt)
                                        append("。在每一句回答中都必须保持这个性格和口吻（例如若设定为猫娘则句尾必须带'喵'，若设定为妈妈则用关心唠叨的语气）。

")
                                    }
                                    append("你是专注卫士的 AI 助手，回答简短、友好、有耐心，使用中文。")                                    append(
                                        "\n你拥有 lock_phone 工具：当用户请求锁机、自律、管住自己、限制使用手机时，" +
                                            "在你的回复末尾单独输出一行 __LOCK__:<分钟数>（例如 __LOCK__:30 表示锁机 30 分钟），" +
                                            "应用会自动执行锁机。其余情况不要输出该标记。"
                                    )
                                    append(
                                        com.focusguard.app.enforce.MemoToolExecutor
                                            .toolInstruction()
                                    )
                                    if (memoSummary.isNotBlank()) {
                                        append("\n用户当前的待办事项（询问待办时据此回答）：\n")
                                        append(memoSummary)
                                    } else {
                                        append("\n用户当前没有待办事项。")
                                    }
                                }
                                val fullHistory = buildList {
                                    add(ChatMessage("system", systemText))
                                    addAll(history)
                                }

                                // 流式输出：AI 回复逐字显示（ChatGPT 式体验）。
                                // 关键：onDelta 在 IO 线程回调，必须切回主线程再更新
                                // Compose 状态，否则 UI 不重组——表现为"卡在思考中"。
                                val reply = aiClient.streamChat(
                                    messages = fullHistory,
                                    baseUrl = settings.apiBaseUrl,
                                    apiKey = settings.apiKey,
                                    modelName = settings.modelName,
                                    apiFormat = settings.apiFormat,
                                    onDelta = { delta ->
                                        scope.launch {
                                            val last = messages.lastOrNull()
                                            if (last != null && last.role == "ai") {
                                                val newText = if (last.text == "…") {
                                                    delta
                                                } else {
                                                    last.text + delta
                                                }
                                                messages = messages.dropLast(1) +
                                                    last.copy(text = newText)
                                            }
                                        }
                                    }
                                )

                                // ── 解析 AI 的工具调用 ────────────────────
                                // 1) 锁机工具（__LOCK__:分钟数）
                                val lockResult =
                                    com.focusguard.app.enforce.LockToolExecutor
                                        .tryExecute(context, reply)
                                // 2) 备忘录工具（__MEMO_ADD__ / __MEMO_DONE__）
                                val memoResult =
                                    com.focusguard.app.enforce.MemoToolExecutor
                                        .tryExecute(context, reply)

                                // 去掉协议标记，再把执行结果作为系统提示追加到气泡
                                var displayReply = com.focusguard.app.enforce
                                    .MemoToolExecutor.stripMarkers(reply)
                                    .replace(Regex("""__LOCK__:\d+"""), "")
                                    .trim()
                                if (!memoResult.isEmpty) {
                                    displayReply += "\n\n" + memoResult.summary()
                                }
                                if (lockResult != null) {
                                    displayReply += "\n\n🔒 已执行锁机 $lockResult 分钟"
                                }

                                // 流结束后：把最后一条 AI 消息（占位/增量）替换为完整回复，
                                // 并持久化。绝不追加第二条 AI 消息（避免重复）。
                                val last = messages.lastOrNull()
                                if (last != null && last.role == "ai") {
                                    messages = messages.dropLast(1) +
                                        last.copy(text = displayReply)
                                    chatHistory.addMessage("ai", displayReply, last.time)
                                }
                            } catch (e: Exception) {
                                // 流式/网络异常：把占位消息替换为错误说明并保存——
                                // 否则占位"…"永远卡在屏幕上，且 AI 回复不会入库
                                // （这就是"对话记录没保存"的根因之一）。
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                val errMsg = "⚠️ 请求失败：${e.message ?: "未知错误"}"
                                val lastMsg = messages.lastOrNull()
                                if (lastMsg != null && lastMsg.role == "ai") {
                                    messages = messages.dropLast(1) +
                                        lastMsg.copy(text = errMsg)
                                    chatHistory.addMessage("ai", errMsg, lastMsg.time)
                                }
                            } finally {
                                sending = false
                            }
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    },
                    modifier = Modifier.size(60.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                    enabled = !sending
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

/**
 * 聊天气泡：用户右对齐紫色，AI 左对齐深色。
 * AI 消息支持 Markdown 渲染（借鉴 compose-markdown 开源实现）；
 * 占位消息（"…"）显示打字跳动动画；长按/点击可复制。
 */
@Composable
private fun ChatBubble(msg: ChatMsg, onCopy: () -> Unit) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.8f else 0.92f),
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
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (isUser) {
                        Text(
                            text = msg.text,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = Color.White
                        )
                    } else if (msg.text == "…") {
                        // 打字跳动动画（思考中）
                        var dotCount by remember { mutableIntStateOf(1) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                kotlinx.coroutines.delay(380)
                                dotCount = (dotCount % 3) + 1
                            }
                        }
                        Text(
                            text = "•".repeat(dotCount),
                            fontSize = 20.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    } else {
                        // AI 消息：Markdown 渲染（compose-markdown 开源库）
                        MarkdownText(
                            markdown = msg.text,
                            modifier = Modifier,
                            style = LocalTextStyle.current.copy(
                                color = Color.White,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = msg.time,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.3f)
                )
                if (!isUser && msg.text != "…") {
                    Spacer(Modifier.width(10.dp))
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}
