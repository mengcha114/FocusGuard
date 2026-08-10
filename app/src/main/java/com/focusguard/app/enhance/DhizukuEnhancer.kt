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
 * ## 降级
 * 任一步骤失败都返回 false，上层回退到覆盖层 + 无障碍方案，功能不中断。
 * 失败的具体原因会通过 [lastError] 暴露，供 Toast/日志展示，便于定位。
 */
object DhizukuEnhancer {

    private const val TAG = "DhizukuEnhancer"
    private const val PREFS_NAME = "dhizuku_enhancer"
    private const val KEY_EVER_READY = "ever_ready"
    private const val KEY_READINESS_CHECKED = "readiness_checked"

    /** Dhizuku 服务器连接是否建立（HiddenApiBypass + init 完成）。 */
    @Volatile
    private var connected = false

    @Volatile
    private var wrappedDpm: DevicePolicyManager? = null

    @Volatile
    private var ownerComponent: ComponentName? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var warmupRunning = false

    /** 最近一次失败原因（排查用，Toast 展示）。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 完整就绪：连接 + 授权 + 包装 DPM 可用。Lock Task 可用的唯一判据。 */
    fun isReady(): Boolean = initialized && wrappedDpm != null && ownerComponent != null

    /**
     * guardTick 专用就绪检查：只读缓存，不触发任何 IPC / Binder 操作。
     *
     * guardTick 运行在后台协程（Dispatchers.Default），在此调用 ensureReady
     * 会触发 Dhizuku Binder 初始化（部分操作需要主线程），造成死锁 → 白屏/ANR。
     * 改为只检查已缓存的 initialized 标志，仅首次锁机前调用一次 ensureReady
     * 完成初始化，后续 tick 均走轻量只读路径。
     */
    fun isReadyCached(): Boolean = isReady()

    /**
     * 是否应优先走 Dhizuku Activity 路径。
     *
     * 进程首次启动时内存缓存尚未初始化，但用户此前已经授权过 Dhizuku。
     * 仅检查 [isReadyCached] 会把这段初始化窗口误判为“无 Dhizuku”，从而先展示
     * 悬浮窗锁机页，几秒后再切到 Activity。成功就绪后持久记录能力提示，后续
     * 进程冷启动也直接展示 Activity；真正准备失败时仍由上层降级悬浮窗。
     */
    fun shouldPreferActivity(context: Context): Boolean {
        if (isReady()) return true
        return try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_EVER_READY, false)
        } catch (_: Throwable) {
            false
        }
    }

    /** 尚无可信探测结果时先等待后台探测，不猜测使用哪套页面。 */
    fun isReadinessUnknown(context: Context): Boolean {
        if (isReady()) return false
        return try {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            !prefs.getBoolean(KEY_READINESS_CHECKED, false) &&
                !prefs.getBoolean(KEY_EVER_READY, false)
        } catch (_: Throwable) {
            false
        }
    }

    /** 幂等后台预热，避免多个守护入口同时发起 Dhizuku Binder 初始化。 */
    fun warmUpAsync(context: Context) {
        if (isReady() || warmupRunning) return
        synchronized(this) {
            if (isReady() || warmupRunning) return
            warmupRunning = true
        }
        Thread {
            try {
                ensureReady(context.applicationContext)
            } finally {
                warmupRunning = false
            }
        }.start()
    }

    /**
     * 建立 Dhizuku 连接（幂等）。
     * 只要求 Dhizuku 已安装并激活为 Device Owner，**不要求已授权本应用**。
     * 支持 Android 8.0+（Dhizuku 官方支持范围，Lock Task 需要 API 21+）。
     */
    fun connect(context: Context): Boolean {
        if (connected) return true
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                lastError = "系统版本过低（需 Android 8.0+）"
                return false
            }
            if (!HiddenApiBypass.setHiddenApiExemptions("")) {
                lastError = "隐藏 API 豁免失败"
                return false
            }
            if (!Dhizuku.init(context.applicationContext)) {
                lastError = "Dhizuku 未安装或未激活为设备所有者"
                Log.d(TAG, lastError)
                return false
            }
            connected = true
            lastError = ""
            Log.d(TAG, "Dhizuku 连接成功")
            true
        } catch (e: Throwable) {
            lastError = "连接异常：${e.message}"
            Log.w(TAG, "Dhizuku 连接失败", e)
            connected = false
            false
        }
    }

    /** 本应用是否已获得 Dhizuku 授权（需先 [connect]）。 */
    fun isPermissionGranted(): Boolean {
        return try {
            val granted = connected && Dhizuku.isPermissionGranted()
            // 查询本身成功时清除旧的瞬时异常，确保 false 被识别为明确未授权。
            if (lastError.startsWith("查询授权状态异常")) lastError = ""
            granted
        } catch (e: Throwable) {
            lastError = "查询授权状态异常：${e.message}"
            Log.w(TAG, "查询 Dhizuku 授权状态失败", e)
            false
        }
    }

    /**
     * 确保完整就绪（连接 + 授权 + 构造包装 DPM）。
     * 幂等；未授权时返回 false 但不阻断后续 [requestPermission]。
     */
    @Synchronized
    fun ensureReady(context: Context): Boolean {
        if (isReady()) return true
        try {
            if (!connect(context)) {
                // 开机后 Dhizuku Provider/服务通常晚于本应用启动，init=false 只
                // 表示“此刻不可达”，不能据此清除历史成功状态。否则重启续锁会
                // 从 Activity 错误降级为悬浮窗。明确连接成功但授权为 false 时，
                // 下方分支仍会清除历史标志。
                markReadinessChecked(context, clearEverReady = false)
                return false
            }
            val permissionGranted = isPermissionGranted()
            val permissionQueryFailed = lastError.startsWith("查询授权状态异常")
            if (!permissionGranted) {
                if (permissionQueryFailed) {
                    // Binder/服务瞬时异常是“未知”，不能当成用户明确撤权。
                    markReadinessChecked(context, clearEverReady = false)
                    return false
                }
                lastError = "未获得 Dhizuku 授权"
                markReadinessChecked(context, clearEverReady = true)
                return false
            }

            val dpm = buildWrappedDpm(context)
            if (dpm == null) return false

            // 2.5.4 提供公开的 getOwnerComponent()（init 时已记录 Device Owner 组件）
            val owner = try {
                Dhizuku.getOwnerComponent()
            } catch (e: Throwable) {
                lastError = "获取 Device Owner 组件失败：${e.message}"
                Log.w(TAG, lastError)
                null
            } ?: return false

            wrappedDpm = dpm
            ownerComponent = owner
            initialized = true
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_EVER_READY, true)
                .putBoolean(KEY_READINESS_CHECKED, true)
                .apply()
            lastError = ""
            Log.d(TAG, "Dhizuku 增强已就绪，owner=$owner")
            return true
        } catch (e: Throwable) {
            lastError = "就绪检查异常：${e.message}"
            Log.w(TAG, "Dhizuku 就绪检查失败", e)
            initialized = false
            return false
        } finally {
            // 瞬时 Binder/包装失败不清历史成功证据，避免下一次又先闪悬浮窗。
            markReadinessChecked(context, clearEverReady = false)
        }
    }

    private fun markReadinessChecked(context: Context, clearEverReady: Boolean) {
        runCatching {
            val editor = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_READINESS_CHECKED, true)
            if (clearEverReady) editor.putBoolean(KEY_EVER_READY, false)
            editor.apply()
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
            Log.w(TAG, "Dhizuku 状态检查失败", e)
            false
        }
    }

    /**
     * 构造包装后的 DevicePolicyManager：
     * 1. 取本应用的 DevicePolicyManager（其 mService 就是系统 device_policy binder）
     * 2. 反射取 mService 字段（HiddenApiBypass 已放行）
     * 3. 用 [com.rosan.dhizuku.api.Dhizuku.binderWrapper] 包装 → 后续所有 DPM 调用
     *    被转发到 Dhizuku 服务器进程执行（以 Device Owner 身份）
     */
    private fun buildWrappedDpm(context: Context): DevicePolicyManager? {
        return try {
            val app = context.applicationContext
            val manager = app.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as DevicePolicyManager

            val field = try {
                DevicePolicyManager::class.java.getDeclaredField("mService")
            } catch (e: NoSuchFieldException) {
                lastError = "反射 mService 字段失败（系统版本差异）：${e.message}"
                Log.w(TAG, lastError)
                return null
            }
            field.isAccessible = true
            val oldInterface = field[manager] as android.os.IInterface

            val oldBinder = oldInterface.asBinder()
            val newBinder = Dhizuku.binderWrapper(oldBinder)
            val newInterface = IDevicePolicyManagerStubAsInterface(newBinder) ?: return null
            field[manager] = newInterface
            manager
        } catch (e: Throwable) {
            lastError = "构造包装 DPM 失败：${e.message}"
            Log.w(TAG, "构造包装 DPM 失败", e)
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
            lastError = "反射 IDevicePolicyManager.Stub.asInterface 失败：${e.message}"
            Log.w(TAG, lastError)
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
            lastError = "请求授权异常：${e.message}"
            Log.w(TAG, "请求 Dhizuku 授权失败", e)
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
            lastError = ""
            Log.d(TAG, "已把 ${context.packageName} 加入 Lock Task 白名单")
            return true
        } catch (e: Throwable) {
            lastError = "setLockTaskPackages 调用失败：${e.message}"
            Log.w(TAG, "授权 Lock Task 失败", e)
            return false
        }
    }

    /** 通过 Dhizuku 接口设置 Lock Task 允许的 Feature 特性（如禁用状态栏下拉、通知栏与锁屏屏障）。 */
    fun setLockTaskFeatures(context: Context, flags: Int): Boolean {
        try {
            if (!ensureReady(context)) return false
            val dpm = wrappedDpm ?: return false
            val comp = ownerComponent ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setLockTaskFeatures(comp, flags)
                Log.d(TAG, "设置 setLockTaskFeatures($flags) 成功")
            }
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "setLockTaskFeatures 失败：${e.message}")
            return false
        }
    }

    /** 通过 Dhizuku 接口设置 Keyguard（锁屏屏障）禁用状态。 */
    fun setKeyguardDisabled(context: Context, disabled: Boolean): Boolean {
        try {
            if (!ensureReady(context)) return false
            val dpm = wrappedDpm ?: return false
            val comp = ownerComponent ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setKeyguardDisabled(comp, disabled)
                Log.d(TAG, "设置 setKeyguardDisabled($disabled) 成功")
            }
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "setKeyguardDisabled 失败：${e.message}")
            return false
        }
    }

    /** 通过 Dhizuku 接口调用 DevicePolicyManager.setStatusBarDisabled 屏蔽/解除通知栏。 */
    fun setStatusBarDisabled(context: Context, disabled: Boolean): Boolean {
        try {
            if (!ensureReady(context)) return false
            val dpm = wrappedDpm ?: return false
            val comp = ownerComponent ?: return false
            dpm.setStatusBarDisabled(comp, disabled)
            Log.d(TAG, "设置 setStatusBarDisabled($disabled) 成功")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "setStatusBarDisabled 失败：${e.message}")
            return false
        }
    }

    /** 本应用是否已被允许进入 Lock Task 模式。 */
    fun isLockTaskPermitted(packageName: String): Boolean {
        val dpm = wrappedDpm ?: return false
        return try {
            dpm.isLockTaskPermitted(packageName)
        } catch (e: Throwable) {
            lastError = "查询 Lock Task 权限异常：${e.message}"
            Log.w(TAG, "查询 Lock Task 权限失败", e)
            false
        }
    }
}
