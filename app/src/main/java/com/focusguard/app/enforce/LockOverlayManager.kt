package com.focusguard.app.enforce

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.focusguard.app.data.LockState
import com.focusguard.app.ui.theme.FocusGuardOverlayTheme

/**
 * TYPE_APPLICATION_OVERLAY 全屏覆盖层管理器。
 *
 * ## 为什么需要这个
 * Activity 的锁机页存在一个无法回避的软肋：小窗（Picture-in-Picture / 浮动窗口）
 * 的 z-order 天然高于普通 Activity。用户在支持小窗的 ROM 上，只需把任意 App
 * 切到小窗，就能在锁机页上方操作，相当于无视锁机。
 *
 * TYPE_APPLICATION_OVERLAY 窗口（需要 SYSTEM_ALERT_WINDOW 权限）是
 * Android 上 App 能申请的最高 z-order：
 * - 覆盖所有普通 Activity（包括小窗、分屏）
 * - 覆盖通知栏拉下来的阴影区域（注意：STATUS_BAR 本身仍在最顶，但内容区域能覆盖）
 * - 不受"清后台"影响——View 属于服务进程，进程活着覆盖层就活着
 *
 * ## 设计约束
 * 1. 仅在锁机期间（shouldBlockNow）显示，解锁/暂停立即隐藏
 * 2. 覆盖层本身只有一个半透明黑色底 + 一行文字，不拦截触摸，
 *    真正的答题交互仍走 Activity（因为输入法需要依附 Activity Window）
 * 3. Compose 运行在 WindowManager View 上需要一个假的 LifecycleOwner
 *
 * ## 权限降级
 * 若用户未授权 SYSTEM_ALERT_WINDOW，覆盖层静默不启动，
 * Activity + 无障碍双重防线仍然生效，功能不中断。
 */
object LockOverlayManager {

    private const val TAG = "LockOverlayManager"

    /** 覆盖层是否当前可见。 */
    @Volatile
    var isShowing: Boolean = false
        private set

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    /**
     * 显示覆盖层（幂等，已显示时不重复添加）。
     *
     * @param context 任意 Context，内部转为 ApplicationContext
     * @param lockState 当前锁机状态，传入以便覆盖层读取倒计时文本
     * @param onStartChallenge 用户点击"去答题"按钮时的回调
     */
    @Synchronized
    fun show(
        context: Context,
        lockState: LockState,
        onStartChallenge: () -> Unit
    ) {
        if (isShowing) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，覆盖层不启动（Activity 防线仍有效）")
            return
        }

        try {
            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val owner = OverlayLifecycleOwner()
            owner.start()
            lifecycleOwner = owner

            val params = buildLayoutParams()

            val root = FrameLayout(appContext)
            val composeView = ComposeView(appContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    FocusGuardOverlayTheme {
                        LockOverlayContent(
                            lockState = lockState,
                            onStartChallenge = onStartChallenge
                        )
                    }
                }
            }
            root.addView(composeView)
            overlayRoot = root

            wm.addView(root, params)
            isShowing = true
            Log.d(TAG, "覆盖层已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示覆盖层失败：${e.message}")
            cleanup()
        }
    }

    /** 隐藏并销毁覆盖层。 */
    @Synchronized
    fun hide() {
        if (!isShowing) return
        cleanup()
        Log.d(TAG, "覆盖层已隐藏")
    }

    private fun cleanup() {
        try {
            val root = overlayRoot
            val wm = windowManager
            if (root != null && wm != null) {
                wm.removeViewImmediate(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "移除覆盖层视图失败：${e.message}")
        }
        try {
            lifecycleOwner?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止 LifecycleOwner 失败：${e.message}")
        }
        overlayRoot = null
        windowManager = null
        lifecycleOwner = null
        isShowing = false
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // NOT_FOCUSABLE 保证覆盖层不抢焦点 → 答题 Activity 的输入法不受影响
            // LAYOUT_IN_SCREEN | LAYOUT_INSET_DECOR 让它覆盖状态栏 / 导航栏区域
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    // ── 最简 LifecycleOwner，让 ComposeView 在非 Activity 上下文里正常运行 ──────

    private class OverlayLifecycleOwner :
        LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun start() {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}
