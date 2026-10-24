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

    /**
     * 长回溯窗口：前台应用**长时间不变**时（一直看同一个视频/游戏），
     * 短窗口里一个事件都没有，而缓存 30 秒就过期 —— 旧实现于是恒返回 null，
     * 表现为「检测不动」：隐私门判成"无法识别前台应用"直接跳过本轮，
     * [isForeground] 复核也永远为假，封锁页于是不弹。
     */
    private const val LONG_LOOKBACK_MS = 10 * 60_000L

    /** 缓存有效期：超过后不再复用（此前 cachedAt 只写不读，前台判断可能永远停在旧应用）。 */
    private const val CACHE_TTL_MS = 30_000L

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
            ?: return freshCached()

        val now = System.currentTimeMillis()
        return try {
            // ① 短窗口：正常切换应用时，最近 10 秒内就有新事件
            // ② 长窗口：前台应用长时间没变（10 秒内没有任何事件）时往前找 10 分钟，
            //    否则缓存过期后这里恒为 null（"识别不动"的根因之一）
            val pkg = latestResumed(usm, now - LOOKBACK_MS, now)
                ?: latestResumed(usm, now - LONG_LOOKBACK_MS, now)

            if (pkg != null) {
                cachedPackage = pkg
                cachedAt = now
                pkg
            } else {
                // 两个窗口都没有可用事件：复用缓存（但要检查是否已过期）
                freshCached()
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询前台应用失败：${e.message}")
            freshCached()
        }
    }

    /**
     * 最近 10 分钟内回到前台、且之后没再离开过的应用（长窗口版）。
     *
     * 供 [com.focusguard.app.detection.AppClassifier.classifyForegroundApp] 使用：
     * 那条路原来读 `queryUsageStats(INTERVAL_DAILY, now-15s, now)` —— 区间参数不生效，
     * 拿到的是**整天**的聚合桶，"当前前台"根本无从谈起。
     */
    fun recentPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        return try {
            latestResumed(usm, now - LONG_LOOKBACK_MS, now)
        } catch (e: Exception) {
            Log.w(TAG, "查询最近前台应用失败：${e.message}")
            null
        }
    }

    /**
     * [from, to] 区间内最后一个"回到前台且之后没有离开"的包名，取不到返回 null。
     *
     * 只看 RESUMED 会误判已经退出的应用：用户回到桌面后，最后一个 RESUMED
     * 仍是刚打开的应用（不少桌面/ROM 不发桌面 RESUMED），所以还要看它之后
     * 有没有 PAUSED / STOPPED —— 有就说明它已不在前台。
     */
    private fun latestResumed(usm: UsageStatsManager, from: Long, to: Long): String? {
        val events = usm.queryEvents(from, to) ?: return null
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTime = 0L
        // 包名 → 最近一次离开前台（PAUSED / STOPPED / BACKGROUND）的时间
        val leftAt = HashMap<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            // ACTIVITY_RESUMED（API 29+）等价于旧的 MOVE_TO_FOREGROUND
            val isResume = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            val isLeave = event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.ACTIVITY_STOPPED ||
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            when {
                isResume && event.timeStamp >= latestTime -> {
                    latestTime = event.timeStamp
                    latestPackage = pkg
                }
                isLeave -> leftAt[pkg] = maxOf(leftAt[pkg] ?: 0L, event.timeStamp)
            }
        }

        val found = latestPackage ?: return null
        return if ((leftAt[found] ?: 0L) > latestTime) null else found
    }

    private fun freshCached(): String? {
        val pkg = cachedPackage ?: return null
        val age = System.currentTimeMillis() - cachedAt
        return if (age in 0..CACHE_TTL_MS) pkg else null
    }

    /**
     * 统一的「当前前台应用」判定（全工程只此一处）：
     * ① 实时事件流（秒级）→ ② 无障碍当前窗口 → ③ 聚合 UsageStats（兜底）。
     *
     * 此前检测管线用聚合数据、守护用事件流，同一个应用在两处会判出不同结果
     * （隐私候选、分类、宽限期复检、时长计时全都受影响）。
     */
    fun bestPackage(
        context: Context,
        store: com.focusguard.app.detection.AppCategoryStore? = null
    ): String? =
        current(context)
            ?: com.focusguard.app.access.GuardAccessibilityService.instance?.currentWindowPackage()
            ?: com.focusguard.app.detection.AppClassifier.classifyForegroundApp(context, store)?.packageName

    /**
     * 目标应用此刻是否**确实**在前台。
     * 执法/弹封锁页之前必须复核：检测到执法之间可能隔了十几秒，用户早已退出该应用。
     */
    fun isForeground(context: Context, pkg: String): Boolean =
        pkg.isNotBlank() && current(context) == pkg

    /** 清空缓存（权限变更或服务重启时调用）。 */
    fun invalidate() {
        cachedPackage = null
        cachedAt = 0L
    }
}
