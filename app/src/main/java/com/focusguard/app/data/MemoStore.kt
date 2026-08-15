package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 备忘录条目。
 *
 * @param id 唯一标识（创建时间戳，删除/勾选按 id 定位，避免列表顺序变化后误删）
 * @param text 事项内容
 * @param done 是否已完成
 * @param priority 0=普通 1=重要 2=紧急
 * @param dueAt 截止时间戳，0 表示未设置
 * @param createdAt 创建时间戳
 * @param fromAi 是否由 AI 在对话中添加（界面上打标，让用户知道来源）
 * @param completedAt 完成时间戳，未完成时为 0（供列表展示与统计）
 */
data class MemoItem(
    val id: Long,
    val text: String,
    val done: Boolean = false,
    val priority: Int = 0,
    val dueAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val fromAi: Boolean = false,
    val completedAt: Long = 0L
) {
    /** 是否已过截止时间且未完成。 */
    val overdue: Boolean
        get() = !done && dueAt > 0 && dueAt < System.currentTimeMillis()

    /** 截止日期（无截止返回 null），供日期选择器与分组用。 */
    val dueDate: LocalDate?
        get() = if (dueAt > 0) Instant.ofEpochMilli(dueAt)
            .atZone(ZoneId.systemDefault()).toLocalDate() else null

    /** 距离截止的自然语言描述，未设置截止时返回空串。 */
    fun dueText(): String {
        if (dueAt <= 0) return ""
        val diff = dueAt - System.currentTimeMillis()
        val absMin = kotlin.math.abs(diff) / 60_000L
        val label = when {
            absMin < 60 -> "$absMin 分钟"
            absMin < 60 * 24 -> "${absMin / 60} 小时"
            else -> "${absMin / (60 * 24)} 天"
        }
        return if (diff < 0) "已逾期 $label" else "剩 $label"
    }

    fun priorityLabel(): String = when (priority) {
        2 -> "紧急"
        1 -> "重要"
        else -> "普通"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("done", done)
        put("priority", priority)
        put("dueAt", dueAt)
        put("createdAt", createdAt)
        put("fromAi", fromAi)
        put("completedAt", completedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): MemoItem = MemoItem(
            id = json.optLong("id", System.currentTimeMillis()),
            text = json.optString("text"),
            done = json.optBoolean("done", false),
            priority = json.optInt("priority", 0),
            dueAt = json.optLong("dueAt", 0L),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            fromAi = json.optBoolean("fromAi", false),
            completedAt = json.optLong("completedAt", 0L)
        )
    }
}

/**
 * 完成历史条目（独立归档）。
 *
 * 「清理已完成 / 删除条目」都不影响历史——历史回答的是"我完成过什么"，
 * 供统计页热力图与每日回顾使用。同一 id 只保留**最近一次**完成记录
 * （重做后刷新完成时间，不重复计数）。
 */
data class CompletedItem(
    val id: Long,
    val text: String,
    val completedAt: Long,
    val priority: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("completedAt", completedAt)
        put("priority", priority)
    }

    companion object {
        fun fromJson(json: JSONObject): CompletedItem = CompletedItem(
            id = json.optLong("id"),
            text = json.optString("text"),
            completedAt = json.optLong("completedAt"),
            priority = json.optInt("priority", 0)
        )
    }
}

/**
 * 备忘录存储。
 *
 * 四个用途：
 * 1. 首页待办清单（勾选完成、优先级、截止时间、进度统计）
 * 2. AI 检测到娱乐时把**未完成**事项注入提示词——
 *    让模型在提醒语里引用（"你还有 xx 没做呢，快去做喵！"）
 * 3. AI 对话中可通过文本工具协议**主动写入/勾选**事项
 *    （见 [com.focusguard.app.enforce.MemoToolExecutor]）
 * 4. 完成历史归档：每次勾选完成写入 history 表，统计页画每日热力图
 *
 * 存储格式为 JSON 数组；旧版本的纯字符串数组会在首次读取时自动迁移，
 * 不会丢掉用户此前手输的待办。
 */
class MemoStore(context: Context) {

    companion object {
        private const val PREFS = "focus_guard_memos"
        private const val KEY_MEMOS = "memos"
        private const val KEY_HISTORY = "history"
        /** 注入提示词的最大条数——太多会挤占 token 且模型只会引用前几条。 */
        private const val PROMPT_LIMIT = 5
        /** 历史归档上限：防止长年使用后 SharedPreferences 无限膨胀。 */
        private const val HISTORY_LIMIT = 5000

        /**
         * 把相对时间描述解析成绝对时间戳；无法识别返回 0（表示不设截止）。
         *
         * 手动输入框与 AI 工具共用这一套解析，两边行为一致：
         * `30m`/`2h`/`3d`、`30分`/`2小时`/`3天`、`今天`/`明天`/`后天`/`本周`。
         */
        fun parseDueText(raw: String?): Long {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return 0L
            val now = System.currentTimeMillis()

            Regex("""^(\d+)\s*([mhd分小时天])""").find(s)?.let { m ->
                val n = m.groupValues[1].toLongOrNull() ?: return@let
                val ms = when (m.groupValues[2]) {
                    "m", "分" -> n * 60_000L
                    "h", "小", "时" -> n * 3_600_000L
                    else -> n * 86_400_000L
                }
                return now + ms
            }

            return when {
                s.contains("今天") || s.contains("今晚") -> endOfDay(0)
                s.contains("明天") -> endOfDay(1)
                s.contains("后天") -> endOfDay(2)
                s.contains("本周") || s.contains("这周") -> now + 3 * 86_400_000L
                else -> 0L
            }
        }

        /** N 天后的 23:59。 */
        private fun endOfDay(dayOffset: Int): Long {
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
                set(java.util.Calendar.HOUR_OF_DAY, 23)
                set(java.util.Calendar.MINUTE, 59)
                set(java.util.Calendar.SECOND, 0)
            }
            return cal.timeInMillis
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部条目（未完成在前，同组内紧急优先、临期优先）。 */
    fun getAll(): List<MemoItem> = load().sortedWith(
        compareBy<MemoItem> { it.done }
            .thenByDescending { it.priority }
            .thenBy { if (it.dueAt > 0) it.dueAt else Long.MAX_VALUE }
            .thenByDescending { it.createdAt }
    )

    /** 未完成条目。 */
    fun getPending(): List<MemoItem> = getAll().filter { !it.done }

    /** 已完成条目数。 */
    fun doneCount(): Int = load().count { it.done }

    /**
     * 添加一条。
     *
     * @return 新条目；文本为空时返回 null
     */
    fun add(
        text: String,
        priority: Int = 0,
        dueAt: Long = 0L,
        fromAi: Boolean = false
    ): MemoItem? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val item = MemoItem(
            // 用时间戳做 id；同毫秒连续添加时自增避免冲突
            id = nextId(),
            text = t,
            priority = priority.coerceIn(0, 2),
            dueAt = dueAt,
            fromAi = fromAi
        )
        save(load() + item)
        return item
    }

    /**
     * 批量导入（剪贴板 / CSV 文件 / 分享文本共用）。
     *
     * 每行一条；行内支持 `|` 分隔扩展字段（与 AI 工具协议一致）：
     * `事项内容|优先级|截止`，优先级 = 普通/重要/紧急 或 0/1/2，
     * 截止 = 30m/2h/3d/今天/明天（复用 [parseDueText]）。
     *
     * 与现有未完成条目文本完全一致的自动跳过（去重）。
     *
     * @return 实际新增条数
     */
    fun addBatch(lines: List<String>, fromAi: Boolean = false): Int {
        val existing = load().filter { !it.done }.map { it.text }.toHashSet()
        var added = 0
        lines.forEach { line ->
            val s = line.trim()
            if (s.isEmpty()) return@forEach
            val parts = s.split('|').map { it.trim() }
            val text = parts.getOrNull(0).orEmpty().trim()
            if (text.isBlank()) return@forEach
            if (existing.contains(text)) return@forEach
            val priority = parsePriorityText(parts.getOrNull(1))
            val dueAt = parseDueText(parts.getOrNull(2))
            add(text = text, priority = priority, dueAt = dueAt, fromAi = fromAi)
            existing.add(text)
            added++
        }
        return added
    }

    private fun parsePriorityText(raw: String?): Int {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0
        s.toIntOrNull()?.let { return it.coerceIn(0, 2) }
        return when {
            s.contains("紧急") || s.contains("急") -> 2
            s.contains("重要") -> 1
            else -> 0
        }
    }

    /** 切换完成状态（完成时写入历史与完成时间，取消完成保留历史、清除完成时间）。 */
    fun toggleDone(id: Long) {
        val item = load().firstOrNull { it.id == id } ?: return
        if (!item.done) markDone(id) else {
            save(load().map { if (it.id == id) it.copy(done = false, completedAt = 0L) else it })
        }
    }

    /** 按 id 标记完成（含历史归档）。找不到返回 false。 */
    fun markDone(id: Long): Boolean {
        val list = load()
        if (list.none { it.id == id }) return false
        val now = System.currentTimeMillis()
        save(list.map {
            if (it.id == id) it.copy(done = true, completedAt = now) else it
        })
        archiveCompletion(id, now)
        return true
    }

    /**
     * 按关键词模糊匹配并标记完成（供 AI 工具调用：AI 只知道文本不知道 id）。
     *
     * @return 被标记完成的条目文本；未匹配到返回 null
     */
    fun markDoneByKeyword(keyword: String): String? {
        val k = keyword.trim()
        if (k.isEmpty()) return null
        val target = load().firstOrNull { !it.done && it.text.contains(k, ignoreCase = true) }
            ?: load().firstOrNull { !it.done && k.contains(it.text, ignoreCase = true) }
            ?: return null
        markDone(target.id)
        return target.text
    }

    /** 编辑内容 / 优先级 / 截止时间。 */
    fun update(id: Long, text: String, priority: Int, dueAt: Long) {
        val t = text.trim()
        if (t.isEmpty()) return
        save(
            load().map {
                if (it.id == id) {
                    it.copy(text = t, priority = priority.coerceIn(0, 2), dueAt = dueAt)
                } else it
            }
        )
    }

    /** 删除指定 id（历史归档不受影响）。 */
    fun remove(id: Long) {
        save(load().filterNot { it.id == id })
    }

    /** 清除全部已完成条目（历史归档不受影响）。 */
    fun clearDone() {
        save(load().filterNot { it.done })
    }

    /** 清空（历史归档不受影响）。 */
    fun clear() = save(emptyList())

    // ── 完成历史 ──────────────────────────────────────────────

    /** 全部完成历史，最新在前。 */
    fun getHistory(): List<CompletedItem> = loadHistory()
        .sortedByDescending { it.completedAt }

    /** 历史总数。 */
    fun historyCount(): Int = loadHistory().size

    /** 按天聚合完成次数：日期 → 完成条数（默认 14 周窗口，热力图用）。 */
    fun completionsByDay(days: Int = 98): Map<LocalDate, Int> {
        val cutoff = LocalDate.now().minusDays(days.toLong())
        return loadHistory().mapNotNull { item ->
            val day = Instant.ofEpochMilli(item.completedAt)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            if (day.isBefore(cutoff)) null else day
        }.groupingBy { it }.eachCount()
    }

    /** 指定日期完成的条目列表。 */
    fun historyForDay(day: LocalDate): List<CompletedItem> =
        getHistory().filter {
            Instant.ofEpochMilli(it.completedAt)
                .atZone(ZoneId.systemDefault()).toLocalDate() == day
        }

    /** 连续完成天数（今天未完成则从昨天起算，GitHub 惯例）。 */
    fun completionStreak(): Int {
        val days = completionsByDay(days = 730).keys.toHashSet()
        if (days.isEmpty()) return 0
        var cursor = LocalDate.now()
        if (cursor !in days) cursor = cursor.minusDays(1)
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 清空完成历史。 */
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /**
     * 注入 AI 提示词用的待办摘要。
     *
     * 只带未完成事项，且标注紧急/逾期——这样模型的提醒语能抓住重点
     * （"你那个**紧急**的数学作业还没写呢"比平铺直叙更有效）。
     * 无待办时返回空串，调用方据此决定是否拼接。
     */
    fun promptSummary(limit: Int = PROMPT_LIMIT): String {
        val pending = getPending().take(limit)
        if (pending.isEmpty()) return ""
        return pending.joinToString("\n") { item ->
            buildString {
                append("- ")
                append(item.text)
                if (item.priority == 2) append("（紧急）")
                else if (item.priority == 1) append("（重要）")
                if (item.overdue) append("（已逾期）")
                else if (item.dueAt > 0) append("（${item.dueText()}）")
            }
        }
    }

    // ── 内部实现 ────────────────────────────────────────────────

    /** 完成事件归档：同 id 覆盖（保留最近一次），总量封顶。 */
    private fun archiveCompletion(id: Long, completedAt: Long) {
        val item = load().firstOrNull { it.id == id } ?: return
        val history = loadHistory().toMutableList()
        history.removeAll { it.id == id }
        history.add(
            CompletedItem(
                id = id,
                text = item.text,
                completedAt = completedAt,
                priority = item.priority
            )
        )
        if (history.size > HISTORY_LIMIT) {
            history.sortByDescending { it.completedAt }
            history.subList(HISTORY_LIMIT, history.size).clear()
        }
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun nextId(): Long {
        val now = System.currentTimeMillis()
        val maxExisting = load().maxOfOrNull { it.id } ?: 0L
        return if (now > maxExisting) now else maxExisting + 1
    }

    private fun load(): List<MemoItem> {
        val raw = prefs.getString(KEY_MEMOS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                // 兼容旧格式：早期版本存的是纯字符串数组
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    MemoItem.fromJson(obj).takeIf { it.text.isNotBlank() }
                } else {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { text ->
                        MemoItem(id = System.currentTimeMillis() + i, text = text)
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadHistory(): List<CompletedItem> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                CompletedItem.fromJson(obj).takeIf { it.text.isNotBlank() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<MemoItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_MEMOS, arr.toString()).apply()
    }
}
