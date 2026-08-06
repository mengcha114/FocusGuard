package com.focusguard.app.usage

import android.util.Log

/**
 * 前台应用使用时长追踪器。
 *
 * 由 MonitorService 以固定节奏（默认每 15 秒）调用 [tick]，
 * 追踪器负责判断前台应用是否切换、累加当前应用的使用秒数，
 * 并给出该应用是否越过了「开始检测」或「强制封锁」阈值。
 *
 * 设计取舍：不用 UsageStatsManager 的历史统计做时长计算。
 * 那套数据以天为粒度聚合、且不同厂商 ROM 行为不一致；
 * 自己累加 tick 更可控，也便于解封后立即重置。
 */
class UsageTracker(private val store: UsageRuleStore) {

    companion object {
        private const val TAG = "UsageTracker"
        /** 单次 tick 之间的间隔超过此值时视为不连续，丢弃该段时间。 */
        private const val MAX_GAP_SECONDS = 120L
    }

    /** 一次 tick 的判定结果。 */
    sealed interface Verdict {
        /** 无规则或未越阈值，无需动作。 */
        data object Idle : Verdict

        /** 已越过检测阈值，应开始（或维持）AI 检测。 */
        data class ShouldDetect(
            val packageName: String,
            val usedMinutes: Int,
            val triggerMinutes: Int
        ) : Verdict

        /** 已越过封锁阈值，应立即全屏封锁该应用。 */
        data class ShouldHardBlock(
            val packageName: String,
            val usedMinutes: Int,
            val limitMinutes: Int
        ) : Verdict
    }

    private var currentPackage: String? = null
    private var lastTickAt: Long = 0L

    /**
     * 记录一次采样。
     *
     * @param foregroundPackage 当前前台应用包名，null 表示桌面/锁屏等无需计时的状态
     * @param nowMs 当前时间戳，便于测试注入
     */
    fun tick(foregroundPackage: String?, nowMs: Long = System.currentTimeMillis()): Verdict {
        val previousPackage = currentPackage
        val previousTick = lastTickAt

        currentPackage = foregroundPackage
        lastTickAt = nowMs

        // 无前台应用（桌面、锁屏、系统界面）→ 不计时
        if (foregroundPackage == null) return Verdict.Idle

        // 应用刚切换过来，本段时间归属不明，从下次 tick 开始计
        if (previousPackage != foregroundPackage) {
            Log.d(TAG, "前台应用切换至 $foregroundPackage")
            return evaluate(foregroundPackage, store.getTodaySeconds(foregroundPackage))
        }

        // 同一应用持续在前台，累加这段时间
        val gapSeconds = (nowMs - previousTick) / 1000
        if (gapSeconds <= 0) return Verdict.Idle
        if (gapSeconds > MAX_GAP_SECONDS) {
            // 服务被系统冻结过，这段时间不能算作使用
            Log.d(TAG, "采样间隔 ${gapSeconds}s 过长，丢弃该段计时")
            return evaluate(foregroundPackage, store.getTodaySeconds(foregroundPackage))
        }

        val total = store.addSeconds(foregroundPackage, gapSeconds)
        return evaluate(foregroundPackage, total)
    }

    /** 应用被解封后重新开始计时。 */
    fun resetFor(packageName: String) {
        store.resetToday(packageName)
    }

    private fun evaluate(packageName: String, totalSeconds: Long): Verdict {
        val rule = store.getRule(packageName) ?: return Verdict.Idle
        val usedMinutes = (totalSeconds / 60).toInt()

        // 封锁优先于检测：越过硬上限就不必再走 AI 了
        rule.hardBlockMinutes?.let { limit ->
            if (usedMinutes >= limit) {
                return Verdict.ShouldHardBlock(packageName, usedMinutes, limit)
            }
        }

        rule.triggerMinutes?.let { trigger ->
            if (usedMinutes >= trigger) {
                return Verdict.ShouldDetect(packageName, usedMinutes, trigger)
            }
        }

        return Verdict.Idle
    }
}
