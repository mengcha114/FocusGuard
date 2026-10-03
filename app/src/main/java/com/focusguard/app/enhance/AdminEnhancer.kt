package com.focusguard.app.enhance

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 系统「设备管理员」（普通 admin + force-lock）能力。
 *
 * 定位：Dhizuku（Device Owner）的**轻量替代**，人人都能激活。
 * 只能做少量事（立即锁屏），但不需要 root / adb / Shizuku。
 */
object AdminEnhancer {

    private const val TAG = "AdminEnhancer"

    private fun component(context: Context) = ComponentName(
        context.applicationContext,
        com.focusguard.app.access.GuardDeviceAdminReceiver::class.java
    )

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    /** 是否已激活为设备管理员。 */
    fun isActive(context: Context): Boolean = runCatching {
        dpm(context)?.isAdminActive(component(context)) == true
    }.getOrDefault(false)

    /**
     * 禁用/恢复相机（设备管理员的 disable-camera 策略，不需要 Dhizuku）。
     * 与 Dhizuku 版本的区别：只在普通 admin 权限范围内，效果相同。
     */
    fun setCameraDisabled(context: Context, disabled: Boolean): Boolean {
        val manager = dpm(context) ?: return false
        val active = runCatching { manager.isAdminActive(component(context)) }.getOrDefault(false)
        if (!active) return false
        return try {
            manager.setCameraDisabled(component(context), disabled)
            Log.d(TAG, "设备管理员 setCameraDisabled($disabled) 成功")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "设备管理员 setCameraDisabled 失败：${e.message}")
            false
        }
    }

    /** 立即锁屏（激活为设备管理员即可用，不需要 Dhizuku）。 */
    fun lockNow(context: Context): Boolean {
        val manager = dpm(context) ?: return false
        val active = runCatching { manager.isAdminActive(component(context)) }.getOrDefault(false)
        if (!active) return false
        return try {
            manager.lockNow()
            Log.d(TAG, "设备管理员 lockNow 成功")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "设备管理员 lockNow 失败：${e.message}")
            false
        }
    }
}
