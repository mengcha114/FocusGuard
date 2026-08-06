package com.focusguard.app.enforce

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.focusguard.app.data.LockState
import com.focusguard.app.ui.screens.UnlockChallengeScreen

/**
 * 解锁挑战答题页（独立 Activity）。
 *
 * 为什么独立成 Activity：答题需要弹出输入法，而锁机页的"失焦即顶回"
 * 会把输入法顶掉形成死循环（闪退）。独立页面拥有自己的窗口，
 * 锁机页通过 [active] 标志感知答题状态，答题期间不执行顶回。
 */
class UnlockChallengeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "UnlockChallengeActivity"

        /** 答题页是否在前台。锁机页据此暂停"失焦即顶回"。 */
        @Volatile
        var active: Boolean = false
            private set

        /** 当前答题页实例，解锁后由它通知锁机页收尾。 */
        @Volatile
        var instance: UnlockChallengeActivity? = null
            private set

        fun show(context: Context, requiredCorrect: Int = 1) {
            val intent = Intent(context, UnlockChallengeActivity::class.java).apply {
                putExtra(EXTRA_REQUIRED_CORRECT, requiredCorrect)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            context.startActivity(intent)
        }

        private const val EXTRA_REQUIRED_CORRECT = "required_correct"
    }

    private lateinit var lockState: LockState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockState = LockState(this)
        instance = this
        active = true
        val requiredCorrect = intent.getIntExtra(EXTRA_REQUIRED_CORRECT, 1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // 沉浸模式，隐藏系统栏
        try {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } catch (e: Exception) {
            Log.w(TAG, "进入沉浸模式失败：${e.message}")
        }

        setContent {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212))
            ) {
                UnlockChallengeScreen(
                    requiredCorrect = requiredCorrect,
                    onUnlocked = {
                        // 答对全部题目：解锁并收尾
                        lockState.releaseLock()
                        LockScreenActivity.instance?.onUnlockedExternally()
                        finish()
                    }
                )
            }
        }
    }

    @Deprecated("Back returns to lock screen, not unlock")
    override fun onBackPressed() {
        // 返回键回到锁机页而不是解锁
        LockScreenActivity.reassert(this)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        active = false
        Log.d(TAG, "答题页已关闭，active=false")
    }
}
