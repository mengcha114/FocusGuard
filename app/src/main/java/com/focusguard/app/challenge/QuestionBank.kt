package com.focusguard.app.challenge

import android.content.Context
import com.focusguard.app.data.GradeStore
import org.json.JSONArray
import java.util.zip.GZIPInputStream

/**
 * 本地题库（assets/question_bank.json，由 tools/build_question_bank.py 生成）。
 * 注意：资源名不能带 .gz 后缀——aapt 会把它改名成 question_bank.json，导致按原名打开失败。
 *
 * 来源：TAL-SCQ5K（好未来）、AGIEval 高考部分（微软），均为 MIT 许可，
 * 出处见 assets/question_bank_LICENSE.txt。年级按题目真实来源判定，难度已筛除过易与过难。
 *
 * 首次使用时懒加载（约 2500 题，解压后 1 MB 左右），之后常驻内存。
 */
object QuestionBank {

    data class Item(
        /** 年级，与 [GradeStore.Grade.level] 一致。 */
        val grade: Int,
        /** 学期：1 上册 / 2 下册 / 0 不限学期。 */
        val sem: Int,
        val subject: String,
        /** SCIENCE / HUMANITIES / ALL */
        val stream: String,
        /** 知识点模块（换一题时互斥轮换）。 */
        val topic: String,
        /** 难度 1 中等 / 2 偏难 / 3 难。 */
        val difficulty: Int,
        val question: String,
        val options: List<String>,
        /** 单选为一个字母；多选为排序后的字母组合，如 "AC"。 */
        val answer: String,
        val explanation: String
    ) {
        val isMulti: Boolean get() = answer.length > 1
    }

    @Volatile private var cache: List<Item>? = null

    /** 单元测试注入。 */
    internal fun setForTest(items: List<Item>) { cache = items }

    fun all(context: Context?): List<Item> {
        cache?.let { return it }
        if (context == null) return emptyList()
        synchronized(this) {
            cache?.let { return it }
            val loaded = runCatching { load(context) }.getOrDefault(emptyList())
            cache = loaded
            return loaded
        }
    }

    /** 题库资源名：优先未压缩 JSON；旧包名兼容 .gz（早期的构建产物）。 */
    private val ASSET_NAMES = listOf("question_bank.json", "question_bank.json.gz")

    /** 最近一次加载失败原因（设置页展示，避免「题库没生效」无从排查）。 */
    @Volatile
    var lastLoadError: String = ""
        private set

    private fun load(context: Context): List<Item> {
        val assets = context.applicationContext.assets
        var text: String? = null
        var lastError: Throwable? = null
        for (name in ASSET_NAMES) {
            text = runCatching {
                assets.open(name).use { raw ->
                    val stream = if (name.endsWith(".gz")) GZIPInputStream(raw) else raw
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }.onFailure { lastError = it }.getOrNull()
            if (text != null) break
        }
        if (text == null) {
            lastLoadError = lastError?.message ?: "题库资源缺失"
            android.util.Log.w("QuestionBank", "题库加载失败：$lastLoadError")
            throw lastError ?: IllegalStateException("题库资源缺失")
        }
        lastLoadError = ""
        val arr = JSONArray(text)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val opts = o.getJSONArray("o")
            Item(
                grade = o.getInt("g"),
                sem = o.optInt("sem", 0),
                subject = o.getString("s"),
                stream = o.getString("st"),
                topic = o.optString("t", o.getString("s")),
                difficulty = o.optInt("d", 2),
                question = o.getString("q"),
                options = List(opts.length()) { opts.getString(it) },
                answer = o.getString("a"),
                explanation = o.optString("e", "")
            )
        }
    }

    /**
     * 按年级、选科取题。
     *
     * - 只取本年级；本年级题少于 [MIN_POOL] 时向下借**低一年级**的偏难题，绝不向上借（不超纲）；
     * - 选科只对高二 / 高三 / 大学生效：数学所有人都出，理工加物化生，文史加史地；
     * - [excludeTopic]：换一题时避开刚才的知识点；[maxDifficulty]：答错后降难度。
     */
    fun find(
        context: Context?,
        grade: GradeStore.Grade,
        stream: GradeStore.Stream,
        excludeTopic: String? = null,
        maxDifficulty: Int = 3
    ): List<Item> {
        val items = all(context)
        val streamOn = grade.level >= GradeStore.Grade.SENIOR_2.level
        fun streamOk(it: Item) = !streamOn || stream == GradeStore.Stream.ALL ||
            it.stream == "ALL" || it.stream == stream.id
        // 学期过滤：上学期只出"上册 / 不限学期"的题；下学期含上册（复习）。
        val termId = context?.let { GradeStore(it).term.id } ?: GradeStore.Term.SECOND.id
        fun semOk(it: Item) = it.sem == 0 || it.sem <= termId
        var pool = items.filter {
            (it.grade == grade.level && semOk(it) && streamOk(it)) || it.grade == 0
        }
        if (pool.size < MIN_POOL && grade.level > 1) {
            pool = pool + items.filter { it.grade == grade.level - 1 && it.difficulty >= 2 && streamOk(it) }
        }
        val byDiff = pool.filter { it.difficulty <= maxDifficulty }.ifEmpty { pool }
        if (excludeTopic.isNullOrBlank()) return byDiff
        return byDiff.filter { it.topic != excludeTopic }.ifEmpty { byDiff }
    }

    private const val MIN_POOL = 40
}
