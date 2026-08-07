package com.focusguard.app.enhance

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Dhizuku 增强：借用 Device Owner 权限实现系统级防退出（Lock Task Mode）。
 *
 * ## 原理
 * [Dhizuku](https://github.com/iamr0s/Dhizuku) 是一个把自己注册为
 * Device Owner 的免 Root 应用（依赖 Shizuku 的 shell 权限执行
 * `dpm set-device-owner`）。它通过 ContentProvider 把 DevicePolicyManager
 * 的 Binder 共享给其他应用；第三方应用把这些 Binder 调用转发到 Dhizuku
 * 服务器进程执行——由于 Dhizuku 是 Device Owner，权限检查通过。
 *
 * ## 对 FocusGuard 的意义
 * Device Owner 可以把任意包加入 Lock Task 白名单
 * （[DevicePolicyManager.setLockTaskPackages]）。白名单内的应用
 * 调用 [android.app.Activity.startLockTask] 后进入**系统级 Kiosk 模式**：
 * - Home / 上滑手势 / 最近任务 **全部被系统禁用**，无法退出
 * - 无需悬浮窗覆盖层、无需无障碍服务，任何第三方手段都破不了
 * 这是"锁机像勒索病毒一样无法退出"的唯一官方实现。
 *
 * ## 降级
 * 未安装 Dhizuku / 未激活 / 未授权时，[init] 返回 false，
 * 上层自动回退到覆盖层 + 无障碍方案，功能不中断。
 */
object DhizukuEnhancer {

    private const val TAG = "DhizukuEnhancer"

    @Volatile
    private var wrappedDpm: DevicePolicyManager? = null

    @Volatile
    private var ownerComponent: ComponentName? = null

    @Volatile
    private var initialized = false

    /** Dhizuku 已初始化、已授权、包装 DPM 可用。 */
    fun isReady(): Boolean = initialized && wrappedDpm != null && ownerComponent != null

    /**
     * 初始化 Dhizuku 连接并构造包装后的 DevicePolicyManager。
     * 幂等；失败返回 false（静默降级）。
     */
    fun init(context: Context): Boolean {
        if (isReady()) return true
        try {
            // HiddenApiBypass：放开非 SDK 接口反射限制（后续要反射 mService 字段）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            if (!HiddenApiBypass.setHiddenApiExemptions("")) return false

            // 连接 Dhizuku 服务器（要求 Dhizuku 已安装并激活为 Device Owner）
            if (!Dhizuku.init(context.applicationContext)) {
                Log.d(TAG, "Dhizuku 未安装或未激活为 Device Owner")
                return false
            }
            if (!Dhizuku.isPermissionGranted()) {
                Log.d(TAG, "Dhizuku 已连接但未授权给本应用")
                return false
            }

            val dpm = buildWrappedDpm(context) ?: return false
            // 通过包装后的 DPM 查询 Device Owner（调用被转发到 Dhizuku 服务器执行）
            val owner = dpm.getDeviceOwnerComponentOnAnyUser() ?: return false
            wrappedDpm = dpm
            ownerComponent = owner
            initialized = true
            Log.d(TAG, "Dhizuku 增强已就绪，owner=$owner")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "Dhizuku 初始化失败：${e.message}")
            initialized = false
            return false
        }
    }

    /**
     * 构造包装后的 DevicePolicyManager：
     * 1. 取本应用的 DevicePolicyManager（其 mService 就是系统 device_policy binder）
     * 2. 反射取 mService 字段
     * 3. 用 [com.rosan.dhizuku.api.Dhizuku.binderWrapper] 包装 → 后续所有 DPM 调用
     *    被转发到 Dhizuku 服务器进程执行（以 Device Owner 身份）
     */
    private fun buildWrappedDpm(context: Context): DevicePolicyManager? {
        return try {
            val app = context.applicationContext
            val manager = app.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as DevicePolicyManager

            val field = DevicePolicyManager::class.java.getDeclaredField("mService")
            field.isAccessible = true
            val oldInterface = field[manager] as android.os.IInterface

            val oldBinder = oldInterface.asBinder()
            val newBinder = Dhizuku.binderWrapper(oldBinder)
            val newInterface = IDevicePolicyManagerStubAsInterface(newBinder) ?: return null
            field[manager] = newInterface
            manager
        } catch (e: Throwable) {
            Log.w(TAG, "构造包装 DPM 失败：${e.message}")
            null
        }
    }

    /** 反射调用隐藏类 IDevicePolicyManager.Stub.asInterface(binder)。 */
    private fun IDevicePolicyManagerStubAsInterface(binder: IBinder): Any? {
        return try {
            val stub = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
            stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Throwable) {
            Log.w(TAG, "反射 IDevicePolicyManager.Stub.asInterface 失败：${e.message}")
            null
        }
    }

    /**
     * 请求 Dhizuku 授权（会拉起 Dhizuku 的授权界面）。
     * 结果通过 [onResult] 回调。
     */
    fun requestPermission(context: Context, onResult: (Boolean) -> Unit) {
        try {
            if (!init(context)) {
                onResult(false)
                return
            }
            if (Dhizuku.isPermissionGranted()) {
                onResult(true)
                return
            }
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                @Throws(RemoteException::class)
                override fun onRequestPermission(grantResult: Int) {
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "请求 Dhizuku 授权失败：${e.message}")
            onResult(false)
        }
    }

    /** 把本应用加入 Lock Task 白名单（仅 Device Owner 可调用）。 */
    fun grantLockTask(context: Context): Boolean {
        try {
            if (!init(context)) return false
            val dpm = wrappedDpm ?: return false
            val comp = ownerComponent ?: return false
            dpm.setLockTaskPackages(comp, arrayOf(context.packageName))
            Log.d(TAG, "已把 ${context.packageName} 加入 Lock Task 白名单")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "授权 Lock Task 失败：${e.message}")
            return false
        }
    }

    /** 本应用是否已被允许进入 Lock Task 模式。 */
    fun isLockTaskPermitted(packageName: String): Boolean {
        val dpm = wrappedDpm ?: return false
        return try {
            dpm.isLockTaskPermitted(packageName)
        } catch (e: Throwable) {
            Log.w(TAG, "查询 Lock Task 权限失败：${e.message}")
            false
        }
    }
}
