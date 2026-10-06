package com.focusguard.app.update

import com.focusguard.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「检测更新」。
 *
 * 两个通道：
 * - 正式版：读 GitHub 的 **releases.atom 订阅源**（公开、**不受 API 限流**），
 *   取最新一条正式 Release 的标签与更新说明；
 * - 测试版：读固定 tag `ci-latest` 的 **发布页**（预发布不进 atom 源），
 *   用发布时间与"本机 APK 安装时间"比较。
 *
 * 为什么不用 api.github.com 做首选：未鉴权的 GitHub API 每 IP 每小时只有 60 次，
 * 国内运营商 NAT 共享 IP 时经常被耗光 ⇒ 返回 **HTTP 403**（用户实测"更新失败 403"）。
 * 所以 API 只作为最后兜底。
 */
object UpdateChecker {

    private const val REPO = "https://github.com/mengcha114/FocusGuard"
    private const val FEED = "$REPO/releases.atom"
    private const val CI_PAGE = "$REPO/releases/tag/ci-latest"
    private const val DL = "$REPO/releases/download"
    private const val API_LATEST = "https://api.github.com/repos/mengcha114/FocusGuard/releases/latest"
    private const val API_BETA = "https://api.github.com/repos/mengcha114/FocusGuard/releases/tags/ci-latest"

    data class UpdateInfo(
        val tag: String,
        val notes: String,
        val downloadUrl: String,
        val pageUrl: String
    )

    /** 探测结果：ok=false 表示"这次没查成"（换通路重试），ok=true 且 info=null 表示"已是最新"。 */
    private data class Probe(val ok: Boolean, val info: UpdateInfo?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** 当前 flavor 对应的安装包名（CI 每次发布都会带上这两个包）。 */
    fun assetName(): String =
        if (BuildConfig.CHALLENGE_MODE == "edu") "app-edu-debug.apk" else "app-general-debug.apk"

    private fun httpGet(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "FocusGuard/" + currentVersion())
            .header("Accept", "*/*")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun plain(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var s = html
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun parseTime(iso: String?): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(iso.orEmpty())?.time ?: 0L
    }.getOrDefault(0L)

    private fun niceTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(ms))

    /** 正式版：从 atom 订阅源取最新一条正式 Release。 */
    private fun probeStable(): Probe {
        val feed = httpGet(FEED) ?: return Probe(false, null)
        val entries = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
            .findAll(feed).map { it.groupValues[1] }.toList()
        for (e in entries) {
            val title = Regex("<title>([^<]*)</title>").find(e)?.groupValues?.get(1).orEmpty()
            // 预发布（测试版）不进正式通道
            if (title.contains("测试版") || title.contains("CI")) continue
            val tag = Regex("v\\d+(?:\\.\\d+)+").find(title)?.value ?: continue
            val notes = plain(Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                .find(e)?.groupValues?.get(1)).take(1200)
            return Probe(
                ok = true,
                info = if (compareVersions(currentVersion(), tag) >= 0) null else UpdateInfo(
                    tag = tag,
                    notes = notes,
                    downloadUrl = "$DL/$tag/${assetName()}",
                    pageUrl = "$REPO/releases/tag/$tag"
                )
            )
        }
        return Probe(false, null)
    }

    /** 测试版：固定 tag `ci-latest` 的发布页（预发布不进 atom 源）。 */
    private fun probeBeta(installedAt: Long): Probe {
        val page = httpGet(CI_PAGE) ?: return Probe(true, null)      // 404 ⇒ 还没有测试版
        val notes = plain(
            Regex("markdown-body[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .find(page)?.groupValues?.get(1)
        ).take(1200)
        // 优先按**版本号**比较：标题形如「CI 测试版 v3.11.27（自动覆盖）」。
        // 不能用发布时间——GitHub 替换资产时 published_at 不变（用户实测会永远显示"已是最新"）。
        val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1).orEmpty()
        val titleVer = Regex("v\\d+(?:\\.\\d+)+").find(title)?.value
        if (titleVer != null) {
            if (compareVersions(currentVersion(), titleVer) <= 0) return Probe(true, null)
            return Probe(true, UpdateInfo(
                tag = titleVer + " 测试版",
                notes = notes.ifBlank { "这是每次构建自动覆盖的测试版，点「去下载」安装。" },
                downloadUrl = "$DL/ci-latest/${assetName()}",
                pageUrl = CI_PAGE
            ))
        }
        // 标题里没有版本号（老发布）才退回时间比较
        val published = parseTime(
            Regex("datetime=\"([0-9T:\\-]+Z)\"").find(page)?.groupValues?.get(1)
        )
        if (published == 0L || published <= installedAt) return Probe(true, null)
        return Probe(
            ok = true,
            info = UpdateInfo(
                tag = "测试版（" + niceTime(published) + "）",
                notes = notes.ifBlank { "这是每次构建自动覆盖的测试版，点「去下载」安装。" },
                downloadUrl = "$DL/ci-latest/${assetName()}",
                pageUrl = CI_PAGE
            )
        )
    }

    /** 兜底：GitHub API（可能被限流 ⇒ 403）。 */
    private fun probeApi(beta: Boolean, installedAt: Long): Probe {
        val json = httpGet(if (beta) API_BETA else API_LATEST)
            ?: return Probe(true, null)
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name").trim().ifBlank { return Probe(true, null) }
        val notes = obj.optString("body").trim().take(1200)
        val page = obj.optString("html_url").ifBlank { "$REPO/releases" }
        if (beta) {
            val published = parseTime(obj.optString("published_at"))
            if (published <= installedAt) return Probe(true, null)
            return Probe(true, UpdateInfo("测试版（" + niceTime(published) + "）", notes, "$DL/ci-latest/${assetName()}", page))
        }
        if (compareVersions(currentVersion(), tag) >= 0) return Probe(true, null)
        return Probe(true, UpdateInfo(tag, notes, "$DL/$tag/${assetName()}", page))
    }

    /**
     * 有新版返回 [UpdateInfo]；已是最新返回 null；连不上时抛异常（由界面提示）。
     * @param beta 测试版通道
     * @param installedAt 本机 APK 安装/更新时间（毫秒），测试版通道用它判断新旧
     */
    fun check(beta: Boolean = false, installedAt: Long = 0L): UpdateInfo? {
        val primary = runCatching { if (beta) probeBeta(installedAt) else probeStable() }.getOrNull()
        if (primary != null && primary.ok) return primary.info
        // 首选通路失败 ⇒ 用 API 兜底；再失败就如实报错（一般是限流 403）
        val fallback = runCatching { probeApi(beta, installedAt) }.getOrNull()
        if (fallback != null && fallback.ok) return fallback.info
        throw IllegalStateException("查不到更新（可能被 GitHub 限流，请稍后再试）")
    }

    /**
     * 版本比较（纯函数，便于单测）：返回 >0 表示 local 更新。
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
