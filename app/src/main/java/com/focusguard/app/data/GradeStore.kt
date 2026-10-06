package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 答题年级与选科方向存储。
 *
 * 规则：
 * - 首次进入应用必须选择具体学段（初一到高三细分）；
 * - 确认后**只能调高、不能调低**（例如高二只能升高三，不能退回到初中）；
 * - 锁机期间（含暂停）不可修改；
 * - 独立存储，不参与云备份。
 */
class GradeStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    )

    enum class Grade(val level: Int, val label: String, val hint: String) {
        PRIMARY(1, "小学 (高年级)", "大数巧妙运算、鸡兔同笼、行程与工程思维应用题"),
        JUNIOR_1(2, "初一 (七年级)", "有理数混合运算、一元一次方程、整式化简、线段与角"),
        JUNIOR_2(3, "初二 (八年级)", "一次函数性质、勾股定理应用、全等三角形、物理声光力初步"),
        JUNIOR_3(4, "初三 (九年级)", "二次函数最值、相似与圆切线、中考电路电功率、酸碱盐"),
        SENIOR_1(5, "高一", "集合与初等函数、二次函数闭区间最值、三角恒等变换、牛顿第二定律"),
        SENIOR_2(6, "高二", "数列通项求和、圆锥曲线焦点弦性质、导数切线极值、电磁感应与动量"),
        SENIOR_3(7, "高三 (高考冲刺)", "高考全真综合真题、函数零点与解析几何推导、二项式与概率"),
        COLLEGE(8, "大学及以上", "高等数学极限导数定积分、线性代数特征值、计算机算法常识");

        companion object {
            fun of(level: Int): Grade? = entries.firstOrNull { it.level == level }
        }
    }

    /** 上/下学期：影响出题的教材进度（上学期只出上册与不限学期的题，下学期也含上册）。 */
    enum class Term(val id: Int, val label: String) {
        FIRST(1, "上学期"),
        SECOND(2, "下学期");

        companion object {
            fun of(id: Int): Term = entries.firstOrNull { it.id == id } ?: SECOND
        }
    }

    /** 高中（高二/高三）及大学的选科/专业偏向。 */
    enum class Stream(val id: String, val label: String, val hint: String) {
        ALL("ALL", "全科 / 通识", "以数学思维为主，涵盖文理通识"),
        SCIENCE("SCIENCE", "理工类 (物化生/计算机)", "以数学思维为主(70%)，辅以物理力学电磁与化学推导"),
        HUMANITIES("HUMANITIES", "文史经管 (史地政/经济)", "以通用数学思维为主(60%)，辅以地理计算与文史逻辑");

        companion object {
            fun of(id: String?): Stream = entries.firstOrNull { it.id == id } ?: ALL
        }
    }

    companion object {
        private const val PREFS = "focus_guard_grade"
        private const val KEY_LEVEL = "grade_level"
        private const val KEY_STREAM = "grade_stream"
        private const val KEY_CONFIRMED_AT = "confirmed_at"
        private const val KEY_TERM = "grade_term"
    }

    val grade: Grade?
        get() = Grade.of(prefs.getInt(KEY_LEVEL, 0))

    val stream: Stream
        get() = Stream.of(prefs.getString(KEY_STREAM, Stream.ALL.id))

    /** 当前学期（默认下学期：一切已学内容都可出，避免"刚开学没题"）。 */
    val term: Term
        get() = Term.of(prefs.getInt(KEY_TERM, Term.SECOND.id))

    /** 设置学期（锁机中禁止；同年级内 上→下 允许，下→上 拒绝）。 */
    fun setTerm(target: Term, locked: Boolean): Boolean {
        if (locked && isChosen) return false
        if (target.id < term.id) return false
        prefs.edit().putInt(KEY_TERM, target.id).apply()
        return true
    }

    val isChosen: Boolean get() = grade != null

    /** 出题用年级（未选择时兜底按初一，保证不抽小学纯算术）。 */
    val effective: Grade get() = grade ?: Grade.JUNIOR_1

    /** 可选的目标年级：未选择时全部可选；已选择后只能选更高学段。 */
    fun selectable(): List<Grade> {
        val cur = grade ?: return Grade.entries
        return Grade.entries.filter { it.level >= cur.level }
    }

    /**
     * 设定学段与选科。
     * @return 是否成功（调低学段、锁机中均会被拒绝）
     */
    fun set(target: Grade, targetStream: Stream = Stream.ALL, locked: Boolean): Boolean {
        if (locked && isChosen) return false
        val cur = grade
        // 学段只能调高不能调低；同年级允许调整选科
        if (cur != null && target.level < cur.level) return false
        prefs.edit()
            .putInt(KEY_LEVEL, target.level)
            .putString(KEY_STREAM, targetStream.id)
            .putLong(KEY_CONFIRMED_AT, System.currentTimeMillis())
            .commit()
        return true
    }
}
