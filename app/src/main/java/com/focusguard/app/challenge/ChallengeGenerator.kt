package com.focusguard.app.challenge

import android.util.Log
import kotlin.math.abs
import kotlin.random.Random

data class ChallengeQuestion(
    val question: String = "",
    val answer: String = "",
    val explanation: String = ""
)

/**
 * 解锁挑战题目生成器（纯本地内置题库）。
 *
 * ## 为什么不再用 AI 出题
 * 1. **重复严重**：同一个提示词让模型反复出题，题型和数字高度雷同，
 *    还不如程序随机生成来得多样。
 * 2. **慢且不可靠**：锁机时用户急着答题，等 API 响应（最长 40 秒）
 *    体验极差；API 异常（401/超时）时更是完全出不来题。
 * 3. **浪费 token**：解锁答题跟屏幕检测抢同一份配额，不划算。
 *
 * ## 内置题库设计
 * 全部题目**答案由程序即时计算**，不存在硬编码错答案的可能。
 * 共 14 类生成器，覆盖运算、进制、数列、逻辑、时间、集合等维度，
 * 每类内部还有随机参数，实际组合数以万计，几乎不会撞题。
 *
 * 难度分三档，由 [difficulty] 控制：
 * - 1（简单）：可心算或稍加纸笔
 * - 2（中等）：需要认真算一会（默认）
 * - 3（困难）：多步推理，配合"连对 5 题"强度使用
 */
class ChallengeGenerator {

    companion object {
        private const val TAG = "ChallengeGenerator"

        /** 上一次出的题型，用于避免连续两题同类型。 */
        @Volatile
        private var lastKind: Int = -1
    }

    /**
     * 生成一道题目。
     *
     * @param difficulty 1=简单 2=中等 3=困难，超出范围会被收敛
     */
    fun generate(difficulty: Int = 2): ChallengeQuestion {
        val level = difficulty.coerceIn(1, 3)
        val kindCount = 14
        // 避免连续同类型，最多重掷 4 次
        var kind = Random.nextInt(kindCount)
        var retry = 0
        while (kind == lastKind && retry < 4) {
            kind = Random.nextInt(kindCount)
            retry++
        }
        lastKind = kind

        return try {
            when (kind) {
                0 -> bigAddition(level)
                1 -> bigSubtraction(level)
                2 -> multiplication(level)
                3 -> powerOfTwo(level)
                4 -> weekdayOffset(level)
                5 -> fibonacciNext(level)
                6 -> arithmeticSequence(level)
                7 -> squareDifference(level)
                8 -> percentCalc(level)
                9 -> divisionRemainder(level)
                10 -> binaryConvert(level)
                11 -> digitSum(level)
                12 -> ageLogic(level)
                13 -> countMultiples(level)
                else -> bigAddition(level)
            }
        } catch (e: Exception) {
            // 任何异常都不能让答题界面开不出题
            Log.w(TAG, "生成题目异常，退回加法：${e.message}")
            bigAddition(1)
        }
    }

    /** 兼容旧调用点。 */
    fun generateLocalQuestion(): ChallengeQuestion = generate(2)

    /** 判定用户作答是否正确，容忍全/半角、空格、单位后缀等常见差异。 */
    fun isAnswerCorrect(userAnswer: String, expected: String): Boolean {
        val a = normalize(userAnswer)
        val b = normalize(expected)
        if (a.isEmpty()) return false
        if (a == b) return true

        // 数字答案按数值比较，避免 "1024" 与 "1,024" 判错
        val na = a.toDoubleOrNull()
        val nb = b.toDoubleOrNull()
        return na != null && nb != null && abs(na - nb) < 1e-9
    }

    private fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
            .replace("，", "")
            .replace(",", "")
            .replace(" ", "")
            .replace("　", "")
            .replace("：", "")
            .replace(":", "")
        // 去掉常见后缀（可能叠加，循环去除）
        val suffixes = listOf("。", ".", "个", "元", "天", "岁", "次", "位", "人", "件")
        var changed = true
        while (changed) {
            changed = false
            for (suf in suffixes) {
                if (s.endsWith(suf) && s.length > suf.length) {
                    s = s.removeSuffix(suf)
                    changed = true
                }
            }
        }
        // 星期一/周一 视为等价
        s = s.replace("周", "星期")
        return s
    }

    // ── 题型 1：大数加法 ──────────────────────────────
    private fun bigAddition(level: Int): ChallengeQuestion {
        val range = when (level) {
            1 -> 1_000 to 9_999
            2 -> 100_000 to 999_999
            else -> 10_000_000 to 99_999_999
        }
        val a = Random.nextInt(range.first, range.second)
        val b = Random.nextInt(range.first, range.second)
        return ChallengeQuestion(
            question = "计算：$a + $b = ?",
            answer = (a.toLong() + b).toString(),
            explanation = "$a + $b = ${a.toLong() + b}"
        )
    }

    // ── 题型 2：大数减法 ──────────────────────────────
    private fun bigSubtraction(level: Int): ChallengeQuestion {
        val hi = when (level) {
            1 -> 9_999
            2 -> 999_999
            else -> 99_999_999
        }
        val lo = hi / 10
        val a = Random.nextInt(lo, hi)
        val b = Random.nextInt(lo / 2, a)
        return ChallengeQuestion(
            question = "计算：$a - $b = ?",
            answer = (a - b).toString(),
            explanation = "$a - $b = ${a - b}"
        )
    }

    // ── 题型 3：乘法 ─────────────────────────────────
    private fun multiplication(level: Int): ChallengeQuestion {
        val range = when (level) {
            1 -> 11 to 99
            2 -> 120 to 999
            else -> 1_200 to 9_999
        }
        val a = Random.nextInt(range.first, range.second)
        val b = Random.nextInt(range.first, range.second)
        return ChallengeQuestion(
            question = "计算：$a × $b = ?",
            answer = (a.toLong() * b).toString(),
            explanation = "$a × $b = ${a.toLong() * b}"
        )
    }

    // ── 题型 4：2 的幂 ───────────────────────────────
    private fun powerOfTwo(level: Int): ChallengeQuestion {
        val n = when (level) {
            1 -> Random.nextInt(6, 11)
            2 -> Random.nextInt(11, 17)
            else -> Random.nextInt(17, 25)
        }
        val value = 1L shl n
        return ChallengeQuestion(
            question = "计算：2 的 $n 次方等于多少？",
            answer = value.toString(),
            explanation = "2^$n = $value"
        )
    }

    // ── 题型 5：星期推算 ─────────────────────────────
    private fun weekdayOffset(level: Int): ChallengeQuestion {
        val names = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val startIndex = Random.nextInt(7)
        val days = when (level) {
            1 -> Random.nextInt(10, 60)
            2 -> Random.nextInt(60, 500)
            else -> Random.nextInt(500, 5_000)
        }
        val targetIndex = (startIndex + days) % 7
        return ChallengeQuestion(
            question = "如果今天是${names[startIndex]}，那么 $days 天后是星期几？（格式如：星期三）",
            answer = names[targetIndex],
            explanation = "$days ÷ 7 = ${days / 7} 余 ${days % 7}，" +
                "从${names[startIndex]}往后推 ${days % 7} 天即${names[targetIndex]}"
        )
    }

    // ── 题型 6：斐波那契下一项 ───────────────────────
    private fun fibonacciNext(level: Int): ChallengeQuestion {
        val length = when (level) {
            1 -> Random.nextInt(5, 7)
            2 -> Random.nextInt(7, 10)
            else -> Random.nextInt(10, 14)
        }
        val seq = mutableListOf(1L, 1L)
        while (seq.size < length + 1) {
            seq.add(seq[seq.size - 1] + seq[seq.size - 2])
        }
        val shown = seq.take(length)
        val next = seq[length]
        return ChallengeQuestion(
            question = "数列规律：${shown.joinToString(", ")}, ? 下一个数是多少？",
            answer = next.toString(),
            explanation = "斐波那契数列，每项为前两项之和：" +
                "${shown[length - 2]} + ${shown[length - 1]} = $next"
        )
    }

    // ── 题型 7：等差/等比数列第 n 项 ─────────────────
    private fun arithmeticSequence(level: Int): ChallengeQuestion {
        val first = Random.nextInt(2, 30)
        val diff = Random.nextInt(3, 20)
        val n = when (level) {
            1 -> Random.nextInt(8, 15)
            2 -> Random.nextInt(20, 60)
            else -> Random.nextInt(80, 300)
        }
        val value = first.toLong() + (n - 1) * diff
        return ChallengeQuestion(
            question = "一个等差数列首项是 $first，公差是 $diff，第 $n 项是多少？",
            answer = value.toString(),
            explanation = "第 n 项 = 首项 + (n-1)×公差 = $first + ${n - 1}×$diff = $value"
        )
    }

    // ── 题型 8：平方差 ───────────────────────────────
    private fun squareDifference(level: Int): ChallengeQuestion {
        val range = when (level) {
            1 -> 10 to 40
            2 -> 40 to 120
            else -> 120 to 400
        }
        val a = Random.nextInt(range.first, range.second)
        val b = Random.nextInt(range.first / 2, a)
        val value = a.toLong() * a - b.toLong() * b
        return ChallengeQuestion(
            question = "计算：$a² − $b² = ?",
            answer = value.toString(),
            explanation = "利用平方差公式 (a+b)(a−b) = (${a + b})×(${a - b}) = $value"
        )
    }

    // ── 题型 9：百分比计算 ───────────────────────────
    private fun percentCalc(level: Int): ChallengeQuestion {
        // 保证结果为整数，避免小数比对麻烦
        val percent = listOf(5, 12, 15, 20, 25, 36, 40, 45, 60, 75).random()
        val base = when (level) {
            1 -> Random.nextInt(2, 20) * 100
            2 -> Random.nextInt(20, 200) * 100
            else -> Random.nextInt(200, 2_000) * 100
        }
        val value = base.toLong() * percent / 100
        return ChallengeQuestion(
            question = "计算：$base 的 $percent% 是多少？",
            answer = value.toString(),
            explanation = "$base × $percent ÷ 100 = $value"
        )
    }

    // ── 题型 10：带余除法 ────────────────────────────
    private fun divisionRemainder(level: Int): ChallengeQuestion {
        val divisor = when (level) {
            1 -> Random.nextInt(3, 10)
            2 -> Random.nextInt(7, 30)
            else -> Random.nextInt(23, 97)
        }
        val quotient = when (level) {
            1 -> Random.nextInt(10, 100)
            2 -> Random.nextInt(100, 2_000)
            else -> Random.nextInt(1_000, 50_000)
        }
        val remainder = Random.nextInt(0, divisor)
        val dividend = divisor.toLong() * quotient + remainder
        return ChallengeQuestion(
            question = "$dividend 除以 $divisor，余数是多少？",
            answer = remainder.toString(),
            explanation = "$dividend = $divisor × $quotient + $remainder，故余数为 $remainder"
        )
    }

    // ── 题型 11：进制转换 ────────────────────────────
    private fun binaryConvert(level: Int): ChallengeQuestion {
        val value = when (level) {
            1 -> Random.nextInt(8, 64)
            2 -> Random.nextInt(64, 1_024)
            else -> Random.nextInt(1_024, 65_536)
        }
        // 随机决定方向：二进制→十进制 或 十进制→二进制
        return if (Random.nextBoolean()) {
            val binary = value.toString(2)
            ChallengeQuestion(
                question = "二进制数 $binary 转换成十进制是多少？",
                answer = value.toString(),
                explanation = "按位权展开求和，$binary(2) = $value(10)"
            )
        } else {
            val binary = value.toString(2)
            ChallengeQuestion(
                question = "十进制数 $value 转换成二进制是多少？（只填 0/1 数字）",
                answer = binary,
                explanation = "不断除 2 取余再逆序，$value(10) = $binary(2)"
            )
        }
    }

    // ── 题型 12：数位之和 ────────────────────────────
    private fun digitSum(level: Int): ChallengeQuestion {
        val digits = when (level) {
            1 -> 5
            2 -> 8
            else -> 12
        }
        val sb = StringBuilder()
        // 首位不为 0
        sb.append(Random.nextInt(1, 10))
        repeat(digits - 1) { sb.append(Random.nextInt(0, 10)) }
        val numStr = sb.toString()
        val sum = numStr.sumOf { it - '0' }
        return ChallengeQuestion(
            question = "数字 $numStr 的各位数字之和是多少？",
            answer = sum.toString(),
            explanation = "${numStr.toCharArray().joinToString(" + ")} = $sum"
        )
    }

    // ── 题型 13：年龄逻辑题 ──────────────────────────
    private fun ageLogic(level: Int): ChallengeQuestion {
        val childAge = Random.nextInt(6, 20)
        val multiple = when (level) {
            1 -> 2
            2 -> Random.nextInt(3, 5)
            else -> Random.nextInt(4, 7)
        }
        val parentAge = childAge * multiple
        val years = Random.nextInt(3, 25)
        val futureChild = childAge + years
        val futureParent = parentAge + years
        return ChallengeQuestion(
            question = "今年儿子 $childAge 岁，父亲的年龄是儿子的 $multiple 倍。" +
                "$years 年后，父亲比儿子大多少岁？",
            answer = (parentAge - childAge).toString(),
            explanation = "父亲今年 ${parentAge} 岁，年龄差恒为 ${parentAge - childAge} 岁。" +
                "$years 年后两人分别 $futureParent 岁和 $futureChild 岁，差值不变。"
        )
    }

    // ── 题型 14：区间内倍数计数 ──────────────────────
    private fun countMultiples(level: Int): ChallengeQuestion {
        val divisor = listOf(3, 4, 6, 7, 8, 9, 11, 13).random()
        val upper = when (level) {
            1 -> Random.nextInt(50, 200)
            2 -> Random.nextInt(200, 2_000)
            else -> Random.nextInt(2_000, 20_000)
        }
        val count = upper / divisor
        return ChallengeQuestion(
            question = "1 到 $upper 之间（含两端），有多少个 $divisor 的倍数？",
            answer = count.toString(),
            explanation = "$upper ÷ $divisor = $count（向下取整），故有 $count 个"
        )
    }
}
