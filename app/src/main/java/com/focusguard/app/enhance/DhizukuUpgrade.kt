package com.focusguard.app.enhance

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * 重启后「系统级 → 普通模式」降级的自动升级。
 *
 * 开机时 Dhizuku（依赖 Shizuku 或自身服务）往往要 10–60 秒才能连上，锁机页的
 * 首轮等待失败后会降级为普通模式悬浮窗。旧实现降级后再也不会重试，整轮锁机都停在
 * 普通模式。这里由守护巡检（每 300ms 一次）驱动后台探测：
 * - 前 3 分钟每 5 秒探测一次，之后每 30 秒一次，直到锁机结束；
 * - 探测在独立线程做（Binder 初始化不能放在巡检协程里，见 DhizukuEnhancer 注释）；
 * - 一旦就绪，回调 [tick] 的 onReady，由守护服务拉起锁机 Activity 进入 Lock Task。
 * - 若 Shizuku 可用，首次探测前先用 Shizuku 拉起 Dhizuku 进程，缩短等待。
 */
object DhizukuUpgrade {

    private const val TAG = "DhizukuUpgrade"
    private const val FAST_INTERVAL_MS = 5_000L
    private const val SLOW_INTERVAL_MS = 30_000L
    private const val FAST_WINDOW_MS = 3 * 60_000L

    /** 是否处于「已降级、等待升级」状态（锁机页据此显示恢复中徽章）。 */
    @Volatile
    var pending: Boolean = false
        private set

    @Volatile private var since = 0L
    @Volatile private var lastProbeAt = 0L
    @Volatile private var probing = false
    @Volatile private var shizukuKicked = false

    fun markPending() {
        if (pending) return
        pending = true
        since = SystemClock.elapsedRealtime()
        lastProbeAt = 0L
        shizukuKicked = false
        Log.d(TAG, "进入待升级状态")
    }

    fun clear() {
        if (!pending) return
        pending = false
        probing = false
        Log.d(TAG, "清除待升级状态")
    }

    /** 守护巡检调用；到点时在后台线程探测 Dhizuku，就绪后在调用线程外回调 [onReady]。 */
    fun tick(context: Context, onReady: () -> Unit) {
        if (!pending || probing) return
        val now = SystemClock.elapsedRealtime()
        val interval = if (now - since < FAST_WINDOW_MS) FAST_INTERVAL_MS else SLOW_INTERVAL_MS
        if (lastProbeAt != 0L && now - lastProbeAt < interval) return
        lastProbeAt = now
        probing = true
        val app = context.applicationContext
        Thread {
            try {
                if (!shizukuKicked) {
                    shizukuKicked = true
                    runCatching { ShizukuEnhancer.wakeDhizuku() }
                }
                val ready = runCatching { DhizukuEnhancer.ensureReady(app) }.getOrDefault(false)
                if (ready && pending) {
                    pending = false
                    onReady()
                }
            } finally {
                probing = false
            }
        }.start()
    }
}
