package com.focusguard.app.enforce

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 没有 Shizuku / Dhizuku 授权时，怎么把被锁应用「真正停掉」。
 *
 * 三级降级（有工具就用工具，没有才走这里）：
 * 1. `setPackagesSuspended` —— 有 Shizuku/Dhizuku 时用（[com.focusguard.app.enhance.LockPolicies.suspendBlock]）；
 * 2. `killBackgroundProcesses` —— 普通权限即可，但 Android 11+ 对第三方收紧，常常不生效；
 * 3. **无障碍代点系统「强行停止」** —— 打开该应用的「应用信息」页，找到「强行停止 / 停止 / Force stop」
 *    并点击。这一步在**我们的封锁悬浮窗后面**完成，用户只看到封锁页。
 *
 * 只在 [requestStop] 被调用后的 20 秒内动作，且只点应用信息页里那个按钮，
 * 绝不误点其他系统界面。
 */
object ForceStopHelper {

    private const val TAG = "ForceStopHelper"
    private const val WINDOW_MS = 20_000L

    @Volatile private var armedPkg: String? = null
    @Volatile private var armedAt = 0L

    fun isArmed(): Boolean {
        val pkg = armedPkg ?: return false
        if (System.currentTimeMillis() - armedAt > WINDOW_MS) {
            armedPkg = null
            return false
        }
        return pkg.isNotEmpty()
    }

    fun armedPackage(): String? = if (isArmed()) armedPkg else null

    fun disarm() {
        armedPkg = null
    }

    /** 是否是「应用信息」类界面（各 ROM 包名不同，用关键词判断）。 */
    fun isAppDetailHost(pkg: String): Boolean =
        pkg == "com.android.settings" || pkg.contains("settings") || pkg.contains("permission")

    /** 按钮文案（中英 + 常见 ROM 说法）。 */
    private val STOP_TEXTS = listOf(
        "强行停止", "强制停止", "停止", "结束运行", "结束", "force stop", "stop", "end"
    )

    /**
     * 请求把 [pkg] 停掉：先试便宜的内核接口，再布置无障碍代点。
     * 应在任意线程调用；界面跳转是无声的（被我们的悬浮窗盖着）。
     */
    fun requestStop(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        // 冲突防护：绝不停止本应用自己（否则守护/锁机一起没了）
        if (pkg == context.packageName) return
        // ① 便宜的一步：后台进程清理（可能无效，但零成本）
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses(pkg)
            Log.d(TAG, "killBackgroundProcesses($pkg) 已尝试")
        }
        // ② 布置无障碍代点，并打开「应用信息」页（在悬浮窗后面）
        armedPkg = pkg
        armedAt = System.currentTimeMillis()
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "已打开应用信息页，等待代点强行停止：$pkg")
        }.onFailure { Log.w(TAG, "打开应用信息页失败：${it.message}") }
    }

    private fun matches(node: AccessibilityNodeInfo?): Boolean {
        val text = node?.text?.toString()?.trim()?.lowercase() ?: return false
        return STOP_TEXTS.any { text == it || text.contains(it) }
    }

    /**
     * 在「应用信息」窗口里点掉强行停止。返回是否点到了。
     * 只遍历有限节点，避免卡顿。
     */
    fun clickForceStop(root: AccessibilityNodeInfo?): Boolean {
        if (!isArmed() || root == null) return false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 300) {
            val node = stack.removeLast()
            visited++
            if (matches(node) && node.isEnabled &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                Log.d(TAG, "已代点强行停止：${armedPackage()}")
                disarm()
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }
}
