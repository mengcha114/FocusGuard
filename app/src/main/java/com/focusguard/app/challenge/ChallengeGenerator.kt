package com.focusguard.app.challenge

import android.content.Context
import com.focusguard.app.data.GradeStore
import java.security.SecureRandom
import kotlin.math.abs

data class ChallengeQuestion(
    val question: String = "",
    val answer: String = "",
    val explanation: String = "",
    /** 题型标识（统计 / 去重用）。 */
    val kind: String = "",
    /** 建议作答时限（秒）。 */
    val timeLimitSec: Int = 90,
    /** 选择题选项（为空表示填空题）。 */
    val options: List<String> = emptyList(),
    /** 题目学科（例如：物理、化学、历史、数学等）。 */
    val subject: String = ""
)

/**
 * 解锁挑战题目生成器（纯本地，答案由程序即时计算，不存在错答案）。
 *
 * ## 年级 × 难度
 * - 年级（[GradeStore.Grade]）决定题型池：小学 = 大数四则；初中及以上在此基础上叠加本年级题型，
 *   任何年级都不会比小学档简单。
 * - 难度 1–3 决定位数 / 规模（强度 2「连对 5 题」用 3）。
 *
 * ## 题型丰富与防重复
 * - 每个年级 15+ 类题型，每类多套题干模板与随机参数；
 * - 题型按「最近出现次数」降权轮换，最近 5 题内同一题型最多 1 次；
 * - 持久化最近 200 道题的指纹，命中则重新生成；
 * - 随机源为 [SecureRandom]，不可预测。
 *
 * ## 输入约束
 * 所有答案都是整数（可为负数），自绘数字键盘（0–9、-、.）即可作答。
 */
class ChallengeGenerator(context: Context? = null) {

    private val rnd = SecureRandom()
    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gradeStore = context?.let { GradeStore(it) }

    companion object {
        private const val PREFS = "focus_guard_challenge_history"
        private const val KEY_RECENT_FP = "recent_fp"
        private const val KEY_RECENT_KINDS = "recent_kinds"
        private const val MAX_FP = 200
        private const val MAX_KINDS = 12

    }

    /** 无 Context 构造时的实例内去重记录。 */
    private val memFp = ArrayDeque<String>()
    private val memKinds = ArrayDeque<String>()

    private class Kind(val id: String, val grade: Int, val weight: Int, val make: (Int) -> ChallengeQuestion)

    private val kinds: List<Kind> = listOf(
        // ── 小学：大数四则（所有年级的基础） ──
        Kind("add", 1, 6) { addition(it) },
        Kind("sub", 1, 6) { subtraction(it) },
        Kind("chainAdd", 1, 5) { chainAdd(it) },
        Kind("chainSub", 1, 5) { chainSub(it) },
        Kind("mixAddSub", 1, 5) { mixAddSub(it) },
        Kind("mul", 1, 6) { multiplication(it) },
        Kind("div", 1, 6) { exactDivision(it) },
        Kind("rem", 1, 4) { divisionRemainder(it) },
        Kind("mixOp", 1, 6) { mixedOperation(it) },
        Kind("paren", 1, 5) { parenthesized(it) },
        Kind("square", 1, 3) { squareOf(it) },
        Kind("percent", 1, 3) { percentOf(it) },
        Kind("multiples", 1, 2) { countMultiples(it) },
        Kind("story", 1, 3) { wordProblem(it) },
        Kind("unit", 1, 2) { unitConvert(it) },
        // ── 初中 ──
        Kind("linear", 2, 5) { linearEquation(it) },
        Kind("system", 2, 4) { linearSystem(it) },
        Kind("power", 2, 3) { powerOf(it) },
        Kind("sqDiff", 2, 3) { squareDifference(it) },
        Kind("pythag", 2, 3) { pythagoras(it) },
        Kind("negative", 2, 3) { signedArithmetic(it) },
        Kind("gcd", 2, 3) { gcdLcm(it) },
        Kind("polyEval", 2, 3) { polynomialValue(it) },
        // ── 高中 ──
        Kind("quadratic", 3, 4) { quadraticRoots(it) },
        Kind("log", 3, 3) { logarithm(it) },
        Kind("arithSum", 3, 3) { arithmeticSum(it) },
        Kind("geoTerm", 3, 3) { geometricTerm(it) },
        Kind("comb", 3, 3) { combination(it) },
        Kind("perm", 3, 2) { permutation(it) },
        Kind("absIneq", 3, 2) { absoluteInequalityCount(it) },
        // ── 大学 ──
        Kind("deriv", 4, 4) { derivativeAt(it) },
        Kind("integral", 4, 4) { definiteIntegral(it) },
        Kind("det2", 4, 3) { determinant2(it) },
        Kind("det3", 4, 3) { determinant3(it) },
        Kind("expect", 4, 2) { expectedValue(it) },
        Kind("limit", 4, 2) { limitRational(it) },
        Kind("modPow", 4, 2) { modularPower(it) }
    )

    /**
     * 生成一道题。
     *
     * @param difficulty 1=基础 2=中等（默认）3=困难（强度 2 连对 5 题）
     * @param numericOnly 保留旧参数：新题库所有答案都是数字，已无需过滤
     * @param grade 指定年级；为空时读取 [GradeStore]（无 Context 时按小学）
     */
    @Suppress("UNUSED_PARAMETER")
    fun generate(difficulty: Int = 2, numericOnly: Boolean = false, grade: GradeStore.Grade? = null): ChallengeQuestion {
        val level = difficulty.coerceIn(1, 3)
        val effectiveGrade = grade ?: gradeStore?.effective ?: GradeStore.Grade.PRIMARY
        val stream = gradeStore?.stream ?: GradeStore.Stream.ALL
        val recentFp = loadFp()

        // 1. 对于初中、高中、大学，优先从权威真题库中按年级与选科出题！
        // 彻底杜绝高中出小学题。
        if (effectiveGrade.level >= 2 && !numericOnly) {
            val bankItems = QuestionBank.find(effectiveGrade, stream).shuffled(rnd)
            for (item in bankItems) {
                val fp = "bank|${item.subject}|${item.question}"
                if (fp !in recentFp) {
                    remember(item.subject, fp)
                    val formattedQ = buildString {
                        append("【${item.subject}】")
                        append(item.question)
                        if (item.options.isNotEmpty()) {
                            append("

")
                            append(item.options.joinToString("
"))
                        }
                    }
                    return ChallengeQuestion(
                        question = formattedQ,
                        answer = item.answer,
                        explanation = item.explanation,
                        kind = item.subject,
                        timeLimitSec = if (effectiveGrade.level >= 3) 120 else 90,
                        options = item.options,
                        subject = item.subject
                    )
                }
            }
        }

        // 2. 本地计算题型池：必须与年级精准匹配，高中不再抽小学基础算术！
        // 高中只抽高等代数/函数/导数/几何/数列/排列组合等高中题型。
        val g = effectiveGrade.level
        val pool = kinds.filter { it.grade == g }.ifEmpty { kinds.filter { it.grade <= g } }
        val recentKinds = loadKinds()

        repeat(40) {
            val kind = pick(pool, recentKinds, g)
            val q = runCatching { kind.make(level) }.getOrNull() ?: return@repeat
            val fp = "${kind.id}|${q.question}"
            if (fp !in recentFp) {
                remember(kind.id, fp)
                return q.copy(kind = kind.id)
            }
        }
        return pool.random(rnd).make(level)
    }

    /** 兼容旧调用点。 */
    fun generateLocalQuestion(): ChallengeQuestion = generate(2)

    /**
     * 加权抽题型：基础权重 × 衰减（最近出现越多越低）；
     * 当前年级的新题型权重 ×1.6，让高年级用户更常遇到本年级的题。
     * 最近 5 题出现过的题型直接排除。
     */
    private fun pick(pool: List<Kind>, recent: List<String>, grade: Int): Kind {
        val last5 = recent.takeLast(5).toSet()
        val candidates = pool.filter { it.id !in last5 }.ifEmpty { pool }
        val weights = candidates.map { k ->
            val seen = recent.count { it == k.id }
            val base = k.weight * (if (k.grade == grade && grade > 1) 1.6 else 1.0)
            base / (1.0 + seen)
        }
        var r = rnd.nextDouble() * weights.sum()
        candidates.forEachIndexed { i, k ->
            r -= weights[i]
            if (r <= 0) return k
        }
        return candidates.last()
    }

    // ── 去重记录 ───────────────────────────────────

    private fun loadFp(): Set<String> = prefs?.getString(KEY_RECENT_FP, "")?.split('\u0001')
        ?.filter { it.isNotEmpty() }?.toSet() ?: memFp.toSet()

    private fun loadKinds(): List<String> = prefs?.getString(KEY_RECENT_KINDS, "")?.split(',')
        ?.filter { it.isNotEmpty() } ?: memKinds.toList()

    private fun remember(kind: String, fp: String) {
        if (prefs == null) {
            synchronized(memFp) {
                memFp.addLast(fp); while (memFp.size > MAX_FP) memFp.removeFirst()
                memKinds.addLast(kind); while (memKinds.size > MAX_KINDS) memKinds.removeFirst()
            }
            return
        }
        val fps = (prefs.getString(KEY_RECENT_FP, "") ?: "").split('\u0001').filter { it.isNotEmpty() } + fp
        val ks = (prefs.getString(KEY_RECENT_KINDS, "") ?: "").split(',').filter { it.isNotEmpty() } + kind
        prefs.edit()
            .putString(KEY_RECENT_FP, fps.takeLast(MAX_FP).joinToString("\u0001"))
            .putString(KEY_RECENT_KINDS, ks.takeLast(MAX_KINDS).joinToString(","))
            .apply()
    }

    // ── 判分 ───────────────────────────────────────

    /** 判定用户作答是否正确，容忍全/半角、空格、千分位、单位后缀等常见差异。 */
    fun isAnswerCorrect(userAnswer: String, expected: String): Boolean {
        val a = normalize(userAnswer)
        val b = normalize(expected)
        if (a.isEmpty()) return false
        if (a == b) return true
        val na = a.toDoubleOrNull()
        val nb = b.toDoubleOrNull()
        return na != null && nb != null && abs(na - nb) < 1e-9
    }

    private fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
            .replace("，", "").replace(",", "")
            .replace(" ", "").replace("　", "")
            .replace("：", "").replace(":", "")
            .replace("－", "-").replace("—", "-").replace("−", "-")
        val suffixes = listOf("。", "个", "元", "天", "岁", "次", "位", "人", "件", "米", "厘米", "千克", "克", "分钟", "秒")
        var changed = true
        while (changed) {
            changed = false
            for (suf in suffixes) {
                if (s.endsWith(suf) && s.length > suf.length) {
                    s = s.removeSuffix(suf); changed = true
                }
            }
        }
        if (s.endsWith(".") && s.length > 1) s = s.dropLast(1)
        return s
    }

    // ── 随机工具 ───────────────────────────────────

    /** [lo, hi] 闭区间随机整数。 */
    private fun r(lo: Long, hi: Long): Long {
        val a = minOf(lo, hi); val b = maxOf(lo, hi)
        return a + (rnd.nextDouble() * (b - a + 1)).toLong().coerceIn(0L, b - a)
    }
    private fun r(lo: Int, hi: Int): Int = r(lo.toLong(), hi.toLong()).toInt()
    private fun digits(n: Int): Long = r(pow10(n - 1), pow10(n) - 1)
    private fun pow10(n: Int): Long { var v = 1L; repeat(n) { v *= 10 }; return v }
    private fun <T> oneOf(vararg xs: T): T = xs[rnd.nextInt(xs.size)]
    private fun nz(lo: Int, hi: Int): Int { var v: Int; do { v = r(lo, hi) } while (v == 0); return v }
    private fun signed(v: Long) = if (v < 0) "($v)" else "$v"

    private fun q(text: String, ans: Long, exp: String, limit: Int) =
        ChallengeQuestion(question = text, answer = ans.toString(), explanation = exp, timeLimitSec = limit)

    // ══════════ 小学：大数四则 ══════════

    private fun addition(lv: Int): ChallengeQuestion {
        val n = when (lv) { 1 -> 6; 2 -> 7; else -> 8 }
        val a = digits(n); val b = digits(n)
        return q(oneOf("计算：$a + $b = ?", "$a 加上 $b 等于多少？"), a + b, "$a + $b = ${a + b}", 90)
    }

    private fun subtraction(lv: Int): ChallengeQuestion {
        val n = when (lv) { 1 -> 6; 2 -> 7; else -> 8 }
        val a = digits(n); val b = r(pow10(n - 2), a - 1).coerceAtMost(a - 1)
        return q(oneOf("计算：$a − $b = ?", "$a 减去 $b 等于多少？"), a - b, "$a − $b = ${a - b}", 90)
    }

    private fun chainAdd(lv: Int): ChallengeQuestion {
        val n = when (lv) { 1 -> 5; 2 -> 6; else -> 8 }
        val xs = List(3) { digits(n) }
        return q("计算：${xs.joinToString(" + ")} = ?", xs.sum(), "逐项相加得 ${xs.sum()}", 100)
    }

    private fun chainSub(lv: Int): ChallengeQuestion {
        val n = when (lv) { 1 -> 6; 2 -> 7; else -> 8 }
        val a = digits(n); val b = digits(n - 1); val c = digits(n - 2)
        return q("计算：$a − $b − $c = ?", a - b - c, "$a − $b = ${a - b}，再减 $c 得 ${a - b - c}", 100)
    }

    private fun mixAddSub(lv: Int): ChallengeQuestion {
        val n = when (lv) { 1 -> 5; 2 -> 6; else -> 7 }
        val a = digits(n); val b = digits(n); val c = digits(n - 1); val d = digits(n - 1)
        val v = a - b + c + d
        return q("计算：$a − $b + $c + $d = ?", v, "从左到右依次计算得 $v", 110)
    }

    private fun multiplication(lv: Int): ChallengeQuestion {
        val (x, y) = when (lv) { 1 -> 3 to 3; 2 -> 4 to 3; else -> 4 to 4 }
        val a = digits(x); val b = digits(y)
        return q(oneOf("计算：$a × $b = ?", "$a 乘以 $b 等于多少？"), a * b, "$a × $b = ${a * b}", 180)
    }

    /** 先随机商与除数再相乘，保证整除。 */
    private fun exactDivision(lv: Int): ChallengeQuestion {
        val (dDig, qDig) = when (lv) { 1 -> 2 to 4; 2 -> 3 to 4; else -> 3 to 5 }
        val d = digits(dDig); val quo = digits(qDig); val n = d * quo
        return q(oneOf("计算：$n ÷ $d = ?", "$n 除以 $d 等于多少？"), quo, "$d × $quo = $n，所以商为 $quo", 180)
    }

    private fun divisionRemainder(lv: Int): ChallengeQuestion {
        val d = when (lv) { 1 -> r(7, 19); 2 -> r(13, 97); else -> r(97, 997) }.toLong()
        val quo = digits(when (lv) { 1 -> 3; 2 -> 4; else -> 4 })
        val rem = r(1L, d - 1)
        val n = d * quo + rem
        return q("$n 除以 $d，余数是多少？", rem, "$n = $d × $quo + $rem", 150)
    }

    /** a × b + c / a × b − c / a ÷ b + c × d，考运算顺序。 */
    private fun mixedOperation(lv: Int): ChallengeQuestion {
        return when (rnd.nextInt(3)) {
            0 -> {
                val a = digits(if (lv >= 2) 3 else 3); val b = digits(if (lv >= 3) 3 else 2); val c = digits(5)
                q("计算：$a × $b + $c = ?", a * b + c, "先乘后加：$a×$b = ${a * b}，再加 $c", 180)
            }
            1 -> {
                val a = digits(3); val b = digits(if (lv >= 3) 3 else 2); val c = r(100L, a * b - 1)
                q("计算：$a × $b − $c = ?", a * b - c, "先乘后减：$a×$b = ${a * b}，再减 $c", 180)
            }
            else -> {
                val b = digits(2); val quo = digits(if (lv >= 2) 3 else 2); val a = b * quo
                val c = digits(2); val d = digits(if (lv >= 3) 3 else 2)
                q("计算：$a ÷ $b + $c × $d = ?", quo + c * d, "$a÷$b = $quo，$c×$d = ${c * d}，相加得 ${quo + c * d}", 200)
            }
        }
    }

    private fun parenthesized(lv: Int): ChallengeQuestion {
        return if (rnd.nextBoolean()) {
            val a = digits(if (lv >= 2) 4 else 3); val b = r(10L, a - 1); val c = digits(if (lv >= 3) 3 else 2)
            q("计算：($a − $b) × $c = ?", (a - b) * c, "先算括号 ${a - b}，再乘 $c", 180)
        } else {
            val a = digits(3); val b = digits(3); val c = digits(if (lv >= 3) 3 else 2)
            q("计算：($a + $b) × $c = ?", (a + b) * c, "先算括号 ${a + b}，再乘 $c", 180)
        }
    }

    private fun squareOf(lv: Int): ChallengeQuestion {
        val a = when (lv) { 1 -> r(21, 99); 2 -> r(101, 399); else -> r(401, 999) }.toLong()
        return q("计算：$a² = ?", a * a, "$a × $a = ${a * a}", 150)
    }

    private fun percentOf(lv: Int): ChallengeQuestion {
        val p = oneOf(4, 6, 8, 12, 15, 18, 24, 35, 45, 64, 72, 85).toLong()
        val base = when (lv) { 1 -> r(10, 99); 2 -> r(100, 999); else -> r(1000, 9999) } * 100L
        return q("$base 的 $p% 是多少？", base * p / 100, "$base × $p ÷ 100 = ${base * p / 100}", 150)
    }

    private fun countMultiples(lv: Int): ChallengeQuestion {
        val d = oneOf(7, 11, 13, 17, 19, 23).toLong()
        val lo = when (lv) { 1 -> r(100, 999); 2 -> r(1000, 9999); else -> r(10000, 99999) }.toLong()
        val hi = lo + r(500, 5000)
        val cnt = hi / d - (lo - 1) / d
        return q("$lo 到 $hi 之间（含两端）有多少个 $d 的倍数？", cnt, "⌊$hi/$d⌋ − ⌊${lo - 1}/$d⌋ = $cnt", 180)
    }

    private fun wordProblem(lv: Int): ChallengeQuestion {
        return when (rnd.nextInt(3)) {
            0 -> {
                val speed = r(45, 98).toLong(); val hours = r(3, 12).toLong() + (if (lv >= 3) 10 else 0)
                q("一辆车每小时行驶 $speed 千米，$hours 小时共行驶多少千米？", speed * hours, "$speed × $hours", 120)
            }
            1 -> {
                val price = r(23, 189).toLong(); val count = r(12, 99).toLong() * (if (lv >= 2) 7 else 1)
                val paid = (price * count / 1000 + 1) * 1000
                q("每件 $price 元，买 $count 件，付 $paid 元，应找回多少元？", paid - price * count,
                    "$paid − $price×$count = ${paid - price * count}", 180)
            }
            else -> {
                val a = r(12, 60).toLong(); val b = r(12, 60).toLong()
                val days = r(6, 40).toLong() * (if (lv >= 2) r(2, 9).toLong() else 1)
                val total = (a + b) * days
                q("一项工程甲每天完成 $a 份，乙每天完成 $b 份，共 $total 份，两人合作需要多少天？",
                    days, "$total ÷ ($a + $b) = $days", 180)
            }
        }
    }

    private fun unitConvert(lv: Int): ChallengeQuestion {
        return if (rnd.nextBoolean()) {
            val h = r(2, 30).toLong(); val m = r(1, 59).toLong(); val s = r(1, 59).toLong()
            val total = h * 3600 + m * 60 + s
            q("$h 小时 $m 分 $s 秒一共是多少秒？", total, "$h×3600 + $m×60 + $s = $total", 120)
        } else {
            val km = r(2, 99).toLong(); val m = r(1, 999).toLong(); val cm = r(1, 99).toLong()
            val total = km * 100000 + m * 100 + cm
            q("$km 千米 $m 米 $cm 厘米一共是多少厘米？", total, "$km×100000 + $m×100 + $cm = $total", 120)
        }
    }

    // ══════════ 初中 ══════════

    private fun linearEquation(lv: Int): ChallengeQuestion {
        val x = nz(-60, 120).toLong() * (if (lv >= 3) 7 else 1)
        val a = nz(-19, 29).toLong(); val b = r(-500, 500).toLong()
        val c = a * x + b
        return q("解方程：${a}x ${if (b >= 0) "+ $b" else "− ${-b}"} = $c，x = ?", x, "${a}x = ${c - b}，x = $x", 150)
    }

    private fun linearSystem(lv: Int): ChallengeQuestion {
        val x = r(-30, 60).toLong(); val y = r(-30, 60).toLong()
        var a1: Long; var b1: Long; var a2: Long; var b2: Long
        do {
            a1 = nz(-9, 12).toLong(); b1 = nz(-9, 12).toLong(); a2 = nz(-9, 12).toLong(); b2 = nz(-9, 12).toLong()
        } while (a1 * b2 - a2 * b1 == 0L)
        val c1 = a1 * x + b1 * y; val c2 = a2 * x + b2 * y
        fun eq(a: Long, b: Long, c: Long) = "${a}x ${if (b >= 0) "+ $b" else "− ${-b}"}y = $c"
        val askX = rnd.nextBoolean()
        return q("解方程组：${eq(a1, b1, c1)}；${eq(a2, b2, c2)}。${if (askX) "x" else "y"} = ?",
            if (askX) x else y, "x = $x，y = $y", 240)
    }

    private fun powerOf(lv: Int): ChallengeQuestion {
        val base = oneOf(2L, 3L, 5L, 6L, 7L, 11L, 12L)
        val maxE = when (base) { 2L -> 30; 3L -> 18; 5L -> 12; 6L -> 10; 7L -> 9; else -> 7 }
        val e = r(maxE / 2, maxE - (3 - lv))
        var v = 1L; repeat(e) { v *= base }
        return q("计算：$base 的 $e 次方 = ?", v, "$base^$e = $v", 150)
    }

    private fun squareDifference(lv: Int): ChallengeQuestion {
        val a = when (lv) { 1 -> r(50, 199); 2 -> r(200, 999); else -> r(1000, 4999) }.toLong()
        val b = r(a / 3, a - 1)
        return q("计算：$a² − $b² = ?", a * a - b * b, "(a+b)(a−b) = ${a + b}×${a - b} = ${a * a - b * b}", 150)
    }

    /** 勾股数：由 (m, n) 生成，保证整数。 */
    private fun pythagoras(lv: Int): ChallengeQuestion {
        val m = r(2, 12 + lv * 4).toLong(); val n = r(1L, m - 1); val k = r(1, 3 + lv).toLong()
        val a = k * (m * m - n * n); val b = k * 2 * m * n; val c = k * (m * m + n * n)
        return if (rnd.nextBoolean()) q("直角三角形两直角边为 $a 和 $b，斜边长是多少？", c, "√($a²+$b²) = $c", 180)
        else q("直角三角形斜边为 $c，一条直角边为 $a，另一条直角边长是多少？", b, "√($c²−$a²) = $b", 180)
    }

    private fun signedArithmetic(lv: Int): ChallengeQuestion {
        val a = r(-999, 999).toLong(); val b = r(-99, 99).toLong(); val c = r(-9999, 9999).toLong()
        val v = a * b - c * (if (lv >= 3) 3 else 1)
        val k = if (lv >= 3) " × 3" else ""
        return q("计算：${signed(a)} × ${signed(b)} − ${signed(c)}$k = ?", v, "注意符号：结果为 $v", 180)
    }

    private fun gcdLcm(lv: Int): ChallengeQuestion {
        val g = r(6, 60).toLong(); var x: Long; var y: Long
        do { x = r(3, 40).toLong(); y = r(3, 40).toLong() } while (gcd(x, y) != 1L || x == y)
        val a = g * x; val b = g * y
        return if (rnd.nextBoolean() || lv == 1) q("$a 和 $b 的最大公因数是多少？", g, "$a = $g×$x，$b = $g×$y", 150)
        else q("$a 和 $b 的最小公倍数是多少？", g * x * y, "lcm = $a×$b ÷ gcd($g) = ${g * x * y}", 180)
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) abs(a) else gcd(b, a % b)

    private fun polynomialValue(lv: Int): ChallengeQuestion {
        val a = nz(-9, 9).toLong(); val b = r(-30, 30).toLong(); val c = r(-200, 200).toLong()
        val x = nz(-15, 15).toLong() * (if (lv >= 3) 2 else 1)
        val v = a * x * x + b * x + c
        return q("当 x = $x 时，${a}x² ${if (b >= 0) "+ $b" else "− ${-b}"}x ${if (c >= 0) "+ $c" else "− ${-c}"} 的值是多少？",
            v, "代入计算得 $v", 180)
    }

    // ══════════ 高中 ══════════

    private fun quadraticRoots(lv: Int): ChallengeQuestion {
        var p: Long; var s: Long
        do { p = r(-40, 40).toLong(); s = r(-40, 40).toLong() } while (p == s)
        val k = r(1, if (lv >= 3) 4 else 2).toLong()
        val b = -k * (p + s); val c = k * p * s
        val ask = oneOf("较大根", "较小根", "两根之和", "两根之积")
        val ans = when (ask) { "较大根" -> maxOf(p, s); "较小根" -> minOf(p, s); "两根之和" -> p + s; else -> p * s }
        return q("方程 ${k}x² ${if (b >= 0) "+ $b" else "− ${-b}"}x ${if (c >= 0) "+ $c" else "− ${-c}"} = 0 的$ask 是多少？",
            ans, "两根为 $p 和 $s", 210)
    }

    private fun logarithm(lv: Int): ChallengeQuestion {
        val base = oneOf(2L, 3L, 4L, 5L, 6L, 7L, 9L)
        val e = r(3, if (base <= 3) 14 + lv else 6 + lv)
        var v = 1L; repeat(e) { v *= base }
        return if (rnd.nextBoolean()) q("log₍$base₎ $v = ?", e.toLong(), "$base^$e = $v", 150)
        else {
            val f = r(2, 5); var w = 1L; repeat(f) { w *= base }
            q("计算：log₍$base₎ $v + log₍$base₎ $w = ?", (e + f).toLong(), "= log₍$base₎ ${v}×$w = ${e + f}", 180)
        }
    }

    private fun arithmeticSum(lv: Int): ChallengeQuestion {
        val a1 = r(-50, 200).toLong(); val d = nz(-15, 30).toLong(); val n = r(20, 60 + lv * 40).toLong()
        val sum = n * (2 * a1 + (n - 1) * d) / 2
        return q("等差数列首项 $a1，公差 $d，前 $n 项和是多少？", sum, "Sₙ = n(2a₁+(n−1)d)/2 = $sum", 210)
    }

    private fun geometricTerm(lv: Int): ChallengeQuestion {
        val a1 = r(1, 9).toLong(); val qq = oneOf(2L, 3L, -2L, -3L); val n = r(6, 10 + lv * 2)
        var v = a1; repeat(n - 1) { v *= qq }
        return q("等比数列首项 $a1，公比 $qq，第 $n 项是多少？", v, "aₙ = a₁·q^(n−1) = $v", 180)
    }

    private fun comb(n: Long, k: Long): Long { var v = 1L; for (i in 1..k) v = v * (n - k + i) / i; return v }

    private fun combination(lv: Int): ChallengeQuestion {
        val n = r(10, 18 + lv * 4).toLong(); val k = r(2L, minOf(6L, n - 2))
        return q(oneOf("计算组合数 C($n, $k) = ?", "从 $n 人中选 $k 人，有多少种选法？"), comb(n, k), "C($n,$k) = ${comb(n, k)}", 180)
    }

    private fun permutation(lv: Int): ChallengeQuestion {
        val n = r(8, 14 + lv * 2).toLong(); val k = r(2, 4 + (lv - 1)).toLong()
        var v = 1L; for (i in 0 until k) v *= (n - i)
        return q("$n 人中选 $k 人排成一排，有多少种排法？", v, "A($n,$k) = $v", 180)
    }

    private fun absoluteInequalityCount(lv: Int): ChallengeQuestion {
        val c = r(-200, 200).toLong(); val w = r(15, 300 * lv).toLong()
        return q("满足 |x − ${signed(c)}| ≤ $w 的整数 x 有多少个？", 2 * w + 1, "x ∈ [${c - w}, ${c + w}]，共 ${2 * w + 1} 个", 150)
    }

    // ══════════ 大学 ══════════

    private fun derivativeAt(lv: Int): ChallengeQuestion {
        val a = nz(-6, 9).toLong(); val b = r(-20, 20).toLong(); val c = r(-50, 50).toLong(); val x = nz(-8, 8).toLong()
        val e = r(3, 4 + lv)
        var xp = 1L; repeat(e - 1) { xp *= x }
        val v = a * e * xp + 2 * b * x + c
        return q("f(x) = ${a}x^$e ${if (b >= 0) "+ $b" else "− ${-b}"}x² ${if (c >= 0) "+ $c" else "− ${-c}"}x，f′($x) = ?",
            v, "f′(x) = ${a * e}x^${e - 1} + ${2 * b}x + $c，代入得 $v", 210)
    }

    /** ∫₀ⁿ (a·x² + b·x) dx，选 n 使结果为整数：a·n³/3 + b·n²/2。 */
    private fun definiteIntegral(lv: Int): ChallengeQuestion {
        // n 取 6 的倍数：n³/3 与 n²/2 都是整数，结果必为整数
        val n = if (lv == 1) oneOf(6L, 12L) else oneOf(6L, 12L, 18L, 24L, 30L)
        val a = nz(-9, 9).toLong(); val b = nz(-20, 20).toLong()
        val v = a * n * n * n / 3 + b * n * n / 2
        return q("计算定积分 ∫₀^$n (${a}x² ${if (b >= 0) "+ $b" else "− ${-b}"}x) dx = ?", v,
            "= ${a}·$n³/3 + ${b}·$n²/2 = $v", 240)
    }

    private fun determinant2(lv: Int): ChallengeQuestion {
        val m = List(4) { r(-40 * lv, 60 * lv).toLong() }
        val v = m[0] * m[3] - m[1] * m[2]
        return q("计算二阶行列式 |${m[0]} ${m[1]}; ${m[2]} ${m[3]}| = ?", v, "ad − bc = $v", 150)
    }

    private fun determinant3(lv: Int): ChallengeQuestion {
        val m = List(9) { r(-9, 9 + lv * 3).toLong() }
        val v = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        return q("计算三阶行列式 |${m[0]} ${m[1]} ${m[2]}; ${m[3]} ${m[4]} ${m[5]}; ${m[6]} ${m[7]} ${m[8]}| = ?",
            v, "按第一行展开得 $v", 300)
    }

    private fun expectedValue(lv: Int): ChallengeQuestion {
        // 概率分母为 20：取值均为 20 的倍数，Σ x·p / 20 必为整数
        val ps = listOf(2L, 3L, 5L, 10L)
        val xs = List(4) { r(-10 * lv, 25 * lv).toLong() * 20 }
        val num = xs.zip(ps).sumOf { (x, p) -> x * p }
        return q("随机变量 X 取 ${xs.joinToString("、")} 的概率分别为 2/20、3/20、5/20、10/20，E(X) = ?",
            num / 20, "Σx·p = ${num}/20 = ${num / 20}", 240)
    }

    private fun limitRational(lv: Int): ChallengeQuestion {
        // lim(x→a) (x² − a²)/(x − a) = 2a 或 (x³ − a³)/(x − a) = 3a²
        val a = nz(-30, 30).toLong() * lv
        return if (rnd.nextBoolean()) q("求极限 lim(x→$a) (x² − ${a * a}) / (x − ${signed(a)}) = ?", 2 * a, "= x + a → ${2 * a}", 180)
        else q("求极限 lim(x→$a) (x³ − ${signed(a * a * a)}) / (x − ${signed(a)}) = ?", 3 * a * a, "= x² + ax + a² → ${3 * a * a}", 210)
    }

    private fun modularPower(lv: Int): ChallengeQuestion {
        val base = r(2, 19).toLong(); val e = r(20, 80 * lv).toLong(); val m = oneOf(7L, 11L, 13L, 17L, 19L, 23L)
        var v = 1L; var b = base % m; var k = e
        while (k > 0) { if (k and 1L == 1L) v = v * b % m; b = b * b % m; k = k shr 1 }
        return q("$base 的 $e 次方除以 $m 的余数是多少？", v, "快速幂取模得 $v", 240)
    }
}
