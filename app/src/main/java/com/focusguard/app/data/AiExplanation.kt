package com.focusguard.app.data

import android.content.Context
import com.focusguard.app.ai.AiClient
import com.focusguard.app.ai.ChatMessage
import com.focusguard.app.token.TokenBudget

/**
 * 「无解析时用 AI 生成解析」。
 *
 * 题库里 CMMLU 那批题只有答案、没有解析（用户拍板收下，并要求这时可调用已配置的 AI 生成解析）。
 * 规则：
 * - 只在题目解析为空时才调用（有解析的题永不消耗 token）；
 * - 结果按题干哈希缓存，同一道题只生成一次；
 * - 调用计入现有每日额度（TokenBudget），未配置密钥 / 额度用完 / 网络失败一律返回 null，
 *   界面只显示正确答案，不出现空白解析框。
 */
object AiExplanation {

    private const val PREFS = "focus_guard_ai_explain"
    private const val MAX_CACHE = 300

    private fun key(question: String) = "q" + question.hashCode().toString(16)

    fun cached(context: Context, question: String): String? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(question), null)
    }.getOrNull()

    private fun save(context: Context, question: String, text: String) = runCatching {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.all.size >= MAX_CACHE) {
            p.edit().apply { p.all.keys.take(p.all.size / 2).forEach { remove(it) } }.apply()
        }
        p.edit().putString(key(question), text).apply()
    }

    /** 取解析：先查缓存，没有再调 AI。失败/超额返回 null。 */
    suspend fun get(
        context: Context,
        question: String,
        options: List<String>,
        answer: String,
        subject: String
    ): String? {
        cached(context, question)?.let { return it }
        val settings = Settings(context)
        if (settings.apiKey.isBlank()) return null
        val budget = TokenBudget(context)
        if (!budget.canCallAi()) return null
        val prompt = buildString {
            append("请只针对这一道题给出简明解析：为什么正确答案是 ")
            append(answer)
            append("、其它选项错在哪里。不超过 200 字，只给解析正文，不要寒暄、不要标题、不要 markdown。\n")
            append("科目：").append(subject).append('\n')
            append("题目：").append(question).append('\n')
            if (options.isNotEmpty()) append("选项：").append(options.joinToString("；"))
        }
        val text = runCatching {
            AiClient().chat(
                messages = listOf(ChatMessage("user", prompt)),
                baseUrl = settings.apiBaseUrl,
                apiKey = settings.apiKey,
                modelName = settings.modelName,
                apiFormat = settings.apiFormat
            )
        }.getOrNull()?.trim()?.take(400)
        if (text.isNullOrBlank()) return null
        runCatching { budget.recordCall() }
        save(context, question, text)
        return text
    }
}
