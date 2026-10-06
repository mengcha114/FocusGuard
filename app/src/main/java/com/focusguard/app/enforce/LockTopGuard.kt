package com.focusguard.app.enforce

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LogStore
import com.focusguard.app.enhance.DhizukuEnhancer
import com.focusguard.app.enhance.LockPolicies

/**
 * 锁机期间的「陌生人守卫」：有别的应用（尤其是语音助手）盖在锁机页上方时，
 * 先顶回，顶不掉就把这个应用限制掉。
 *
 * 分档（用户拍板「激进：连系统助手也尽量限制」）：
 * 1. 第一次看到 → 交给既有的顶回动作（无障碍 HOME + reassert）；
 * 2. 同一个包连续 [REASSERT_STRIKES] 次仍出现在上方 → 冻结它
 *    （`LockPolicies.suspendBlock`，带引用计数，锁机结束自动解冻）；
 *    系统自带的语音助手冻结常被系统拒绝 → 再试「隐藏」，仍失败就只反复顶回并记一条日志。
 *
 * 硬底线：**绝不**碰我们依赖或用户离不开的东西 —— 系统 UI/桌面/输入法/电话/相机/
 * 权限与安装界面（详见 [isProtected]）。任何一次行动的记录都会在锁机结束时还原
 * （[releaseAll] 由 LockPolicies.onLockEnd 调用）。
 */
object LockTopGuard {

    private const val TAG = "LockTopGuard"

    /** 同一个包要被顶回几次才升级为冻结。 */
    private const val REASSERT_STRIKES = 2

    /** 同一个包的动作冷却。 */
    private const val PER_PKG_COOLDOWN_MS = 3_000L

    /** 单次锁机最多冻结几个（防止系统级拉锯）。 */
    private const val MAX_FREEZE_PER_LOCK = 6

    /** 连续失败多少次开始熔断。 */
    private const val FAIL_STRIKES = 3
    private const val FAIL_COOLDOWN_MS = 60_000L

    /** 绝不能碰：我们依赖它们，或属于系统底座 / 用户正在用的东西。 */
    private val NEVER = setOf(
        "com.android.systemui", "com.android.shell",
        "moe.shizuku.privileged.api", "com.rosan.dhizuku",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.settings", "com.android.keyguard",
        "com.android.phone", "com.android.dialer", "com.android.server.telecom",
        "com.android.incallui", "com.google.android.dialer",
        "com.android.camera", "com.huawei.camera", "com.android.camera2"
    )

    private val SYSTEM_UI_HINTS = listOf("systemui", "launcher", "keyguard", "telecom", "incallui")

    /** 系统语音助手（尽量限制；冻结失败时退化为顶回）。 */
    private val ASSISTANTS = setOf(
        "com.huawei.vassistant", "com.hihonor.assistant",
        "com.google.android.googlequicksearchbox", "com.google.android.apps.googlequicksearchbox",
        "com.miui.voiceassist", "com.xiaomi.voiceassist",
        "com.vivo.assistant", "com.coloros.assistantscreen",
        "com.samsung.android.bixby.agent", "com.samsung.android.bixby.voiceinput"
    )

    @Volatile private var strikes = HashMap<String, Int>()
    @Volatile private var lastActionAt = HashMap<String, Long>()
    @Volatile private var frozenByUs = LinkedHashSet<String>()
    @Volatile private var hiddenByUs = LinkedHashSet<String>()
    @Volatile private var failCount = 0
    @Volatile private var failUntil = 0L

    /** 白名单：这些包即使盖在锁机页上方也**绝不**动手（顶回仍由无障碍负责）。 */
    fun isProtected(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == context.packageName) return true
        if (pkg in NEVER) return true
        if (SYSTEM_UI_HINTS.any { pkg.contains(it, ignoreCase = true) }) return true
        // 输入法：答题要用键盘，正在打字时被冻掉等于锁死解锁流程
        if (isInputMethod(context, pkg)) return true
        // 桌面：顶回动作依赖它，冻了会黑屏
        if (isLauncher(context, pkg)) return true
        return false
    }

    private fun isInputMethod(context: Context, pkg: String): Boolean = runCatching {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.inputMethodList.any { it.packageName == pkg }
    }.getOrDefault(false)

    private fun isLauncher(context: Context, pkg: String): Boolean = runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName == pkg }
    }.getOrDefault(false)

    private fun isSystemApp(context: Context, pkg: String): Boolean = runCatching {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    /**
     * 锁机期间「看到陌生窗口」时调用（无障碍窗口事件 / 守护巡检都会走这里）。
     * 返回 true = 已升级处理（调用方可跳过默认顶回），false = 交回调用方顶回。
     */
    fun onForeignAboveLock(context: Context, pkg: String): Boolean {
        val app = context.applicationContext
        if (!com.focusguard.app.data.Settings(app).strangerGuard) return false
        if (isProtected(app, pkg)) return false
        val now = System.currentTimeMillis()
        if (now < failUntil) return false
        if (now - (lastActionAt[pkg] ?: 0L) < PER_PKG_COOLDOWN_MS) return true
        val n = (strikes[pkg] ?: 0) + 1
        strikes[pkg] = n
        if (n < REASSERT_STRIKES) return false
        lastActionAt[pkg] = now
        if (frozenByUs.size >= MAX_FREEZE_PER_LOCK) return true

        val assistant = pkg in ASSISTANTS
        val frozen = runCatching { LockPolicies.suspendBlock(app, pkg) }.getOrDefault(false)
        if (frozen) {
            frozenByUs.add(pkg)
            report(app, pkg, if (assistant) "语音助手已冻结（锁机期间禁止呼出）" else "盖在锁机页上方，已冻结")
            return true
        }
        if (assistant &&
            runCatching { DhizukuEnhancer.setApplicationHidden(app, setOf(pkg), true) }
                .getOrDefault(false)
        ) {
            hiddenByUs.add(pkg)
            report(app, pkg, "语音助手已隐藏（锁机期间禁止呼出）")
            return true
        }
        failCount++
        if (failCount >= FAIL_STRIKES) {
            failUntil = now + FAIL_COOLDOWN_MS
            failCount = 0
        }
        val what = if (isSystemApp(app, pkg)) "系统应用" else "该应用"
        report(app, pkg, "无法限制（$what，或缺少冻结权限），已改为反复顶回锁机页")
        return true
    }

    /** 锁机结束 / 服务销毁：把本机制加的冻结与隐藏全部还原。 */
    fun releaseAll(context: Context) {
        val app = context.applicationContext
        frozenByUs.forEach { pkg -> runCatching { LockPolicies.releaseBlock(app, pkg) } }
        hiddenByUs.forEach { pkg ->
            runCatching { DhizukuEnhancer.setApplicationHidden(app, setOf(pkg), false) }
        }
        if (frozenByUs.isNotEmpty() || hiddenByUs.isNotEmpty()) {
            Log.d(TAG, "已还原 ${frozenByUs.size} 个冻结 / ${hiddenByUs.size} 个隐藏")
        }
        frozenByUs.clear()
        hiddenByUs.clear()
        strikes.clear()
        lastActionAt.clear()
        failCount = 0
        failUntil = 0L
    }

    /** 当前焦点窗口的包名（无障碍服务在跑时可用）——用来判断"谁盖在锁机页上方"。 */
    fun focusedWindowPackage(): String? = runCatching {
        com.focusguard.app.access.GuardAccessibilityService.instance
            ?.windows
            ?.firstOrNull { it.isFocused }
            ?.root?.packageName?.toString()
    }.getOrNull()

    private fun report(context: Context, pkg: String, reason: String) {
        runCatching {
            val label = runCatching {
                context.packageManager
                    .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
                    .toString()
            }.getOrDefault(pkg)
            LogStore(context).addLog(
                DetectionLog(
                    classification = "NEUTRAL",
                    confidence = 1f,
                    reason = "锁机防护：$label（$pkg）$reason",
                    action = "NONE",
                    source = "ERROR",
                    appLabel = label
                )
            )
        }
    }
}
