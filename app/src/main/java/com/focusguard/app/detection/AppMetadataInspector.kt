package com.focusguard.app.detection

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * 从系统元数据推断应用分类。完全离线，零 token。
 *
 * 判据按可信度从高到低：
 *
 * 1. **系统应用标记** —— FLAG_SYSTEM，直接判系统
 * 2. **默认启动器** —— 桌面应用判系统
 * 3. **输入法 / 无障碍等系统角色** —— 判系统
 * 4. **Play 商店应用类目** —— Android 12+ 提供 `category` 字段，
 *    CATEGORY_GAME 是最强的游戏信号，开发者自己声明的，比关键词猜测可靠得多
 * 5. **包名与应用名特征词** —— 兜底启发式
 *
 * 这套推断能覆盖大部分应用，只有真正判不出来的才留给 AI。
 */
object AppMetadataInspector {

    private const val TAG = "AppMetadataInspector"

    /** 包名特征：游戏。多数手游包名带这些片段。 */
    private val gamePackageHints = listOf(
        ".tmgp.", ".game.", "games", ".gp.", "mihoyo", "netease.g",
        "supercell", "gameloft", "ubisoft", "playrix", "zynga",
        "moonton", "garena", "lilith", "papegames", "hypergryph"
    )

    /** 应用名特征：游戏 */
    private val gameNameHints = listOf(
        "游戏", "手游", "赛车", "传奇", "battle", "arena", "quest",
        "legend", "craft", "clash", "puzzle", "shooter", "racing", "rpg"
    )

    /** 包名/应用名特征：学习与办公 */
    private val studyHints = listOf(
        "edu", "study", "learn", "school", "class", "course", "lesson",
        "dict", "translate", "note", "office", "docs", "sheet", "slide",
        "pdf", "reader", "book", "code", "dev", "git", "terminal", "ssh",
        "markdown", "todo", "task", "calendar", "mail",
        "学习", "教育", "课", "词典", "翻译", "笔记", "文档", "办公",
        "阅读", "读书", "考试", "题库", "作业", "编程", "开发"
    )

    /** 包名/应用名特征：短视频与直播 */
    private val shortVideoHints = listOf(
        "aweme", "douyin", "tiktok", "kuaishou", "gifmaker", "nebula",
        "huoshan", "weishi", "zhihu.short", "shorts",
        "抖音", "快手", "小视频", "短视频"
    )

    /** 包名/应用名特征：长视频 */
    private val videoHints = listOf(
        "bili", "qqlive", "youku", "iqiyi", "mgtv", "sohu.video",
        "youtube", "netflix", "video", "movie", "tv",
        "视频", "影视", "电影", "剧场"
    )

    /** 包名/应用名特征：社交 */
    private val socialHints = listOf(
        "weibo", "wechat", "tencent.mm", "mobileqq", "xhs", "xingin",
        "zhihu", "tieba", "douban", "instagram", "twitter", "facebook",
        "snapchat", "telegram", "discord",
        "微博", "微信", "贴吧", "社区", "论坛"
    )

    /** 包名/应用名特征：购物（归娱乐倾向，但需 AI 复核，因为也可能在比价办公） */
    private val shoppingHints = listOf(
        "taobao", "tmall", "jd.", "pinduoduo", "amazon", "shopee",
        "淘宝", "天猫", "京东", "拼多多", "购物", "商城"
    )

    /**
     * 推断分类。返回 null 表示无法判断，应交给 AI。
     */
    fun infer(context: Context, packageName: String): AppCategory? {
        val pm = context.packageManager
        val appInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }

        // 1. 系统应用
        if (isSystemApp(appInfo)) {
            Log.d(TAG, "$packageName 判定系统应用（FLAG_SYSTEM）")
            return AppCategory.SYSTEM
        }

        // 2. 默认启动器 / 桌面
        if (isLauncher(context, packageName)) {
            Log.d(TAG, "$packageName 判定系统应用（启动器）")
            return AppCategory.SYSTEM
        }

        // 3. 输入法
        if (isInputMethod(context, packageName)) {
            return AppCategory.SYSTEM
        }

        val label = try {
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
        val haystack = "${packageName.lowercase()} ${label.lowercase()}"

        // 4. Play 商店类目：开发者自己声明的，可信度高
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> {
                    Log.d(TAG, "$packageName 判定游戏（CATEGORY_GAME）")
                    return AppCategory.GAME
                }
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> {
                    Log.d(TAG, "$packageName 判定学习办公（CATEGORY_PRODUCTIVITY）")
                    return AppCategory.STUDY
                }
                ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.VIDEO
                ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
                ApplicationInfo.CATEGORY_NEWS -> return AppCategory.SOCIAL
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_IMAGE,
                ApplicationInfo.CATEGORY_MAPS -> return AppCategory.SYSTEM
            }
        }

        // 5. 特征词兜底
        return when {
            gamePackageHints.any { haystack.contains(it) } ||
                gameNameHints.any { haystack.contains(it) } -> AppCategory.GAME

            shortVideoHints.any { haystack.contains(it) } -> AppCategory.SHORT_VIDEO

            videoHints.any { haystack.contains(it) } -> AppCategory.VIDEO

            socialHints.any { haystack.contains(it) } -> AppCategory.SOCIAL

            shoppingHints.any { haystack.contains(it) } -> AppCategory.SOCIAL

            studyHints.any { haystack.contains(it) } -> AppCategory.STUDY

            else -> null // 交给 AI
        }
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    private fun isLauncher(context: Context, packageName: String): Boolean {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == packageName }
        } catch (e: Exception) {
            false
        }
    }

    private fun isInputMethod(context: Context, packageName: String): Boolean {
        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.inputMethodList.any { it.packageName == packageName }
        } catch (e: Exception) {
            false
        }
    }
}
