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
     * 尝试进入 Lock Task 模式。
     *
     * 流程：初始化 Dhizuku → 确保本包在白名单 → startLockTask。
     * 已在白名单时跳过授权（少一次 IPC）。
     *
     * @return 是否成功进入
     */
    fun enter(activity: Activity): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
            if (!DhizukuEnhancer.ensureReady(activity)) return false

            if (!DhizukuEnhancer.isLockTaskPermitted(activity.packageName)) {
                if (!DhizukuEnhancer.grantLockTask(activity)) {
                    Log.w(TAG, "加入 Lock Task 白名单失败，放弃进入")
                    return false
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    if (DhizukuEnhancer.isReady()) {
                        // 1. LOCK_TASK_FEATURE_NONE (0)：彻底关闭通知栏下拉、状态栏信息、System Info 与 Keyguard 扩展
                        DhizukuEnhancer.setLockTaskFeatures(activity, android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                        // 2. 状态栏彻底硬屏蔽
                        DhizukuEnhancer.setStatusBarDisabled(activity, true)
                        // 3. 禁用 Keyguard 屏障干扰
                        DhizukuEnhancer.setKeyguardDisabled(activity, true)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "设置 DPM 特性失败: ${e.message}")
                }
            }
            activity.startLockTask()
            lockTaskActive = true
            Log.d(TAG, "已进入系统级 Lock Task 模式")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "进入 Lock Task 失败：${e.message}")
            lockTaskActive = false
            false
        }
    }

    /** 退出 Lock Task 模式（解锁 / 番茄钟休息 / 暂停时调用）。 */
    fun exit(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            activity.stopLockTask()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    if (DhizukuEnhancer.isReady()) {
                        DhizukuEnhancer.setStatusBarDisabled(activity, false)
                        DhizukuEnhancer.setKeyguardDisabled(activity, false)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "退出 Lock Task 失败：${e.message}")
        } finally {
            lockTaskActive = false
        }
    }
}
