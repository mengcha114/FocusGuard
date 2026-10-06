package com.focusguard.app.data

/**
 * 防破解 / 硬化类开关的方向判定（纯函数，便于单测）。
 *
 * 规则与 [SettingsDiff.isLoosening] 保持一致：
 * - **关闭**这类开关 = 放宽限制 ⇒ 需要答题验证；
 * - **打开**这类开关 = 收紧限制 ⇒ 直接保存，不答题。
 */
object SecurityToggles {

    /** true = 这次操作是把「已开启的防破解开关」关掉（需要答题）。 */
    fun isSecuritySwitchOff(enabledNow: Boolean, next: Boolean): Boolean =
        enabledNow && !next
}
