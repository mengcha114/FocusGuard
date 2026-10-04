package com.focusguard.app.enhance

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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

    /**
     * 是否处于「已降级、等待升级」状态（锁机页据此显示「⏳ 恢复中」徽章）。
     *
     * 注意：**这里必须是普通线程安全字段，不能用 Compose 快照状态**。
     * 该值由守护后台线程写入（markPending/clear/tick），而锁机页会在
     * BoxWithConstraints 的测量期读取它——跨线程写 + 测量期读快照状态会抛
     * `IllegalStateException: Reading a state that was created after the snapshot was taken`
     * 并导致「打开锁机页闪退」（用户实测堆栈已确认）。
     * 锁机页每秒重组一次，所以徽章仍会在 ≤1 秒内更新。
     */
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

    /**
     * 打开 Dhizuku 应用，让它的进程/服务起来（自动重试在 Dhizuku 未运行时无能为力）。
     * 锁机页在前台时具备启动其他 Activity 的权限，因此这是最可靠的一条恢复路径。
     */
    fun openDhizukuApp(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage("com.rosan.dhizuku")
            ?: return@runCatching false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        markPending()
        lastProbeAt = 0L
        true
    }.getOrDefault(false)

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
                } else if (!ready) {
                    // 清掉连接缓存再试：重启后 Dhizuku 进程晚启动时，一次失败可能让
                    // connected/initialized 处于半初始化状态，后续重试全部无效。
                    runCatching { DhizukuEnhancer.resetForRetry() }
                }
            } finally {
                probing = false
            }
        }.start()
    }
}
