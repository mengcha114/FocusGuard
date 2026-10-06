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

    private const val API =
        "https://api.github.com/repos/mengcha114/FocusGuard/releases/latest"

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
     */
    fun check(): UpdateInfo? {
        val request = Request.Builder()
            .url(API)
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
            if (compareVersions(currentVersion(), tag) >= 0) return null
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
            return UpdateInfo(tag = tag, notes = notes, downloadUrl = url, pageUrl = page)
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
