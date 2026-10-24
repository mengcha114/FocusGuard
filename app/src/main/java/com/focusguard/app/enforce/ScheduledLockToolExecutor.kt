package com.focusguard.app.enforce

import android.content.Context
import com.focusguard.app.data.ScheduledLockRule
import com.focusguard.app.data.Settings

/**
 * AI 对话里的「定时锁机」工具：用户说「21 点锁我 2 小时」「每天 7:30 锁 1 小时」时，
 * 由模型输出一个文本标记，应用解析后**新增**一条定时锁机规则。
 *
 * 协议（回复末尾单独一行）：
 * - `__SCHEDULE_LOCK__:21:00|120`          今天晚上/下一次 21:00 起锁 120 分钟（每天）
 * - `__SCHEDULE_LOCK__:07:30|60|每天`      每天 7:30 起锁 60 分钟
 * - `__SCHEDULE_LOCK__:20:00|90|工作日`    工作日 20:00 起锁 90 分钟
 * - `__SCHEDULE_LOCK__:09:00|30|周末`      周末 9:00 起锁 30 分钟
 *
 * 防漏洞：**只允许新增**。取消/关闭计划属于放宽限制，必须由用户在「设置 → 定时锁机」里
 * 自己操作并答题验证；模型没有任何删除/关闭通道。
 */
object ScheduledLockToolExecutor {

    private val pattern = Regex(
        """__SCHEDULE_LOCK__:\s*(\d{1,2}):(\d{2})\s*\|\s*(\d{1,4})(?:\s*\|\s*([^\n|]{1,16}))?"""
    )

    data class Request(val hour: Int, val minute: Int, val minutes: Int, val daysLabel: String?)

    fun parse(reply: String): List<Request> = pattern.findAll(reply).mapNotNull { m ->
        val h = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val mi = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val mins = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
        if (h !in 0..23 || mi !in 0..59) return@mapNotNull null
        Request(h, mi, mins.coerceIn(5, 24 * 60), m.groupValues[4].trim().ifBlank { null })
    }.toList()

    fun stripMarkers(text: String): String =
        text.replace(Regex("""__SCHEDULE_LOCK__:[^\n]*"""), "")

    fun toolInstruction(): String = listOf(
        "定时锁机工具（用户要「X 点锁我 Y 小时 / 每天几点锁多久」这类**计划**时用；注意 1 小时 = 60 分钟）：",
        "- 「今晚 21 点锁我 2 小时」 ⇒ 输出 __SCHEDULE_LOCK__:21:00|120",
        "- 「每天 7:30 锁我 1 小时」 ⇒ 输出 __SCHEDULE_LOCK__:07:30|60|每天",
        "- 「工作日 20 点锁 90 分钟」 ⇒ 输出 __SCHEDULE_LOCK__:20:00|90|工作日",
        "- 「周末 9 点锁半小时」 ⇒ 输出 __SCHEDULE_LOCK__:09:00|30|周末",
        "- 只允许**新增**计划；用户要取消或关闭计划时，告诉他去「设置 → 定时锁机」里操作（那里需要答题验证）。" +
            "禁止自行输出任何删除/关闭指令。"
    ).joinToString("\n")

    /** 写入设置并保证守护在跑（到点由守护 300ms 巡检触发）；返回给用户看的说明。 */
    fun schedule(context: Context, req: Request): String {
        val days = when {
            req.daysLabel == null -> setOf(1, 2, 3, 4, 5, 6, 7)
            req.daysLabel.contains("工作日") || req.daysLabel.contains("周一至周五") -> setOf(1, 2, 3, 4, 5)
            req.daysLabel.contains("周末") || req.daysLabel.contains("周六") -> setOf(6, 7)
            else -> setOf(1, 2, 3, 4, 5, 6, 7)
        }
        val settings = Settings(context)
        val rule = ScheduledLockRule(
            days = days,
            hour = req.hour,
            minute = req.minute,
            minutes = req.minutes,
            strength = 2
        )
        settings.scheduledLocks = settings.scheduledLocks + rule
        com.focusguard.app.service.LockGuardService.ensureRunning(context)
        com.focusguard.app.service.GuardWatchdogWorker.schedule(context)
        val hm = "%02d:%02d".format(req.hour, req.minute)
        val dayText = when {
            days.size == 7 -> "每天"
            days == setOf(1, 2, 3, 4, 5) -> "工作日"
            else -> "周末"
        }
        val hours = req.minutes / 60
        val rest = req.minutes % 60
        val len = if (hours > 0 && rest > 0) "${hours} 小时 ${rest} 分钟"
        else if (hours > 0) "${hours} 小时" else "${rest} 分钟"
        return "已添加定时锁机：$dayText $hm 起锁 $len（可在「设置 → 定时锁机」查看）"
    }
}
