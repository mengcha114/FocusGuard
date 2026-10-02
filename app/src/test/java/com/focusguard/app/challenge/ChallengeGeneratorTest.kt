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
            val q = gen.generate(3, grade = GradeStore.Grade.COLLEGE)
            assertTrue(gen.isAnswerCorrect(q.answer, q.answer))
            assertTrue(gen.isAnswerCorrect(" ${q.answer} ", q.answer))
            assertFalse(gen.isAnswerCorrect((q.answer.toLong() + 1).toString(), q.answer))
        }
    }

    @Test
    fun primaryGradeOnlyUsesArithmeticKinds() {
        repeat(600) {
            val q = gen.generate(2, grade = GradeStore.Grade.PRIMARY)
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

    @Test
    fun questionBankContainsHighSchoolMathAndScience() {
        val hsMath = QuestionBank.find(GradeStore.Grade.SENIOR, GradeStore.Stream.ALL).filter { it.subject == "数学" }
        assertTrue("高中数学真题不足", hsMath.size >= 8)
        val hsScience = QuestionBank.find(GradeStore.Grade.SENIOR, GradeStore.Stream.SCIENCE).filter { it.subject in listOf("物理", "化学", "生物") }
        assertTrue("高中理科综合真题不足", hsScience.size >= 15)
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
        repeat(150) {
            val q = g.generate(2, grade = GradeStore.Grade.SENIOR)
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
        assertTrue(store.set(GradeStore.Grade.JUNIOR, locked = false))
        assertFalse(store.set(GradeStore.Grade.PRIMARY, locked = false))
        // 允许在同年级切换选科
        assertTrue(store.set(GradeStore.Grade.JUNIOR, GradeStore.Stream.SCIENCE, locked = false))
        assertFalse(store.set(GradeStore.Grade.SENIOR, locked = true))
        assertTrue(store.set(GradeStore.Grade.SENIOR, GradeStore.Stream.HUMANITIES, locked = false))
        assertEquals(GradeStore.Grade.SENIOR, store.grade)
        assertEquals(GradeStore.Stream.HUMANITIES, store.stream)
        assertEquals(listOf(GradeStore.Grade.COLLEGE), store.selectable())
    }
}
