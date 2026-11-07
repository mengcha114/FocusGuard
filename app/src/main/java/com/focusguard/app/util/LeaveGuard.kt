package com.focusguard.app.util

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 「我们主动离开应用」的全局标记。
 *
 * 为什么需要它：为了满足「退出后立刻从最近任务消失」，`MainActivity.onUserLeaveHint`
 * 会 `finishAndRemoveTask()`。但很多正常操作也会离开前台——跳浏览器下载、分享/导出日志、
 * 系统设置页、录屏授权框……这些如果被当成"用户退出"，整个任务会被移除，
 * 而且**不会产生崩溃日志**（我们自己杀的进程），用户看到的就是「点一下就闪退」。
 *
 * 所以凡是"去开别人"的入口都必须先 [markLeaving]，15 秒内的离开一律不隐藏。
 */
object LeaveGuard {

    private const val WINDOW_MS = 15_000L

    @Volatile
    private var expectedAt = 0L

    fun markLeaving() {
        expectedAt = SystemClock.elapsedRealtime()
    }

    /** 是否是"我们自己刚跳出去"导致的离开。 */
    fun isExpected(): Boolean {
        val at = expectedAt
        if (at == 0L) return false
        return SystemClock.elapsedRealtime() - at in 0..WINDOW_MS
    }

    /** 打开外部界面并打标记；返回是否真的打开了。 */
    fun open(context: Context, intent: Intent): Boolean = runCatching {
        markLeaving()
        context.startActivity(
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /**
     * 顶层是不是「系统图片选择器 / 相册 / 文件选择器」。
     *
     * 用户反馈「选背景图时闪退或像被强制退出」的根因就在这里：
     * 部分 ROM（MIUI/鸿蒙等）把相册选择器当成"用户离开了本应用"，
     * 于是触发 [主界面.onUserLeaveHint] → `finishAndRemoveTask()`
     * 把我们自己的任务连同 ActivityResult 回调一起销毁。
     * 选择器返回时已经没有任务可回，看起来就是"应用被强制退出"，
     * 而且因为是自杀，不会留下任何崩溃日志。
     *
     * 兜底判断：只要顶上是这些选择器，就绝不是用户在退出应用。
     */
    fun isPickerHost(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg == "com.android.providers.media.module" ||
            pkg == "com.google.android.providers.media.module" ||
            pkg == "com.android.documentsui" ||
            pkg == "com.google.android.documentsui" ||
            pkg == "com.android.intentresolver" ||
            pkg == "com.google.android.intentresolver" ||
            pkg == "com.miui.gallery" ||
            pkg == "com.huawei.photos" ||
            pkg == "com.huawei.hidisk" ||
            pkg == "com.coloros.gallery3d" ||
            pkg == "com.oppo.gallery3d" ||
            pkg == "com.vivo.gallery" ||
            pkg == "com.sec.android.gallery3d" ||
            pkg == "com.android.gallery3d" ||
            pkg.contains("photopicker") ||
            pkg.contains("gallery")
    }
}
