package com.focusguard.app.boot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusguard.app.FocusGuardApp
import com.focusguard.app.data.LockState
import com.focusguard.app.data.Settings
import com.focusguard.app.enforce.LockScreenActivity

/**
 * 开机自启动接收器。
 *
 * - 开机时若锁机状态仍在（锁机是软件全屏覆盖 + 状态持久化），
 *   直接拉起锁机页，用户重启手机也无法绕过锁机
 * - 若守护服务开机前正在运行，发通知提醒重新开启
 *   （MediaProjection 授权无法在后台自动重放，需要用户点一下）
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT) return

        Log.d(TAG, "开机广播：$action")

        // 1. 锁机状态恢复（重启也锁）
        val lockState = LockState(context)
        if (lockState.isLocked && lockState.shouldBlockNow) {
            Log.d(TAG, "开机恢复锁机状态")
            LockScreenActivity.show(context)
        }

        // 2. 守护服务提醒重新开启
        val settings = Settings(context)
        if (settings.serviceRunning) {
            Log.d(TAG, "守护服务开机前在运行，发送提醒")
            notifyReopen(context)
        }
    }

    private fun notifyReopen(context: Context) {
        try {
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                1,
                Intent(context, com.focusguard.app.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = android.app.Notification.Builder(context, FocusGuardApp.CHANNEL_ID)
                .setSmallIcon(com.focusguard.app.R.drawable.ic_shield)
                .setContentTitle("守护已暂停，等待重新开启")
                .setContentText("开机后屏幕录制授权需要重新确认，点击重新开启守护")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1001, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发送开机提醒失败：${e.message}")
        }
    }
}
