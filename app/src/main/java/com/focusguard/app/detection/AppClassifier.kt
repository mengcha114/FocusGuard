package com.focusguard.app.detection

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

enum class AppCategory {
    GAME,        // 游戏 → 娱乐
    STUDY,       // 学习/办公 → 学习
    SYSTEM,      // 系统/桌面 → 中性
    VIDEO,       // 长视频 → 需结合内容
    SHORT_VIDEO, // 短视频 → 需结合内容
    SOCIAL,      // 社交/购物 → 需结合内容
    UNKNOWN      // 未知 → 交给 AI
}

data class AppInfo(
    val packageName: String,
    val category: AppCategory,
    val label: String = ""
)

/**
 * 应用分类器。
 *
 * 分类优先级：
 *  1. 用户手动覆盖（最高，永不被覆盖）
 *  2. 内置静态表（高可信度的常见应用）
 *  3. 自动学习缓存（历史判定结果）
 *  4. 系统元数据推断（FLAG、CATEGORY、特征词，离线，零 token）
 *  5. UNKNOWN → 交给检测流水线做文字/AI 进一步判断
 */
object AppClassifier {

    private const val TAG = "AppClassifier"

    /** 内置静态分类表，覆盖最常见的高频应用。 */
    private val builtinMap = mapOf(
        // ── 游戏 ────────────────────────────────────────────
        "com.tencent.tmgp.sgame"             to AppCategory.GAME,  // 王者荣耀
        "com.miHoYo.Yuanshen"                to AppCategory.GAME,  // 原神
        "com.tencent.tmgp.pubgmhd"           to AppCategory.GAME,  // 和平精英
        "com.tencent.tmgp.cf"                to AppCategory.GAME,  // CF手游
        "com.tencent.tmgp.dn"                to AppCategory.GAME,  // 地下城与勇士
        "com.netease.g93na"                  to AppCategory.GAME,  // 阴阳师
        "com.netease.mrzhna"                 to AppCategory.GAME,  // 明日之后
        "com.supercell.clashofclans"         to AppCategory.GAME,
        "com.supercell.clashroyale"          to AppCategory.GAME,
        "com.garena.game.codm"               to AppCategory.GAME,
        "com.activision.callofduty.shooter"  to AppCategory.GAME,
        "com.ea.gp.fifamobile"               to AppCategory.GAME,
        "com.epicgames.fortnite"             to AppCategory.GAME,
        "com.tencent.ig"                     to AppCategory.GAME,  // 刺激战场
        "com.papegames.nn4s"                 to AppCategory.GAME,  // 恋与制作人
        "com.hypergryph.arknights"           to AppCategory.GAME,  // 明日方舟
        "com.miHoYo.enterprise.HSRBeta"      to AppCategory.GAME,  // 崩坏星穹
        "com.miHoYo.hkrpg"                   to AppCategory.GAME,
        "com.xiaomi.gamecenter"              to AppCategory.GAME,

        // ── 学习/办公 ────────────────────────────────────────
        "cn.xuexi.android"                   to AppCategory.STUDY, // 学习强国
        "com.dedao.npp"                      to AppCategory.STUDY, // 得到
        "com.youdao.dict"                    to AppCategory.STUDY, // 有道词典
        "com.koolearn.android"               to AppCategory.STUDY, // 新东方在线
        "com.tencent.weread"                 to AppCategory.STUDY, // 微信读书
        "com.microsoft.office.word"          to AppCategory.STUDY,
        "com.microsoft.office.excel"         to AppCategory.STUDY,
        "com.microsoft.office.powerpoint"    to AppCategory.STUDY,
        "com.microsoft.teams"                to AppCategory.STUDY,
        "com.microsoft.launcher.enterprise"  to AppCategory.STUDY,
        "com.kingsoft.moffice_eng"           to AppCategory.STUDY, // WPS
        "cn.wps.moffice_eng"                 to AppCategory.STUDY,
        "com.jschina.android.zxxs"           to AppCategory.STUDY, // 极速口语
        "com.fenbi.android.omega"            to AppCategory.STUDY, // 粉笔
        "com.hujiang.hjdict"                 to AppCategory.STUDY, // 沪江词典
        "com.anki.flashcards"                to AppCategory.STUDY,
        "com.gitapp.android"                 to AppCategory.STUDY,
        "com.termux"                         to AppCategory.STUDY,
        "com.google.android.apps.docs"       to AppCategory.STUDY,
        "com.google.android.apps.sheets"     to AppCategory.STUDY,
        "com.google.android.apps.slides"     to AppCategory.STUDY,
        "com.notion.id"                      to AppCategory.STUDY,
        "com.obsidian"                       to AppCategory.STUDY,

        // ── 视频平台（需结合内容）──────────────────────────
        "tv.danmaku.bili"                    to AppCategory.VIDEO,  // B站
        "com.tencent.qqlive"                 to AppCategory.VIDEO,  // 腾讯视频
        "com.youku.phone"                    to AppCategory.VIDEO,  // 优酷
        "com.qiyi.video"                     to AppCategory.VIDEO,  // 爱奇艺
        "com.iqiyi.i18n"                     to AppCategory.VIDEO,
        "com.sohu.sohuvideo"                 to AppCategory.VIDEO,  // 搜狐视频
        "com.mango.player"                   to AppCategory.VIDEO,  // 芒果TV
        "com.yixia.aio"                      to AppCategory.VIDEO,  // 小影
        "com.google.android.youtube"         to AppCategory.VIDEO,
        "com.netflix.mediaclient"            to AppCategory.VIDEO,
        "com.disney.disneyplus"              to AppCategory.VIDEO,

        // ── 短视频（需结合内容）──────────────────────────
        "com.ss.android.ugc.aweme"           to AppCategory.SHORT_VIDEO, // 抖音
        "com.ss.android.ugc.aweme.lite"      to AppCategory.SHORT_VIDEO,
        "com.kuaishou.nebula"                to AppCategory.SHORT_VIDEO, // 快手
        "com.smile.gifmaker"                 to AppCategory.SHORT_VIDEO, // 快手极速版
        "com.ss.android.article.video"       to AppCategory.SHORT_VIDEO, // 西瓜视频
        "com.ss.android.ugc.live"            to AppCategory.SHORT_VIDEO,

        // ── 社交/购物（需结合内容）────────────────────────
        "com.sina.weibo"                     to AppCategory.SOCIAL,  // 微博
        "com.tencent.mm"                     to AppCategory.SOCIAL,  // 微信
        "com.tencent.mobileqq"               to AppCategory.SOCIAL,  // QQ
        "com.xingin.xhs"                     to AppCategory.SOCIAL,  // 小红书
        "com.zhihu.android"                  to AppCategory.SOCIAL,  // 知乎
        "com.douban.frodo"                   to AppCategory.SOCIAL,  // 豆瓣
        "com.baidu.tieba"                    to AppCategory.SOCIAL,  // 贴吧
        "com.taobao.taobao"                  to AppCategory.SOCIAL,  // 淘宝
        "com.jingdong.app.mall"              to AppCategory.SOCIAL,  // 京东
        "com.xunmeng.pinduoduo"              to AppCategory.SOCIAL,  // 拼多多
        "com.alibaba.android.rimet"          to AppCategory.SOCIAL,  // 钉钉（办公，但需 AI 确认场景）
    )

    private val studyKeywords = listOf(
        "课程", "教程", "学习", "编程", "讲座", "公开课",
        "教学", "培训", "笔记", "课件", "作业", "考试",
        "复习", "预习", "论文", "报告", "研究", "分析",
        "document", "code", "IDE", "terminal", "editor",
        "spreadsheet", "word", "excel", "powerpoint"
    )

    private val entertainmentKeywords = listOf(
        "搞笑", "娱乐", "综艺", "八卦", "段子", "笑话",
        "电影", "电视剧", "动漫", "直播", "打赏", "刷",
        "funny", "entertainment", "comedy", "game", "play",
        "battle", "rank", "level", "reward", "gacha"
    )

    /**
     * 获取前台应用信息，并按四级优先级确定分类。
     *
     * @param store 可选的持久化分类存储，传入时启用用户覆盖和自动学习
     */
    fun classifyForegroundApp(context: Context, store: AppCategoryStore? = null): AppInfo? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 15_000, now
        )

        if (stats.isNullOrEmpty()) return null

        // UsageStats 可能包含当前进程自身，排除掉
        val foreground = stats
            .filter { it.packageName != context.packageName }
            .maxByOrNull { it.lastTimeUsed } ?: return null

        val packageName = foreground.packageName
        val pm = context.packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

        val category = resolveCategory(context, packageName, store)
        return AppInfo(packageName, category, label)
    }

    /**
     * 四级分类解析：
     * 用户覆盖 > 内置表 > 学习缓存 > 元数据推断 > UNKNOWN
     */
    fun resolveCategory(
        context: Context,
        packageName: String,
        store: AppCategoryStore? = null
    ): AppCategory {
        // 1. 用户手动覆盖
        store?.getUserOverride(packageName)?.let { return it }

        // 2. 内置静态表
        builtinMap[packageName]?.let { return it }

        // 3. 自动学习缓存
        store?.getLearned(packageName)?.let { return it }

        // 4. 系统元数据推断（离线零成本）
        val inferred = AppMetadataInspector.infer(context, packageName)
        if (inferred != null) {
            Log.d(TAG, "$packageName 元数据推断为 $inferred")
            store?.putLearned(packageName, inferred)
            return inferred
        }

        return AppCategory.UNKNOWN
    }

    /**
     * AI 判定结果回写到学习缓存。
     * 仅当分类明确（非中性）且置信度足够高时才记录。
     */
    fun learnFromAiResult(
        packageName: String,
        classification: String,
        confidence: Float,
        store: AppCategoryStore?
    ) {
        // 目前刻意不写入应用级缓存：
        // 只有 GAME/STUDY 才是应用级的稳定属性，但 AI 的 ENTERTAINMENT 判定
        // 往往来自"一次娱乐内容"（比如 B 站刷了个搞笑视频），固化到应用层面
        // 会把整个应用误判成娱乐，与"视频平台不误杀"的原则冲突。
        // 元数据推断层（AppMetadataInspector）已经覆盖了稳定属性的学习。
        @Suppress("UNUSED_PARAMETER")
        return
    }

    fun classifyByScreenText(text: String): String =
        classifyByScreenText(text, emptyList(), emptyList())

    /**
     * 屏幕文字分类（L2 检测）。
     *
     * @param studyExtra 用户自定义学习/工作特征词（与内置词表合并）
     * @param entertainmentExtra 用户自定义娱乐特征词（与内置词表合并）
     */
    fun classifyByScreenText(
        text: String,
        studyExtra: List<String>,
        entertainmentExtra: List<String>
    ): String {
        val lowerText = text.lowercase()
        val mergedStudy = studyKeywords + studyExtra.filter { it.isNotBlank() }
        val mergedEntertainment = entertainmentKeywords + entertainmentExtra.filter { it.isNotBlank() }
        val studyScore = mergedStudy.count { lowerText.contains(it) }
        val entertainmentScore = mergedEntertainment.count { lowerText.contains(it) }

        return when {
            studyScore > entertainmentScore && studyScore >= 2 -> "STUDY_WORK"
            entertainmentScore > studyScore && entertainmentScore >= 2 -> "ENTERTAINMENT"
            else -> "AMBIGUOUS"
        }
    }

    fun needsAiDetection(category: AppCategory): Boolean =
        category in listOf(AppCategory.VIDEO, AppCategory.SHORT_VIDEO, AppCategory.SOCIAL, AppCategory.UNKNOWN)

    fun classifyByAppInfo(appInfo: AppInfo): String = when (appInfo.category) {
        AppCategory.GAME -> "ENTERTAINMENT"
        AppCategory.STUDY -> "STUDY_WORK"
        AppCategory.SYSTEM -> "NEUTRAL"
        AppCategory.VIDEO,
        AppCategory.SHORT_VIDEO,
        AppCategory.SOCIAL,
        AppCategory.UNKNOWN -> "NEEDS_AI"
    }
}
