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
        /** 常驻服务通知渠道（静默，IMPORTANCE_LOW）。 */
        const val CHANNEL_ID = "focus_guard_service"

        /**
         * 提醒通知渠道（IMPORTANCE_HIGH）——像微信/QQ 那样弹出横幅 + 提示音。
         * 用于"检测到娱乐"这类需要用户立刻注意的消息。
         */
        const val ALERT_CHANNEL_ID = "focus_guard_alert"

        /** 待办到期提醒通知渠道。 */
        const val MEMO_CHANNEL_ID = "focus_guard_memo"

        const val NOTIFICATION_ID = 1001

        /** 娱乐提醒通知 id（每次覆盖同一条，不堆积）。 */
        const val ALERT_NOTIFICATION_ID = 1005

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
        val manager = getSystemService(NotificationManager::class.java)

        // 常驻服务通知：静默，不打扰
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(serviceChannel)

        // 提醒通知：IMPORTANCE_HIGH 才会像微信/QQ 那样弹出横幅（Heads-up）
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "专注提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "检测到娱乐行为时弹出提醒"
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 220, 120, 220)
            enableLights(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(alertChannel)

        // 待办到期提醒：IMPORTANCE_HIGH 横幅 + 提示音，与专注提醒同级
        val memoChannel = NotificationChannel(
            MEMO_CHANNEL_ID,
            "待办到期提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "备忘录事项到达截止时间时提醒"
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 100, 180)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(memoChannel)
    }
}
