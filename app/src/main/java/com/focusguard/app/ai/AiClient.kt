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
    var retryable: Boolean = false,
    /** 内部标记：响应解析失败（200 但格式异常），需要降级重试。 */
    var parseFailed: Boolean = false,
    /** 内部标记：网络请求失败（超时/断连），需要换最简请求重试一次。 */
    var networkFailed: Boolean = false
)

/** 对话消息（AI 对话页用）。role: system/user/assistant。 */
data class ChatMessage(
    val role: String,
    val content: String
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
        /**
         * 输出上限。
         *
         * 注意：不能太小！思考模型（Kimi K2.x、agnes 等）的 reasoning_content
         * 会先吃掉大量输出 token，max_tokens=200 时思考还没写完就被截断
         * （finish_reason=length），content 永远为空 → 永远掉进文本兜底，
         * reason 显示"用户要求我判断…"这类提示词复述（用户看到的"无用信息"）。
         * 1024 给足思考 + 最终 JSON 的空间；非思考模型实际只消耗一小部分，
         * max_tokens 是上限不是固定消耗。
         */
        private const val MAX_OUTPUT_TOKENS = 1024

        /** 最近 N 次请求的诊断信息（导出日志用）。线程安全环形缓冲。 */
        private val diagnostics = java.util.Collections.synchronizedList(
            java.util.LinkedList<String>()
        )

        private const val MAX_DIAGNOSTICS = 40

        /** 记录一次请求诊断。 */
        fun recordDiagnostic(line: String) {
            synchronized(diagnostics) {
                if (diagnostics.size >= MAX_DIAGNOSTICS) {
                    diagnostics.removeAt(0)
                }
                diagnostics.add("[${
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                }] $line")
            }
        }

        /** 导出最近诊断。 */
        fun exportDiagnostics(): String = synchronized(diagnostics) {
            if (diagnostics.isEmpty()) return "（暂无 AI 调用诊断）"
            diagnostics.joinToString("\n")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 读超时 90s：思考模型（agnes 等）生成 1024 token 的推理可能超过 45s，
        // 之前 45s 导致频繁 "请求失败：timeout"
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreen(
        imageBytes: ByteArray,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        whitelist: String,
        customPrompt: String = "",
        apiFormat: String = "openai"
    ): AiResult = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        // 降级链：优先带 tools（函数调用，输出结构化，思考模型也能正常返回）
        // → tools 模式 400 或解析失败 → 去掉 tools 重试（走 content JSON）
        // → 仍失败（detail 不被支持）→ 再去掉 detail 重试
        val withTools = postRequest(
            baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt, apiFormat,
            withDetail = true, withTools = true
        )
        // 网络失败（超时/断连）：可能只是临时网络抖动或 tools 参数导致服务端
        // 处理变慢。用最简请求（去 detail + 去 tools）重试一次，不再递归降级，
        // 避免网络持续故障时一次检测卡 3 次超时。
        if (withTools.networkFailed) {
            Log.w(TAG, "tools 模式网络失败（${withTools.reason}），最简请求重试一次")
            return@withContext postRequest(
                baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt, apiFormat,
                withDetail = false, withTools = false
            )
        }
        if (withTools.retryable || withTools.parseFailed) {
            Log.w(TAG, "tools 模式失败（400=${withTools.retryable} 解析失败=${withTools.parseFailed}），降级为 content 模式重试")
            val contentOnly = postRequest(
                baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt, apiFormat,
                withDetail = true, withTools = false
            )
            if (contentOnly.retryable || contentOnly.parseFailed) {
                Log.w(TAG, "content 模式仍失败，去掉 detail 完全降级重试")
                return@withContext postRequest(
                    baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt, apiFormat,
                    withDetail = false, withTools = false
                )
            }
            contentOnly
        } else {
            withTools
        }
    }

    /**
     * 流式文本对话（SSE，ChatGPT 式逐字显示）。
     *
     * 借鉴主流开源聊天实现：请求带 stream=true，逐行解析 SSE，
     * 每收到一段文本就通过 [onDelta] 回调（调用方负责切回主线程）。
     * 流结束后返回完整回复文本。
     *
     * 网关不支持流式 / 网络失败时，内部自动降级为一次性 [chat]。
     */
    suspend fun streamChat(
        messages: List<ChatMessage>,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        apiFormat: String = "openai",
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            // ── 构造请求体（与 chat 相同，但带 stream 标志） ──
            val (body, url, headers) = when (apiFormat) {
                "anthropic" -> {
                    val system = messages.filter { it.role == "system" }
                        .joinToString("\n") { it.content }
                    val msgs = messages.filter { it.role != "system" }.map {
                        JSONObject()
                            .put("role", if (it.role == "user") "user" else "assistant")
                            .put("content", it.content)
                    }
                    Triple(
                        JSONObject().apply {
                            put("model", modelName)
                            if (system.isNotBlank()) put("system", system)
                            put("messages", JSONArray().apply { msgs.forEach { put(it) } })
                            put("max_tokens", 1024)
                            put("stream", true)
                        }, "${baseUrl.trimEnd('/')}/v1/messages", mapOf(
                            "x-api-key" to apiKey,
                            "anthropic-version" to "2023-06-01",
                            "Content-Type" to "application/json"
                        )
                    )
                }
                "gemini" -> {
                    val contents = messages.filter { it.role != "system" }.map {
                        JSONObject()
                            .put("role", if (it.role == "user") "user" else "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", it.content)))
                    }
                    Triple(
                        JSONObject().apply {
                            put("contents", JSONArray().apply { contents.forEach { put(it) } })
                            put(
                                "generationConfig",
                                JSONObject().put("maxOutputTokens", 1024)
                            )
                        }, "${baseUrl.trimEnd('/')}/v1beta/models/${modelName}:streamGenerateContent?alt=sse", mapOf(
                            "x-goog-api-key" to apiKey,
                            "Content-Type" to "application/json"
                        )
                    )
                }
                else -> {
                    val msgs = messages.map {
                        JSONObject().put("role", it.role).put("content", it.content)
                    }
                    Triple(
                        JSONObject().apply {
                            put("model", modelName)
                            put("messages", JSONArray().apply { msgs.forEach { put(it) } })
                            put("max_tokens", 1024)
                            put("stream", true)
                        }, "${baseUrl.trimEnd('/')}/chat/completions", mapOf(
                            "Authorization" to "Bearer $apiKey",
                            "Content-Type" to "application/json"
                        )
                    )
                }
            }

            recordDiagnostic("对话(流式) 模型=$modelName 协议=$apiFormat")

            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            val request = builder
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val full = StringBuilder()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordDiagnostic("对话(流式) HTTP ${resp.code}")
                    return@withContext chat(
                        messages, baseUrl, apiKey, modelName, apiFormat
                    )
                }
                // 逐行解析 SSE
                val source = resp.body?.source()
                if (source == null) {
                    return@withContext chat(messages, baseUrl, apiKey, modelName, apiFormat)
                }
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = try {
                        when (apiFormat) {
                            "anthropic" -> JSONObject(payload)
                                .optString("type").let { t ->
                                    if (t == "content_block_delta") {
                                        JSONObject(payload)
                                            .optJSONObject("delta")
                                            ?.optString("text", "")
                                    } else ""
                                }
                            "gemini" -> JSONObject(payload)
                                .optJSONArray("candidates")?.optJSONObject(0)
                                ?.optJSONObject("content")?.optJSONArray("parts")
                                ?.optJSONObject(0)?.optString("text", "")
                            else -> JSONObject(payload)
                                .optJSONArray("choices")?.optJSONObject(0)
                                ?.optJSONObject("delta")?.optString("content", "")
                        }.orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    if (delta.isNotEmpty()) {
                        full.append(delta)
                        onDelta(delta)
                    }
                }
            }
            recordDiagnostic("对话(流式) 完成 ${full.length} 字符")
            full.toString().ifBlank { "（AI 未返回内容）" }
        } catch (e: Exception) {
            Log.w(TAG, "流式对话失败（${e.message}），降级一次性对话")
            recordDiagnostic("对话(流式)失败降级 ${e.message}")
            chat(messages, baseUrl, apiKey, modelName, apiFormat)
        }
    }

    /**
     * 文本对话（AI 对话页用）。
     *
     * 复用当前配置的模型与协议（openai / anthropic / gemini），
     * 发送纯文本消息，返回 AI 回复文本。失败时返回错误说明（供直接展示）。
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        apiFormat: String = "openai"
    ): String = withContext(Dispatchers.IO) {
        try {
            // ── 按协议构造请求体与 URL ──────────────────
            val (body, url, headers) = when (apiFormat) {
                "anthropic" -> {
                    val system = messages.filter { it.role == "system" }
                        .joinToString("\n") { it.content }
                    val msgs = messages.filter { it.role != "system" }.map {
                        JSONObject()
                            .put("role", if (it.role == "user") "user" else "assistant")
                            .put("content", it.content)
                    }
                    Triple(
                        JSONObject().apply {
                            put("model", modelName)
                            if (system.isNotBlank()) put("system", system)
                            put("messages", JSONArray().apply { msgs.forEach { put(it) } })
                            put("max_tokens", 1024)
                        }, "${baseUrl.trimEnd('/')}/v1/messages", mapOf(
                            "x-api-key" to apiKey,
                            "anthropic-version" to "2023-06-01",
                            "Content-Type" to "application/json"
                        )
                    )
                }
                "gemini" -> {
                    // system 消息走 systemInstruction 字段（此前被静默丢弃 → 不读人设）
                    val system = messages.filter { it.role == "system" }
                        .joinToString("\n") { it.content }
                    val contents = messages.filter { it.role != "system" }.map {
                        JSONObject()
                            .put("role", if (it.role == "user") "user" else "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", it.content)))
                    }
                    Triple(
                        JSONObject().apply {
                            if (system.isNotBlank()) {
                                put(
                                    "systemInstruction",
                                    JSONObject().put(
                                        "parts",
                                        JSONArray().put(JSONObject().put("text", system))
                                    )
                                )
                            }
                            put("contents", JSONArray().apply { contents.forEach { put(it) } })
                            put(
                                "generationConfig",
                                JSONObject().put("maxOutputTokens", 1024)
                            )
                        }, "${baseUrl.trimEnd('/')}/v1beta/models/${modelName}:generateContent", mapOf(
                            "x-goog-api-key" to apiKey,
                            "Content-Type" to "application/json"
                        )
                    )
                }
                else -> {
                    val msgs = messages.map {
                        JSONObject().put("role", it.role).put("content", it.content)
                    }
                    Triple(
                        JSONObject().apply {
                            put("model", modelName)
                            put("messages", JSONArray().apply { msgs.forEach { put(it) } })
                            put("max_tokens", 1024)
                        }, "${baseUrl.trimEnd('/')}/chat/completions", mapOf(
                            "Authorization" to "Bearer $apiKey",
                            "Content-Type" to "application/json"
                        )
                    )
                }
            }

            recordDiagnostic("对话 模型=$modelName 协议=$apiFormat")

            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            val request = builder
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // 网络失败（超时/断连）自动重试一次，临时抖动可恢复
            var lastError: String? = null
            repeat(2) { attempt ->
                try {
                    val result = client.newCall(request).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            recordDiagnostic("对话 HTTP ${resp.code} 响应=${respBody.take(200)}")
                            return@withContext "请求失败（${resp.code}）：${respBody.take(120)}"
                        }
                        when (apiFormat) {
                            "anthropic" -> JSONObject(respBody)
                                .optJSONArray("content")?.optJSONObject(0)?.optString("text")
                            "gemini" -> JSONObject(respBody)
                                .optJSONArray("candidates")?.optJSONObject(0)
                                ?.optJSONObject("content")?.optJSONArray("parts")
                                ?.optJSONObject(0)?.optString("text")
                            else -> JSONObject(respBody)
                                .optJSONArray("choices")?.optJSONObject(0)
                                ?.optJSONObject("message")?.optString("content")
                        }.orEmpty()
                    }
                    return@withContext if (result.isBlank()) {
                        "（AI 未返回内容，请确认模型支持文本对话）"
                    } else {
                        result
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    if (attempt == 0) {
                        Log.w(TAG, "对话网络失败（${e.message}），重试一次")
                        recordDiagnostic("对话网络失败重试")
                        kotlinx.coroutines.delay(1000L)
                    }
                }
            }
            Log.e(TAG, "对话失败", lastError?.let { RuntimeException(it) } ?: RuntimeException("unknown"))
            recordDiagnostic("对话失败 $lastError")
            "请求失败：$lastError"
        } catch (e: Exception) {
            Log.e(TAG, "对话失败", e)
            recordDiagnostic("对话失败 ${e.message}")
            "请求失败：${e.message}"
        }
    }

    /** 单次请求。返回 [AiResult] 且 retryable=true 表示需要降级重试。 */
    private fun postRequest(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        base64Image: String,
        whitelist: String,
        customPrompt: String,
        apiFormat: String,
        withDetail: Boolean,
        withTools: Boolean
    ): AiResult {
        return try {
            // ── 按协议构造请求 ─────────────────────────
            val request = when (apiFormat) {
                "anthropic" -> buildAnthropicRequest(
                    baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt
                )
                "gemini" -> buildGeminiRequest(
                    baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt, withDetail
                )
                else -> buildOpenAiRequest(
                    baseUrl, apiKey, modelName, base64Image, whitelist, customPrompt,
                    withDetail, withTools
                )
            }

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                // 记录诊断（截断响应，避免日志过大）
                recordDiagnostic(
                    "请求 协议=$apiFormat 模型=$modelName URL=${request.url}" +
                        " → HTTP ${response.code} 响应=${responseBody.take(600)}"
                )
                if (!response.isSuccessful) {
                    Log.e(TAG, "接口返回 ${response.code}: $responseBody")
                    val serverMsg = extractErrorMessage(responseBody)
                    // 400 时降级重试（去掉 tools / detail）——仅 OpenAI 兼容协议支持 tools 降级
                    val retryable = response.code == 400 && apiFormat == "openai"
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
                    parseResponse(responseBody, apiFormat).also { it.retryable = false }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "视觉识别失败", e)
            AiResult(classification = "NEUTRAL", reason = "请求失败：${e.message}").also {
                it.retryable = false
                // 网络层失败（超时/断连）标记出来，上层用最简请求重试一次
                val isNetwork = e is java.io.IOException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.net.ConnectException ||
                    e is java.net.UnknownHostException
                it.networkFailed = isNetwork
            }
        }
    }

    /** 定义 classify_screen 函数工具。 */
    private fun buildClassifyTools(customPrompt: String = ""): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "classify_screen")
                put("description", buildString {
                    append("判断手机屏幕内容属于学习工作、娱乐还是中性")
                    if (customPrompt.isNotBlank()) {
                        append("。用户额外要求：$customPrompt")
                    }
                })
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("c", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray().apply {
                                put("STUDY_WORK"); put("ENTERTAINMENT"); put("NEUTRAL")
                            })
                            put("description", "分类结果")
                        })
                        put("p", JSONObject().apply {
                            put("type", "number")
                            put("description", "置信度 0.0-1.0")
                        })
                        put("r", JSONObject().apply {
                            put("type", "string")
                            put("description", "给用户看的简短提醒语：按用户设定的角色口吻写（如猫娘卖萌），10-30字，禁止复述提示词")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("c"); put("p"); put("r")
                    })
                })
            })
        })
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

    // ── 三种协议请求构造 ─────────────────────────────

    /** OpenAI 兼容格式（OpenAI/Kimi/GLM/Qwen/DeepSeek/Moonshot 等）。 */
    private fun buildOpenAiRequest(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        base64Image: String,
        whitelist: String,
        customPrompt: String,
        withDetail: Boolean,
        withTools: Boolean
    ): Request {
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
            // ── 函数调用：让模型通过工具输出结构化分类 ──
            // 比"让模型输出 JSON 文本"稳定得多：
            // 思考模型即使 content 为空，也会正常调用工具，
            // 结果从 tool_calls[].function.arguments 读取，必然可解析。
            if (withTools) {
                put("tools", buildClassifyTools(customPrompt))
                put("tool_choice", JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply { put("name", "classify_screen") })
                })
            }
        }

        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /** Anthropic Claude 格式（/v1/messages）。 */
    private fun buildAnthropicRequest(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        base64Image: String,
        whitelist: String,
        customPrompt: String
    ): Request {
        // system 提示词单独放顶层
        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", base64Image)
                })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", "判断屏幕内容，调用 classify_screen 返回 JSON：{\"c\":分类,\"p\":置信度,\"r\":理由}。分类：STUDY_WORK/ENTERTAINMENT/NEUTRAL。")
            })
        }

        val body = JSONObject().apply {
            put("model", modelName)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("system", buildSystemPrompt(whitelist, customPrompt))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
        }

        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /** Google Gemini 格式（generateContent）。 */
    private fun buildGeminiRequest(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        base64Image: String,
        whitelist: String,
        customPrompt: String,
        withDetail: Boolean
    ): Request {
        val body = JSONObject().apply {
            // 系统指令必须走 systemInstruction 字段：
            // 塞在 contents[user] 里模型会当作普通用户输入而不服从
            // （症状：不读 prompt、不输出分类 JSON、无法触发锁机）
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", buildSystemPrompt(whitelist, customPrompt))
                    })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                put("temperature", 0.2)
                // 强制 JSON 输出（与提示词要求一致的约束）
                put("responseMimeType", "application/json")
            })
        }

        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1beta/models/$modelName:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
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
        // 注意：不要提任何"函数""classify_screen"字样。
        // 用户网关不支持真 tool_calls，模型看到函数定义会把它复述成文本
        // （"请确保您的环境中已定义了 classify_screen 函数…"），
        // 导致解析兜底把垃圾塞进原因。直接要求输出 JSON 最稳。
        return """判断手机截图中用户在做什么，直接输出 JSON（不要代码块、不要解释、不要任何其他文字）：

{"c":"分类","p":置信度,"r":"提醒语"}

分类 c 只能是三者之一：STUDY_WORK（学习、工作、编程、阅读文档、网课、教程、办公）、ENTERTAINMENT（游戏、娱乐短视频、直播、漫画、社交闲逛、购物）、NEUTRAL（锁屏、桌面、设置、通话、导航）。

p 是置信度，0 到 1 之间的小数。

r 是给用户看的简短提醒语（10-30字）：
- 面向用户、有实际内容，例如"主人~ 你已经在看短视频啦，休息一下喵！"
- 按用户设定的角色口吻写$custom
- 绝对禁止复述提示词、禁止写"用户要求我""我需要判断"这类话
- 输出里只能有 JSON 本身，禁止输出 JSON 以外的任何说明文字$extra"""
    }

    private fun parseResponse(responseBody: String, apiFormat: String = "openai"): AiResult {
        return try {
            val json = JSONObject(responseBody)

            // ── 按协议提取文本内容与 token 用量 ────────
            val (text, totalTokens) = when (apiFormat) {
                // Anthropic：content[0].text，usage.input_tokens+output_tokens
                "anthropic" -> {
                    val text = json.optJSONArray("content")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
                    val usage = json.optJSONObject("usage")
                    val tokens = (usage?.optInt("input_tokens", 0) ?: 0) +
                        (usage?.optInt("output_tokens", 0) ?: 0)
                    text to tokens
                }
                // Gemini：candidates[0].content.parts[0].text
                "gemini" -> {
                    val text = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
                    val tokens = json.optJSONObject("usageMetadata")
                        ?.optInt("totalTokenCount", 0)
                        ?: 0
                    text to tokens
                }
                // OpenAI 兼容：choices[0].message（可能含 tool_calls）
                else -> {
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        return AiResult("NEUTRAL", 0f, "无响应内容", 0)
                    }
                    val message = choices.getJSONObject(0).optJSONObject("message")
                        ?: return AiResult("NEUTRAL", 0f, "响应缺少 message 字段", 0)

                    // ── 优先解析函数调用结果（结构化，最可靠） ──
                    // 思考模型（Kimi K2.x/K3）即使 content 为空，
                    // 也会通过 tool_calls 返回分类结果。
                    val toolArgs = try {
                        message.optJSONArray("tool_calls")
                            ?.optJSONObject(0)
                            ?.optJSONObject("function")
                            ?.optString("arguments")
                            .orEmpty()
                            .trim()
                    } catch (e: Exception) {
                        ""
                    }

                    if (toolArgs.isNotEmpty()) {
                        val result = JSONObject(extractJson(toolArgs))
                        return buildResult(result, json)
                    }

                    // 部分模型（如 Kimi K2.x 思考模型）把最终答案放在 reasoning_content 里，
                    // content 可能为空字符串；两者都取来拼在一起解析。
                    // 注意：思考内容可能极长（几百到几千字），只取**末尾 500 字**参与解析——
                    // 结论与 JSON 通常位于思考末尾，截断后既保留答案又避免浪费解析时间。
                    val content = buildString {
                        append(message.optString("content").orEmpty().trim())
                        val reasoning = message.optString("reasoning_content").orEmpty().trim()
                        if (reasoning.isNotEmpty()) {
                            val tail = if (reasoning.length > 500) reasoning.takeLast(500)
                            else reasoning
                            append('\n')
                            append(tail)
                        }
                    }
                    content to (json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0)
                }
            }

            if (text.isBlank()) {
                return AiResult("NEUTRAL", 0f, "模型返回空内容", totalTokens).also {
                    it.parseFailed = true
                }
            }
            // 先按 JSON 解析；失败则尝试解析"函数调用文本"（部分网关不支持
            // 真 tool_calls，模型会按提示词输出 classify_screen(c=..., p=..., r=...) 文本）；
            // 再退到纯文本关键词兜底，只有三者都失败才算真正解析失败
            try {
                val result = JSONObject(extractJson(text))
                buildResult(result, json).copy(totalTokens = totalTokens)
            } catch (jsonError: Exception) {
                parseFunctionCallText(text, totalTokens)
                    ?: parseFromPlainText(text, totalTokens)
                    ?: throw jsonError
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败: $responseBody", e)
            // 用更可读的提示替代大段原始 JSON，方便用户判断问题类型
            val hint = when {
                responseBody.isBlank() -> "响应为空"
                responseBody.contains("\"content\":\"\"", true) &&
                    !responseBody.contains("tool_calls", true) ->
                    "模型返回空内容（可尝试换非思考模型）"
                else -> "响应格式异常"
            }
            // 把响应原文一并记入诊断缓冲，导出日志时可看到完整内容
            recordDiagnostic("解析失败[$hint] 原文=${responseBody.take(600)}")
            AiResult("NEUTRAL", 0f, "结果解析失败：$hint").also {
                // 解析失败标记：上层会自动降级重试（去掉 tools）
                it.parseFailed = true
            }
        }
    }

    /** 从已解析的 JSON 结果构建 AiResult。 */
    private fun buildResult(result: JSONObject, rawJson: JSONObject): AiResult {
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
            // 防御：模型有时把思考过程整个塞进 r 字段，只保留前面 120 字
            .replace('\n', ' ')
            .take(120)

        val totalTokens = rawJson.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0

        return AiResult(
            classification = normalizeClassification(classification),
            confidence = confidence.coerceIn(0f, 1f),
            reason = reason,
            totalTokens = totalTokens
        )
    }

    private fun normalizeClassification(raw: String): String {
        val upper = raw.trim().uppercase()
        return when {
            upper.contains("ENTERTAIN") -> "ENTERTAINMENT"
            upper.contains("STUDY") || upper.contains("WORK") -> "STUDY_WORK"
            else -> "NEUTRAL"
        }
    }

    /**
     * 从模型输出里抠出 JSON 对象。
     *
     * 用花括号配平扫描而不是正则：非贪婪正则 `\{.*?}` 遇到嵌套对象
     * 会在第一个 `}` 处截断，产生不合法的 JSON（这正是"响应格式异常"的
     * 主要来源）。这里从第一个 `{` 开始逐字符配平，并跳过字符串字面量
     * 内部的花括号。
     */
    private fun extractJson(content: String): String {
        val start = content.indexOf('{')
        if (start < 0) return content

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until content.length) {
            val c = content[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return content.substring(start, i + 1)
                }
            }
        }
        // 花括号没配平（输出被 max_tokens 截断）→ 返回原文，交给纯文本兜底
        return content
    }

    /**
     * 解析"函数调用文本"格式的输出。
     *
     * 部分模型/网关不支持真正的 function calling（tool_calls），
     * 但会按提示词把结果写成文本形式：
     * `classify_screen(c="NEUTRAL", p=0.9, r="主人~ 该休息啦喵")`
     *
     * 直接提取三个参数：
     * - c → 分类
     * - p → 置信度
     * - r → **角色提醒语**（用户设置的猫娘/妈妈口吻等），
     *   直接作为 reason 展示，避免出现"文本兜底解析：classify_screen(c=..."
     *   这类把函数调用原文当原因显示的无用信息。
     */
    private fun parseFunctionCallText(text: String, totalTokens: Int): AiResult? {
        val pattern = Regex(
            """classify_screen\s*\(\s*c\s*=\s*"?([A-Za-z_]+)"?\s*,\s*p\s*=\s*"?([0-9]*\.?[0-9]+)"?\s*,\s*r\s*=\s*"([^"]*)"\s*\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val match = pattern.find(text) ?: return null

        val c = match.groupValues[1]
        val p = match.groupValues[2].toFloatOrNull() ?: 0.6f
        val r = match.groupValues[3].trim()

        Log.d(TAG, "函数调用文本解析成功：$c $p")
        return AiResult(
            classification = normalizeClassification(c),
            confidence = p.coerceIn(0f, 1f),
            reason = r,
            totalTokens = totalTokens
        )
    }

    /**
     * 纯文本兜底解析。
     *
     * 模型不肯输出 JSON 时（说明文字、Markdown 列表、思考模型只输出 reasoning），
     * 从自然语言里找分类关键词，避免整轮检测白白浪费掉这次 token。
     */
    private fun parseFromPlainText(text: String, totalTokens: Int): AiResult? {
        // ── 元话语过滤 ──────────────────────────────────
        // 无 tools 网关的模型常见病：把提示词/函数说明复述成文本
        // （"请确保您的环境中已定义了 classify_screen 函数""这段代码定义了…
        //  函数""这只是一个示例"）。这类输出没有任何判断价值，
        // 直接拒绝 → 走 parseFailed（外层会显示"结果解析失败"），
        // 而不是把复述文本当 reason 展示。
        val metaMarkers = listOf(
            "classify_screen", "函数", "请确保", "这段代码", "示例",
            "请注意", "综上所述", "具体上下文", "无法准确判断"
        )
        if (metaMarkers.any { text.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "文本含元话语（模型复述提示词），拒绝兜底解析")
            return null
        }
        val upper = text.uppercase()
        val classification = when {
            upper.contains("ENTERTAINMENT") -> "ENTERTAINMENT"
            upper.contains("STUDY_WORK") || upper.contains("STUDY") -> "STUDY_WORK"
            upper.contains("NEUTRAL") -> "NEUTRAL"
            // 中文兜底：模型有时直接用中文回答
            text.contains("娱乐") || text.contains("游戏") -> "ENTERTAINMENT"
            text.contains("学习") || text.contains("工作") -> "STUDY_WORK"
            else -> return null
        }

        // 尝试从文本里捞置信度（0.85 / 85% 两种写法）
        val confidence = Regex("""0?\.\d+""").find(text)?.value?.toFloatOrNull()
            ?: Regex("""(\d{1,3})\s*%""").find(text)?.groupValues?.get(1)
                ?.toFloatOrNull()?.div(100f)
            ?: 0.6f

        // reason 要显示**判断依据**而不是提示词复述：
        // 思考文本开头通常是"用户要求我…"（提示词回显，无信息量），
        // 真正的结论在文本尾部（"所以这应该是…"）。取最后一段非空文本。
        val tail = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .lastOrNull()
            ?.take(60)
            .orEmpty()

        Log.d(TAG, "JSON 解析失败但纯文本兜底成功：$classification")
        return AiResult(
            classification = classification,
            confidence = confidence.coerceIn(0f, 1f),
            reason = "文本兜底解析：$tail",
            totalTokens = totalTokens
        )
    }
}
