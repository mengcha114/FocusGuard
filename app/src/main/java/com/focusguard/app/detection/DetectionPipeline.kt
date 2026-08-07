package com.focusguard.app.detection

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.focusguard.app.ai.AiClient
import com.focusguard.app.capture.ScreenCapturer
import com.focusguard.app.data.Settings
import com.focusguard.app.token.DecisionCache
import com.focusguard.app.token.ImageHasher
import com.focusguard.app.token.TokenBudget

/**
 * 检测来源。用于日志展示、统计节约效果，也方便排查误判。
 */
enum class DetectionSource {
    WHITELIST,      // 用户白名单，零 token
    APP_CATEGORY,   // 应用分类直接判定，零 token
    SCREEN_TEXT,    // 屏幕文字规则判定，零 token
    SCREEN_UNCHANGED, // 画面与上次几乎相同，复用上次结论，零 token
    CACHE_HIT,      // 感知哈希命中历史判定，零 token
    BLANK_SCREEN,   // 息屏/纯色屏，无识别价值，零 token
    BUDGET_EXCEEDED, // 今日配额用尽，退化为本地判定
    AI_VISION,      // 视觉大模型判定，消耗 token
    ERROR
}

data class DetectionOutcome(
    val classification: String,   // STUDY_WORK / ENTERTAINMENT / NEUTRAL
    val confidence: Float,
    val reason: String,
    val source: DetectionSource,
    val packageName: String = "",
    val appLabel: String = ""
) {
    /** 本次判定是否真的花了 token。 */
    val consumedToken: Boolean get() = source == DetectionSource.AI_VISION
}

/**
 * 多级检测流水线，目标是让视觉大模型的调用次数降到最低。
 *
 * 判定顺序（越靠前越省钱，命中即返回）：
 *
 *  L0 白名单        用户显式声明的应用 → 直接放行
 *  L1 应用分类      纯游戏 / 纯学习 / 系统应用 → 直接定论
 *  L2 屏幕文字      无障碍读文字，关键词打分 → 有明显倾向就定论
 *  L3 画面去重      与上一帧感知哈希几乎相同 → 复用上次结论
 *  L4 空白画面      息屏、纯色屏 → 判为中性
 *  L5 判定缓存      历史上见过高度相似的画面 → 复用历史结论
 *  L6 预算闸门      今日 token 配额耗尽 → 退化为本地保守判定
 *  L7 视觉大模型    以上都无法定论时才真正调用
 *
 * 关键设计取舍：视频平台不因"是视频应用"就判娱乐。
 * B 站上的编程教程必须能被识别为学习，宁可多花一次 token，
 * 也不能误杀正常的学习行为。
 */
class DetectionPipeline(
    private val context: Context,
    private val settings: Settings,
    private val aiClient: AiClient,
    private val screenCapturer: ScreenCapturer,
    private val tokenBudget: TokenBudget,
    private val decisionCache: DecisionCache,
    private val categoryStore: AppCategoryStore
) {

    companion object {
        private const val TAG = "DetectionPipeline"
        /** 相邻两帧判定为"未变化"的汉明距离阈值。 */
        private const val UNCHANGED_THRESHOLD = 4
    }

    private var lastHash: Long? = null
    private var lastPackage: String? = null
    private var lastClassification: String? = null
    private var lastConfidence: Float = 0f
    private var lastReason: String = ""

    /**
     * 各级开关。用户在设置里关掉 Token 节约系统后，
     * L2 文字预过滤、L3 画面去重、L5 判定缓存全部跳过，
     * 每轮检测都直接调用视觉大模型。
     *
     * L0 白名单、L1 应用分类、L4 空白画面保持始终生效：
     * 这三级是零成本的常识判断（用户显式声明、纯游戏应用、息屏），
     * 没有任何理由花钱去问模型"现在是不是息屏"。
     */
    private val dedupOn: Boolean get() = settings.screenHashDedupEnabled
    private val textPrefilterOn: Boolean get() = settings.screenTextPrefilterEnabled
    private val cacheOn: Boolean get() = settings.decisionCacheEnabled

    suspend fun detect(projection: MediaProjection?): DetectionOutcome {
        val appInfo = AppClassifier.classifyForegroundApp(context, categoryStore)
        val pkg = appInfo?.packageName.orEmpty()
        val label = appInfo?.label.ifNullOrBlank { pkg }

        // ── L0 白名单 ─────────────────────────────────
        if (isWhitelisted(label, pkg)) {
            return saved(
                DetectionOutcome(
                    "STUDY_WORK", 1f, "白名单放行：$label",
                    DetectionSource.WHITELIST, pkg, label
                )
            )
        }

        // ── L1 应用分类 ───────────────────────────────
        if (appInfo != null) {
            val verdict = AppClassifier.classifyByAppInfo(appInfo)
            if (verdict != "NEEDS_AI") {
                val reason = when (verdict) {
                    "ENTERTAINMENT" -> "$label 属于游戏/娱乐类应用"
                    "STUDY_WORK" -> "$label 属于学习/办公类应用"
                    else -> "$label 属于系统或中性界面"
                }
                return saved(
                    DetectionOutcome(verdict, 0.95f, reason, DetectionSource.APP_CATEGORY, pkg, label)
                )
            }
        }

        // ── L2 屏幕文字（可关闭）──────────────────────
        if (textPrefilterOn) {
            ScreenTextReader.readCurrentScreenText()?.let { text ->
                val textVerdict = AppClassifier.classifyByScreenText(text)
                if (textVerdict == "STUDY_WORK" || textVerdict == "ENTERTAINMENT") {
                    val reason = if (textVerdict == "STUDY_WORK") {
                        "屏幕文字含学习/工作特征（$label）"
                    } else {
                        "屏幕文字含娱乐特征（$label）"
                    }
                    return saved(
                        DetectionOutcome(textVerdict, 0.8f, reason, DetectionSource.SCREEN_TEXT, pkg, label)
                    )
                }
            }
        }

        // 以下步骤需要画面，先确认前置条件
        if (projection == null) {
            return DetectionOutcome(
                "NEUTRAL", 0f, "屏幕录制不可用，跳过本轮",
                DetectionSource.ERROR, pkg, label
            )
        }

        val capture = screenCapturer.capture(projection)
            ?: return DetectionOutcome(
                "NEUTRAL", 0f, "截屏失败", DetectionSource.ERROR, pkg, label
            )

        try {
            val hash = ImageHasher.dHash(capture.bitmap)

            // ── L3 画面去重（可关闭）─────────────────
            val prevHash = lastHash
            if (dedupOn &&
                prevHash != null &&
                lastPackage == pkg &&
                lastClassification != null &&
                ImageHasher.isSimilar(prevHash, hash, UNCHANGED_THRESHOLD)
            ) {
                Log.d(TAG, "画面未变化，复用上次结论")
                return saved(
                    DetectionOutcome(
                        lastClassification!!, lastConfidence,
                        "画面未变化，沿用上次判定（$lastReason）",
                        DetectionSource.SCREEN_UNCHANGED, pkg, label
                    )
                )
            }

            // ── L4 空白画面 ───────────────────────────
            if (ImageHasher.isNearlyBlank(capture.bitmap)) {
                remember(hash, pkg, "NEUTRAL", 0.9f, "息屏或纯色画面")
                return saved(
                    DetectionOutcome(
                        "NEUTRAL", 0.9f, "息屏或纯色画面，无需识别",
                        DetectionSource.BLANK_SCREEN, pkg, label
                    )
                )
            }

            // ── L5 判定缓存（可关闭）─────────────────
            if (cacheOn) {
                decisionCache.lookup(pkg, hash)?.let { cached ->
                    remember(hash, pkg, cached.classification, cached.confidence, cached.reason)
                    return saved(
                        DetectionOutcome(
                            cached.classification, cached.confidence,
                            "命中历史判定：${cached.reason}",
                            DetectionSource.CACHE_HIT, pkg, label
                        )
                    )
                }
            }

            // ── L6 预算闸门 ───────────────────────────
            // 每次都从 SharedPreferences 重新读取，而不是构造时缓存，
            // 否则用户在设置里改完密钥后仍会看到"未配置"
            val currentApiKey = settings.apiKey
            if (currentApiKey.isBlank()) {
                return DetectionOutcome(
                    "NEUTRAL", 0f, "未配置 API 密钥，请在设置中填写并保存",
                    DetectionSource.ERROR, pkg, label
                )
            }
            if (!tokenBudget.canCallAi()) {
                Log.w(TAG, "今日 AI 配额已用尽，退化为本地判定")
                return DetectionOutcome(
                    "NEUTRAL", 0.3f,
                    "今日 AI 配额已用尽（${tokenBudget.dailyCallLimit} 次），仅做本地判定",
                    DetectionSource.BUDGET_EXCEEDED, pkg, label
                )
            }

            // ── L7 视觉大模型 ─────────────────────────
            // 把备忘录未完成事项注入提示词：检测到娱乐时，
            // 让模型在提醒语里引用待办（如"你还有 xxx 没做呢"）
            val memoText = runCatching {
                com.focusguard.app.data.MemoStore(context).getAll()
                    .take(5)
                    .joinToString("\n- ", prefix = "- ")
            }.getOrDefault("")
            val effectivePrompt = buildString {
                append(settings.aiCustomPrompt)
                if (memoText.isNotBlank()) {
                    append("\n\n用户的待办事项（检测到娱乐时，在提醒语里引用还没做完的事，鼓励用户去做）：\n")
                    append(memoText)
                }
            }

            val aiResult = aiClient.analyzeScreen(
                imageBytes = capture.jpegBytes,
                baseUrl = settings.apiBaseUrl,
                apiKey = currentApiKey,
                modelName = settings.modelName,
                whitelist = settings.whitelist,
                customPrompt = effectivePrompt,
                apiFormat = settings.apiFormat
            )
            tokenBudget.recordCall()

            val reason = aiResult.reason.ifBlank { "AI 视觉识别" }
            // 只有缓存开启时才写入缓存，关闭时用户期望每次都新鲜判定
            if (cacheOn) {
                decisionCache.put(pkg, hash, aiResult.classification, aiResult.confidence, reason)
            }
            // AI 判定稳定时回写到应用级学习缓存（GAME/STUDY 才做，避免内容随时变的应用误学）
            AppClassifier.learnFromAiResult(pkg, aiResult.classification, aiResult.confidence, categoryStore)
            remember(hash, pkg, aiResult.classification, aiResult.confidence, reason)

            Log.d(TAG, "AI 判定 ${aiResult.classification} ${aiResult.confidence}，今日已用 ${tokenBudget.callsToday} 次")

            return DetectionOutcome(
                aiResult.classification, aiResult.confidence, reason,
                DetectionSource.AI_VISION, pkg, label
            )
        } finally {
            capture.recycle()
        }
    }

    /** 记录一次成功避免的调用，并返回原结果，方便链式书写。 */
    private fun saved(outcome: DetectionOutcome): DetectionOutcome {
        tokenBudget.recordSavedCall()
        return outcome
    }

    private fun remember(
        hash: Long,
        pkg: String,
        classification: String,
        confidence: Float,
        reason: String
    ) {
        lastHash = hash
        lastPackage = pkg
        lastClassification = classification
        lastConfidence = confidence
        lastReason = reason
    }

    /** 用户手动停止或屏幕熄灭后清掉上一帧状态，避免跨会话误复用。 */
    fun resetFrameState() {
        lastHash = null
        lastPackage = null
        lastClassification = null
        lastConfidence = 0f
        lastReason = ""
    }

    private fun isWhitelisted(label: String, packageName: String): Boolean {
        val raw = settings.whitelist
        if (raw.isBlank()) return false
        return raw.split(',', '，', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { entry ->
                label.contains(entry, ignoreCase = true) ||
                    packageName.contains(entry, ignoreCase = true)
            }
    }
}

private inline fun String?.ifNullOrBlank(fallback: () -> String): String =
    if (this.isNullOrBlank()) fallback() else this
