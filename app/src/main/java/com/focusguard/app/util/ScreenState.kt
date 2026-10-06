package com.focusguard.app.util

import android.content.Context
import android.os.PowerManager

/**
 * 屏幕是否处于「亮屏可交互」状态。
 *
 * 用途：锁机/封锁期间那些「防线丢了就重新拉起界面」的自愈路径（自愈闹钟、
 * 看门狗、检测服务兜底、无障碍重断言、兜底页重试）必须先问一句屏幕亮不亮。
 *
 * 为什么：用户按电源键息屏后，锁机页会走 onPause 把 `foreground` 置 false，
 * 这些路径会立刻认为「防线丢失」并重新拉起界面；而拉起界面（Activity 或带
 * TURN_SCREEN_ON 的窗口）会把屏幕重新点亮 —— 用户看到的就是
 * 「锁机后无法息屏 / 刚熄屏就自己亮起来」。
 */
object ScreenState {

    fun isInteractive(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    }.getOrDefault(true)
}
