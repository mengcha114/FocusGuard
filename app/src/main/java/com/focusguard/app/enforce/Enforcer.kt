package com.focusguard.app.enforce

import android.content.Context
import android.util.Log
import com.focusguard.app.data.AppBlockStore
import com.focusguard.app.data.Settings

/**
 * 执法器。
 *
 * 三种执法模式：
 * - LOCK：全局软件锁机（全屏 LockScreenActivity，答题/时间结束解锁）
 * - APP_BLOCK：仅锁该软件——对判定娱乐的应用下发临时封锁
 *   （[AppBlockStore]），封锁期内打开该应用即被全屏挡住，
 *   退出该应用去用别的则不受影响
 * - WARN：仅横幅提醒，不锁机
 */
class Enforcer(private val context: Context) {

    companion object {
        private const val TAG = "Enforcer"
    }

    fun enforce(
        mode: Settings.EnforcementMode,
        reason: String,
        packageName: String = "",
        appLabel: String = ""
    ): String {
        return when (mode) {
            Settings.EnforcementMode.LOCK -> {
                lockScreen()
                "LOCK"
            }
            Settings.EnforcementMode.APP_BLOCK -> {
                blockApp(reason, packageName, appLabel)
                "APP_BLOCK"
            }
            Settings.EnforcementMode.WARN -> {
                showWarning(reason)
                "WARN"
            }
        }
    }

    /** 软件全屏锁机：拉起全屏锁屏页，无需设备管理员。 */
    private fun lockScreen() {
        try {
            // 关键：必须先启动锁机守护服务 + 看门狗！
            // 否则 AI 执法弹出的锁机页被上滑销毁后没有任何东西把它拉回
            // （守护只在手动锁机/开应用时才启动，AI 执法路径此前漏了这一步）
            com.focusguard.app.service.LockGuardService.ensureRunning(context)
            com.focusguard.app.service.GuardWatchdogWorker.schedule(context)

            LockScreenActivity.show(context)
            Log.d(TAG, "软件锁机已启动（全屏覆盖 + 守护已就位）")
        } catch (e: Exception) {
            Log.e(TAG, "拉起锁屏页失败", e)
            blockApp("锁机失败: ${e.message}", packageName = "", appLabel = "")
        }
    }

    /**
     * 仅锁该软件：对该应用下发临时封锁，并立即拉起全屏封锁页。
     *
     * @param reason 封锁原因（记日志用）
     * @param packageName 目标应用包名（为空时无法定位，仅提醒）
     * @param appLabel 目标应用显示名
     */
    private fun blockApp(reason: String, packageName: String, appLabel: String) {
        try {
            if (packageName.isBlank()) {
                Log.w(TAG, "缺少包名，无法执行应用封锁")
                return
            }
            val settings = Settings(context)
            val minutes = settings.appBlockMinutes.coerceAtLeast(1)
            val until = System.currentTimeMillis() + minutes * 60_000L
            AppBlockStore(context).block(packageName, until)
            Log.d(TAG, "$appLabel 已被封锁 $minutes 分钟（$reason）")

            com.focusguard.app.service.LockGuardService.ensureRunning(context)
            com.focusguard.app.service.GuardWatchdogWorker.schedule(context)

            AppBlockActivity.show(
                context = context,
                packageName = packageName,
                appLabel = appLabel,
                usedMinutes = 0,
                limitMinutes = minutes,
                blockUntil = until
            )
        } catch (e: Exception) {
            Log.e(TAG, "应用封锁失败", e)
        }
    }

    private fun showWarning(reason: String) {
        Log.d(TAG, "Warning mode: $reason")
    }
}
