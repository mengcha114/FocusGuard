package com.focusguard.app.update

import com.focusguard.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 「检测更新」：查 GitHub 最新 Release，与本地版本比对。
 *
 * 走公开仓库的 releases 接口（无需鉴权），并用 flavor 名挑对应的安装包：
 * 学段版取 `app-edu-debug.apk`，通用版取 `app-general-debug.apk`。
 */
object UpdateChecker {

    private const val API_LATEST =
        "https://api.github.com/repos/mengcha114/FocusGuard/releases/latest"

    /** 测试版通道：CI 每次构建自动覆盖的 pre-release。 */
    private const val API_BETA =
        "https://api.github.com/repos/mengcha114/FocusGuard/releases/tags/ci-latest"

    data class UpdateInfo(
        val tag: String,
        val notes: String,
        val downloadUrl: String,
        val pageUrl: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** 当前 flavor 对应的安装包名（CI 每次发布都会带上这两个包）。 */
    fun assetName(): String =
        if (BuildConfig.CHALLENGE_MODE == "edu") "app-edu-debug.apk" else "app-general-debug.apk"

    /**
     * 有新版返回 [UpdateInfo]；已是最新返回 null。
     * 网络/解析失败抛异常，由调用方提示。
     *
     * @param beta 测试版通道：查 CI 构建的 pre-release（tag `ci-latest`）。
     *   它的 tag 不是版本号，所以改用「发布时间晚于本机 APK 安装时间」判断；
     *   本机没装过测试版时同样成立。
     * @param installedAt 本机 APK 的安装/更新时间（毫秒），测试版通道用。
     */
    fun check(beta: Boolean = false, installedAt: Long = 0L): UpdateInfo? {
        val url = if (beta) API_BETA else API_LATEST
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FocusGuard/" + currentVersion())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name").trim()
            if (tag.isBlank()) throw IllegalStateException("响应缺少 tag_name")
            val page = json.optString("html_url")
            val notes = json.optString("body").trim()
            if (beta) {
                // 测试版：用发布时间判断（tag 是固定的 ci-latest，不是版本号）
                val published = runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        .parse(json.optString("published_at"))
                        ?.time ?: 0L
                }.getOrDefault(0L)
                val nameVersion = Regex("v?(\\d+(?:\\.\\d+)+)")
                    .find(json.optString("name"))?.groupValues?.get(1).orEmpty()
                val newerByVersion = nameVersion.isNotBlank() &&
                    compareVersions(currentVersion(), nameVersion) > 0
                if (published <= installedAt && !newerByVersion) return null
            } else if (compareVersions(currentVersion(), tag) >= 0) {
                return null
            }
            var url = page
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name") == assetName()) {
                        val u = a.optString("browser_download_url")
                        if (u.isNotBlank()) url = u
                        break
                    }
                }
            }
            val display = if (beta) {
                "测试版（" + java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.getDefault()
                ).format(java.util.Date(json.optString("published_at").let {
                    runCatching {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            .parse(it)?.time ?: 0L
                    }.getOrDefault(0L)
                })) + "）"
            } else {
                tag
            }
            return UpdateInfo(tag = display, notes = notes, downloadUrl = url, pageUrl = page)
        }
    }

    /**
     * 版本比较（纯函数，便于单测）：返回 >0 表示 local 更新。
     *
     * 只比较数字段：`v3.11.21` / `3.11.21` / `3.11.21-preview` 都按 3,11,21 处理；
     * 段数不同时缺失段按 0 计（`3.11` == `3.11.0`）。
     */
    fun compareVersions(local: String, remote: String): Int {
        fun parts(s: String): List<Int> = s.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '+')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(local)
        val b = parts(remote)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
