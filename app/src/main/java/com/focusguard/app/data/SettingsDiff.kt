package com.focusguard.app.data

/**
 * 「放宽限制需答题」的方向判定（纯函数，便于单测）。
 *
 * 只把**实质削弱限制**的改动判为放宽：
 * - 缩短锁机时长、更难触发锁机、更容易解锁、放行应用、削弱 AI 检测。
 * 其余（改 API 配置、提示词、隐私开关、敏感列表、词表、外观）都不算放宽，
 * 不再弹答题——它们要么与限制强度无关，要么由「锁机期间禁止修改」单独兜住。
 */
object SettingsDiff {

    /** 参与方向判定的字段快照。 */
    data class Snapshot(
        val lockMinutes: Int,
        val appBlockMinutes: Int,
        val consecutiveViolations: Int,
        val lockStrength: Int,
        val enforceRank: Int,
        val intervalMinutes: Int,
        val alertDelaySeconds: Int,
        val confidencePercent: Int,
        val smartScheduleEnabled: Boolean,
        val alertEnabled: Boolean,
        val whitelist: Set<String>,
        val tokenSaving: Boolean,
        val hashDedup: Boolean,
        val textPrefilter: Boolean,
        val decisionCache: Boolean,
        val adaptiveInterval: Boolean
    )

    /** 执法模式位次：数值越大越严。 */
    fun enforceRank(mode: Settings.EnforcementMode): Int = when (mode) {
        Settings.EnforcementMode.WARN -> 0
        Settings.EnforcementMode.APP_BLOCK -> 1
        Settings.EnforcementMode.LOCK -> 2
    }

    fun isLoosening(old: Snapshot, new: Snapshot): Boolean =
        // 缩短锁机时长
        new.lockMinutes < old.lockMinutes ||
            new.appBlockMinutes < old.appBlockMinutes ||
            // 更难触发锁机
            new.consecutiveViolations > old.consecutiveViolations ||
            new.intervalMinutes > old.intervalMinutes ||
            new.alertDelaySeconds > old.alertDelaySeconds ||
            new.confidencePercent > old.confidencePercent ||
            (old.smartScheduleEnabled && !new.smartScheduleEnabled) ||
            (!old.alertEnabled && new.alertEnabled) ||
            // 更容易解锁
            new.lockStrength < old.lockStrength ||
            new.enforceRank < old.enforceRank ||
            // 放行应用
            (new.whitelist - old.whitelist).isNotEmpty() ||
            // 削弱 AI 检测：开启省 token 系列
            // 注意：**调低每日调用次数不算放宽**（用户要求：调低不该逼着答题），
            // 它由「锁机期间禁止修改检测相关配置」单独兜住（见 SettingsScreen.isBlockedDuringLock）
            (!old.tokenSaving && new.tokenSaving) ||
            (!old.hashDedup && new.hashDedup) ||
            (!old.textPrefilter && new.textPrefilter) ||
            (!old.decisionCache && new.decisionCache) ||
            (!old.adaptiveInterval && new.adaptiveInterval)
}
