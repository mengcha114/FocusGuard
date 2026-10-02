package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 答题年级（决定解锁 / 验证题的难度与题型）。
 *
 * 规则：
 * - 首次进入应用必须选择；
 * - 确认后**只能调高、不能调低**（防止为了解锁临时改成小学难度）；
 * - 锁机期间（含暂停）不能修改；
 * - 独立 prefs 文件，不参与备份（allowBackup=false）。
 */
class GradeStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    )

    enum class Grade(val level: Int, val label: String, val hint: String) {
        PRIMARY(1, "小学", "大数四则运算、连加连减、整除除法、生活科学常识"),
        JUNIOR(2, "初中", "初中全科：代数方程、初中物理/化学、生物地理常识"),
        SENIOR(3, "高中", "高中各科（可自主选择理科/文科/全科方向）"),
        COLLEGE(4, "大学及以上", "高等数学/线代/计算机/经管文史/大学英语");

        companion object {
            fun of(level: Int): Grade? = entries.firstOrNull { it.level == level }
        }
    }

    /** 高中及以上的选科/专业方向。 */
    enum class Stream(val id: String, val label: String, val hint: String) {
        ALL("ALL", "全科 / 通识", "不限科目，全面考察"),
        SCIENCE("SCIENCE", "理工类 (物化生/计算机)", "偏重数学、物理、化学、生物、计算机"),
        HUMANITIES("HUMANITIES", "文史经管 (史地政/经济)", "偏重语文、英语、历史、地理、经管逻辑");

        companion object {
            fun of(id: String?): Stream = entries.firstOrNull { it.id == id } ?: ALL
        }
    }

    companion object {
        private const val PREFS = "focus_guard_grade"
        private const val KEY_LEVEL = "grade_level"
        private const val KEY_STREAM = "grade_stream"
        private const val KEY_CONFIRMED_AT = "confirmed_at"
    }

    /** 当前年级；未选择时为 null。 */
    val grade: Grade?
        get() = Grade.of(prefs.getInt(KEY_LEVEL, 0))

    /** 当前选科方向（默认为全科）。 */
    val stream: Stream
        get() = Stream.of(prefs.getString(KEY_STREAM, Stream.ALL.id))

    val isChosen: Boolean get() = grade != null

    /** 出题用年级（未选择时按小学，保证任何时候都能出题）。 */
    val effective: Grade get() = grade ?: Grade.PRIMARY

    /** 可选的目标年级：未选择时全部可选；已选择后只能选更高的。 */
    fun selectable(): List<Grade> {
        val cur = grade ?: return Grade.entries
        return Grade.entries.filter { it.level > cur.level }
    }

    /**
     * 设定年级与选科方向。
     * @return 是否成功（调低年级、锁机中都会被拒绝）
     */
    fun set(target: Grade, targetStream: Stream = Stream.ALL, locked: Boolean): Boolean {
        if (locked && isChosen) return false
        val cur = grade
        if (cur != null && target.level < cur.level) return false
        prefs.edit()
            .putInt(KEY_LEVEL, target.level)
            .putString(KEY_STREAM, targetStream.id)
            .putLong(KEY_CONFIRMED_AT, System.currentTimeMillis())
            .commit()
        return true
    }
}
