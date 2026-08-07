package com.focusguard.app.token

import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 智能检测调度器（秒级精度）。
 *
 * ## 为什么不用固定间隔
 * 固定 N 分钟检测有两个硬伤：
 * - 用户刚打开娱乐应用 30 秒就切走，固定间隔完全抓不到
 * - 用户连续两小时认真学习，固定间隔仍在烧 token
 *
 * ## 算法：风险驱动 + 停留时长学习 + 提醒收紧
 *
 * ### 1. 风险分（risk ∈ [0,1]）—— 指数加权移动平均（EWMA）
 * 每次检测结果映射为瞬时风险 r：娱乐=1.0、中性=0.35、学习=0.0。
 * 然后 `risk = α·r + (1-α)·risk`（α=0.45）。
 * EWMA 的意义：既反映"最近一次"，也保留"最近几次"的趋势，
 * 单次误判不会让间隔剧烈跳变，但连续娱乐会迅速把风险推高。
 *
 * ### 2. 间隔映射 —— 风险越高，间隔指数级收紧
 * `interval = base · (minRatio/1)^risk`，即
 * - risk=0（稳定学习）→ interval = base（甚至可放宽到 maxRatio）
 * - risk=1（持续娱乐）→ interval = base · minRatio（最短）
 * 用指数而非线性：风险从 0.5→0.8 时收紧幅度明显大于 0.2→0.5，
 * 符合"越可疑越要盯紧"的直觉。
 *
 * ### 3. 停留时长学习 —— 每个应用学它自己的切换节奏
 * 记录每个包名的前台停留时长样本（EWMA 平均值 dwell）。
 * 检测间隔不应超过"用户通常在这个应用停留多久"的一半——
 * 否则等你去检测，用户早切走了（奈奎斯特采样思想：
 * 采样周期 ≤ 目标现象周期的一半才能捕捉到它）。
 * 短视频类应用 dwell 可能只有 40 秒 → 检测间隔自动压到 20 秒左右。
 *
 * ### 4. 提醒收紧 —— 用户设置的连续违规次数参与折半
 * 每弹一次提醒（未达执法阈值），间隔折半：
 * `interval /= 2^warnCount`。
 * 与用户的"连续 N 次违规才执法"配置天然契合：
 * 第 1 次提醒后间隔减半、第 2 次再减半，让执法在
 * 用户真的持续娱乐时快速到来，而不是干等 N×base 分钟。
 *
 * ### 5. 冷却与边界
 * - 屏幕熄灭/无前台应用 → 进入省电间隔（base·maxRatio）
 * - 结果为 ERROR（网络失败等）→ 轻微退避，避免连续打空
 * - 最终结果 clamp 到 [MIN_SECONDS, MAX_SECONDS]
 */
class SmartScheduler(
    /** 用户设置的基准间隔（分钟）。 */
    private var baseMinutes: Int
) {

    companion object {
        private const val TAG = "SmartScheduler"

        /** EWMA 平滑系数：越大越"看最近一次"。 */
        private const val RISK_ALPHA = 0.45

        /** 停留时长 EWMA 系数。 */
        private const val DWELL_ALPHA = 0.35

        /** 风险=1 时的间隔压缩比（base 的 1/6）。 */
        private const val MIN_RATIO = 1.0 / 6.0

        /** 稳定学习时的间隔放宽比（base 的 2 倍）。 */
        private const val MAX_RATIO = 2.0

        /** 硬下限：秒。太密会明显耗电且 token 飙升。 */
        private const val MIN_SECONDS = 20L

        /** 硬上限：秒（10 分钟）。 */
        private const val MAX_SECONDS = 600L

        /** 提醒折半的最大次数（超过按满档算，避免间隔趋近 0）。 */
        private const val MAX_WARN_HALVING = 3

        /** 各分类的瞬时风险值。 */
        private fun riskOf(classification: String): Double = when (classification) {
            "ENTERTAINMENT" -> 1.0
            "NEUTRAL" -> 0.35
            "STUDY_WORK" -> 0.0
            else -> 0.3
        }
    }

    /** 当前风险分（EWMA）。 */
    private var risk = 0.3

    /** 连续提醒次数（执法后归零）。 */
    private var warnCount = 0

    /** 每个包名的平均前台停留秒数（EWMA）。 */
    private val dwellByPackage = mutableMapOf<String, Double>()

    /** 当前前台包名与其开始时间，用于测量停留时长。 */
    private var currentPackage: String? = null
    private var currentPackageSince = 0L

    /** 最近一次算出的间隔（秒），供 UI 展示。 */
    var lastIntervalSeconds: Long = 0L
        private set

    /** 最近一次决策依据（供 UI/日志展示，便于用户理解为何这样调度）。 */
    var lastReason: String = ""
        private set

    /** 用户在设置里改了基准间隔 → 立即生效。 */
    fun updateBase(minutes: Int) {
        baseMinutes = minutes.coerceAtLeast(1)
    }

    /**
     * 报告当前前台应用（每个巡检 tick 调用）。
     *
     * 用于学习每个应用的停留时长：包名变化时结算上一个应用的停留样本。
     */
    fun onForegroundApp(packageName: String?, nowMs: Long = System.currentTimeMillis()) {
        if (packageName == currentPackage) return

        // 结算上一个应用的停留时长
        val prev = currentPackage
        if (prev != null && currentPackageSince > 0) {
            val dwellSeconds = (nowMs - currentPackageSince) / 1000.0
            // 只取合理区间的样本（<3 秒多半是过渡界面，>30 分钟多半是忘记锁屏）
            if (dwellSeconds in 3.0..1800.0) {
                val old = dwellByPackage[prev]
                dwellByPackage[prev] = if (old == null) {
                    dwellSeconds
                } else {
                    DWELL_ALPHA * dwellSeconds + (1 - DWELL_ALPHA) * old
                }
            }
        }
        currentPackage = packageName
        currentPackageSince = nowMs
    }

    /** 检测结果反馈：更新风险分。 */
    fun onDetectionResult(classification: String, isError: Boolean = false) {
        if (isError) {
            // 网络/接口错误：不改变风险判断，只轻微退避（在 nextDelaySeconds 里体现）
            lastReason = "上次检测失败，轻微退避"
            return
        }
        val r = riskOf(classification)
        risk = RISK_ALPHA * r + (1 - RISK_ALPHA) * risk
        risk = risk.coerceIn(0.0, 1.0)
    }

    /** 弹出提醒（未达执法阈值）→ 间隔折半，尽快确认用户是否收手。 */
    fun onWarned() {
        warnCount = min(warnCount + 1, MAX_WARN_HALVING)
    }

    /** 执法完成（锁机/封锁）→ 提醒计数归零，风险回落一半。 */
    fun onEnforced() {
        warnCount = 0
        risk *= 0.5
    }

    /** 已回到学习状态 → 提醒计数归零。 */
    fun onCalm() {
        warnCount = 0
    }

    /**
     * 计算下一次检测的等待秒数（秒级精度）。
     *
     * @param screenOn 屏幕是否亮着（熄屏进入省电节奏）
     * @param foregroundPackage 当前前台包名（用于停留时长约束）
     * @param lastWasError 上次检测是否失败（失败则退避）
     */
    fun nextDelaySeconds(
        screenOn: Boolean = true,
        foregroundPackage: String? = currentPackage,
        lastWasError: Boolean = false
    ): Long {
        val baseSeconds = baseMinutes.coerceAtLeast(1) * 60.0

        // 熄屏：没有画面可检测，直接用上限省电
        if (!screenOn) {
            lastIntervalSeconds = MAX_SECONDS
            lastReason = "屏幕熄灭，省电节奏"
            return MAX_SECONDS
        }

        // ── 1. 风险映射：interval = base · (MIN_RATIO)^risk，risk=0 时再放宽 ──
        // risk=0 → base·MAX_RATIO；risk=1 → base·MIN_RATIO
        // 用对数插值让曲线在中段平滑
        val ratio = MAX_RATIO.pow(1.0 - risk) * MIN_RATIO.pow(risk)
        var seconds = baseSeconds * ratio
        val reasonParts = mutableListOf<String>()
        reasonParts.add("风险 ${(risk * 100).toInt()}%")

        // ── 2. 停留时长约束（奈奎斯特）：间隔 ≤ 该应用平均停留时长的一半 ──
        val dwell = foregroundPackage?.let { dwellByPackage[it] }
        if (dwell != null && dwell > 0) {
            val nyquist = dwell / 2.0
            if (nyquist < seconds) {
                seconds = nyquist
                reasonParts.add("该应用平均停留 ${dwell.toInt()}s → 采样 ${nyquist.toInt()}s")
            }
        }

        // ── 3. 提醒折半：每次提醒后间隔减半 ──
        if (warnCount > 0) {
            val divisor = 2.0.pow(warnCount)
            seconds /= divisor
            reasonParts.add("已提醒 ${warnCount} 次 → 间隔 /${divisor.toInt()}")
        }

        // ── 4. 错误退避：接口失败时不要连环打空 ──
        if (lastWasError) {
            seconds = max(seconds, 60.0)
            reasonParts.add("上次失败，退避 ≥60s")
        }

        val result = seconds.toLong().coerceIn(MIN_SECONDS, MAX_SECONDS)
        lastIntervalSeconds = result
        lastReason = reasonParts.joinToString(" · ")
        Log.d(TAG, "下次检测 ${result}s（$lastReason）")
        return result
    }

    /** 当前风险分（0-1），供 UI 展示。 */
    fun currentRisk(): Double = risk

    /** 已学习到的应用停留时长（调试/导出用）。 */
    fun dwellSnapshot(): Map<String, Int> =
        dwellByPackage.mapValues { it.value.toInt() }

    /** 重置节奏（停止守护/屏幕长时间熄灭后）。 */
    fun reset() {
        risk = 0.3
        warnCount = 0
        currentPackage = null
        currentPackageSince = 0L
    }
}
