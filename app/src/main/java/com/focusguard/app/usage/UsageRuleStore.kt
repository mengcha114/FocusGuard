package com.focusguard.app.usage

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用使用时长的配置与当日累计存储。
 *
 * 两条规则（均为可选，任意组合）：
 * - triggerMinutes：使用超过此时长后，开始启动 AI 检测（低频检测→正式监控）
 * - hardBlockMinutes：使用超过此时长后，封锁整个应用，需答题才能继续
 */
data class AppUsageRule(
    val packageName: String,
    /** 触发 AI 检测的时长阈值（分钟），null 表示不设置 */
    val triggerMinutes: Int? = null,
    /** 强制封锁的时长阈值（分钟），null 表示不设置 */
    val hardBlockMinutes: Int? = null
) {
    init {
        require(triggerMinutes == null || triggerMinutes > 0) { "triggerMinutes 必须 > 0" }
        require(hardBlockMinutes == null || hardBlockMinutes > 0) { "hardBlockMinutes 必须 > 0" }
        if (triggerMinutes != null && hardBlockMinutes != null) {
            require(hardBlockMinutes >= triggerMinutes) { "hardBlockMinutes 必须 >= triggerMinutes" }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", packageName)
        triggerMinutes?.let { put("trigger", it) }
        hardBlockMinutes?.let { put("hardBlock", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): AppUsageRule = AppUsageRule(
            packageName = json.getString("pkg"),
            triggerMinutes = if (json.has("trigger")) json.getInt("trigger") else null,
            hardBlockMinutes = if (json.has("hardBlock")) json.getInt("hardBlock") else null
        )
    }
}

/**
 * 使用时长规则仓库 + 当日累计时间记录。
 *
 * 累计时间每天自动归零。规则永久保存，直到用户删除。
 */
class UsageRuleStore(context: Context) {

    companion object {
        private const val RULES_FILE = "usage_rules.json"
        private const val DAILY_FILE = "usage_daily.json"

        /** 封锁界面上展示的解封时间提示。计时按自然日归零。 */
        fun resetHint(): String = "计时将在次日 00:00 归零"
    }

    private val rulesFile = File(context.filesDir, RULES_FILE)
    private val dailyFile = File(context.filesDir, DAILY_FILE)
    private val lock = Any()

    private val rules: MutableMap<String, AppUsageRule> = loadRules().toMutableMap()
    private val dailySeconds: MutableMap<String, Long> = loadDaily().toMutableMap()

    // ── 规则管理 ─────────────────────────────────────────────────────

    fun getRule(packageName: String): AppUsageRule? = synchronized(lock) { rules[packageName] }

    fun setRule(rule: AppUsageRule) = synchronized(lock) {
        rules[rule.packageName] = rule
        saveRules()
    }

    fun removeRule(packageName: String) = synchronized(lock) {
        if (rules.remove(packageName) != null) saveRules()
    }

    fun allRules(): List<AppUsageRule> = synchronized(lock) { rules.values.toList() }

    // ── 当日累计时间 ─────────────────────────────────────────────────

    /** 累加 delta 秒，返回该 App 今日累计的总秒数。 */
    fun addSeconds(packageName: String, deltaSeconds: Long): Long = synchronized(lock) {
        val total = (dailySeconds[packageName] ?: 0L) + deltaSeconds
        dailySeconds[packageName] = total
        saveDaily()
        total
    }

    fun getTodaySeconds(packageName: String): Long =
        synchronized(lock) { dailySeconds[packageName] ?: 0L }

    /** 手动重置某应用今日计时（用于解封后重新计算）。 */
    fun resetToday(packageName: String) = synchronized(lock) {
        dailySeconds.remove(packageName)
        saveDaily()
    }

    // ── 私有 I/O ─────────────────────────────────────────────────────

    private fun loadRules(): Map<String, AppUsageRule> {
        if (!rulesFile.exists()) return emptyMap()
        return try {
            val json = JSONObject(rulesFile.readText())
            json.keys().asSequence().associate { key ->
                key to AppUsageRule.fromJson(json.getJSONObject(key))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveRules() {
        try {
            val json = JSONObject()
            rules.forEach { (key, rule) -> json.put(key, rule.toJson()) }
            rulesFile.writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun loadDaily(): MutableMap<String, Long> {
        if (!dailyFile.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(dailyFile.readText())
            val today = today()
            if (json.optString("date") != today) {
                // 跨天自动归零
                return mutableMapOf()
            }
            val times = json.optJSONObject("times") ?: return mutableMapOf()
            times.keys().asSequence().associate { key -> key to times.getLong(key) }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveDaily() {
        try {
            val times = JSONObject()
            dailySeconds.forEach { (pkg, secs) -> times.put(pkg, secs) }
            val json = JSONObject().apply {
                put("date", today())
                put("times", times)
            }
            dailyFile.writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun today() =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}
