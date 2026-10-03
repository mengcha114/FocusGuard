package com.focusguard.app.enhance

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LogStore

/**
 * 防破解专项：识别并处置「能用来破解锁机」的工具。
 *
 * 对手与对策（详见计划文档）：
 * - 第三方「守护/冻结」类工具（冰箱、小黑屋、Island、黑阈、绿色守护…）：靠 Shizuku/root
 *   调 `am force-stop` / `pm suspend` 掐掉我们的进程 → **锁机期间先把它们冻结/隐藏**；
 * - 自动化/脚本（Auto.js、自动点击器、按键精灵、Tasker、MacroDroid）：自动答题或自动点解锁
 *   → 同样冻结/隐藏；
 * - 多开/分身/虚拟机（应用分身、VirtualApp、VMOS…）：在容器里用娱乐应用绕过前台检测 → 冻/隐；
 * - 远程控制（TeamViewer、向日葵、AnyDesk、AirDroid）：让同伙远程操作 → 冻/隐；
 * - 特权工具（Magisk、Xposed/LSPosed、幸运破解器、游戏/时间修改器）→ 冻/隐；
 * - root / Xposed / 调试器本身 → DPM 挡不住，**只留痕提示**，不拒绝解锁（避免误伤）。
 *
 * 安全边界：绝不冻结/隐藏 系统应用、系统更新过的应用、Shizuku、Dhizuku、以及本应用自己。
 */
object CrackGuard {

    private const val TAG = "CrackGuard"
    private const val PREFS = "focus_guard_crack"
    private const val KEY_HIDDEN = "hidden_pkgs"
    private const val KEY_REPORTED_LOCK = "reported_lock"
    private const val KEY_USER_TOKENS = "user_tokens"

    /** 绝不能碰的包：我们依赖它们，或属于系统底座。 */
    private val PROTECTED = setOf(
        "moe.shizuku.privileged.api",     // Shizuku 本体（我们依赖）
        "com.rosan.dhizuku",              // Dhizuku 本体（我们依赖）
        "com.android.shell", "com.android.systemui"
    )

    /** 包名特征（足够特异，避免误伤）。 */
    private val PKG_TOKENS = listOf(
        "catchingnow.icebox", "oasisfeng.island", "thanox", "brevent", "greenify",
        "autojs", "autox.js", "macrodroid", "net.dinglisch.android.taskerm",
        "parallel.space", "virtualapp", "vmos", "dualspace", "multiapp", "lbe.parallel",
        "xposed", "magisk", "lsposed", "lucky.patcher", "chelpus",
        "teamviewer", "anydesk", "airdroid", "sunlogin", "oray.sunlogin", "todesk", "rustdesk",
        "omarea", "ice.box", "freezeyou", "kernelsu", "apatch", "virtualxposed", "vphonegaga",
        "youlong", "yltool"
    )

    /** 应用名特征（中文工具基本靠这个命中）。 */
    private val LABEL_TOKENS = listOf(
        "冰箱", "小黑屋", "黑阈", "黑域", "绿色守护", "空调狗", "冰冻", "冻结大师", "应用冻结",
        "autojs", "auto.js", "自动点击器", "自动脚本", "按键精灵", "触动精灵", "按键宏", "auto clicker",
        "tasker", "macrodroid", "快捷指令自动化",
        "多开", "分身", "双开", "虚拟大师", "虚拟机", "vmos", "光速虚拟机", "炼妖壶", "island",
        "xposed", "magisk", "lsposed", "面具", "幸运破解", "lucky patcher", "烧饼", "游戏修改",
        "gg修改器", "gameguardian", "时间修改", "改机",
        "teamviewer", "向日葵", "anydesk", "airdroid", "远程控制", "远程协助",
        "scene", "微霸", "thanox", "黑盒",
        // 进程守护 / 「安全护盾」类：会拦截其它应用的操作（能掐掉我们的守护进程）
        "游龙", "游龙工具", "安全护盾", "进程守护",
        // 自动化 / 宏
        "自动精灵", "一触即发", "易点", "超级点击器", "脚本精灵", "auto js",
        // 多开 / 沙箱 / 云手机（只留明确的产品名，避免误伤普通应用）
        "多开助手", "双开助手", "红手指", "gaga", "虚拟大师",
        // 远程控制
        "todesk", "rustdesk", "网易uu远程", "uu远程",
        // 特权 / 改机 / 冻结
        "kernelsu", "apatch", "太极", "应用变量", "冰箱pro", "hibernator", "servicely",
        "greenify", "superfreezz", "nap time", "naptime"
    )

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户自定义特征（逗号/分号/顿号/换行分隔，供特殊工具补充）。 */
    fun userTokens(c: Context): String = prefs(c).getString(KEY_USER_TOKENS, "").orEmpty()

    fun setUserTokens(c: Context, value: String) =
        prefs(c).edit().putString(KEY_USER_TOKENS, value).apply()

    /**
     * 扫描已安装应用，返回命中「破解/自动化工具」特征的包名。
     * 已排除系统应用、系统更新过的应用、Shizuku/Dhizuku 与本应用。
     */
    fun matched(context: Context): Set<String> {
        val app = context.applicationContext
        val pm = app.packageManager
        val extras = userTokens(app)
            .split(',', '，', ';', '；', '、', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        return apps.filter { info ->
            val pkg = info.packageName
            if (pkg == app.packageName || pkg in PROTECTED) return@filter false
            val sys = (info.flags and (
                android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                )) != 0
            if (sys) return@filter false
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault("")
            // 用户白名单 / 已被判为学习办公的应用一律豁免：宁可漏冻，也不能误冻正常应用
            val white = com.focusguard.app.data.Settings(app).whitelist
                .split(',', '，', ';', '；', '、', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (white.any { w -> label.contains(w, true) || pkg.contains(w, true) }) {
                return@filter false
            }
            val categoryStore = com.focusguard.app.detection.AppCategoryStore.shared(app)
            val study = com.focusguard.app.detection.AppCategory.STUDY
            if (categoryStore.getUserOverride(pkg) == study || categoryStore.getLearned(pkg) == study) {
                return@filter false
            }
            val haystack = (pkg + " " + label).lowercase()
            PKG_TOKENS.any { haystack.contains(it) } ||
                LABEL_TOKENS.any { haystack.contains(it.lowercase()) } ||
                extras.any { haystack.contains(it) }
        }.map { it.packageName }.toSet()
    }

    /** 锁机期间隐藏命中工具（比冻结更彻底），逐包验证后记录，锁机结束恢复。 */
    fun applyHide(context: Context): Boolean {
        val app = context.applicationContext
        val targets = matched(app)
        if (targets.isEmpty()) return true
        val ok = DhizukuEnhancer.setApplicationHidden(app, targets, true)
        if (ok) {
            prefs(app).edit().putStringSet(KEY_HIDDEN, targets).apply()
            Log.d(TAG, "锁机期间隐藏 ${targets.size} 个破解/自动化工具")
        } else {
            Log.w(TAG, "隐藏破解工具未全部成功，下轮重试")
        }
        return ok
    }

    /** 恢复被隐藏的工具；逐包验证，未恢复的保留记录下次再试。 */
    fun revertHide(context: Context) {
        val app = context.applicationContext
        val hidden = prefs(app).getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        if (hidden.isEmpty()) return
        DhizukuEnhancer.setApplicationHidden(app, hidden, false)
        val left = hidden.filter { DhizukuEnhancer.isApplicationHidden(app, it) }.toSet()
        prefs(app).edit().putStringSet(KEY_HIDDEN, left).apply()
        Log.d(TAG, if (left.isEmpty()) "已恢复全部隐藏的破解工具" else "仍有 ${left.size} 个未恢复，稍后重试")
    }

    /** 当前仍被隐藏的工具数（设置页展示）。 */
    fun hiddenCount(context: Context): Int =
        prefs(context).getStringSet(KEY_HIDDEN, emptySet()).orEmpty().size

    /**
     * 锁机期间检测「破解环境」（root / Xposed / 调试器）并留痕。
     * 每次锁机只记一条日志；**不拒绝解锁**（避免误伤与不可解释的卡死）。
     */
    fun reportIfNeeded(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val lockTag = runCatching {
            com.focusguard.app.data.LockState(app).lockUntil.toString()
        }.getOrDefault("")
        if (lockTag.isNotBlank() && p.getString(KEY_REPORTED_LOCK, "") == lockTag) return

        val findings = mutableListOf<String>()
        if (isRooted()) findings += "设备已 root"
        if (isXposed()) findings += "检测到 Xposed/LSPosed"
        if (isDebuggerAttached()) findings += "检测到调试器连接"
        if (findings.isEmpty()) {
            if (lockTag.isNotBlank()) p.edit().putString(KEY_REPORTED_LOCK, lockTag).apply()
            return
        }
        p.edit().putString(KEY_REPORTED_LOCK, lockTag).apply()
        val text = findings.joinToString("、")
        Log.w(TAG, "锁机期间检测到破解环境：$text")
        runCatching {
            LogStore(app).addLog(
                DetectionLog(
                    classification = "NEUTRAL",
                    confidence = 1f,
                    reason = "检测到破解环境：$text（已记录；DPM 无法阻止 root/Xposed 改内存）",
                    action = "NONE",
                    source = "APP_CATEGORY",
                    appLabel = ""
                )
            )
        }
    }

    private fun isRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/adb/magisk"
        )
        return paths.any { runCatching { java.io.File(it).exists() }.getOrDefault(false) }
    }

    private fun isXposed(): Boolean = runCatching {
        Class.forName("de.robv.android.xposed.XposedBridge")
        true
    }.getOrDefault(false)

    private fun isDebuggerAttached(): Boolean =
        runCatching { android.os.Debug.isDebuggerConnected() }.getOrDefault(false)
}
