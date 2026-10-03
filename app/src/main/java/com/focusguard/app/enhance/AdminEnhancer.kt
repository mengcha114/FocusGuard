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

    /** 系统激活弹窗 Intent（权限页与设置页共用，避免两处文案不一致）。 */
    fun activationIntent(context: Context): android.content.Intent =
        android.content.Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "用于锁机期间立即锁屏、禁用相机、要求用密码解锁等管理能力；" +
                    "不需要 root 或 Shizuku，激活后可在「设置 → 安全 → 设备管理器应用」里随时撤销。"
            )
        }

    /**
     * 锁机期间限制锁屏特性（禁指纹/人脸/智能锁 → 必须用密码解锁）。
     * 传 [DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE] 恢复。
     */
    fun setKeyguardDisabledFeatures(context: Context, flags: Int): Boolean {
        val manager = dpm(context) ?: return false
        val active = runCatching { manager.isAdminActive(component(context)) }.getOrDefault(false)
        if (!active) return false
        return try {
            manager.setKeyguardDisabledFeatures(component(context), flags)
            Log.d(TAG, "设备管理员 setKeyguardDisabledFeatures($flags) 成功")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "设备管理员 setKeyguardDisabledFeatures 失败：${e.message}")
            false
        }
    }

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
