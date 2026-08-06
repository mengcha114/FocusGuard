package com.focusguard.app.token

/**
 * 自适应检测间隔。
 *
 * 核心观察：用户的状态是有惯性的。
 * 连续多次都判定为"学习/工作"时，说明用户处在稳定的专注状态，
 * 此时高频检测既浪费 token 又浪费电；
 * 反之一旦出现娱乐迹象，就应该立刻收紧检测频率。
 *
 * 策略：
 * - 连续判定为学习 → 间隔逐步倍增（最多到基准的 4 倍）
 * - 判定为娱乐 → 间隔立刻回到最小值，加密监控
 * - 判定为中性（锁屏、桌面）→ 温和放宽
 */
class AdaptiveScheduler(private val baseMinutes: Int) {

    companion object {
        private const val MAX_MULTIPLIER = 4f
        private const val MIN_MULTIPLIER = 0.5f
        /** 连续几次学习判定后开始放宽间隔。 */
        private const val CALM_STREAK_THRESHOLD = 2
    }

    private var multiplier = 1f
    private var studyStreak = 0

    /** 当前应等待的秒数。 */
    fun nextDelaySeconds(): Long {
        val minutes = (baseMinutes.coerceAtLeast(1) * multiplier)
            .coerceAtLeast(baseMinutes * MIN_MULTIPLIER)
        return (minutes * 60).toLong().coerceAtLeast(30L)
    }

    /** 当前倍率，用于界面展示。 */
    fun currentMultiplier(): Float = multiplier

    fun onResult(classification: String) {
        when (classification) {
            "ENTERTAINMENT" -> {
                // 发现娱乐迹象，立刻加密监控
                studyStreak = 0
                multiplier = MIN_MULTIPLIER
            }
            "STUDY_WORK" -> {
                studyStreak++
                if (studyStreak >= CALM_STREAK_THRESHOLD) {
                    multiplier = (multiplier * 1.5f).coerceAtMost(MAX_MULTIPLIER)
                } else {
                    multiplier = multiplier.coerceAtLeast(1f)
                }
            }
            else -> {
                // 中性画面（锁屏、桌面）没有风险，温和放宽
                studyStreak = 0
                multiplier = (multiplier * 1.2f).coerceAtMost(MAX_MULTIPLIER)
            }
        }
    }

    /** 屏幕熄灭或用户手动停止后重置节奏。 */
    fun reset() {
        multiplier = 1f
        studyStreak = 0
    }
}
