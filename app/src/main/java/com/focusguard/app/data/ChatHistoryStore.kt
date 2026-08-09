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

    /**
     * 更新最后一条 AI 消息的文本（流式输出实时持久化用）。
     *
     * 流式回复每收到一段增量就调用一次——即使页面销毁、协程被取消，
     * 已收到的内容也已落盘，用户重新进入能看到（部分）回复。
     */
    fun updateLastMessage(text: String) {
        val list = getMessages().toMutableList()
        if (list.isEmpty()) return
        val lastIndex = list.indexOfLast { it.role == "ai" }
        if (lastIndex < 0) return
        list[lastIndex] = list[lastIndex].copy(text = text)
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
        // commit() 同步落盘：聊天记录数据量小（≤100 条），
        // 防止进程被杀 / Activity 立即销毁时 apply() 异步写丢失
        prefs.edit().putString(KEY_MESSAGES, arr.toString()).commit()
    }
}
