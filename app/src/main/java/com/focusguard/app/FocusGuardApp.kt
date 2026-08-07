package com.focusguard.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FocusGuardApp : Application() {

    companion object {
        const val CHANNEL_ID = "focus_guard_service"
        const val NOTIFICATION_ID = 1001

        private const val TAG = "FocusGuardApp"
        private const val CRASH_FILE = "crash_log.txt"
        private const val MAX_CRASH_ENTRIES = 5

        /**
         * 读取崩溃日志全文（日志页展示用）。无崩溃记录返回空串。
         */
        fun readCrashLog(context: android.content.Context): String {
            return try {
                val f = File(context.filesDir, CRASH_FILE)
                if (f.exists()) f.readText() else ""
            } catch (e: Exception) {
                ""
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        installCrashHandler()
    }

    /**
     * 全局崩溃捕获：把崩溃栈写入应用私有目录 crash_log.txt，
     * 日志页可查看/导出——闪退问题从此不再盲猜。
     * 写入完成后交给原 handler（默认行为：终止进程）。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val entry = buildString {
                    append("===== ")
                    append(
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date())
                    )
                    append(" =====\n线程：")
                    append(thread.name)
                    append("\n")
                    append(sw)
                    append("\n")
                }
                val f = File(filesDir, CRASH_FILE)
                val existing = if (f.exists()) f.readText() else ""
                val lines = existing.split("===== ")
                // 只保留最近 N 条崩溃
                val kept = lines.takeLast(MAX_CRASH_ENTRIES + 1)
                    .joinToString("===== ")
                f.writeText(kept + "\n" + entry)
                Log.e(TAG, "已记录崩溃：${throwable.message}")
            } catch (e: Exception) {
                // 写崩溃日志本身失败则放弃
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
