package com.focusguard.app.detection

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 应用分类的持久化存储。
 *
 * 分为两层：
 * - **用户覆盖**：用户在应用管控页手动指定的分类，优先级最高，永不被自动逻辑改写
 * - **自动学习**：系统元数据推断或 AI 一次性归类的结果，永久保存
 *
 * 这一层是让"自动识别"真正省 token 的关键：
 * 一个应用只需要被识别一次，此后每次遇到它都是零成本查表。
 */
class AppCategoryStore(context: Context) {

    companion object {
        private const val TAG = "AppCategoryStore"
        private const val FILE_USER = "app_category_user.json"
        private const val FILE_LEARNED = "app_category_learned.json"
    }

    private val userFile = File(context.filesDir, FILE_USER)
    private val learnedFile = File(context.filesDir, FILE_LEARNED)
    private val lock = Any()

    private val userOverrides: MutableMap<String, AppCategory> =
        load(userFile).toMutableMap()
    private val learned: MutableMap<String, AppCategory> =
        load(learnedFile).toMutableMap()

    // ── 用户手动覆盖 ────────────────────────────────────

    fun getUserOverride(packageName: String): AppCategory? =
        synchronized(lock) { userOverrides[packageName] }

    fun setUserOverride(packageName: String, category: AppCategory) = synchronized(lock) {
        userOverrides[packageName] = category
        save(userFile, userOverrides)
    }

    fun clearUserOverride(packageName: String) = synchronized(lock) {
        if (userOverrides.remove(packageName) != null) {
            save(userFile, userOverrides)
        }
    }

    fun allUserOverrides(): Map<String, AppCategory> =
        synchronized(lock) { userOverrides.toMap() }

    // ── 自动学习结果 ────────────────────────────────────

    fun getLearned(packageName: String): AppCategory? =
        synchronized(lock) { learned[packageName] }

    fun putLearned(packageName: String, category: AppCategory) = synchronized(lock) {
        learned[packageName] = category
        save(learnedFile, learned)
        Log.d(TAG, "已学习分类：$packageName -> $category")
    }

    fun allLearned(): Map<String, AppCategory> = synchronized(lock) { learned.toMap() }

    fun learnedCount(): Int = synchronized(lock) { learned.size }

    /** 清空自动学习结果，保留用户手动覆盖。 */
    fun clearLearned() = synchronized(lock) {
        learned.clear()
        if (learnedFile.exists()) learnedFile.delete()
    }

    // ── 读写 ────────────────────────────────────────────

    private fun load(file: File): Map<String, AppCategory> {
        if (!file.exists()) return emptyMap()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyMap()
            val json = JSONObject(text)
            val result = mutableMapOf<String, AppCategory>()
            json.keys().forEach { key ->
                runCatching { AppCategory.valueOf(json.getString(key)) }
                    .getOrNull()
                    ?.let { result[key] = it }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "读取 ${file.name} 失败：${e.message}")
            emptyMap()
        }
    }

    private fun save(file: File, map: Map<String, AppCategory>) {
        try {
            val json = JSONObject()
            map.forEach { (pkg, cat) -> json.put(pkg, cat.name) }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "写入 ${file.name} 失败：${e.message}")
        }
    }
}
