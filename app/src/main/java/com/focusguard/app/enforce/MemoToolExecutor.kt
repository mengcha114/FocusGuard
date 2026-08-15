package com.focusguard.app.enforce

import android.content.Context
import android.util.Log
import com.focusguard.app.data.MemoStore

/**
 * AI 对话里的「备忘录工具」执行器。
 *
 * ## 为什么用文本协议而不是 function calling
 * 用户的网关（agnes 等）对 tools 支持不一致——实测模型会把
 * `classify_screen(c=..)` 当成**普通文本**输出而不是 tool_calls。
 * 因此这里沿用与 [LockToolExecutor] 相同的做法：在 system 提示词里
 * 声明标记格式，客户端从回复文本里解析并执行，兼容所有网关。
 *
 * ## 协议
 * ```
 * __MEMO_ADD__:事项内容|优先级|截止
 * __MEMO_DONE__:关键词
 * ```
 * - 优先级、截止可省略：`__MEMO_ADD__:写数学作业`
 * - 优先级：`普通` / `重要` / `紧急`（也接受 0/1/2）
 * - 截止：`30m`（30 分钟后）、`2h`（2 小时后）、`3d`（3 天后）、`今天`、`明天`
 * - 一条回复里可以有多个标记，按出现顺序执行
 */
object MemoToolExecutor {

    private const val TAG = "MemoToolExecutor"

    /** 执行结果，供界面回显给用户。 */
    data class Result(
        val added: List<String> = emptyList(),
        val completed: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = added.isEmpty() && completed.isEmpty()

        /** 给用户看的一行小结（附加到 AI 回复末尾）。 */
        fun summary(): String = buildString {
            if (added.isNotEmpty()) {
                append("📝 已记入备忘录：")
                append(added.joinToString("、"))
            }
            if (completed.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("✅ 已勾选完成：")
                append(completed.joinToString("、"))
            }
        }
    }

    private val addPattern = Regex("""__MEMO_ADD__\s*[:：]\s*([^\n]+)""")
    private val donePattern = Regex("""__MEMO_DONE__\s*[:：]\s*([^\n]+)""")

    /** 从 AI 回复里解析并执行备忘录操作。 */
    fun tryExecute(context: Context, reply: String): Result {
        val store = MemoStore(context)
        val added = mutableListOf<String>()
        val completed = mutableListOf<String>()

        addPattern.findAll(reply).forEach { match ->
            try {
                val parts = match.groupValues[1].split('|').map { it.trim() }
                val text = parts.getOrNull(0).orEmpty()
                if (text.isBlank()) return@forEach
                val priority = parsePriority(parts.getOrNull(1))
                val dueAt = MemoStore.parseDueText(parts.getOrNull(2))
                store.add(text = text, priority = priority, dueAt = dueAt, fromAi = true)
                    ?.let { added.add(it.text) }
            } catch (e: Exception) {
                Log.w(TAG, "解析 __MEMO_ADD__ 失败：${e.message}")
            }
        }

        donePattern.findAll(reply).forEach { match ->
            try {
                val keyword = match.groupValues[1].trim()
                store.markDoneByKeyword(keyword)?.let { completed.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "解析 __MEMO_DONE__ 失败：${e.message}")
            }
        }

        if (added.isNotEmpty() || completed.isNotEmpty()) {
            Log.d(TAG, "备忘录工具执行：新增 ${added.size} 条，完成 ${completed.size} 条")
            // 数据已变：重排到期提醒（新增带截止的待办 / 完成项不再提醒）
            try {
                com.focusguard.app.service.MemoReminder.sync(context)
            } catch (e: Exception) {
                Log.w(TAG, "提醒重排失败：${e.message}")
            }
        }
        return Result(added, completed)
    }

    /**
     * 把回复里的工具标记整行删掉（不让协议文本出现在气泡里）。
     * 标记删除后把连续 3 个以上的空行压缩成 2 个，避免残留空白。
     */
    fun stripMarkers(reply: String): String = reply
        .replace(addPattern, "")
        .replace(donePattern, "")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    private fun parsePriority(raw: String?): Int {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0
        s.toIntOrNull()?.let { return it.coerceIn(0, 2) }
        return when {
            s.contains("紧急") || s.contains("急") -> 2
            s.contains("重要") -> 1
            else -> 0
        }
    }

    /**
     * 供 system 提示词使用的工具说明。
     * 与锁机工具说明拼在一起交给模型。
     */
    fun toolInstruction(): String =
        "\n你还拥有备忘录工具：\n" +
            "· 当用户提到要做的事、需要记住的事、或让你帮记待办时，" +
            "在回复末尾单独输出一行 __MEMO_ADD__:事项内容|优先级|截止" +
            "（优先级可填 普通/重要/紧急，截止可填 30m、2h、3d、今天、明天，均可省略）。\n" +
            "· 当用户说某件事做完了，输出一行 __MEMO_DONE__:关键词（关键词用于匹配已有事项）。\n" +
            "· 一次可输出多行标记。不需要操作备忘录时不要输出这些标记。" +
            "标记会被应用自动执行并从对话里隐藏，所以你仍要用自然语言告诉用户你记下了什么。"
}
