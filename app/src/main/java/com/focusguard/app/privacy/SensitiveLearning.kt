package com.focusguard.app.privacy

import android.content.Context

/**
 * 「AI 判定为隐私敏感」的应用名单。
 *
 * 应用类型识别时，模型除了给出内容分类，还会标记这个应用是否属于隐私敏感
 * （网银、支付、证件、密码管理、私人相册等）。命中后记入本名单：
 * 之后遇到同一应用**直接本地跳过截图与上传**，不需要再问一次模型。
 *
 * 局限（必须说明）：AI 只能看到已经上传的那一张截图，所以"第一次"仍然会传。
 * 本地判定（敏感应用特征、密码输入框、卡号/身份证/验证码等文字）负责挡住绝大多数，
 * 本名单是补充——把模型发现的应用从此前移到本地拦截。
 *
 * 名单在设置页可见可清空：误标会导致该应用不再被检测。
 */
object SensitiveLearning {

    private const val PREFS = "focus_guard_sensitive_learning"
    private const val KEY_PACKAGES = "packages"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 纯逻辑（传入 SharedPreferences，便于单测） ──────────

    internal fun packagesOf(prefs: android.content.SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()

    internal fun isMarkedIn(prefs: android.content.SharedPreferences, packageName: String): Boolean =
        packageName.isNotBlank() && packagesOf(prefs).contains(packageName)

    internal fun markIn(prefs: android.content.SharedPreferences, packageName: String) {
        if (packageName.isBlank()) return
        val current = packagesOf(prefs).toMutableSet()
        if (current.add(packageName)) {
            prefs.edit().putStringSet(KEY_PACKAGES, current).apply()
        }
    }

    internal fun clearIn(prefs: android.content.SharedPreferences) {
        prefs.edit().remove(KEY_PACKAGES).apply()
    }

    // ── Context 版本（运行时用） ────────────────────────────

    /** 全部被标记的包名。 */
    fun packages(context: Context): Set<String> = packagesOf(prefs(context))

    fun isMarked(context: Context, packageName: String): Boolean =
        isMarkedIn(prefs(context), packageName)

    /** 标记一个应用（幂等）。 */
    fun mark(context: Context, packageName: String) = markIn(prefs(context), packageName)

    /** 取消标记一个应用。 */
    fun unmark(context: Context, packageName: String) {
        val prefs = prefs(context)
        val current = packagesOf(prefs).toMutableSet()
        if (current.remove(packageName)) prefs.edit().putStringSet(KEY_PACKAGES, current).apply()
    }

}
}
