package com.focusguard.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LogStore

/**
 * 主线程卡顿探针（ANR 取证）。
 *
 * 背景：用户反馈「第二次打开就闪退、且没有任何崩溃日志」。ANR（主线程卡住被系统杀掉）
 * **不会**走未捕获异常处理器，所以崩溃日志里什么都看不到。这里每秒往主线程投一个探针，
 * 量它的往返延迟；超过阈值就把「主线程卡住 N 秒」写进启动轨迹与检测日志——
 * 这样即使进程随后被系统杀掉，下次打开也能看到是卡在哪。
 *
 * 开销：每秒一次 post，可忽略。
 */
object MainThreadWatch {

    private const val TAG = "MainThreadWatch"
    private const val INTERVAL_MS = 1_000L
    private const val WARN_MS = 6_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    private val probe = object : Runnable {
        override fun run() {
            val sentAt = android.os.SystemClock.elapsedRealtime()
            handler.post {
                val delay = android.os.SystemClock.elapsedRealtime() - sentAt
                if (delay >= WARN_MS) {
                    report(appContext, delay)
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private var appContext: Context? = null

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        handler.postDelayed(probe, INTERVAL_MS)
    }

    private var lastReportAt = 0L

    private fun report(context: Context?, delayMs: Long) {
        val app = context ?: return
        val now = System.currentTimeMillis()
        if (now - lastReportAt < 5 * 60_000L) return
        lastReportAt = now
        val seconds = delayMs / 1000
        Log.w(TAG, "主线程卡顿 ${seconds}s")
        StartupTrace.mark(app, "main.blocked ${seconds}s")
        runCatching {
            LogStore(app).addLog(
                DetectionLog(
                    classification = "NEUTRAL",
                    confidence = 1f,
                    reason = "主线程卡顿 ${seconds} 秒（很可能是被系统判为无响应而结束进程）",
                    action = "NONE",
                    source = "ERROR",
                    appLabel = ""
                )
            )
        }
    }
}
