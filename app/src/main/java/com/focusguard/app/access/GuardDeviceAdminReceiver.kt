package com.focusguard.app.access

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 系统「设备管理员」接收器。
 *
 * 为什么要它：Dhizuku/Shizuku 不是人人都有，而没有它们时「执法瞬间锁屏」
 * 这类能力就没有来源。设备管理员（普通 admin + force-lock 策略）不需要 root、
 * 不需要 adb，用户在系统弹窗里点一下就能激活，之后可以：
 * - 立即锁屏（`lockNow`）——执法瞬间把屏幕灭掉；
 * - 在系统「设备管理员」列表里可见，用户随时能撤销（不做不可逆的事）。
 *
 * 注意：设备管理员**不能**卸载保护/禁改时间（那些是 Device Owner 才有的），
 * 所以这里只是 Dhizuku 的补充，不替代它。
 */
class GuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("GuardDeviceAdmin", "设备管理员已激活（force-lock 可用）")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d("GuardDeviceAdmin", "设备管理员已停用")
    }
}
