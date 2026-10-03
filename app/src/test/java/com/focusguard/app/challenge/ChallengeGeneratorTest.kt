package com.focusguard.app.challenge

import com.focusguard.app.data.GradeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeGeneratorTest {

    private val gen = ChallengeGenerator()
    private val primaryKinds = setOf(
        "add", "sub", "chainAdd", "chainSub", "mixAddSub", "mul", "div", "rem", "mixOp",
        "paren", "square", "percent", "multiples", "story", "unit"
    )

    @Test
    fun everyAnswerIsAnIntegerTypableOnKeypad() {
        for (g in GradeStore.Grade.entries) for (lv in 1..3) repeat(400) {
            val q = gen.generate(lv, numericOnly = true, grade = g)
            assertTrue("${q.kind}: '${q.answer}' for ${q.question}", q.answer.matches(Regex("-?\\d+")))
            assertTrue(q.question.isNotBlank())
        }
    }

    @Test
    fun answerChecksAcceptOwnAnswerAndRejectOffByOne() {
        repeat(500) {
            val q = gen.generate(3, numericOnly = true, grade = GradeStore.Grade.COLLEGE)
            assertTrue(gen.isAnswerCorrect(q.answer, q.answer))
            assertTrue(gen.isAnswerCorrect(" ${q.answer} ", q.answer))
            val n = q.answer.toLongOrNull()
            if (n != null) {
                assertFalse(gen.isAnswerCorrect((n + 1).toString(), q.answer))
            }
        }
    }

    @Test
    fun primaryGradeOnlyUsesArithmeticKinds() {
        repeat(600) {
            val q = gen.generate(2, numericOnly = true, grade = GradeStore.Grade.PRIMARY)
            assertTrue(q.kind, q.kind in primaryKinds)
        }
    }

    @Test
    fun higherGradesIncludeTheirOwnKinds() {
        val seen = HashSet<String>()
        repeat(800) { seen += gen.generate(2, numericOnly = true, grade = GradeStore.Grade.COLLEGE).kind }
        assertTrue("college kinds missing: $seen", seen.any { it in setOf("deriv", "integral", "det2", "det3") })
        assertTrue("too few kinds: ${seen.size}", seen.size >= 7)
    }

    private fun item(g: Int, s: String, st: String, d: Int = 2, a: String = "B", t: String = s) =
        QuestionBank.Item(g, s, st, t, d, "$s 第$g 年级题 $t $d $a", listOf("A. 1", "B. 2", "C. 3", "D. 4"), a, "")

    @Test
    fun bankRespectsGradeAndStream() {
        QuestionBank.setForTest(
            List(50) { item(7, "数学", "ALL", t = "函数$it") } +
                List(5) { item(7, "物理", "SCIENCE") } + List(5) { item(7, "历史", "HUMANITIES") } +
                List(50) { item(6, "数学", "ALL") } + List(5) { item(8, "高数", "ALL") }
        )
        val sci = QuestionBank.find(null, GradeStore.Grade.SENIOR_3, GradeStore.Stream.SCIENCE)
        assertTrue(sci.none { it.subject == "历史" }); assertTrue(sci.any { it.subject == "物理" })
        val hum = QuestionBank.find(null, GradeStore.Grade.SENIOR_3, GradeStore.Stream.HUMANITIES)
        assertTrue(hum.none { it.subject == "物理" })
        // 不向上借：高二绝不出高三 / 大学题
        assertTrue(QuestionBank.find(null, GradeStore.Grade.SENIOR_2, GradeStore.Stream.ALL).all { it.grade <= 6 })
        // 答错降难度
        QuestionBank.setForTest(listOf(item(7, "数学", "ALL", d = 1), item(7, "数学", "ALL", d = 3, t = "x")))
        assertTrue(QuestionBank.find(null, GradeStore.Grade.SENIOR_3, GradeStore.Stream.ALL, maxDifficulty = 1).all { it.difficulty == 1 })
    }

    @Test
    fun multiSelectAnswerIsOrderInsensitive() {
        assertTrue(gen.isAnswerCorrect("CA", "AC"))
        assertTrue(gen.isAnswerCorrect("a, c", "AC"))
        assertFalse(gen.isAnswerCorrect("A", "AC"))
        assertFalse(gen.isAnswerCorrect("ABC", "AC"))
    }

    @Test
    fun primaryIsStillHard() {
        // 小学档：加减至少 6 位数，乘法至少 3 位 × 3 位
        repeat(300) {
            val q = gen.generate(1, grade = GradeStore.Grade.PRIMARY)
            if (q.kind == "add" || q.kind == "sub") {
                val nums = Regex("\\d+").findAll(q.question).map { it.value }.toList()
                assertTrue(q.question, nums.first().length >= 6)
            }
        }
    }

    @Test
    fun noKindRepeatsWithinFiveAndNoQuestionRepeats() {
        val g = ChallengeGenerator()
        val kinds = ArrayList<String>()
        val texts = HashSet<String>()
        repeat(60) {
            val q = g.generate(2, numericOnly = true, grade = GradeStore.Grade.SENIOR_2)
            assertTrue("repeated question: ${q.question}", texts.add(q.question))
            kinds += q.kind
        }
        kinds.windowed(5).forEach { w -> assertEquals("kind repeated within 5: $w", w.size, w.toSet().size) }
    }

    @Test
    fun specificAnswersAreCorrect() {
        // 抽样核对几类题的答案计算（从题干反算）
        repeat(300) {
            val q = gen.generate(2, grade = GradeStore.Grade.PRIMARY)
            val n = Regex("\\d+").findAll(q.question).map { it.value.toLong() }.toList()
            when (q.kind) {
                "add" -> assertEquals(n[0] + n[1], q.answer.toLong())
                "sub" -> assertEquals(n[0] - n[1], q.answer.toLong())
                "mul" -> assertEquals(n[0] * n[1], q.answer.toLong())
                "div" -> assertEquals(n[0] / n[1], q.answer.toLong()).also { assertEquals(0L, n[0] % n[1]) }
                "rem" -> assertEquals(n[0] % n[1], q.answer.toLong())
            }
        }
    }

    @Test
    fun gradeCanOnlyBeRaised() {
        val store = GradeStore(com.focusguard.app.data.FakePrefs())
        assertTrue(store.set(GradeStore.Grade.JUNIOR_1, locked = false))
        assertFalse(store.set(GradeStore.Grade.PRIMARY, locked = false))
        // 允许在同年级切换选科
        assertTrue(store.set(GradeStore.Grade.JUNIOR_1, GradeStore.Stream.SCIENCE, locked = false))
        assertFalse(store.set(GradeStore.Grade.SENIOR_2, locked = true))
        assertTrue(store.set(GradeStore.Grade.SENIOR_2, GradeStore.Stream.HUMANITIES, locked = false))
        assertEquals(GradeStore.Grade.SENIOR_2, store.grade)
        assertEquals(GradeStore.Stream.HUMANITIES, store.stream)
        assertTrue(store.selectable().contains(GradeStore.Grade.COLLEGE))
    }
}
