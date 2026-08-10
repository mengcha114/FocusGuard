package com.focusguard.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusguard.app.data.LockState

/**
 * 锁机自愈闹钟（AlarmManager 自续环）。
 *
 * ## 为什么需要它
 * 用户"杀后台"（最近任务划掉）后进程被杀，锁机恢复依赖系统的
 * START_STICKY 服务重启——华为/荣耀 ROM 上这一般要 5~10 秒
 * （用户实测约 7 秒），这期间就是破解窗口。
 *
 * AlarmManager 闹钟**不受杀后台影响**（只有"强行停止"才失效）：
 * 锁机激活期间持续注册精确闹钟，进程被杀后 ≤5 秒强制重启守护服务，
 * 服务立刻重新挂起全屏悬浮窗。
 *
 * ## 自适应间隔（省电与速度兼顾）
 * - 一切健康（服务在跑 + 悬浮窗在/锁机页在前台）→ 30 秒探测一次
 * - 检测到异常（进程死了 / 悬浮窗丢了）→ 5 秒快速恢复
 *
 * ## 自续环
 * Receiver 每次触发后重新 schedule 下一个闹钟；锁机结束/暂停时
 * Receiver 检查 `shouldBlockNow` 为 false → 不再续环，自然停止。
 */
object LockGuardAlarm {

    private const val TAG = "LockGuardAlarm"
    private const val ACTION = "com.focusguard.app.action.LOCK_GUARD_ALARM"
    private const val REQUEST_CODE = 10086

    /** 健康探测间隔（服务在跑 + 防线在位）：省电。 */
    private const val HEALTHY_INTERVAL_MS = 30_000L

    /** 异常恢复间隔（进程被杀/防线丢失）：快速拉活。供服务启动时注册首个闹钟。 */
    const val RECOVERY_INTERVAL_MS = 5_000L

    /** 注册下一个闹钟。 */
    fun schedule(context: Context, intervalMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        val triggerAt = System.currentTimeMillis() + intervalMs
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: Exception) {
            // 个别 ROM 限制 setExactAndAllowWhileIdle → 退回普通 set
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                Log.w(TAG, "注册闹钟失败：${e2.message}")
            }
        }
    }

    /** 取消闹钟（服务停止/锁机结束时调用）。 */
    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "取消闹钟失败：${e.message}")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, LockGuardAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 闹钟广播接收器：进程被杀后由系统拉活，重启守护并续环。 */
    class LockGuardAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val appCtx = context.applicationContext
                val lockState = LockState(appCtx)

                // 锁机已结束/暂停中 → 停止自续环（下次锁机由服务重新注册）
                if (!lockState.isLocked || !lockState.shouldBlockNow) {
                    Log.d(TAG, "锁机未激活，自愈闹钟停止续环")
                    return
                }

                // 锁机仍在 → 确保守护服务活着（进程被杀后由闹钟拉活）
                LockGuardService.ensureRunning(appCtx)

                // 防线在位性检查：悬浮窗丢了直接补挂（进程活着但窗口被系统清理）
                val overlayOk = com.focusguard.app.enforce.LockOverlayManager.isShowing
                val activityOk = com.focusguard.app.enforce.LockScreenActivity.foreground
                // LockTask 真正生效时防线是 Activity（系统锁死），不挂悬浮窗；
                // 未生效则必须悬浮窗兜底。
                val lockTaskOn = com.focusguard.app.enhance.LockTaskEnhancer.lockTaskActive
                if (lockTaskOn && !activityOk &&
                    !com.focusguard.app.enforce.UnlockChallengeActivity.foreground
                ) {
                    Log.w(TAG, "自愈闹钟：LockTask 模式防线丢失，拉起锁机 Activity")
                    com.focusguard.app.enforce.LockScreenActivity
                        .show(appCtx, forceActivity = true)
                } else if (!lockTaskOn && !overlayOk && !activityOk &&
                    com.focusguard.app.enforce.LockOverlayManager.canShow(appCtx) &&
                    !com.focusguard.app.enforce.UnlockChallengeActivity.foreground
                ) {
                    Log.w(TAG, "自愈闹钟：检测到防线丢失，立即补挂悬浮窗")
                    com.focusguard.app.enforce.LockOverlayManager.show(
                        context = appCtx,
                        lockState = lockState,
                        force = true,
                        onStartChallenge = {
                            com.focusguard.app.enforce.LockScreenActivity
                                .startChallengeFromOverlay(appCtx, LockState(appCtx))
                        },
                        onRequestPause = {
                            com.focusguard.app.enforce.LockScreenActivity
                                .showForPause(appCtx)
                        }
                    )
                }

                // 自适应间隔续环：健康 30s 探测，异常 5s 快速恢复
                val healthy = LockGuardService.isRunning && (overlayOk || activityOk)
                schedule(appCtx, if (healthy) HEALTHY_INTERVAL_MS else RECOVERY_INTERVAL_MS)
            } catch (e: Exception) {
                Log.w(TAG, "自愈闹钟处理异常：${e.message}")
            }
        }
    }
}
