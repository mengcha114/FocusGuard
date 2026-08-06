package com.focusguard.app.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * 前台应用探测器。
 *
 * 只依赖「使用情况访问」权限，**不依赖无障碍服务**。
 * 这是防破解体系的基础：无障碍被系统回收后，锁机仍然有效。
 *
 * 实现取舍：优先用 [UsageStatsManager.queryEvents] 读取
 * ACTIVITY_RESUMED 事件流，比 queryUsageStats 的聚合数据实时得多
 * （聚合数据在部分 ROM 上有分钟级延迟，无法用于 500ms 级别的守护轮询）。
 */
object ForegroundAppDetector {

    private const val TAG = "ForegroundAppDetector"

    /** 事件回溯窗口。太小会漏事件，太大浪费 CPU。 */
    private const val LOOKBACK_MS = 10_000L

    /** 缓存最近一次探测结果，事件窗口内无新事件时复用。 */
    @Volatile
    private var cachedPackage: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 获取当前前台应用包名。取不到返回 null。
     *
     * @param context 任意 Context
     */
    fun current(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return cachedPackage

        val now = System.currentTimeMillis()
        return try {
            val events = usm.queryEvents(now - LOOKBACK_MS, now)
            var latestPackage: String? = null
            var latestTime = 0L
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // ACTIVITY_RESUMED（API 29+）等价于旧的 MOVE_TO_FOREGROUND
                val isResume = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (isResume && event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName
                }
            }

            if (latestPackage != null) {
                cachedPackage = latestPackage
                cachedAt = now
                latestPackage
            } else {
                // 窗口内无切换事件说明前台没变，复用缓存
                cachedPackage
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询前台应用失败：${e.message}")
            cachedPackage
        }
    }

    /** 清空缓存（权限变更或服务重启时调用）。 */
    fun invalidate() {
        cachedPackage = null
        cachedAt = 0L
    }
}
