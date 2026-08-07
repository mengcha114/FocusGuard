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
 * ## 状态机（重要）
 * 连接与授权是**两个独立阶段**，绝不能绑死：
 * 1. [connect]：Dhizuku 服务器可达（已安装 + 已激活为 Device Owner）
 * 2. [isPermissionGranted]：Dhizuku 是否已授权本应用
 * 3. [ensureReady]：连接 + 已授权 + 包装 DPM 构造完成 → Lock Task 可用
 *
 * 早期实现把「已授权」当作 [connect] 的前置条件，导致首次授权前
 * 流程直接短路——用户永远无法发起授权请求，UI 永远显示"未授权"。
 *
 * ## 降级
 * 任一步骤失败都返回 false，上层回退到覆盖层 + 无障碍方案，功能不中断。
 */
object DhizukuEnhancer {

    private const val TAG = "DhizukuEnhancer"

    /** Dhizuku 服务器连接是否建立（HiddenApiBypass + init 完成）。 */
    @Volatile
    private var connected = false

    @Volatile
    private var wrappedDpm: DevicePolicyManager? = null

    @Volatile
    private var ownerComponent: ComponentName? = null

    @Volatile
    private var initialized = false

    /** 完整就绪：连接 + 授权 + 包装 DPM 可用。Lock Task 可用的唯一判据。 */
    fun isReady(): Boolean = initialized && wrappedDpm != null && ownerComponent != null

    /**
     * 建立 Dhizuku 连接（幂等）。
     * 只要求 Dhizuku 已安装并激活为 Device Owner，**不要求已授权本应用**。
     */
    fun connect(context: Context): Boolean {
        if (connected) return true
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            if (!HiddenApiBypass.setHiddenApiExemptions("")) return false
            if (!Dhizuku.init(context.applicationContext)) {
                Log.d(TAG, "Dhizuku 未安装或未激活为 Device Owner")
                return false
            }
            connected = true
            Log.d(TAG, "Dhizuku 连接成功")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Dhizuku 连接失败：${e.message}")
            connected = false
            false
        }
    }

    /** 本应用是否已获得 Dhizuku 授权（需先 [connect]）。 */
    fun isPermissionGranted(): Boolean = try {
        connected && Dhizuku.isPermissionGranted()
    } catch (e: Throwable) {
        Log.w(TAG, "查询 Dhizuku 授权状态失败：${e.message}")
        false
    }

    /**
     * 确保完整就绪（连接 + 授权 + 构造包装 DPM）。
     * 幂等；未授权时返回 false 但不阻断后续 [requestPermission]。
     */
    fun ensureReady(context: Context): Boolean {
        if (isReady()) return true
        try {
            if (!connect(context)) return false
            if (!isPermissionGranted()) return false

            val dpm = buildWrappedDpm(context) ?: return false
            // 通过包装后的 DPM 查询 Device Owner（调用被转发到 Dhizuku 服务器执行）。
            // getDeviceOwnerComponentOnAnyUser 是 @hide 方法（不在 android.jar stub），
            // 运行时反射调用（HiddenApiBypass 已放行非 SDK 接口）。
            val owner = queryDeviceOwner(dpm) ?: return false
            wrappedDpm = dpm
            ownerComponent = owner
            initialized = true
            Log.d(TAG, "Dhizuku 增强已就绪，owner=$owner")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "Dhizuku 就绪检查失败：${e.message}")
            initialized = false
            return false
        }
    }

    /**
     * 权限页/状态检查用：尝试连接并在已授权时完成就绪。
     * 不拉起任何界面，失败静默返回 false。
     */
    fun autoCheck(context: Context): Boolean {
        if (isReady()) return true
        if (!connect(context)) return false
        return try {
            if (Dhizuku.isPermissionGranted()) {
                ensureReady(context)
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Dhizuku 状态检查失败：${e.message}")
            false
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

    /** 反射调用隐藏方法 DevicePolicyManager.getDeviceOwnerComponentOnAnyUser()。 */
    private fun queryDeviceOwner(dpm: DevicePolicyManager): ComponentName? {
        return try {
            dpm.javaClass.getMethod("getDeviceOwnerComponentOnAnyUser")
                .invoke(dpm) as? ComponentName
        } catch (e: Throwable) {
            Log.w(TAG, "查询 Device Owner 失败：${e.message}")
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
     * 请求 Dhizuku 授权（拉起 Dhizuku 授权界面）。
     * 前置条件：已 [connect]。结果通过 [onResult] 回调。
     */
    fun requestPermission(context: Context, onResult: (Boolean) -> Unit) {
        try {
            if (!connect(context)) {
                onResult(false)
                return
            }
            if (isPermissionGranted()) {
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
            if (!ensureReady(context)) return false
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
