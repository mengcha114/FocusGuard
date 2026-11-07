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

        /** 待办到期提醒通知渠道（提示音 + 震动，也是升级前的默认渠道）。 */
        const val MEMO_CHANNEL_ID = "focus_guard_memo"

        /** 待办提醒的其余三种固定渠道，按「提示音 / 震动」组合划分。 */
        const val MEMO_CHANNEL_SILENT_ID = "focus_guard_memo_silent"
        const val MEMO_CHANNEL_SOUND_ID = "focus_guard_memo_sound"
        const val MEMO_CHANNEL_VIBRATE_ID = "focus_guard_memo_vibrate"

        /** 四个渠道统一的震动节奏。 */
        private val MEMO_VIBRATION = longArrayOf(0, 180, 100, 180)

        /**
         * 按用户勾选的提示音/震动组合挑固定渠道。
         * 复用旧渠道作为「提示音 + 震动」，老用户在该渠道里做的静音等调整不会丢。
         */
        fun memoChannelId(sound: Boolean, vibrate: Boolean): String = when {
            sound && vibrate -> MEMO_CHANNEL_ID
            sound -> MEMO_CHANNEL_SOUND_ID
            vibrate -> MEMO_CHANNEL_VIBRATE_ID
            else -> MEMO_CHANNEL_SILENT_ID
        }

        /**
         * 幂等创建待办提醒的四个固定渠道。
         * 渠道的声音/震动只能建渠道时定型，所以按组合预先建好，提醒时只选 id。
         */
        fun ensureMemoChannels(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(
                        MEMO_CHANNEL_ID, "待办到期提醒", NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "备忘录事项到达截止时间时提醒"
                        setShowBadge(true)
                        enableVibration(true)
                        vibrationPattern = MEMO_VIBRATION
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
                // 静默：仍有高重要性横幅，只是不响不震
                manager.createNotificationChannel(
                    NotificationChannel(
                        MEMO_CHANNEL_SILENT_ID, "待办提醒 · 静默", NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "备忘录到期时只显示通知横幅"
                        setShowBadge(true)
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
                // 仅提示音
                manager.createNotificationChannel(
                    NotificationChannel(
                        MEMO_CHANNEL_SOUND_ID, "待办提醒 · 提示音", NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "备忘录到期时响铃并显示通知横幅"
                        setShowBadge(true)
                        setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                        enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
                // 仅震动
                manager.createNotificationChannel(
                    NotificationChannel(
                        MEMO_CHANNEL_VIBRATE_ID, "待办提醒 · 震动", NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "备忘录到期时震动并显示通知横幅"
                        setShowBadge(true)
                        setSound(null, null)
                        enableVibration(true)
                        vibrationPattern = MEMO_VIBRATION
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
            }.onFailure { Log.w(TAG, "创建待办提醒渠道失败：${it.message}") }
        }

        const val NOTIFICATION_ID = 1001

        /** 娱乐提醒通知 id（每次覆盖同一条，不堆积）。 */
        const val ALERT_NOTIFICATION_ID = 1005

        private const val TAG = "FocusGuardApp"
        private const val CRASH_FILE = "crash_log.txt"
        private const val MAX_CRASH_ENTRIES = 5

        /**
         * 崩溃文件的最后修改时间；无文件返回 0。
         *
         * 配合 [ackCrashLog] 实现「每次崩溃只弹窗提示一次」——
         * 否则弹窗每次打开应用都会出现（用户反馈的问题）。
         */
        fun crashLogModifiedAt(context: android.content.Context): Long = try {
            val f = File(context.filesDir, CRASH_FILE)
            if (f.exists()) f.lastModified() else 0L
        } catch (e: Exception) {
            0L
        }

        /** 记下「这条崩溃已经提示过」，避免重复弹窗。 */
        fun ackCrashLog(context: android.content.Context, modifiedAt: Long) {
            context.applicationContext
                .getSharedPreferences("focus_guard_crash_ack", android.content.Context.MODE_PRIVATE)
                .edit().putLong("ack_mtime", modifiedAt).apply()
        }

        /** 是否还有「没提示过」的崩溃。 */
        fun hasUnseenCrash(context: android.content.Context): Boolean {
            val modified = crashLogModifiedAt(context)
            if (modified <= 0L) return false
            val ack = context.applicationContext
                .getSharedPreferences("focus_guard_crash_ack", android.content.Context.MODE_PRIVATE)
                .getLong("ack_mtime", 0L)
            return modified > ack
        }

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

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase)
        // 比 onCreate 更早的一步也打点：如果连这里都没跑到，说明死在框架/ROM 层
        runCatching { com.focusguard.app.util.StartupTrace.mark(this, "app.attachBaseContext") }
    }

    override fun onCreate() {
        super.onCreate()
        // 整段保护：这里任何一步失败都不能让应用打不开。
        // 用户反馈「清掉后台后一打开就闪退，且没有崩溃日志」，轨迹定位到
        // 「app.onCreate 之后、main.onCreate 之前」，所以这一段逐步打点 + 全部兜底。
        runCatching { com.focusguard.app.util.CrashLogger.install(this) }
        // 读上次进程退出原因（内存不足/ANR/被强停）：这类退出没有 Java 崩溃日志，
        // 只有系统侧的退出历史能说明真相，写进检测日志供下次打开时查看
        runCatching { com.focusguard.app.util.ExitDiagnostics.recordPreviousExit(this) }
        runCatching { com.focusguard.app.util.StartupTrace.begin(this) }
        runCatching { installCrashHandler() }
        runCatching { com.focusguard.app.util.StartupTrace.mark(this, "app.crashHandler") }
        runCatching { createNotificationChannel() }
        // 防破解默认值迁移：老用户已开过防破解的，补上「锁机期间挂起 Shizuku」
        runCatching { com.focusguard.app.enhance.LockPolicies.migrateShizukuLock(this) }
        // 主线程卡顿探针：ANR 不会产生崩溃日志，靠它留下证据
        runCatching { com.focusguard.app.util.MainThreadWatch.start(this) }
        runCatching { com.focusguard.app.util.StartupTrace.mark(this, "app.channel") }
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
                    // 版本与机型：排查时第一眼就知道是哪一版、什么设备
                    append("版本 v")
                    append(BuildConfig.VERSION_NAME)
                    append(" (vc")
                    append(BuildConfig.VERSION_CODE)
                    append(") · ")
                    append(android.os.Build.MANUFACTURER)
                    append(" ")
                    append(android.os.Build.MODEL)
                    append(" · Android ")
                    append(android.os.Build.VERSION.RELEASE)
                    append("\n")
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

        // 待办到期提醒：按提醒形式组合建四个固定渠道（通知/提示音/震动/全屏共用）
        ensureMemoChannels(this)
    }
}
