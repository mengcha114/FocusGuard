package com.focusguard.app.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiResult(
    val classification: String = "NEUTRAL", // STUDY_WORK / ENTERTAINMENT / NEUTRAL
    val confidence: Float = 0f,
    val reason: String = "",
    /** 服务端返回的真实 token 用量，取不到时为 0。 */
    val totalTokens: Int = 0
)

/**
 * 视觉大模型客户端。
 *
 * token 相关的设计：
 * 1. 系统提示词压到最短——每次调用都要重发，一个字都是钱
 * 2. `detail: "low"` 让模型按低分辨率档位计费，成本约为高清档的 1/5
 * 3. `max_tokens` 卡在 120——只需要一个短 JSON，不需要长篇解释
 * 4. 优先读取响应里的 usage 字段，用真实用量而非估算值记账
 */
class AiClient {

    companion object {
        private const val TAG = "AiClient"
        /** 输出上限。判定结果只是一个短 JSON，给多了纯属浪费。 */
        private const val MAX_OUTPUT_TOKENS = 120
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreen(
        imageBytes: ByteArray,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        whitelist: String
    ): AiResult = withContext(Dispatchers.IO) {
        try {
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val userContent = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                        // 低分辨率档位，显著降低图像 token 消耗
                        put("detail", "low")
                    })
                })
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", buildSystemPrompt(whitelist))
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            }

            val body = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("max_tokens", MAX_OUTPUT_TOKENS)
                put("temperature", 0)
            }

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "接口返回 ${response.code}")
                    return@withContext AiResult(
                        classification = "NEUTRAL",
                        reason = "API 错误 ${response.code}"
                    )
                }
                parseResponse(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "视觉识别失败", e)
            AiResult(classification = "NEUTRAL", reason = "请求失败：${e.message}")
        }
    }

    /**
     * 极简系统提示词。
     *
     * 刻意压缩到 200 字以内：这段文字每次调用都要重新发送，
     * 冗长的说明会持续产生输入 token 费用。
     * 只保留分类定义和最容易出错的边界规则。
     */
    private fun buildSystemPrompt(whitelist: String): String {
        val extra = if (whitelist.isNotBlank()) "\n白名单（视为学习）：$whitelist" else ""
        return """判断手机截图中用户在做什么，输出 JSON。

STUDY_WORK：学习、工作、编程、阅读文档、网课、教程、办公
ENTERTAINMENT：游戏、娱乐短视频、直播、漫画、社交闲逛、购物
NEUTRAL：锁屏、桌面、设置、通话、导航

重要：视频/社交类应用要看具体内容。技术教程、网课、知识科普算 STUDY_WORK，不要因为是视频应用就判娱乐。$extra

仅输出：{"c":"分类","p":0.0-1.0,"r":"理由20字内"}"""
    }

    private fun parseResponse(responseBody: String): AiResult {
        return try {
            val json = JSONObject(responseBody)

            // 优先使用服务端真实用量记账
            val totalTokens = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0

            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return AiResult("NEUTRAL", 0f, "无响应内容", totalTokens)
            }

            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val result = JSONObject(extractJson(content))

            // 兼容压缩字段名（c/p/r）与完整字段名
            val classification = result.optString("c")
                .ifBlank { result.optString("classification", "NEUTRAL") }
            val confidence = if (result.has("p")) {
                result.optDouble("p", 0.5)
            } else {
                result.optDouble("confidence", 0.5)
            }.toFloat()
            val reason = result.optString("r")
                .ifBlank { result.optString("reason", "") }

            AiResult(
                classification = normalizeClassification(classification),
                confidence = confidence.coerceIn(0f, 1f),
                reason = reason,
                totalTokens = totalTokens
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败", e)
            AiResult("NEUTRAL", 0f, "结果解析失败")
        }
    }

    private fun normalizeClassification(raw: String): String {
        val upper = raw.trim().uppercase()
        return when {
            upper.contains("ENTERTAIN") -> "ENTERTAINMENT"
            upper.contains("STUDY") || upper.contains("WORK") -> "STUDY_WORK"
            else -> "NEUTRAL"
        }
    }

    private fun extractJson(content: String): String {
        Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.let { return it.groupValues[1] }
        Regex("\\{.*?}", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.let { return it.value }
        return content
    }
}
