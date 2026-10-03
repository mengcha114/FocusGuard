package com.focusguard.app.challenge

import java.security.SecureRandom

/**
 * 通用版题库：刻意啰嗦的多步计算题。
 *
 * 设计目标不是考知识，而是**逼用户动手算**：答案必为整数，题干包含多步运算
 * （大数乘除、括号嵌套、百分比与分数互化、去尾/进一），强度 2（连对 5 题）时步数更多。
 * 全部由程序生成，无需联网、无需题库资源。
 */
object GeneralQuestions {

    private val rnd = SecureRandom()

    private fun r(lo: Long, hi: Long): Long {
        val a = minOf(lo, hi); val b = maxOf(lo, hi)
        return a + (rnd.nextDouble() * (b - a + 1)).toLong().coerceIn(0L, b - a)
    }
    private fun digits(n: Int): Long { var v = r(1, 9); repeat(n - 1) { v = v * 10 + r(0, 9) }; return v }
    private fun oneOf(vararg xs: Long): Long = xs[rnd.nextInt(xs.size)]
    private fun nz(lo: Long, hi: Long): Long { var v = 0L; while (v == 0L) v = r(lo, hi); return v }

    /**
     * @param strength 解锁强度（1 单题 / 2 连对 5 题），决定步数与规模
     */
    fun generate(strength: Int, excludeTopic: String? = null): ChallengeQuestion {
        val hard = strength >= 2
        val pool = if (hard) listOf("mix", "chainMul", "percent", "fraction")
        else listOf("mix", "paren", "percent")
        val kinds = pool.filter { it != excludeTopic }.ifEmpty { pool }
        return when (kinds[rnd.nextInt(kinds.size)]) {
            "chainMul" -> chainMul(hard)
            "percent" -> percent(hard)
            "fraction" -> fractionMix(hard)
            "paren" -> paren(hard)
            else -> mixed(hard)
        }
    }

    private fun q(text: String, ans: Long, exp: String, sec: Int) =
        ChallengeQuestion(question = text, answer = ans.toString(), explanation = exp,
            kind = "general", timeLimitSec = sec, subject = "计算")

    /** 多步混合：a×b + c÷d − e，除法保证整除。 */
    private fun mixed(hard: Boolean): ChallengeQuestion {
        val a = digits(if (hard) 3 else 2)
        val b = digits(if (hard) 3 else 2)
        val d = digits(2)
        val quo = digits(if (hard) 3 else 2)
        val c = d * quo
        val e = digits(if (hard) 5 else 4)
        val ans = a * b + c / d - e
        return q(
            "计算（结果取整数）：$a × $b + $c ÷ $d − $e = ?",
            ans,
            "$a×$b = ${a * b}；$c÷$d = $quo；${a * b} + $quo − $e = $ans",
            if (hard) 200 else 150
        )
    }

    /** 连乘连除：a × b ÷ c × d，保证整除。 */
    private fun chainMul(hard: Boolean): ChallengeQuestion {
        val c = digits(2)
        val k = digits(2)
        val a = c * k
        val b = digits(if (hard) 3 else 2)
        val d = digits(if (hard) 2 else 1)
        val ans = a * b / c * d
        return q(
            "计算：$a × $b ÷ $c × $d = ?",
            ans,
            "$a×$b = ${a * b}；÷$c = ${a * b / c}；×$d = $ans",
            180
        )
    }

    /** 括号嵌套 + 去尾/进一。 */
    private fun paren(hard: Boolean): ChallengeQuestion {
        val a = digits(3)
        val b = digits(if (hard) 3 else 2)
        val c = digits(2)
        val d = digits(if (hard) 4 else 3)
        val inner = (a - b) * c
        val ans = if (hard) inner + d else inner - (d % 1000)
        return q(
            if (hard) "计算：($a − $b) × $c + $d = ?" else "计算：($a + $b) × $c − ${d % 1000} = ?",
            if (hard) ans else (a + b) * c - (d % 1000),
            if (hard) "括号内 ${a - b}，×$c 得 $inner，再加 $d 得 $ans"
            else "括号内 ${a + b}，×$c 得 ${(a + b) * c}，再减 ${d % 1000}",
            180
        )
    }

    /** 百分比多步：base 的 p% 加上 base 的 q%。 */
    private fun percent(hard: Boolean): ChallengeQuestion {
        val p = oneOf(12, 15, 24, 35, 45, 64).toInt()
        val qq = oneOf(8, 16, 25, 32, 48).toInt()
        val base = if (hard) r(1000, 9999) * 100 else r(100, 999) * 100
        val ans = base * (p + qq) / 100
        return q(
            "某数 $base，先取它的 $p%，再加上它的 $qq%，结果是多少？",
            ans,
            "$base × ($p+$qq)% = $base × ${p + qq} ÷ 100 = $ans",
            if (hard) 200 else 150
        )
    }

    /** 分数与整数混合：base ÷ denom × num + rest，保证整除。 */
    private fun fractionMix(hard: Boolean): ChallengeQuestion {
        val den = oneOf(3, 4, 6, 8, 12, 16).toLong()
        val num = r(5, if (hard) 23 else 13)
        val unit = digits(4) * den
        val rest = digits(if (hard) 5 else 4)
        val ans = unit / den * num + rest
        return q(
            "计算：$unit ÷ $den × $num + $rest = ?",
            ans,
            "$unit÷$den = ${unit / den}；×$num = ${unit / den * num}；+$rest = $ans",
            200
        )
    }
}
