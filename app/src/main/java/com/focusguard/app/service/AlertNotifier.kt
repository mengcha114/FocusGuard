package com.focusguard.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusguard.app.FocusGuardApp
import com.focusguard.app.MainActivity
import com.focusguard.app.R

/**
 * 弹出式提醒（Heads-up 横幅），样式与微信/QQ 消息一致。
 *
 * ## 为什么单独一个渠道
 * 常驻守护通知必须是 IMPORTANCE_LOW（否则每次刷新都响一声，非常吵）；
 * 而"检测到娱乐"这类消息需要用户立刻看到，必须 IMPORTANCE_HIGH 才会
 * 以横幅形式浮出屏幕顶部。同一个渠道无法兼顾，因此拆成
 * [FocusGuardApp.ALERT_CHANNEL_ID] 独立渠道。
 *
 * ## 关键 API
 * - `setPriority(PRIORITY_HIGH)`：Android 7 及以下的 Heads-up 依据
 * - `setCategory(CATEGORY_MESSAGE)`：让系统按"消息"处理（微信/QQ 同款）
 * - `setDefaults(DEFAULT_ALL)`：声音 + 振动 + 呼吸灯
 * - `setStyle(BigTextStyle)`：长文本可展开，不再两行截断
 */
object AlertNotifier {

    private const val TAG = "AlertNotifier"

    /**
     * 弹出娱乐提醒横幅。
     *
     * @param title 标题，如"⚠️ 检测到娱乐行为"
     * @param message 正文（AI 按角色口吻生成的提醒语）
     * @param countdownSeconds >0 时在正文追加"N 秒后锁机"倒计时说明
     */
    fun alertEntertainment(
        context: Context,
        title: String,
        message: String,
        countdownSeconds: Int = 0
    ) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                context,
                7,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val body = buildString {
                append(message.ifBlank { "检测到你正在娱乐，请回到学习状态" })
                if (countdownSeconds > 0) {
                    append("\n\n⏳ ")
                    append(countdownSeconds)
                    append(" 秒后将自动锁机，现在切回学习即可避免")
                }
            }

            val notification = Notification.Builder(context, FocusGuardApp.ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                // 让通知以横幅形式弹出（微信/QQ 同款行为）
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setDefaults(Notification.DEFAULT_ALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()

            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify(FocusGuardApp.ALERT_NOTIFICATION_ID, notification)
            Log.d(TAG, "已弹出娱乐提醒：$title")
        } catch (e: Exception) {
            Log.w(TAG, "弹出提醒失败：${e.message}")
        }
    }

    /** 撤销提醒横幅（用户已切回学习 / 已锁机）。 */
    fun cancelAlert(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(FocusGuardApp.ALERT_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "撤销提醒失败：${e.message}")
        }
    }

    /** 通用横幅提醒（守护中断、权限丢失等）。 */
    fun alert(context: Context, title: String, message: String, id: Int = 1006) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, FocusGuardApp.ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setDefaults(Notification.DEFAULT_ALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
            context.getSystemService(NotificationManager::class.java)
                ?.notify(id, notification)
        } catch (e: Exception) {
            Log.w(TAG, "弹出通用提醒失败：${e.message}")
        }
    }
}
