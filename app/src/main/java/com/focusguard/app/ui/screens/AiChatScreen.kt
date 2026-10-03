package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
private data class ChatMsg(
    val role: String,
    val text: String,
    val time: String,
    val thinking: String = "",
    /** 是否来自检测日志的 AI 提醒（不参与发送给模型的历史）。 */
    val fromLog: Boolean = false
)

/** AI 工具执行结果：在 IO 线程算好，回主线程展示。 */
private data class ToolOutcome(
    val notes: List<String>,
    val unfreeze: List<Pair<String, String>>,
    val unlock: List<Pair<String, String>>,
    val limit: List<Triple<String, String, Int>>
)

/** 需要答题才能执行的放宽类操作（解冻 / 解除应用封锁 / 放大每日上限）。 */
private data class PendingVerify(
    val description: String,
    val unfreeze: List<Pair<String, String>>,
    val unlock: List<Pair<String, String>>,
    val limit: List<Triple<String, String, Int>>
)

/**
 * AI 对话页。
 *
 * 两个 Tab：
 * 1. **AI 对话**：与当前配置的模型聊天（openai/anthropic/gemini 协议自适应）。
 *    - **流式输出**：回复逐字显示（ChatGPT 式），Markdown 渲染
 *    - **检测时 AI 的提醒**自动进入对话（来源=AI 视觉的日志 reason）
 *    - **锁机工具**：AI 输出 `__LOCK__:<分钟数>` 即触发锁机
 *    - **冻结/解冻工具**：AI 输出 `__FREEZE__:<应用名>` / `__UNFREEZE__:<应用名>`；
 *      解冻由**应用本身弹出答题验证**（本地题库，错 2 次冷却 5 分钟），模型不得自己出题
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

    // 会话消息：持久化的手动对话历史 + 检测日志里 AI 给出的提醒（按时间正序）。
    // 检测提醒只回显最近 10 条——历史检测最多 500 条，全量塞进来会把手动
    // 对话淹没（"退出重进后聊天记录被历史 AI 检测顶掉"的根因）。
    // 注意：这里**不再**把检测日志的 AI 提醒拼进对话列表。
    // 历史检测最多 500 条，拼进来会把手动对话顶到上面（用户报告「返回再进对话内容被历史日志覆盖」），
    // 而且要在组合线程读日志文件。检测记录看「检测日志」Tab 即可。
    var messages by remember {
        mutableStateOf(
            chatHistory.getMessages().map { ChatMsg(it.role, it.text, it.time, it.thinking) }
        )
    }

    // 放宽类操作（解冻 / 解除封锁 / 放大上限）：AI 请求后必须答题验证，通过才执行
    var pendingVerify by remember { mutableStateOf<PendingVerify?>(null) }

    /** 把一条系统说明追加到最后一条 AI 气泡（工具执行结果用）。 */
    fun appendSystemNote(note: String) {
        val last = messages.lastOrNull() ?: return
        if (last.role != "ai") return
        val updated = last.copy(text = last.text + "\n\n" + note)
        messages = messages.dropLast(1) + updated
        chatHistory.updateLastMessage(updated.text, updated.thinking)
    }

    // 进入页面时清理：上次对话中途退出可能留下占位"…"（流式被取消，
    // 占位已入库但未收到任何增量）→ 标记为中断，避免看到永远的"…"
    LaunchedEffect(Unit) {
        val saved = chatHistory.getMessages()
        if (saved.isNotEmpty()) {
            val last = saved.last()
            if (last.role == "ai" && last.text == "…") {
                chatHistory.updateLastMessage("（回复未完成，可重发此问题）")
                messages = messages.map {
                    if (it.role == "ai" && it.text == "…") {
                        it.copy(text = "（回复未完成，可重发此问题）")
                    } else it
                }
            }
        }
    }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 消息数变化（含退出重进重新加载）时自动滚到最新一条，
    // 让用户第一眼看到的是自己的最新对话而不是历史检测提醒
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (tab == 0) {
                    Text(
                        text = "与 AI 聊天 · 检测提醒也会出现在这里",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
            }
            if (tab == 0 && messages.isNotEmpty()) {
                TextButton(onClick = {
                    chatHistory.clear()
                    messages = emptyList()
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            // Tab 切换（TabRow：空间充足，图标与文字互不遮挡）
            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
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
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
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
                    .padding(bottom = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("问 AI 点什么…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
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
                                val placeholder = ChatMsg("ai", "…", now)
                                messages = messages + placeholder
                                chatHistory.addMessage("ai", "…", now)

                                val history = chatHistory.getMessages()
                                    .filter { it.text != "…" }
                                    .map {
                                        ChatMessage(
                                            if (it.role == "user") "user" else "assistant",
                                            it.text
                                        )
                                    }
                                // 系统提示词：复用设置里的提醒风格 + 注入备忘录 + 工具协议
                                val memoStore =
                                    com.focusguard.app.data.MemoStore(context)
                                                                val memoSummary = memoStore.promptSummary(limit = 10)
                                val completedSummary = memoStore.completionSummary(limit = 10)
                                val systemText = buildString {
                                    val prompt = settings.aiCustomPrompt.trim()
                                    if (prompt.isNotBlank()) {
                                        append("【核心人设/角色指令】你必须严格使用以下人设口吻回答一切问题：")
                                        append(prompt)
                                        append("。在每一句回答中都必须保持这个性格和口吻（例如若设定为猫娘则句尾必须带'喵'，若设定为妈妈则用关心唠叨的语气）。\n\n")
                                    }
                                    append("你是专注卫士的 AI 助手，回答简短、友好、有耐心，使用中文。")
                                    append(
                                        "\n【工具使用规则（必须严格遵守，违反会打扰用户）】\n" +
                                            "1. 只有用户**明确要求**时才输出工具标记；一次最多输出一个，且单独占一行。\n" +
                                            "2. 用户没要求的事绝不要做，也不要"顺手"多做一步；不要重复执行同一操作。\n" +
                                            "3. 你**没有执行能力**：标记由应用执行。不要说"已完成/已锁定/已解冻"，" +
                                            "只能说"已请求执行"，等应用返回结果。\n" +
                                            "4. 用户的要求不在下面的工具列表里（例如卸载应用、破解、绕过锁机）→ " +
                                            "直接说做不到，**绝不编造操作或结果**。\n" +
                                            "5. 不确定要操作哪个应用时先问清楚，不要猜。\n" +
                                            "6. 不要输出 JSON / 函数调用格式，只输出文本标记。\n"
                                    )
                                    append(
                                        "\n你拥有 lock_phone 工具：当用户请求锁机、自律、管住自己、限制使用手机时，" +
                                            "在你的回复末尾单独输出一行 __LOCK__:<分钟数>（例如 __LOCK__:30 表示锁机 30 分钟），" +
                                            "应用会自动执行锁机。其余情况不要输出该标记。" +
                                            "注意：只能输出 __LOCK__: 这样的文本标记，禁止输出 JSON 或函数调用格式" +
                                            "（例如 {\"name\":\"lock_phone\"...} 是错误的，写了应用也无法识别）。"
                                    )
                                    append(
                                        com.focusguard.app.enforce.MemoToolExecutor
                                            .toolInstruction()
                                    )
                                    // 冻结 / 解冻应用工具（解冻由应用弹窗答题）
                                    append(
                                        com.focusguard.app.enforce.AppFreezeToolExecutor
                                            .toolInstruction(context)
                                    )
                                    append(
                                        "\n注意：解冻的答题验证由应用自己弹出（本地题库），" +
                                            "**你不要自己出题、也不要询问用户任何算术或常识题**，" +
                                            "更不要因为用户回答了你写的问题就认为已验证通过；" +
                                            "你只需要输出 __UNFREEZE__ 标记，并告知用户「应用会弹出答题验证」。"
                                    )
                                    // 应用管控工具（锁住 / 解除封锁 / 每日上限）
                                    append(
                                        com.focusguard.app.enforce.AppLockToolExecutor
                                            .toolInstruction()
                                    )
                                    // 当前状态：锁机 / 冻结 / 锁住 / 上限，便于回答「我现在被锁了什么」
                                    append(
                                        com.focusguard.app.enforce.AppLockToolExecutor
                                            .statusSummary(context)
                                    )
                                    if (memoSummary.isNotBlank()) {
                                        append("\n用户当前的待办事项（询问待办时据此回答）：\n")
                                        append(memoSummary)
                                    } else {
                                        append("\n用户当前没有待办事项。")
                                    }
                                    if (completedSummary.isNotBlank()) {
                                        append("\n用户近期已完成的事项（用户问完成情况/回顾时据此回答）：\n")
                                        append(completedSummary)
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
                                                // 流式实时持久化：每收到一段增量就同步落盘。
                                                // 即使页面销毁、协程被取消，已流式收到的内容
                                                // 也已在存储中——"聊天记录退出后不保存"的根治。
                                                chatHistory.updateLastMessage(newText)
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
                                // 反幻觉：免费模型经常"一本正经地执行用户没要求的操作"。
                                // 只有用户最近几条消息里**提到过这个应用**、或**明确表达过这类动作**，
                                // 才真正执行；否则忽略并在气泡里说明，避免乱锁应用。
                                val recentUserText = history.takeLast(6)
                                    .filter { it.role == "user" }
                                    .joinToString(" ") { it.content }
                                    .lowercase()
                                val actionAsked = listOf(
                                    "锁", "冻结", "解冻", "解除", "限制", "停用", "封", "答题"
                                ).any { recentUserText.contains(it) }

                                // 3) 冻结 / 解冻应用工具（解冻只登记，等答题通过再执行）
                                // 4) 应用管控工具（锁住 / 解除封锁 / 每日上限）
                                // 解析要枚举已安装应用、执行要走 Dhizuku/Shizuku Binder 与 startActivity，
                                // 统一放 IO 线程，避免主线程卡住（原实现在主线程，会 ANR）
                                val tools = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val freezeNotes = mutableListOf<String>()
                                val unfreezeTargets = mutableListOf<Pair<String, String>>()
                                val unlockTargets = mutableListOf<Pair<String, String>>()
                                val limitTargets = mutableListOf<Triple<String, String, Int>>()
                                com.focusguard.app.enforce.AppFreezeToolExecutor
                                    .parse(reply)
                                    .forEach { req ->
                                        val target = com.focusguard.app.enforce
                                            .AppFreezeToolExecutor.resolve(context, req.query)
                                        // 反幻觉闸门：既没提到这个应用、也没表达这类动作 → 忽略
                                        val mentioned = target != null &&
                                            (recentUserText.contains(req.query.lowercase()) ||
                                                recentUserText.contains(target.second.lowercase()))
                                        when {
                                            target != null && !mentioned && !actionAsked -> {
                                                freezeNotes += "已忽略一个你没要求的操作「${target.second}」" +
                                                    "（模型自作主张）"
                                            }
                                            target == null ->
                                                freezeNotes += "没找到应用「${req.query}」"
                                            req.unfreeze -> unfreezeTargets += target
                                            com.focusguard.app.enforce.AppFreezeToolExecutor
                                                .freeze(context, target.first) != null ->
                                                freezeNotes += "已冻住「${target.second}」（解冻需要答题）"
                                            else ->
                                                freezeNotes += "冻结「${target.second}」失败：需要先授权 Dhizuku 或 Shizuku"
                                        }
                                    }
                                // 4) 应用管控工具：锁住（立即生效）/ 解除封锁（答题）/ 每日上限（调小立即，调大答题）
                                com.focusguard.app.enforce.AppLockToolExecutor
                                    .parse(reply)
                                    .forEach { req ->
                                        val target = com.focusguard.app.enforce
                                            .AppFreezeToolExecutor.resolve(context, req.query)
                                        val askedKind = listOf(
                                            "锁", "冻结", "解冻", "解除", "限制", "停用", "封", "答题"
                                        ).any { recentUserText.contains(it) }
                                        val mentioned2 = target != null &&
                                            (recentUserText.contains(req.query.lowercase()) ||
                                                recentUserText.contains(target.second.lowercase()))
                                        if (target != null && !mentioned2 && !askedKind) {
                                            freezeNotes += "已忽略一个你没要求的操作「${target.second}」" +
                                                "（模型自作主张）"
                                        } else if (target == null) {
                                            freezeNotes += "没找到应用「${req.query}」"
                                        } else when (req.kind) {
                                            "lock" -> {
                                                val r = com.focusguard.app.enforce
                                                    .AppLockToolExecutor
                                                    .lockApp(context, target.first, req.minutes)
                                                freezeNotes += if (r.shownNow) {
                                                    "已锁住「${r.label}」${r.minutes} 分钟（打开即被挡住）"
                                                } else {
                                                    "已登记封锁「${r.label}」${r.minutes} 分钟" +
                                                        "（你当前不在该应用，打开时会被挡住）"
                                                }
                                            }
                                            "unlock" -> unlockTargets += target
                                            "limit" -> {
                                                val mins = req.minutes ?: 0
                                                if (mins <= 0) {
                                                    freezeNotes += "「${target.second}」的上限分钟数没给对"
                                                } else if (com.focusguard.app.enforce.AppLockToolExecutor
                                                        .isLimitTightening(context, target.first, mins)
                                                ) {
                                                    com.focusguard.app.enforce.AppLockToolExecutor
                                                        .applyLimit(context, target.first, mins)
                                                    freezeNotes += "已把「${target.second}」每日上限设为 $mins 分钟"
                                                } else {
                                                    limitTargets += Triple(target.first, target.second, mins)
                                                }
                                            }
                                        }
                                    }
                                ToolOutcome(freezeNotes, unfreezeTargets, unlockTargets, limitTargets)
                                }
                                val freezeNotes = tools.notes
                                val unfreezeTargets = tools.unfreeze
                                val unlockTargets = tools.unlock
                                val limitTargets = tools.limit

                                // 去掉协议标记，再把执行结果作为系统提示追加到气泡
                                var displayReply = com.focusguard.app.enforce
                                    .MemoToolExecutor
                                    .stripMarkers(
                                        com.focusguard.app.ai.AiClient.stripThinking(reply)
                                    )
                                    // 冻结/解冻、锁住/上限标记也从气泡里隐藏（结果由下面 notes 说明）
                                    .let {
                                        com.focusguard.app.enforce.AppFreezeToolExecutor
                                            .stripMarkers(it)
                                    }
                                    .let {
                                        com.focusguard.app.enforce.AppLockToolExecutor
                                            .stripMarkers(it)
                                    }
                                    .replace(Regex("""__LOCK__:\d+"""), "")
                                    // 过滤模型输出的 function calling JSON（lock_phone 行）
                                    .replace(
                                        Regex("""\{\s*"name"\s*:\s*"lock_phone"[^\n]*"""),
                                        ""
                                    )
                                    .trim()
                                if (!memoResult.isEmpty) {
                                    displayReply += "\n\n" + memoResult.summary()
                                }
                                if (lockResult != null) {
                                    displayReply += "\n\n🔒 已执行锁机 $lockResult 分钟"
                                }
                                if (freezeNotes.isNotEmpty()) {
                                    displayReply += "\n\n❄️ " + freezeNotes.joinToString("；")
                                }
                                // 诊断：模型只是嘴上说「已解冻/已解除」却没输出标记时，明确提示，
                                // 免得用户以为操作成功了（用户报告「AI 封锁应用后没弹出答题」）。
                                // 注意 freezeNotes 是 val（IO 块里算好的不可变列表），这里单独追加。
                                if (unfreezeTargets.isEmpty() && unlockTargets.isEmpty() &&
                                    limitTargets.isEmpty() &&
                                    (reply.contains("解冻") || reply.contains("解除封锁")) &&
                                    !reply.contains("__")
                                ) {
                                    displayReply += "\n\n⚠️ 没有识别到解冻指令（模型未输出标记）：" +
                                        "请再说一次「解冻 XX」；也可以到设置页用「强制解冻」"
                                }
                                if (unfreezeTargets.isNotEmpty() || unlockTargets.isNotEmpty() ||
                                    limitTargets.isNotEmpty()
                                ) {
                                    val parts = mutableListOf<String>()
                                    if (unfreezeTargets.isNotEmpty()) {
                                        parts += "解冻「" + unfreezeTargets.joinToString("、") { it.second } + "」"
                                    }
                                    if (unlockTargets.isNotEmpty()) {
                                        parts += "解除「" + unlockTargets.joinToString("、") { it.second } + "」的应用封锁"
                                    }
                                    if (limitTargets.isNotEmpty()) {
                                        parts += "把「" + limitTargets.joinToString("、") { it.second } + "」的每日上限放宽"
                                    }
                                    displayReply += "\n\n🔐 " + parts.joinToString("；") + " 需要先答题验证"
                                    pendingVerify = PendingVerify(
                                        description = "你正在" + parts.joinToString("；") + "，请先答对一道题。",
                                        unfreeze = unfreezeTargets.toList(),
                                        unlock = unlockTargets.toList(),
                                        limit = limitTargets.toList()
                                    )
                                }

                                // 流结束后：把最后一条 AI 消息（占位/增量）替换为完整回复，
                                // 并持久化（更新占位所在的那条记录，绝不追加第二条）。
                                val last = messages.lastOrNull()
                                if (last != null && last.role == "ai") {
                                    val thinking =
                                        com.focusguard.app.ai.AiClient.extractThinking(reply)
                                    messages = messages.dropLast(1) +
                                        last.copy(text = displayReply, thinking = thinking)
                                    chatHistory.updateLastMessage(displayReply, thinking)
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
                                    chatHistory.updateLastMessage(errMsg)
                                }
                            } finally {
                                sending = false
                            }
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    },
                    modifier = Modifier.size(60.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
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

    // 放宽类操作验证：解冻 / 解除封锁 / 放大上限都必须先答题（与「放宽限制」同一套规则）
    pendingVerify?.let { pending ->
        com.focusguard.app.ui.components.VerifyDialog(
            title = "放宽限制需先答题",
            description = pending.description +
                "（本题由应用本地题库按你的年级「" +
                com.focusguard.app.data.GradeStore(context).effective.label +
                "」出题，与 AI 无关；答错立即换题，错 2 次要等 5 分钟）",
            confirmText = "验证并执行",
            onPassed = {
                pendingVerify = null
                val app = context.applicationContext
                Thread {
                    val notes = mutableListOf<String>()
                    pending.unfreeze.forEach { (pkg, label) ->
                        notes += if (com.focusguard.app.enforce.AppFreezeToolExecutor
                                .unfreeze(app, setOf(pkg))
                        ) "🔓 已解冻「$label」" else "解冻「$label」未完成：请确认 Dhizuku / Shizuku 可用"
                    }
                    pending.unlock.forEach { (pkg, label) ->
                        com.focusguard.app.enforce.AppLockToolExecutor.unlockApp(app, pkg)
                        notes += "🔓 已解除「$label」的应用封锁"
                    }
                    pending.limit.forEach { (pkg, label, mins) ->
                        com.focusguard.app.enforce.AppLockToolExecutor.applyLimit(app, pkg, mins)
                        notes += "已把「$label」的每日上限放宽到 $mins 分钟"
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        appendSystemNote(notes.joinToString("\n"))
                    }
                }.start()
            },
            onCancel = {
                pendingVerify = null
                appendSystemNote("已取消本次操作（未放宽任何限制）")
            }
        )
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
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.92f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                color = if (isUser) scheme.primary else scheme.surfaceVariant,
                contentColor = if (isUser) scheme.onPrimary else scheme.onSurface,
                border = if (isUser) null else androidx.compose.foundation.BorderStroke(1.dp, scheme.outline.copy(alpha = 0.35f))
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (isUser) {
                        Text(
                            text = msg.text,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = scheme.onPrimary
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
                            color = scheme.onSurface.copy(alpha = 0.8f)
                        )
                    } else {
                        Column {
                            // 思考过程：默认折叠，点击展开/收起
                            if (msg.thinking.isNotBlank()) {
                                var expanded by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { expanded = !expanded }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (expanded) {
                                            Icons.Default.ExpandLess
                                        } else {
                                            Icons.Default.ExpandMore
                                        },
                                        contentDescription = null,
                                        tint = scheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (expanded) "收起思考" else "💭 查看思考过程",
                                        fontSize = 11.sp,
                                        color = scheme.onSurface.copy(alpha = 0.75f)
                                    )
                                }
                                if (expanded) {
                                    Text(
                                        text = msg.thinking,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        color = scheme.onSurface.copy(alpha = 0.75f),
                                        modifier = Modifier
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            // AI 消息：Markdown 渲染（compose-markdown 开源库）
                            MarkdownText(
                                markdown = msg.text,
                                modifier = Modifier,
                                style = LocalTextStyle.current.copy(
                                    color = scheme.onSurface,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp
                                )
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = msg.time,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                )
                if (msg.fromLog) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "检测提醒",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
                if (!isUser && msg.text != "…") {
                    Spacer(Modifier.width(10.dp))
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}
