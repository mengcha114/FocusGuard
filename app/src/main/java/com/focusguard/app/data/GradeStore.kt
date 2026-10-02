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
        PRIMARY(1, "小学", "大数四则运算、连加连减、整除除法、混合运算"),
        JUNIOR(2, "初中", "在大数运算基础上加入方程、乘方、勾股定理、百分比"),
        SENIOR(3, "高中", "再加入二次方程、对数、数列求和、排列组合"),
        COLLEGE(4, "大学及以上", "再加入求导、积分、行列式、概率");

        companion object {
            fun of(level: Int): Grade? = entries.firstOrNull { it.level == level }
        }
    }

    companion object {
        private const val PREFS = "focus_guard_grade"
        private const val KEY_LEVEL = "grade_level"
        private const val KEY_CONFIRMED_AT = "confirmed_at"
    }

    /** 当前年级；未选择时为 null。 */
    val grade: Grade?
        get() = Grade.of(prefs.getInt(KEY_LEVEL, 0))

    val isChosen: Boolean get() = grade != null

    /** 出题用年级（未选择时按小学，保证任何时候都能出题）。 */
    val effective: Grade get() = grade ?: Grade.PRIMARY

    /** 可选的目标年级：未选择时全部可选；已选择后只能选更高的。 */
    fun selectable(): List<Grade> {
        val cur = grade ?: return Grade.entries
        return Grade.entries.filter { it.level > cur.level }
    }

    /**
     * 设定年级。
     * @return 是否成功（调低、锁机中、相同值都会被拒绝）
     */
    fun set(target: Grade, locked: Boolean): Boolean {
        if (locked && isChosen) return false
        val cur = grade
        if (cur != null && target.level <= cur.level) return false
        prefs.edit()
            .putInt(KEY_LEVEL, target.level)
            .putLong(KEY_CONFIRMED_AT, System.currentTimeMillis())
            .commit()
        return true
    }
}
