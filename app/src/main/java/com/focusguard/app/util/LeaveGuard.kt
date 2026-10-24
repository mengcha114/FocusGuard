package com.focusguard.app.util

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 「我们主动离开应用」的全局标记。
 *
 * 为什么需要它：为了满足「退出后立刻从最近任务消失」，`MainActivity.onUserLeaveHint`
 * 会 `finishAndRemoveTask()`。但很多正常操作也会离开前台——跳浏览器下载、分享/导出日志、
 * 系统设置页、录屏授权框……这些如果被当成"用户退出"，整个任务会被移除，
 * 而且**不会产生崩溃日志**（我们自己杀的进程），用户看到的就是「点一下就闪退」。
 *
 * 所以凡是"去开别人"的入口都必须先 [markLeaving]，15 秒内的离开一律不隐藏。
 */
object LeaveGuard {

    private const val WINDOW_MS = 15_000L

    @Volatile
    private var expectedAt = 0L

    fun markLeaving() {
        expectedAt = SystemClock.elapsedRealtime()
    }

    /** 是否是"我们自己刚跳出去"导致的离开。 */
    fun isExpected(): Boolean {
        val at = expectedAt
        if (at == 0L) return false
        return SystemClock.elapsedRealtime() - at in 0..WINDOW_MS
    }

    /** 打开外部界面并打标记；返回是否真的打开了。 */
    fun open(context: Context, intent: Intent): Boolean = runCatching {
        markLeaving()
        context.startActivity(
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
