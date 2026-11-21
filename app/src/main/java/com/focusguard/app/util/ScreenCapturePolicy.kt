package com.focusguard.app.util

import com.focusguard.app.BuildConfig

/**
 * 录屏/截屏策略。
 *
 * - CI 测试版（分支构建，CI 传 `-PallowScreenCapture=true`）**允许**录屏与截屏，
 *   方便录屏演示液态玻璃效果；
 * - 正式版（打 tag 的 Release）**禁止**录屏/截屏，防止锁机被外部相机或录屏工具绕过。
 *
 * 该开关在编译期由 BuildConfig 决定，运行时不可被用户或第三方改动。
 */
object ScreenCapturePolicy {

    /** 当前安装包是否允许录屏/截屏。 */
    val allowCapture: Boolean get() = BuildConfig.ALLOW_SCREEN_CAPTURE

    /** 需要禁止截屏时返回 FLAG_SECURE，允许时返回 0（便于按位或拼接窗口标志）。 */
    val secureFlag: Int
        get() = if (allowCapture) 0 else android.view.WindowManager.LayoutParams.FLAG_SECURE
}
