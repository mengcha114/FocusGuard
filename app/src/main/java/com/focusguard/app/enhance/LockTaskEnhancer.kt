package com.focusguard.app.enhance

import android.app.Activity
import android.os.Build
import android.util.Log

/**
 * Lock Task Mode（系统级 Kiosk）的进入/退出封装。
 *
 * 进入后系统禁用 Home / 上滑 / 最近任务，锁机页**无法被任何手势退出**
 * （无需悬浮窗覆盖层、无需无障碍服务）。解锁或番茄钟休息时调用 [exit] 释放。
 *
 * Dhizuku 不可用（未安装/未激活/未授权）时静默降级，返回 false，
 * 上层维持覆盖层 + 无障碍方案。
 */
object LockTaskEnhancer {

    private const val TAG = "LockTaskEnhancer"

    /**
     * Lock Task 是否真正生效（enter 成功置 true，exit 置 false）。
     *
     * 守护服务据此判断：Dhizuku 已连接 ≠ LockTask 一定生效——
     * 白名单授权失败等都会让 enter 返回 false。此时必须退回悬浮窗方案，
     * 否则锁机页 Activity 能被正常退出（"配置了 Dhizuku 依然容易退出"）。
     */
    @Volatile
    var lockTaskActive: Boolean = false
        private set

    /**
     * 【后台线程调用】准备 Lock Task：Dhizuku 连接 + 白名单 + DPM 策略配置。
     *
     * 全部是跨进程 Binder 调用，首次可能耗时数百毫秒到数秒——
     * 因此必须在后台线程执行，绝不能放主线程（否则首帧画不出来 = 白屏 ANR）。
     *
     * @return 是否已具备进入 Lock Task 的条件
     */
    fun prepare(context: android.content.Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
            if (!DhizukuEnhancer.ensureReady(context)) return false

            if (!DhizukuEnhancer.isLockTaskPermitted(context.packageName)) {
                if (!DhizukuEnhancer.grantLockTask(context)) {
                    Log.w(TAG, "加入 Lock Task 白名单失败，放弃进入")
                    return false
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    // 1. LOCK_TASK_FEATURE_NONE (0)：彻底关闭通知栏下拉、状态栏信息、System Info 与 Keyguard 扩展
                    DhizukuEnhancer.setLockTaskFeatures(
                        context,
                        android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NONE
                    )
                    // 2. 状态栏彻底硬屏蔽
                    DhizukuEnhancer.setStatusBarDisabled(context, true)
                    // 3. 禁用 Keyguard 屏障干扰
                    DhizukuEnhancer.setKeyguardDisabled(context, true)
                } catch (e: Throwable) {
                    Log.w(TAG, "设置 DPM 特性失败: ${e.message}")
                }
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "准备 Lock Task 失败：${e.message}")
            false
        }
    }

    /**
     * 【主线程调用】真正启动 Lock Task（startLockTask 要求主线程 + resumed）。
     *
     * @return 是否成功进入
     */
    fun startOnUi(activity: Activity): Boolean {
        return try {
            activity.startLockTask()
            lockTaskActive = true
            Log.d(TAG, "已进入系统级 Lock Task 模式")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "startLockTask 失败：${e.message}")
            lockTaskActive = false
            false
        }
    }

    /**
     * 兼容旧调用点的同步进入（内部先 prepare 再 startOnUi）。
     * 注意：会阻塞调用线程，主线程慎用。
     */
    fun enter(activity: Activity): Boolean {
        if (!prepare(activity.applicationContext)) return false
        return startOnUi(activity)
    }

    /**
     * 退出 Lock Task 模式（解锁 / 番茄钟休息 / 暂停时调用）。
     *
     * stopLockTask 必须主线程同步执行；DPM 策略解除（Binder IPC）放后台线程，
     * 避免解锁瞬间主线程被阻塞造成卡顿。
     */
    fun exit(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            runCatching { activity.stopLockTask() }
            val appCtx = activity.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && DhizukuEnhancer.isReady()) {
                Thread {
                    runCatching {
                        DhizukuEnhancer.setStatusBarDisabled(appCtx, false)
                        DhizukuEnhancer.setKeyguardDisabled(appCtx, false)
                    }
                }.start()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "退出 Lock Task 失败：${e.message}")
        } finally {
            lockTaskActive = false
        }
    }
}
