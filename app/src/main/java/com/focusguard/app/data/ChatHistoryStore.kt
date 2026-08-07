package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 对话历史存储。
 *
 * 只持久化**手动对话**（用户消息 + AI 回复）；
 * 检测时 AI 给出的提醒不存这里（它们从检测日志动态生成，避免重复）。
 * 限制最多保留 100 条，防止无限增长。
 */
class ChatHistoryStore(context: Context) {

    companion object {
        private const val PREFS = "focus_guard_chat_history"
        private const val KEY_MESSAGES = "messages"
        private const val MAX_MESSAGES = 100
    }

    /** 一条对话消息（与 AiChatScreen 展示格式一致）。 */
    data class ChatEntry(val role: String, val text: String, val time: String)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部手动对话历史（时间正序）。 */
    fun getMessages(): List<ChatEntry> {
        val raw = prefs.getString(KEY_MESSAGES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                ChatEntry(
                    role = obj.optString("role", "user"),
                    text = obj.optString("text", ""),
                    time = obj.optString("time", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 追加一条消息（自动裁剪到上限）。 */
    fun addMessage(role: String, text: String, time: String) {
        val list = getMessages().toMutableList()
        list.add(ChatEntry(role, text, time))
        while (list.size > MAX_MESSAGES) list.removeAt(0)
        save(list)
    }

    /** 清空对话历史。 */
    fun clear() {
        save(emptyList())
    }

    private fun save(list: List<ChatEntry>) {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("role", entry.role)
                    .put("text", entry.text)
                    .put("time", entry.time)
            )
        }
        prefs.edit().putString(KEY_MESSAGES, arr.toString()).apply()
    }
}
