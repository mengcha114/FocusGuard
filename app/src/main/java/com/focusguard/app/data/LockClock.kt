package com.focusguard.app.data

import android.content.Context

/**
 * 锁机计时用的时钟抽象。
 *
 * 锁机、暂停、番茄钟阶段全部以单调时钟（[elapsed]）为准，墙钟（[wall]）
 * 只用于展示与篡改检测；[bootCount] 用于识别设备重启（单调时钟在重启时归零）。
 * 抽成接口是为了单元测试能模拟改时间、重启、进程被杀等场景。
 */
interface LockClock {
    /** 单调时钟（毫秒），不受用户改系统时间影响，设备重启归零。 */
    fun elapsed(): Long

    /** 不含深度睡眠的运行时长（毫秒），设备重启归零；用于区分「被杀」与「睡眠」。 */
    fun uptime(): Long

    /** 墙钟（毫秒），用户可随意修改。 */
    fun wall(): Long

    /** 设备启动次数；不可用时返回 -1（退回「基准大于当前单调时钟」判定）。 */
    fun bootCount(): Int
}

/** 系统实现。 */
class SystemLockClock(context: Context) : LockClock {
    private val resolver = context.applicationContext.contentResolver

    override fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()

    override fun uptime(): Long = android.os.SystemClock.uptimeMillis()

    override fun wall(): Long = System.currentTimeMillis()

    override fun bootCount(): Int = try {
        android.provider.Settings.Global.getInt(
            resolver,
            android.provider.Settings.Global.BOOT_COUNT,
            -1
        )
    } catch (e: Exception) {
        -1
    }
}
