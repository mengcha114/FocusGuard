package com.focusguard.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DetectionLog(
    val timestamp: Long = System.currentTimeMillis(),
    val classification: String = "NEUTRAL", // STUDY_WORK / ENTERTAINMENT / NEUTRAL
    val confidence: Float = 0f,
    val reason: String = "",
    val action: String = "NONE",            // LOCK / EXIT / WARN / NONE
    val source: String = "AI_VISION",       // APP_CATEGORY / SCREEN_TEXT / AI_VISION / ERROR
    val appLabel: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("classification", classification)
        put("confidence", confidence)
        put("reason", reason)
        put("action", action)
        put("source", source)
        put("appLabel", appLabel)
    }

    fun getTimeFormatted(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    fun getDateFormatted(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

    /** 本条检测是否消耗了大模型 token。 */
    fun usedAiToken(): Boolean = source == "AI_VISION"

    companion object {
        fun fromJson(json: JSONObject): DetectionLog = DetectionLog(
            timestamp = json.optLong("timestamp", 0),
            classification = json.optString("classification", "NEUTRAL"),
            confidence = json.optDouble("confidence", 0.0).toFloat(),
            reason = json.optString("reason", ""),
            action = json.optString("action", "NONE"),
            source = json.optString("source", "AI_VISION"),
            appLabel = json.optString("appLabel", "")
        )
    }
}

class LogStore(context: Context) {

    companion object {
        private const val MAX_LOGS = 500
        private const val FILE_NAME = "detection_logs.json"
    }

    private val logFile = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    fun addLog(log: DetectionLog) = synchronized(lock) {
        val logs = readLogs().toMutableList()
        logs.add(0, log)
        if (logs.size > MAX_LOGS) {
            logs.subList(MAX_LOGS, logs.size).clear()
        }
        writeLogs(logs)
    }

    fun getAllLogs(): List<DetectionLog> = synchronized(lock) { readLogs() }

    fun getTodayLogs(): List<DetectionLog> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return getAllLogs().filter { it.getDateFormatted() == today }
    }

    fun getTodayViolations(): List<DetectionLog> =
        getTodayLogs().filter { it.classification == "ENTERTAINMENT" }

    fun getTodayCheckCount(): Int = getTodayLogs().size

    /** 今日调用大模型的次数，用于展示 token 消耗情况。 */
    fun getTodayAiCallCount(): Int = getTodayLogs().count { it.usedAiToken() }

    /** 今日被本地规则拦下、未消耗 token 的检测占比（0-100）。 */
    fun getTodayTokenSavedPercent(): Int {
        val logs = getTodayLogs().filter { it.source != "ERROR" }
        if (logs.isEmpty()) return 100
        val saved = logs.count { !it.usedAiToken() }
        return saved * 100 / logs.size
    }

    fun getTodayFocusScore(): Int {
        val logs = getTodayLogs().filter { it.classification != "NEUTRAL" }
        if (logs.isEmpty()) return 100
        val violations = logs.count { it.classification == "ENTERTAINMENT" }
        return ((logs.size - violations) * 100 / logs.size).coerceIn(0, 100)
    }

    fun clearLogs() = synchronized(lock) {
        if (logFile.exists()) logFile.delete()
    }

    private fun readLogs(): List<DetectionLog> {
        if (!logFile.exists()) return emptyList()
        return try {
            val text = logFile.readText()
            if (text.isBlank()) return emptyList()
            val array = JSONArray(text)
            (0 until array.length()).map { DetectionLog.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeLogs(logs: List<DetectionLog>) {
        try {
            val array = JSONArray()
            logs.forEach { array.put(it.toJson()) }
            logFile.writeText(array.toString())
        } catch (e: Exception) {
            // 写入失败不应影响检测主流程
        }
    }
}
