package com.focusguard.app.enforce

import android.content.Context
import android.util.Log

/**
 * AI 对话的锁机工具执行器。
 *
 * AI 对话页的 system 提示词声明了文本工具协议：
 * 回复末尾输出 `__LOCK__:<分钟数>` 即请求锁机。
 * 本类负责解析该标记并真正执行锁机：
 * 1. 持久化锁机状态（LockState）
 * 2. 启动锁机守护服务 + 看门狗（防退出）
 * 3. 拉起全屏锁机页
 *
 * 锁机强度沿用设置里的「AI 锁机设置」。
 */
object LockToolExecutor {

    private const val TAG = "LockToolExecutor"

    private val lockPattern = Regex("""__LOCK__:\s*(\d{1,4})""")

    /**
     * 解析文本中的锁机标记并执行。
     *
     * @return 执行成功时返回锁机分钟数，无标记或失败返回 null
     */
    fun tryExecute(context: Context, reply: String): Int? {
        val match = lockPattern.find(reply) ?: return null
        val minutes = match.groupValues[1].toIntOrNull()?.coerceIn(1, 480) ?: return null

        try {
            val lockState = com.focusguard.app.data.LockState(context)
            val settings = com.focusguard.app.data.Settings(context)

            lockState.startLock(minutes, "AI_CHAT")
            // 用 AI 锁机设置里的解锁强度
            lockState.unlockStrength = settings.aiLockStrength
            if (settings.aiLockStrength == 3) {
                lockState.setupFriendChallenge()
            }

            com.focusguard.app.service.LockGuardService.ensureRunning(context)
            com.focusguard.app.service.GuardWatchdogWorker.schedule(context)
            LockScreenActivity.show(context)

            Log.d(TAG, "AI 对话触发锁机 $minutes 分钟")
            return minutes
        } catch (e: Exception) {
            Log.w(TAG, "AI 对话锁机失败：${e.message}")
            return null
        }
    }
}
