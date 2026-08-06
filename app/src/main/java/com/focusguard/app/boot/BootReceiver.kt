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
import com.focusguard.app.service.GuardWatchdogWorker
import com.focusguard.app.service.LockGuardService
import com.focusguard.app.usage.UsageRuleStore

/**
 * 开机 / 应用更新自启动接收器。
 *
 * 触发场景：
 * - BOOT_COMPLETED、QUICKBOOT_POWERON（各厂商快速启动）
 * - MY_PACKAGE_REPLACED（应用被覆盖安装升级后）
 *
 * 做三件事：
 * 1. 启动 [LockGuardService]——防破解的主防线，不依赖无障碍
 * 2. 注册 [GuardWatchdogWorker]——最后一道兜底
 * 3. 锁机状态仍在 → 直接拉起锁机页（重启手机也无法绕过锁机）
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val TRIGGER_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in TRIGGER_ACTIONS) return

        Log.d(TAG, "收到启动广播：$action")
        val app = context.applicationContext

        try {
            val lockState = LockState(app)
            val usageRuleStore = UsageRuleStore(app)

            // 1. 有锁机或有硬封锁规则 → 启动守护服务
            val hasBlockRule = usageRuleStore.allRules().any { it.hardBlockMinutes != null }
            if (lockState.isLocked || hasBlockRule) {
                Log.d(TAG, "存在锁机或封锁规则，启动锁机守护服务")
                LockGuardService.start(app)
            }

            // 2. 无论如何都注册看门狗（自身开销极小，能兜住守护被杀）
            GuardWatchdogWorker.schedule(app)

            // 3. 锁机状态仍在 → 立即恢复锁机页
            if (lockState.isLocked && lockState.shouldBlockNow) {
                Log.d(TAG, "开机后锁机状态仍有效，恢复锁机页")
                LockScreenActivity.show(app)
            }

            // 4. 守护服务（AI 检测）开机前在运行 → 提醒用户重新授权
            val settings = Settings(app)
            if (settings.serviceRunning) {
                notifyReopen(app)
            }
        } catch (e: Exception) {
            Log.w(TAG, "启动广播处理失败：${e.message}")
        }
    }

    /**
     * 提醒重新开启 AI 守护。
     * MediaProjection 授权无法在后台自动重放，必须用户点一下。
     */
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
                .setContentTitle("AI 守护待重新开启")
                .setContentText("屏幕录制授权需要重新确认，点击开启（锁机守护不受影响）")
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
