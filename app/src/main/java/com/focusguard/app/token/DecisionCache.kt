package com.focusguard.app.token

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 判定结果缓存。
 *
 * 缓存键是 "包名 + 图像感知哈希"。当用户在同一个页面反复停留，
 * 或者在一段时间内多次回到同一个界面（比如反复切回同一个视频），
 * 直接复用上次的大模型判定结果，不再重复付费。
 *
 * 缓存带过期时间：内容相同但时间久远时重新判定，
 * 避免"早上看教程被判学习，晚上同一个 App 刷娱乐"却继续复用旧结论。
 */
class DecisionCache(context: Context) {

    companion object {
        private const val TAG = "DecisionCache"
        private const val FILE_NAME = "decision_cache.json"
        private const val MAX_ENTRIES = 120
        private const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L // 6 小时
        private const val HASH_THRESHOLD = 5
    }

    data class Entry(
        val packageName: String,
        val imageHash: Long,
        val classification: String,
        val confidence: Float,
        val reason: String,
        val createdAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("packageName", packageName)
            put("imageHash", imageHash)
            put("classification", classification)
            put("confidence", confidence.toDouble())
            put("reason", reason)
            put("createdAt", createdAt)
        }

        companion object {
            fun fromJson(json: JSONObject) = Entry(
                packageName = json.optString("packageName"),
                imageHash = json.optLong("imageHash"),
                classification = json.optString("classification", "NEUTRAL"),
                confidence = json.optDouble("confidence", 0.0).toFloat(),
                reason = json.optString("reason"),
                createdAt = json.optLong("createdAt")
            )
        }
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private var entries: MutableList<Entry> = load().toMutableList()

    /**
     * 查询缓存。命中条件：同一个应用 + 图像指纹足够接近 + 未过期。
     *
     * @return 命中的判定结果，未命中返回 null
     */
    fun lookup(packageName: String, imageHash: Long, ttlMs: Long = DEFAULT_TTL_MS): Entry? =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val hit = entries.firstOrNull { entry ->
                entry.packageName == packageName &&
                    now - entry.createdAt < ttlMs &&
                    ImageHasher.isSimilar(entry.imageHash, imageHash, HASH_THRESHOLD)
            }
            if (hit != null) {
                Log.d(TAG, "缓存命中：$packageName -> ${hit.classification}")
            }
            hit
        }

    fun put(
        packageName: String,
        imageHash: Long,
        classification: String,
        confidence: Float,
        reason: String
    ) = synchronized(lock) {
        // 同一应用下指纹相近的旧记录先移除，避免缓存膨胀
        entries.removeAll { old ->
            old.packageName == packageName &&
                ImageHasher.isSimilar(old.imageHash, imageHash, HASH_THRESHOLD)
        }
        entries.add(
            0,
            Entry(packageName, imageHash, classification, confidence, reason, System.currentTimeMillis())
        )
        if (entries.size > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size).clear()
        }
        save()
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        if (file.exists()) file.delete()
    }

    fun size(): Int = synchronized(lock) { entries.size }

    private fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val array = JSONArray(text)
            (0 until array.length()).map { Entry.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "缓存读取失败：${e.message}")
            emptyList()
        }
    }

    private fun save() {
        try {
            val array = JSONArray()
            entries.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "缓存写入失败：${e.message}")
        }
    }
}
