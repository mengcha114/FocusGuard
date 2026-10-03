package com.focusguard.app.enforce

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.access.GuardAccessibilityService
import com.focusguard.app.usage.UsageRuleStore
import kotlinx.coroutines.delay

/**
 * 应用硬封锁界面。
 *
 * 与 [BlockActivity] 的区别：
 * - BlockActivity 是一次性提示，用户点掉即可继续
 * - AppBlockActivity 是持续封锁，只要目标应用还超限就一直挡在前面，
 *   返回键无效，唯一出路是回桌面
 *
 * 之所以用 Activity 而不是悬浮窗：Activity 能可靠地覆盖住目标应用的
 * 全部内容（包括视频等使用 SurfaceView 的界面），悬浏窗在部分机型上
 * 会被视频层盖住，达不到"无法查看任何内容"的要求。
 */
class AppBlockActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AppBlockActivity"
        private const val EXTRA_PACKAGE = "package_name"
        private const val EXTRA_LABEL = "app_label"
        private const val EXTRA_USED_MINUTES = "used_minutes"
        private const val EXTRA_LIMIT_MINUTES = "limit_minutes"
        private const val EXTRA_BLOCK_UNTIL = "block_until"

        /**
         * 拉起应用封锁页。
         *
         * @param blockUntil 临时封锁截止时间戳（毫秒）。>0 时显示
         *   「封锁至 HH:mm + 剩余倒计时」；=0 时显示旧的"今日已用/上限"样式。
         */
        fun show(
            context: Context,
            packageName: String,
            appLabel: String,
            usedMinutes: Int,
            limitMinutes: Int,
            blockUntil: Long = 0L
        ) {
            val intent = Intent(context, AppBlockActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, appLabel)
                putExtra(EXTRA_USED_MINUTES, usedMinutes)
                putExtra(EXTRA_LIMIT_MINUTES, limitMinutes)
                putExtra(EXTRA_BLOCK_UNTIL, blockUntil)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            context.startActivity(intent)
        }

        /**
         * 当前挂在前台的封锁页实例（弱引用语义：onDestroy 时清空）。
         *
         * 用于「用户改了限额」这种场景：规则一变，正在显示的封锁页必须
         * 立即撤掉，否则用户会看到"限额都改了还在被封锁"。
         */
        @Volatile
        private var instance: AppBlockActivity? = null

        /**
         * 若封锁页正在显示指定应用，则立刻关闭它。
         *
         * @param packageName 被解除封锁的包名；传 null 表示无条件关闭
         */
        fun dismissIfShowing(packageName: String? = null) {
            val act = instance ?: return
            if (packageName != null && act.blockedPackage != packageName) return
            act.runOnUiThread {
                runCatching {
                    Log.d(TAG, "限额已变更，关闭封锁页：${act.blockedPackage}")
                    act.finish()
                }
            }
        }
    }

    private var blockedPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上也能显示，防止用户息屏再唤醒绕过
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 保持屏幕常亮，避免息屏后封锁页被系统回收
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        instance = this
        blockedPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        // 走到 Activity 说明没有悬浮窗可用：同样要真正停掉被锁应用
        // （后台声音/画中画随之结束），否则「封住」只封了界面
        runCatching {
            if (blockedPackage.isNotBlank()) {
                Thread {
                    runCatching {
                        com.focusguard.app.enhance.LockPolicies
                            .suspendBlock(applicationContext, blockedPackage)
                    }
                }.start()
            }
        }
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { blockedPackage }
        val used = intent.getIntExtra(EXTRA_USED_MINUTES, 0)
        val limit = intent.getIntExtra(EXTRA_LIMIT_MINUTES, 0)
        val blockUntil = intent.getLongExtra(EXTRA_BLOCK_UNTIL, 0L)

        setContent {
            // 与悬浮窗共用同一套界面（含内嵌答题）：两条路径外观与规则完全一致
            AppBlockContent(
                appLabel = label,
                usedMinutes = used,
                limitMinutes = limit,
                blockUntil = blockUntil,
                onUnlocked = {
                    // 答题通过：解除本次封锁（回退一段今日用量）后回桌面
                    com.focusguard.app.enforce.AppLockToolExecutor
                        .grantExtraTime(this, blockedPackage, com.focusguard.app.data.Settings(this).appBlockMinutes)
                    goHome()
                },
                onGoHome = { goHome() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 复用同一实例时刷新数据，避免显示上一个应用的信息
        setIntent(intent)
        recreate()
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // 自检：回到前台时确认封锁是否仍然成立。
        // 用户可能在别处（应用管控页）刚把限额改大或删掉规则——
        // 这时封锁页应当自己退场，而不是继续挡着。
        if (!stillBlocked()) {
            Log.d(TAG, "封锁条件已不成立，封锁页自行退出：$blockedPackage")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        // 兜底路径（无悬浮窗权限）同样要解除「封锁挂起」，否则应用会一直被冻着
        runCatching {
            com.focusguard.app.enhance.LockPolicies.releaseBlock(applicationContext, blockedPackage)
        }
    }

    /**
     * 封锁是否仍然成立。
     *
     * 两个来源都要看：AI 执法下发的临时封锁（AppBlockStore），
     * 以及用户配置的每日硬上限（UsageRuleStore）。任一成立就继续挡。
     */
    private fun stillBlocked(): Boolean {
        if (blockedPackage.isBlank()) return false
        return runCatching {
            val tempBlocked = com.focusguard.app.data.AppBlockStore(this)
                .isBlocked(blockedPackage)
            if (tempBlocked) return@runCatching true

            val store = com.focusguard.app.usage.UsageRuleStore.shared(this)
            val limit = store.getRule(blockedPackage)?.hardBlockMinutes
                ?: return@runCatching false
            val usedMinutes = (store.getTodaySeconds(blockedPackage) / 60).toInt()
            usedMinutes >= limit
        }.getOrDefault(true) // 读取异常时保守起见继续挡
    }

    @Deprecated("Back is intentionally disabled while blocked")
    override fun onBackPressed() {
        // 封锁期间返回键直接回桌面，而不是退回被封锁的应用
        goHome()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 注意：这里不能直接 finish。
        // 早期实现一 finish 就把封锁页销毁，用户按 Home 再点回被封锁的应用
        // 就能正常使用（这正是"超过最高时间后仍可使用"的直接原因）。
        // 现在交给 LockGuardService 巡检：用户若再打开被封锁应用，
        // 守护服务会在 1 秒内重新拉起本页面。
        Log.d(TAG, "用户离开封锁页，守护服务将在其重新打开被封应用时拦截")
    }

    /**
     * 封锁页失焦（下拉通知栏等）时不做处理，
     * 顶回逻辑统一由守护服务负责，避免与系统窗口争抢焦点。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            GuardAccessibilityService.instance?.dismissNotificationShade()
        }
    }

    private fun goHome() {
        val service = GuardAccessibilityService.instance
        if (service != null) {
            service.performHome()
        } else {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
        finish()
    }
}

/** 「仅锁该软件」临时封锁页：显示封锁截止时间与剩余倒计时。 */
@Composable
private fun LimitRow(
    label: String,
    value: String,
    valueColor: Color,
    labelColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = labelColor)
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

private fun formatMinutes(minutes: Int): String = when {
    minutes <= 0 -> "0 分钟"
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}
