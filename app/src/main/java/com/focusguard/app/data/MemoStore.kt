package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 备忘录存储。
 *
 * 两个用途：
 * 1. 首页显眼位置展示未完成事项
 * 2. AI 检测到娱乐时，把未完成事项注入提示词——
 *    让模型在提醒语里引用（如"主人别玩了喵！你还有 xx 没做呢，快去做喵！"）
 */
class MemoStore(context: Context) {

    companion object {
        private const val PREFS = "focus_guard_memos"
        private const val KEY_MEMOS = "memos"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部备忘录条目。 */
    fun getAll(): List<String> {
        val raw = prefs.getString(KEY_MEMOS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 添加一条。 */
    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val list = getAll().toMutableList()
        list.add(t)
        save(list)
    }

    /** 删除指定位置的条目。 */
    fun removeAt(index: Int) {
        val list = getAll().toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(list)
        }
    }

    /** 清空。 */
    fun clear() {
        save(emptyList())
    }

    private fun save(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_MEMOS, arr.toString()).apply()
    }
}
