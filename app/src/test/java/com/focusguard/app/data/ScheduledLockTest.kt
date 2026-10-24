package com.focusguard.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 定时锁机的核心判定是纯函数，直接测真实实现（不 mock）。 */
class ScheduledLockTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, s)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 2026-10-26 是星期一。 */
    private val rule = ScheduledLockRule(
        id = "t", days = setOf(1, 2, 3, 4, 5), hour = 21, minute = 30, minutes = 60, strength = 2
    )

    @Test
    fun dueInsideWindow() {
        assertTrue(ScheduledLock.isDue(at(2026, 10, 26, 21, 30), rule))
        assertTrue(ScheduledLock.isDue(at(2026, 10, 26, 21, 31, 59), rule))
    }

    @Test
    fun notDueBeforeOrAfterWindow() {
        assertFalse(ScheduledLock.isDue(at(2026, 10, 26, 21, 29, 59), rule))
        assertFalse(ScheduledLock.isDue(at(2026, 10, 26, 21, 32), rule))
        assertFalse(ScheduledLock.isDue(at(2026, 10, 26, 20, 0), rule))
    }

    @Test
    fun notDueOnDisabledRule() {
        assertFalse(ScheduledLock.isDue(at(2026, 10, 26, 21, 30), rule.copy(enabled = false)))
    }

    @Test
    fun notDueOnOtherWeekday() {
        // 2026-10-25 是星期日，规则只配了周一到周五
        assertFalse(ScheduledLock.isDue(at(2026, 10, 25, 21, 30), rule))
        assertTrue(ScheduledLock.isDue(at(2026, 10, 24, 21, 30), rule.copy(days = setOf(6))))
    }

    @Test
    fun crossMidnightWindowFiresAfterMidnight() {
        // 23:59 开始、窗口 2 分钟 ⇒ 次日 00:00 仍应触发
        val late = ScheduledLockRule(id = "late", days = setOf(1), hour = 23, minute = 59)
        assertTrue(ScheduledLock.isDue(at(2026, 10, 27, 0, 0), late))
        assertFalse(ScheduledLock.isDue(at(2026, 10, 27, 0, 1, 30), late))
    }

    @Test
    fun repeatedTicksInWindowStillDue() {
        // isDue 本身幂等；防重复靠 Settings.scheduledLastFired + MIN_GAP_MS
        assertTrue(ScheduledLock.isDue(at(2026, 10, 26, 21, 30), rule))
        assertTrue(ScheduledLock.isDue(at(2026, 10, 26, 21, 30, 1), rule))
        assertTrue(ScheduledLock.MIN_GAP_MS >= 10 * 60_000L)
    }

    @Test
    fun nextTriggerPointsForward() {
        val now = at(2026, 10, 26, 22, 0)          // 周一 22:00，今天已过
        val delta = ScheduledLock.nextTriggerInMs(now, rule)
        assertNotNull(delta)
        val text = ScheduledLock.nextTriggerText(now, rule)
        assertTrue(text.contains("21:30"))
        assertFalse(text.contains("今天"))
    }

    @Test
    fun nextTriggerIsTodayWhenBefore() {
        val now = at(2026, 10, 26, 8, 0)
        assertTrue(ScheduledLock.nextTriggerText(now, rule).contains("今天"))
    }

    @Test
    fun disabledRuleHasNoNextTrigger() {
        assertTrue(ScheduledLock.nextTriggerInMs(at(2026, 10, 26, 8, 0), rule.copy(enabled = false)) == null)
    }

    // 注意：JSON 序列化不在这里测 —— JVM 单元测试里的 org.json 是空实现（未 mock），
    // 真机上才有真实实现。JSON 往返在设备上由设置页读写自然验证。

}
