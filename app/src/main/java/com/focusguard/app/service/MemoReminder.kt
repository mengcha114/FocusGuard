package com.focusguard.app.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.focusguard.app.FocusGuardApp
import com.focusguard.app.MainActivity
import com.focusguard.app.R
import com.focusguard.app.data.MemoPrefs
import com.focusguard.app.data.MemoStore

/**
 * 待办到期提醒。
 *
 * ## 设计要点
 * - **墙钟触发（RTC_WAKEUP）**：待办的截止时间是"人话时间"，必须用墙钟。
 *   （锁机自愈闹钟用 ELAPSED_REALTIME 防时间篡改，那是另一个战场；
 *   这里若用 elapsed 会在重启后基准归零、提醒全部消失——血泪教训。）
 * - **重启恢复**：BootReceiver 开机后调用 [sync] 全量重排，闹钟不丢。
 * - **精确闹钟降级**：Android 12+ 用户可在系统里关闭精确闹钟权限，
 *   检查 [AlarmManager.canScheduleExactAlarms]，不可用就退回非精确
 *   `setAndAllowWhileIdle`（可能延迟，但不丢提醒）。
 * - **幂等**：闹钟触发时 Receiver 重新查库，条目已删除/已完成则静默跳过；
 *   因此删除与完成条目时无需精确取消，sync 用 FLAG_UPDATE_CURRENT
 *   覆盖同 id 的旧闹钟即可。
 */
object MemoReminder {

    private const val TAG = "MemoReminder"
    const val ACTION = "com.focusguard.app.action.MEMO_DUE"
    const val EXTRA_ID = "memo_id"
    const val EXTRA_TEXT = "memo_text"

    /** 应用启动 / 开机 / 数据变更后调用：为全部未完成的未来待办重排闹钟。 */
    fun sync(context: Context) {
        try {
            val store = MemoStore(context)
            val now = System.currentTimeMillis()
            val leadMs = MemoPrefs(context).reminderLeadMinutes * 60_000L
            val dueItems = store.getPending().filter { it.dueAt > now }
            dueItems.forEach { item ->
                val triggerAt = (item.dueAt - leadMs).coerceAtLeast(now + 1_000L)
                schedule(context, item.id, item.text, triggerAt)
            }
            Log.d(TAG, "提醒重排完成：${dueItems.size} 条待办闹钟")
        } catch (e: Exception) {
            Log.w(TAG, "提醒重排失败：${e.message}")
        }
    }

    /** 取消单条提醒（条目删除/完成时调用，Receiver 端仍有兜底检查）。 */
    fun cancel(context: Context, id: Long) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pendingIntent(context, id, ""))
        } catch (e: Exception) {
            Log.w(TAG, "取消提醒失败：${e.message}")
        }
    }

    /** 是否具备精确闹钟能力（Android 12+ 需 SCHEDULE_EXACT_ALARM 授权）。 */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.canScheduleExactAlarms() ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 是否可使用全屏提醒。
     * Android 14+ 该权限默认不授予普通应用，需用户在本应用的通知设置里手动允许；
     * 未授权时 setFullScreenIntent 会被系统忽略，退化为普通通知。
     */
    fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.canUseFullScreenIntent() ?: false
        }.getOrDefault(false)
    }

    private fun schedule(context: Context, id: Long, text: String, triggerAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, id, text)
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: Exception) {
            // 个别 ROM 限制 → 退回最宽松的 set
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                Log.w(TAG, "注册提醒失败：${e2.message}")
            }
        }
    }

    private fun pendingIntent(context: Context, id: Long, text: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (id % 1_000_000).toInt(),
            Intent(context, MemoReminderReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/**
 * 到期闹钟接收器：条目仍存在且未完成才发通知，随后重排剩余提醒。
 * 顶层类（不做 object 嵌套）——Manifest 注册名与类名一致，避免 `$` 嵌套类注册陷阱。
 */
class MemoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val appCtx = context.applicationContext
            if (intent.action != MemoReminder.ACTION) return
            val id = intent.getLongExtra(MemoReminder.EXTRA_ID, -1L)
            val text = intent.getStringExtra(MemoReminder.EXTRA_TEXT).orEmpty()

            // 幂等检查：已完成 / 已删除的条目静默跳过
            val store = MemoStore(appCtx)
            val item = store.getAll().firstOrNull { it.id == id && !it.done }
            if (item == null) {
                Log.d(TAG, "待办已不存在或已完成，跳过提醒：$text")
                return
            }

            sendNotification(appCtx, item.id, item.text)

            // 本条闹钟已消耗，重排其余（防提前量设置变化）
            MemoReminder.sync(appCtx)
        } catch (e: Exception) {
            Log.w(TAG, "提醒接收器异常：${e.message}")
        }
    }

    private fun sendNotification(context: Context, id: Long, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (!nm.areNotificationsEnabled()) return

            // 渠道已由 Application 幂等创建；这里再兜一次，防极端情况下进程直接进广播
            FocusGuardApp.ensureMemoChannels(context)

            val prefs = MemoPrefs(context)
            val sound = prefs.hasReminderFlag(MemoPrefs.REMINDER_SOUND)
            val vibrate = prefs.hasReminderFlag(MemoPrefs.REMINDER_VIBRATE)
            val fullScreen = prefs.hasReminderFlag(MemoPrefs.REMINDER_FULLSCREEN)

            // 全屏与普通通知共用同一个 PendingIntent：点击后都能定位到这条待办
            val openPi = PendingIntent.getActivity(
                context,
                (id % 1_000_000).toInt(),
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_MEMO, true)
                    .putExtra(MainActivity.EXTRA_MEMO_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = android.app.Notification.Builder(
                context,
                FocusGuardApp.memoChannelId(sound, vibrate)
            )
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("待办到期")
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPi)
                .setAutoCancel(true)
                // 高重要性 + 提醒类别：保证横幅能浮出（API 26+ 实际由渠道决定）
                .setCategory(android.app.Notification.CATEGORY_REMINDER)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)

            if (fullScreen) {
                // 权限缺失/ROM 拒绝时 setFullScreenIntent 会被忽略或抛异常：
                // 兜住后普通通知照常显示，用户未解锁也能在通知栏看到。
                runCatching { builder.setFullScreenIntent(openPi, true) }
            }

            nm.notify(NOTIF_BASE + (id % 1000).toInt(), builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "发送提醒通知失败：${e.message}")
        }
    }

    private companion object {
        const val TAG = "MemoReminder"
        const val NOTIF_BASE = 11000
    }
}
