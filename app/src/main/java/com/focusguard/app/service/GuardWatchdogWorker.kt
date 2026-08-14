package com.focusguard.app.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.focusguard.app.data.LockState
import com.focusguard.app.enforce.LockScreenActivity
import java.util.concurrent.TimeUnit

/**
 * 守护看门狗。
 *
 * 用户反馈"无障碍退出后锁机失效"，前台服务是第一道防线，
 * 但前台服务本身仍可能被激进 ROM 杀掉。WorkManager 是最后一道：
 * 系统级调度，进程被杀后仍会被唤起，兼容性优于 JobScheduler。
 *
 * 每 15 分钟（WorkManager 周期任务的系统最小间隔）检查一次：
 * - [LockGuardService] 是否还在运行，不在就重启
 * - 锁机状态是否仍激活但锁机页缺失，缺失就拉起
 */
class GuardWatchdogWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "GuardWatchdogWorker"
        private const val WORK_NAME = "focusguard_guard_watchdog"

        /** WorkManager 周期任务系统最小间隔是 15 分钟，小于该值会被强制提升。 */
        private const val INTERVAL_MINUTES = 15L

        /** 注册周期看门狗。重复调用安全（KEEP 策略）。 */
        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<GuardWatchdogWorker>(
                    INTERVAL_MINUTES, TimeUnit.MINUTES
                )
                    .setConstraints(
                        Constraints.Builder()
                            // 不设任何约束，任何状态下都要跑
                            .setRequiresBatteryNotLow(false)
                            .setRequiresCharging(false)
                            .setRequiresDeviceIdle(false)
                            .build()
                    )
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    // UPDATE：配置变化时替换旧任务，避免多份并存
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
                Log.d(TAG, "看门狗已注册，间隔 $INTERVAL_MINUTES 分钟")
            } catch (e: Exception) {
                Log.w(TAG, "注册看门狗失败：${e.message}")
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "取消看门狗失败：${e.message}")
            }
        }
    }

    override fun doWork(): Result {
        return try {
            val lockState = LockState(context)

            // 1. 锁机守护服务未运行 → 重启
            if (!LockGuardService.isRunning) {
                Log.d(TAG, "检测到锁机守护未运行，重新启动")
                LockGuardService.start(context)
            }

            // 2. 锁机激活但锁机页缺失 → 拉起
            if (lockState.isLocked && lockState.shouldBlockNow &&
                LockScreenActivity.instance == null &&
                !com.focusguard.app.enforce.UnlockChallengeActivity.active &&
                !LockScreenActivity.friendUnlockActive
            ) {
                Log.d(TAG, "检测到锁机激活但锁机页缺失，拉起锁机页")
                LockScreenActivity.show(context)
            }

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "看门狗执行异常：${e.message}")
            // retry 让 WorkManager 用指数退避重试
            Result.retry()
        }
    }
}
