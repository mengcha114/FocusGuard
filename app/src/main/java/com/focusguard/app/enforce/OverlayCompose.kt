package com.focusguard.app.enforce

import android.content.Context
import androidx.compose.runtime.Composable
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

/**
 * 在**没有 Activity** 的悬浮窗里承载 Compose 内容。
 *
 * Compose 需要 Lifecycle / SavedStateRegistry / ViewModelStore 三个 owner，
 * 悬浮窗只有 Service/Application 上下文，所以这里提供一个最小宿主。
 * （与 [AppBlockOverlay] 内部那套是同一思路，抽出来给草稿纸复用。）
 *
 * 这样悬浮窗里的草稿纸可以直接用 Compose 版 [com.focusguard.app.ui.components.ScratchpadOverlay]，
 * 不必再维护一份原生 View 实现 —— 之前两份实现分叉，正是
 * 「悬浮窗里草稿纸不能用/和答题页不一样」的根源。
 */
fun overlayComposeView(context: Context, content: @Composable () -> Unit): ComposeView {
    val owner = OverlayComposeOwner().apply { start() }
    return ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent(content)
    }
}

private class OverlayComposeOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
}
