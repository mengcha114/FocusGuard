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
    val totalTokens: Int = 0,
    /** 内部标记：该结果是否需要去掉 detail 参数后重试。 */
    var retryable: Boolean = false
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
        private const val MAX_OUTPUT_TOKENS = 200
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
        whitelist: String,
        customPrompt: String = ""
    ): AiResult = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        // 先按完整参数请求（含 detail:low 节省图像 token）
        val result = postRequest(
            baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt, withDetail = true
        )
        // 部分兼容 API 不接受 detail 字段，会返回 400。
        // 此时去掉 detail 重试一次，避免用户卡在"请求参数错误"。
        if (result.retryable) {
            Log.w(TAG, "API 拒绝 detail 参数（400），降级重试")
            return@withContext postRequest(
                baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt, withDetail = false
            )
        }
        result
    }

    /** 单次请求。返回 [AiResult] 且 retryable=true 表示需要降级重试。 */
    private fun postRequest(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        base64Image: String,
        whitelist: String,
        customPrompt: String,
        withDetail: Boolean
    ): AiResult {
        return try {
            val userContent = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                        if (withDetail) {
                            // 低分辨率档位，显著降低图像 token 消耗
                            put("detail", "low")
                        }
                    })
                })
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", buildSystemPrompt(whitelist, customPrompt))
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
                // 不传 temperature：OpenAI 系默认即可；
                // Kimi K2.x/K3 的 temperature 是固定值（1.0/0.6），
                // 传了任何其他值都会直接返回 400 invalid_request_error
                // ── Kimi 思考模型适配 ──────────────────────
                // K2.5/K2.6 默认开启思考，思考内容会吃掉 max_tokens，
                // 导致 content 为空 → "结果解析失败"。显式关闭思考。
                if (modelName.contains("k2.5", true) || modelName.contains("k2.6", true)) {
                    put("thinking", JSONObject().apply { put("type", "disabled") })
                }
                // K3 不支持 thinking 参数，改用 reasoning_effort 降档省 token
                if (modelName.contains("k3", true)) {
                    put("reasoning_effort", "low")
                }
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
                    Log.e(TAG, "接口返回 ${response.code}: $responseBody")
                    val serverMsg = extractErrorMessage(responseBody)
                    // 400 时先假设是 detail 参数不被支持，去掉后重试一次；
                    // 若仍失败则把服务端原始错误透出给用户
                    val retryable = response.code == 400
                    AiResult(
                        classification = "NEUTRAL",
                        reason = if (serverMsg.isBlank()) {
                            "API 错误 ${response.code}"
                        } else {
                            "API 错误 ${response.code}：$serverMsg"
                        },
                        totalTokens = 0
                    ).also { it.retryable = retryable }
                } else {
                    parseResponse(responseBody).also { it.retryable = false }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "视觉识别失败", e)
            AiResult(classification = "NEUTRAL", reason = "请求失败：${e.message}").also {
                it.retryable = false
            }
        }
    }

    /**
     * 从 OpenAI 兼容错误响应里提取可读的错误信息。
     * 优先返回 error.message，附带 error.type 便于用户按错误码排查。
     */
    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val json = JSONObject(body)
            val err = json.optJSONObject("error")
            val type = err?.optString("type")?.takeIf { it.isNotBlank() }
            val message = err?.optString("message")?.takeIf { it.isNotBlank() }
            when {
                message == null -> ""
                type == null || message.contains(type) -> message
                else -> "$message（类型：$type）"
            }
        } catch (e: Exception) {
            body.take(150)
        }
    }

    /**
     * 极简系统提示词。
     *
     * 刻意压缩：这段文字每次调用都要重新发送，
     * 冗长的说明会持续产生输入 token 费用。
     */
    private fun buildSystemPrompt(whitelist: String, customPrompt: String): String {
        val extra = if (whitelist.isNotBlank()) "\n白名单（视为学习）：$whitelist" else ""
        val custom = if (customPrompt.isNotBlank()) "\n\n用户额外要求：$customPrompt" else ""
        return """判断手机截图中用户在做什么，输出 JSON。

STUDY_WORK：学习、工作、编程、阅读文档、网课、教程、办公
ENTERTAINMENT：游戏、娱乐短视频、直播、漫画、社交闲逛、购物
NEUTRAL：锁屏、桌面、设置、通话、导航

重要：视频/社交类应用要看具体内容。技术教程、网课、知识科普算 STUDY_WORK，不要因为是视频应用就判娱乐。$extra$custom

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

            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: return AiResult("NEUTRAL", 0f, "响应缺少 message 字段", totalTokens)

            // 部分模型（如 Kimi K2.x 思考模型）把最终答案放在 reasoning_content 里，
            // content 可能为空字符串；两者都取来拼在一起解析。
            val content = buildString {
                append(message.optString("content").orEmpty().trim())
                val reasoning = message.optString("reasoning_content").orEmpty().trim()
                if (reasoning.isNotEmpty()) {
                    append('\n')
                    append(reasoning)
                }
            }

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
            Log.e(TAG, "解析响应失败: $responseBody", e)
            // 用更可读的提示替代大段原始 JSON，方便用户判断问题类型
            val hint = when {
                responseBody.isBlank() -> "响应为空"
                responseBody.contains("\"reasoning_content\"", true) ->
                    "模型只输出了思考内容，未输出分类结果（可尝试换非思考模型）"
                responseBody.contains("\"content\":\"\"", true) ->
                    "模型返回空内容（max_tokens 可能被思考占用）"
                else -> "响应格式异常"
            }
            AiResult("NEUTRAL", 0f, "结果解析失败：$hint")
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
