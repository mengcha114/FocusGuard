package com.focusguard.app.data

import android.content.Context
import com.focusguard.app.ai.AiClient
import com.focusguard.app.ai.ChatMessage
import com.focusguard.app.token.TokenBudget

/**
 * 出题时用「用户已配置的 AI」再校验一次难度/学段（用户要求）：
 * 通过 → 出给用户；不通过 → 调用方重筛一道。
 *
 * 安全底线（绝不能卡住答题）：
 * - 未配置密钥 / 每日额度用完 / 超时 3 秒 / 网络或返回异常 ⇒ **一律放行**（fail-open）；
 * - 结果按题干哈希缓存（OK/NO 都缓存）⇒ 同一道题只校验一次；
 * - 调用计入 `TokenBudget`，与检测、AI 解析共用同一份额度。
 */
object AiQuestionCheck {

    private const val PREFS = "focus_guard_ai_qcheck"
    private const val MAX_CACHE = 500

    private fun key(q: String) = "q" + q.hashCode().toString(16)

    /** true=通过，false=不通过，null=没校验过。 */
    fun cached(context: Context, question: String): Boolean? = runCatching {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(key(question))) null else p.getBoolean(key(question), true)
    }.getOrNull()

    private fun save(context: Context, question: String, ok: Boolean) = runCatching {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.all.size >= MAX_CACHE) {
            p.edit().apply { p.all.keys.take(p.all.size / 2).forEach { remove(it) } }.apply()
        }
        p.edit().putBoolean(key(question), ok).apply()
    }

    /** 校验这道题是否符合当前学段/学期、是否课内且偏难。任何不确定性都返回 true。 */
    suspend fun check(context: Context, question: String, options: List<String>): Boolean {
        cached(context, question)?.let { return it }
        val settings = Settings(context)
        if (settings.apiKey.isBlank()) return true
        val budget = TokenBudget(context)
        if (!budget.canCallAi()) return true
        val grade = GradeStore(context).effective.label
        val term = GradeStore(context).term.label
        val prompt = buildString {
            append("你是中学老师。判断下面这道题是否适合作为【")
            append(grade).append(" · ").append(term)
            append("】学生的限时答题题目。要求：①符合该学段与学期的课内知识（不要课外杂学）；")
            append("②偏难（不要纯概念、过简单的基础题）；③必须能靠选项或短答案作答。")
            append("只回答 OK 或 NO，不要输出其它任何字符。\n")
            append("题目：").append(question).append('\n')
            if (options.isNotEmpty()) append("选项：").append(options.joinToString("；"))
        }
        val reply = kotlinx.coroutines.withTimeoutOrNull(3_000L) {
            AiClient().chat(
                messages = listOf(ChatMessage("user", prompt)),
                baseUrl = settings.apiBaseUrl,
                apiKey = settings.apiKey,
                modelName = settings.modelName,
                apiFormat = settings.apiFormat
            )
        } ?: return true
        // AiClient 失败时是返回错误字符串（不抛异常）⇒ 必须判字符串
        if (reply.isBlank() || reply.contains("请求失败") || reply.contains("未返回内容")) return true
        val verdict = reply.trim().uppercase()
        if (!verdict.startsWith("OK") && !verdict.startsWith("NO")) return true
        val ok = verdict.startsWith("OK")
        runCatching { budget.recordCall() }
        save(context, question, ok)
        return ok
    }
}
