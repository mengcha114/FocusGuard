package com.focusguard.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusguard.app.data.LockState

/**
 * 锁机期间媒体键拦截：蓝牙耳机/线控长按是唤醒语音助手的物理入口
 * （走 MediaButton 广播路由，不经过应用窗口，dispatchKeyEvent 拦不住）。
 *
 * 锁机中收到媒体键事件直接吞掉（abortBroadcast 终止有序广播），
 * 语音助手与播放器都收不到；锁机结束/暂停期间放行。
 */
class MediaButtonBlocker : BroadcastReceiver() {

    companion object {
        private const val TAG = "MediaButtonBlocker"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        try {
            val state = LockState(context)
            if (state.isLocked && state.shouldBlockNow) {
                Log.d(TAG, "锁机中吞掉媒体键事件")
                if (isOrderedBroadcast) abortBroadcast()
            }
        } catch (e: Exception) {
            Log.w(TAG, "媒体键拦截处理失败：${e.message}")
        }
    }
}
