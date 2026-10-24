package com.focusguard.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * 定时锁机：一条规则 = 「每周某几天 · HH:mm 开始 · 锁 N 分钟 · 强度」。
 *
 * 为什么不走 AlarmManager：守护服务（LockGuardService）本来就在前台常驻、每 300ms
 * 巡检一次，直接在 guardTick 里判断即可 —— 免掉 Android 12+「精确闹钟」权限的麻烦，
 * 也不会因为闹钟被厂商清理而漏触发。
 *
 * 防漏洞的关键都在 [isDue] / [Settings.scheduledLastFired] 这一对：
 * - 触发窗口只有 [WINDOW_MS] 宽 ⇒ 服务被杀/手机休眠几小时后恢复，不会"补上一小时的锁"；
 * - 每条规则记「上次触发时间」⇒ 300ms 轮询不会重复上锁；
 * - 已锁机时再触发 ⇒ `LockState.startLock` 只延长/加码，不会被短锁覆盖（原有逻辑）。
 */
data class ScheduledLockRule(
    val id: String = UUID.randomUUID().toString(),
    /** 生效的星期：1=周一 … 7=周日 */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val hour: Int = 21,
    val minute: Int = 30,
    val minutes: Int = 60,
    /** 解锁强度（1~4，与锁机设置一致） */
    val strength: Int = 2,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("days", JSONArray(days.sorted()))
        put("hour", hour)
        put("minute", minute)
        put("minutes", minutes)
        put("strength", strength)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): ScheduledLockRule? = runCatching {
            val daysArr = o.optJSONArray("days")
            val days = mutableSetOf<Int>()
            if (daysArr != null) for (i in 0 until daysArr.length()) days.add(daysArr.getInt(i))
            ScheduledLockRule(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                days = days.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) },
                hour = o.optInt("hour", 21).coerceIn(0, 23),
                minute = o.optInt("minute", 30).coerceIn(0, 59),
                minutes = o.optInt("minutes", 60).coerceIn(5, 24 * 60),
                strength = o.optInt("strength", 2).coerceIn(1, 4),
                enabled = o.optBoolean("enabled", true)
            )
        }.getOrNull()

        fun listToJson(rules: List<ScheduledLockRule>): String {
            val arr = JSONArray()
            rules.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): List<ScheduledLockRule> = runCatching {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }
}

object ScheduledLock {

    /** 触发窗口：开始后的 2 分钟内都算"该锁了"，错过这个窗口不再补锁。 */
    const val WINDOW_MS = 2 * 60_000L

    /** 同一规则两次触发的最小间隔（防 300ms 轮询重复上锁 / 防时钟回拨重复触发）。 */
    const val MIN_GAP_MS = 30 * 60_000L

    /** 星期几（1=周一 … 7=周日）。 */
    private fun weekdayOf(cal: Calendar): Int {
        val dow = cal.get(Calendar.DAY_OF_WEEK) // 1=周日 … 7=周六
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    /** 某天的 HH:mm 时间戳（毫秒）。 */
    private fun atTime(base: Calendar, hour: Int, minute: Int): Long {
        val c = base.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * 现在是否该触发这条规则（纯函数，便于单测）。
     *
     * 同时检查「今天」和「昨天」两个候选开始时刻，覆盖跨零点的情况
     * （例如 23:59 开始、窗口 2 分钟 ⇒ 00:00 也应该触发）。
     */
    fun isDue(nowMs: Long, rule: ScheduledLockRule): Boolean {
        if (!rule.enabled) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        for (dayOffset in 0..1) {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_MONTH, -dayOffset)
            if (weekdayOf(c) !in rule.days) continue
            val start = atTime(c, rule.hour, rule.minute)
            if (nowMs >= start && nowMs < start + WINDOW_MS) return true
        }
        return false
    }

    /** 距离下一次触发还有多久（毫秒，>0）；永不触发返回 null。界面显示"下次：今天 21:30"用。 */
    fun nextTriggerInMs(nowMs: Long, rule: ScheduledLockRule): Long? {
        if (!rule.enabled || rule.days.isEmpty()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        var best: Long? = null
        for (dayOffset in 0..8) {                 // 往后看 8 天肯定能覆盖到
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_MONTH, dayOffset)
            if (weekdayOf(c) !in rule.days) continue
            val start = atTime(c, rule.hour, rule.minute)
            if (start + WINDOW_MS <= nowMs) continue
            val delta = (start - nowMs).coerceAtLeast(0L)
            if (best == null || delta < best!!) best = delta
        }
        return best
    }

    /** 人类可读的"下次触发"，例如「今天 21:30」「明天 07:00」。 */
    fun nextTriggerText(nowMs: Long, rule: ScheduledLockRule): String {
        val delta = nextTriggerInMs(nowMs, rule) ?: return "未启用"
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val target = Calendar.getInstance().apply { timeInMillis = nowMs + delta }
        val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        val hm = String.format("%02d:%02d", rule.hour, rule.minute)
        return if (sameDay) "今天 $hm" else {
            val tomorrow = now.clone() as Calendar
            tomorrow.add(Calendar.DAY_OF_MONTH, 1)
            val isTomorrow = tomorrow.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                tomorrow.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
            if (isTomorrow) "明天 $hm" else {
                val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                "${names[weekdayOf(target) - 1]} $hm"
            }
        }
    }
}
