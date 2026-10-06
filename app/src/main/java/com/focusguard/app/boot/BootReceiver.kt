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

        /** 最近一次处理的时刻，用于给「开机/解锁/快启/升级」这几种前后脚到达的广播去重。 */
        @Volatile
        private var lastHandledAt = 0L
        // 注意：本接收器不是 directBootAware（LockState 等数据在凭据加密存储里，
        // 开机解锁前读不到），所以不收 LOCKED_BOOT_COMPLETED —— 收了也没用。
        private val TRIGGER_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.USER_SWITCHED",
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

        // 去重：BOOT_COMPLETED 与 USER_UNLOCKED 往往前后脚到达（QUICKBOOT 亦然），
        // 重复拉起会造成锁机页闪动
        val now = System.currentTimeMillis()
        if (now - lastHandledAt < 60_000L) {
            Log.d(TAG, "启动广播去重（${now - lastHandledAt}ms 内已处理过）")
            return
        }
        lastHandledAt = now

        try {
            val lockState = LockState(app)
            val usageRuleStore = UsageRuleStore.shared(app)

            // 0. 锁机仍在生效 → **最先**拉起锁机页：重启后用户最关心的就是它，
            //    服务启动/看门狗注册都是异步的，没必要排在前面等（此前排在最后，
            //    白白多等了几十毫秒到几百毫秒）
            if (lockState.isLocked && lockState.shouldBlockNow) {
                Log.d(TAG, "开机后锁机状态仍有效，立即恢复锁机页与悬浮窗")
                runCatching { LockScreenActivity.show(app) }
            }

            // 1. 有锁机 / 硬封锁规则 / 「仅锁该软件」临时封锁 → 启动守护服务
            val hasBlockRule = usageRuleStore.allRules().any { it.hardBlockMinutes != null }
            // 临时封锁（AI 执法下发）在重启后可能仍在有效期内，同样要守护；
            // 此前漏了这一项，重启后「仅锁该软件」直接失效。
            val hasTempBlock = runCatching {
                com.focusguard.app.data.AppBlockStore(app).anyBlocked()
            }.getOrDefault(false)
            if (lockState.isLocked || hasBlockRule || hasTempBlock) {
                Log.d(TAG, "存在锁机/封锁规则/临时封锁，启动锁机守护服务")
                LockGuardService.start(app)
            }

            // 2. 无论如何都注册看门狗与自愈闹钟（秒级拉活防破解）。
            //    首拍用 1 秒（此前直接排 5 秒，等于让"重启后锁机页出现"白等最多 5 秒），
            //    之后由闹钟自己按 RECOVERY/HEALTHY 间隔续环。
            GuardWatchdogWorker.schedule(app)
            try {
                com.focusguard.app.service.LockGuardAlarm.schedule(app, 1_000L)
            } catch (e: Exception) {
                Log.w(TAG, "注册开机自愈闹钟失败：${e.message}")
            }

            // 2.5 待办到期提醒重排：重启后 AlarmManager 全部清空，必须重建
            try {
                com.focusguard.app.service.MemoReminder.sync(app)
            } catch (e: Exception) {
                Log.w(TAG, "待办提醒重排失败：${e.message}")
            }

            // 4. 屏幕录制（MediaProjection）授权**不可能跨重启保留**：
            //    重启后令牌失效，但我们的标志还留着「已授权」→ 权限页显示已授权、
            //    实际检测跑不起来（用户反馈「重启后掉权限」）。这里如实清掉，
            //    并在需要时提醒用户一键恢复（点通知打开应用会自动弹出授权框）。
            val settings = Settings(app)
            if (settings.screenCaptureGranted) {
                settings.screenCaptureGranted = false
                Log.d(TAG, "重启后已清除屏幕录制授权标志（令牌无法跨重启保留）")
            }
            if (settings.serviceRunning) {
                // 开机自动恢复守护（默认开，可在设置里关）：
                // 把主界面拉起来并带一个标记，由界面在**完全就绪之后**再请求录屏授权
                //（绝不在 onCreate 里弹授权框——v3.11.19 修过的闪退就是那条路径）。
                // 被系统拦（后台启动限制 / 自启动管理）时退回「点通知恢复」。
                var launched = false
                if (settings.autoResumeGuardOnBoot && !lockState.isLocked) {
                    launched = runCatching {
                        app.startActivity(
                            android.content.Intent(
                                app, com.focusguard.app.MainActivity::class.java
                            ).apply {
                                addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                )
                                putExtra(
                                    com.focusguard.app.MainActivity.EXTRA_AUTO_RESUME_GUARD, true
                                )
                            }
                        )
                        com.focusguard.app.util.StartupTrace.mark(app, "boot.autoResumeLaunch")
                        true
                    }.getOrDefault(false)
                    if (launched) {
                        Log.d(TAG, "已拉起主界面自动恢复守护")
                    } else {
                        Log.w(TAG, "自动拉起主界面失败（系统限制），退回通知提醒")
                    }
                }
                if (!launched) notifyReopen(app)
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
            val notification = android.app.Notification.Builder(
                context, FocusGuardApp.ALERT_CHANNEL_ID
            )
                // 重启后这条必须一直留着直到用户处理（此前 60 秒就消失，容易错过）
                .setSmallIcon(com.focusguard.app.R.drawable.ic_shield)
                .setContentTitle("重启后需要恢复 AI 检测")
                .setContentText("屏幕录制授权重启后必须重新确认：点这里一键恢复（锁机守护不受影响）")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 独立通知位：1001 是常驻服务通知，守护会周期性重刷，用它会让这条提醒几秒内被覆盖
            nm.notify(1008, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发送开机提醒失败：${e.message}")
        }
    }
}
