package com.focusguard.app.privacy

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 隐私透明度计数：今日上传次数、跳过次数与最近一次跳过原因。
 * 只记计数与脱敏后的原因，不保存任何屏幕内容；跨天自动归零。
 */
object PrivacyStats {

    private const val PREFS = "focus_guard_privacy_stats"
    private const val KEY_DATE = "date"
    private const val KEY_UPLOADS = "uploads"
    private const val KEY_SKIPS = "skips"
    private const val KEY_LAST_SKIP = "last_skip"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun rollIfNeeded(p: SharedPreferences): SharedPreferences.Editor {
        val e = p.edit()
        if (p.getString(KEY_DATE, "") != today()) {
            e.putString(KEY_DATE, today()).putInt(KEY_UPLOADS, 0).putInt(KEY_SKIPS, 0)
        }
        return e
    }

    fun recordUpload(context: Context) {
        val p = prefs(context)
        rollIfNeeded(p).putInt(KEY_UPLOADS, p.getInt(KEY_UPLOADS, 0) + 1).apply()
    }

    fun recordSkip(context: Context, reason: String) {
        val p = prefs(context)
        rollIfNeeded(p)
            .putInt(KEY_SKIPS, p.getInt(KEY_SKIPS, 0) + 1)
            .putString(KEY_LAST_SKIP, Redactor.redact(reason))
            .apply()
    }

    data class Snapshot(val uploads: Int, val skips: Int, val lastSkip: String)

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        val fresh = p.getString(KEY_DATE, "") == today()
        return Snapshot(
            uploads = if (fresh) p.getInt(KEY_UPLOADS, 0) else 0,
            skips = if (fresh) p.getInt(KEY_SKIPS, 0) else 0,
            lastSkip = if (fresh) p.getString(KEY_LAST_SKIP, "").orEmpty() else ""
        )
    }
}
