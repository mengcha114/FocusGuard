package com.focusguard.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LogStore

/**
 * 主线程卡顿探针（ANR 取证）。
 *
 * 关键设计：**判定放在后台线程**。
 * 早期版本是"主线程自己测自己"——主线程一旦彻底卡死，探针自己也跑不起来，
 * 于是什么都记不到（这正是用户「打开就闪退、且没有任何日志」时我们一无所获的原因）。
 * 现在：后台线程每秒 ping 一次主线程，并在后台判断"上一次 ping 有没有被消费"；
 * 超过阈值就从后台抓主线程堆栈写进轨迹与检测日志，即使主线程永远醒不过来也能记录。
 */
object MainThreadWatch {

    private const val TAG = "MainThreadWatch"
    private const val INTERVAL_MS = 1_000L
    private const val WARN_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var ackAt = 0L
    @Volatile private var started = false
    @Volatile private var lastReportAt = 0L

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        ackAt = SystemClock.elapsedRealtime()
        Thread {
            while (true) {
                mainHandler.post { ackAt = SystemClock.elapsedRealtime() }
                Thread.sleep(INTERVAL_MS)
                val lag = SystemClock.elapsedRealtime() - ackAt
                if (lag >= WARN_MS) report(app, lag)
            }
        }.apply { name = "main-thread-watch"; isDaemon = true }.start()
    }

    private fun report(app: Context, lagMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastReportAt < 3 * 60_000L) return
        lastReportAt = now
        val seconds = lagMs / 1000
        Log.w(TAG, "主线程卡顿 ${seconds}s")
        val stack = runCatching {
            Thread.getAllStackTraces()[Looper.getMainLooper().thread]
                ?.take(16)?.joinToString("\n") { "    at $it" }.orEmpty()
        }.getOrDefault("")
        StartupTrace.mark(app, "main.blocked ${seconds}s")
        runCatching {
            LogStore(app).addLog(
                DetectionLog(
                    classification = "NEUTRAL",
                    confidence = 1f,
                    reason = "主线程卡顿 ${seconds} 秒（会被系统判无响应而结束进程）。主线程栈：\n$stack",
                    action = "NONE",
                    source = "ERROR",
                    appLabel = ""
                )
            )
        }
    }
}
