package com.focusguard.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「放宽限制需答题」的方向判定。
 * 这次用户反馈的误判就出在这里，所以逐条覆盖：只有实质削弱限制才算放宽。
 */
class SettingsDiffTest {

    private fun base() = SettingsDiff.Snapshot(
        lockMinutes = 30, appBlockMinutes = 30,
        consecutiveViolations = 2, lockStrength = 2, enforceRank = 2,
        intervalMinutes = 3, alertDelaySeconds = 15, confidencePercent = 70,
        smartScheduleEnabled = true, alertEnabled = true,
        whitelist = setOf("课程表"),
        tokenSaving = false, hashDedup = false, textPrefilter = false,
        decisionCache = false, adaptiveInterval = false
    )

    @Test
    fun identicalSettingsAreNotLoosening() {
        assertFalse(SettingsDiff.isLoosening(base(), base()))
    }

    @Test
    fun stricterChangesAreNotLoosening() {
        val s = base()
        val stricter = s.copy(
            lockMinutes = 60, appBlockMinutes = 60, consecutiveViolations = 1,
            lockStrength = 4, intervalMinutes = 1, alertDelaySeconds = 0,
            confidencePercent = 50,
            // 注意：关闭智能调度按定义属于放宽（检测变少），因此这里保持开启
            smartScheduleEnabled = true, alertEnabled = false,
            // 关掉省 token 系列 = 检测更频繁 = 更严
            tokenSaving = false, hashDedup = false, textPrefilter = false,
            decisionCache = false, adaptiveInterval = false
        )
        assertFalse(SettingsDiff.isLoosening(s, stricter))
    }

    @Test
    fun shorteningLockIsLoosening() {
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(lockMinutes = 10)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(appBlockMinutes = 5)))
    }

    @Test
    fun harderToTriggerIsLoosening() {
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(consecutiveViolations = 5)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(intervalMinutes = 10)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(alertDelaySeconds = 60)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(confidencePercent = 95)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(smartScheduleEnabled = false)))
        assertTrue(SettingsDiff.isLoosening(base().copy(alertEnabled = false), base()))
    }

    @Test
    fun easierUnlockIsLoosening() {
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(lockStrength = 1)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(enforceRank = 0)))
    }

    @Test
    fun addingWhitelistEntryIsLoosening() {
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(whitelist = setOf("课程表", "某游戏"))))
        // 删条目不算放宽
        assertFalse(SettingsDiff.isLoosening(base().copy(whitelist = setOf("课程表", "某游戏")), base()))
    }

    @Test
    fun weakeningAiDetectionIsLoosening() {
        // 调低每日调用次数**不再**判为放宽（用户要求：调低不该逼着答题）；
        // 它由「锁机期间禁止修改」兜底，见 SettingsScreen.isBlockedDuringLock
        assertFalse(SettingsDiff.isLoosening(base(), base().copy(dailyCallLimit = 0)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(tokenSaving = true)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(hashDedup = true)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(textPrefilter = true)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(decisionCache = true)))
        assertTrue(SettingsDiff.isLoosening(base(), base().copy(adaptiveInterval = true)))
        // 已经是开启状态再改其他项，不重复触发
        assertFalse(SettingsDiff.isLoosening(base().copy(tokenSaving = true), base().copy(tokenSaving = true)))
    }
}
