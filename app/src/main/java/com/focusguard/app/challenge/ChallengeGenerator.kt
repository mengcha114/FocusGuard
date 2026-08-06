package com.focusguard.app.challenge

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
import kotlin.random.Random

data class ChallengeQuestion(
    val question: String = "",
    val answer: String = "",
    val explanation: String = ""
)

/**
 * 解锁挑战题目生成器。
 *
 * 优先调用大模型出题（纯文本调用，token 消耗很低）；
 * 网络异常或未配置 API 时回退到本地随机生成器，
 * 保证离线状态下依然有可用且答案精确的题目。
 */
class ChallengeGenerator {

    companion object {
        private const val TAG = "ChallengeGenerator"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun generateQuestion(
        baseUrl: String,
        apiKey: String,
        modelName: String
    ): ChallengeQuestion = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext generateLocalQuestion()

        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "请出一道新的题目。")
                })
            }

            val body = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("max_tokens", 500)
                put("temperature", 0.9)
            }

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "出题接口返回 ${response.code}，回退本地题库")
                    return@withContext generateLocalQuestion()
                }
                val text = response.body?.string().orEmpty()
                parseResponse(text) ?: generateLocalQuestion()
            }
        } catch (e: Exception) {
            Log.w(TAG, "出题请求失败，回退本地题库: ${e.message}")
            generateLocalQuestion()
        }
    }

    /** 判定用户作答是否正确，容忍全/半角、空格、单位后缀等常见差异。 */
    fun isAnswerCorrect(userAnswer: String, expected: String): Boolean {
        val a = normalize(userAnswer)
        val b = normalize(expected)
        if (a.isEmpty()) return false
        if (a == b) return true

        // 数字答案按数值比较，避免 "1024" 与 "1,024" 判错
        val na = a.toDoubleOrNull()
        val nb = b.toDoubleOrNull()
        return na != null && nb != null && kotlin.math.abs(na - nb) < 1e-9
    }

    private fun normalize(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace("，", "")
            .replace(",", "")
            .replace(" ", "")
            .replace("　", "")
            .removeSuffix("。")
            .removeSuffix(".")
            .removeSuffix("个")
            .removeSuffix("元")
            .removeSuffix("天")
    }

    private fun parseResponse(responseBody: String): ChallengeQuestion? {
        return try {
            val content = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val result = JSONObject(extractJson(content))
            val question = result.optString("question").trim()
            val answer = result.optString("answer").trim()
            if (question.isEmpty() || answer.isEmpty()) return null

            ChallengeQuestion(
                question = question,
                answer = answer,
                explanation = result.optString("explanation").trim()
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析出题结果失败: ${e.message}")
            null
        }
    }

    private fun extractJson(content: String): String {
        Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.let { return it.groupValues[1] }
        Regex("\\{\\s*\"question\".*?}", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.let { return it.value }
        return content
    }

    /**
     * 本地题目生成：全部由程序即时计算答案，
     * 不使用硬编码答案，因此不存在答案写错的可能。
     */
    private fun generateLocalQuestion(): ChallengeQuestion {
        return when (Random.nextInt(6)) {
            0 -> bigAddition()
            1 -> bigSubtraction()
            2 -> multiplication()
            3 -> powerOfTwo()
            4 -> weekdayOffset()
            else -> fibonacciNext()
        }
    }

    private fun bigAddition(): ChallengeQuestion {
        val a = Random.nextInt(100_000, 999_999)
        val b = Random.nextInt(100_000, 999_999)
        return ChallengeQuestion(
            question = "计算：$a + $b = ?",
            answer = (a + b).toString(),
            explanation = "$a + $b = ${a + b}"
        )
    }

    private fun bigSubtraction(): ChallengeQuestion {
        val a = Random.nextInt(500_000, 999_999)
        val b = Random.nextInt(100_000, 499_999)
        return ChallengeQuestion(
            question = "计算：$a - $b = ?",
            answer = (a - b).toString(),
            explanation = "$a - $b = ${a - b}"
        )
    }

    private fun multiplication(): ChallengeQuestion {
        val a = Random.nextInt(120, 999)
        val b = Random.nextInt(120, 999)
        return ChallengeQuestion(
            question = "计算：$a × $b = ?",
            answer = (a.toLong() * b).toString(),
            explanation = "$a × $b = ${a.toLong() * b}"
        )
    }

    private fun powerOfTwo(): ChallengeQuestion {
        val n = Random.nextInt(11, 21)
        val value = 1L shl n
        return ChallengeQuestion(
            question = "计算：2 的 $n 次方等于多少？",
            answer = value.toString(),
            explanation = "2^$n = $value"
        )
    }

    private fun weekdayOffset(): ChallengeQuestion {
        val names = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val startIndex = Random.nextInt(7)
        val days = Random.nextInt(50, 500)
        val targetIndex = (startIndex + days) % 7
        return ChallengeQuestion(
            question = "如果今天是${names[startIndex]}，那么 $days 天后是星期几？（回答格式如：星期三）",
            answer = names[targetIndex],
            explanation = "$days ÷ 7 = ${days / 7} 余 ${days % 7}，" +
                "从${names[startIndex]}往后推 ${days % 7} 天即${names[targetIndex]}"
        )
    }

    private fun fibonacciNext(): ChallengeQuestion {
        val length = Random.nextInt(6, 10)
        val seq = mutableListOf(1L, 1L)
        while (seq.size < length + 1) {
            seq.add(seq[seq.size - 1] + seq[seq.size - 2])
        }
        val shown = seq.take(length)
        val next = seq[length]
        return ChallengeQuestion(
            question = "数列规律：${shown.joinToString(", ")}, ? 下一个数是多少？",
            answer = next.toString(),
            explanation = "斐波那契数列，每项为前两项之和：${shown[length - 2]} + ${shown[length - 1]} = $next"
        )
    }
}

private const val SYSTEM_PROMPT = """你是一个出题助手，专门生成高难度但不需要专业知识储备的数学计算题、逻辑推理题和思考题。

规则：
1. 不需要任何专业背景知识
2. 只需要基本的加减乘除运算能力（可以是大数字计算）
3. 或者需要逻辑推理能力
4. 难度要足够高，需要认真思考，不能一眼看出答案
5. 答案必须精确、唯一，且为简短的数字或短词，便于文本比对

常见题型：
- 大数字加减乘除（如：1234567 + 7654321 = ?）
- 幂运算（如：2 的 17 次方是多少？）
- 数字规律题（如：2, 6, 12, 20, ? 下一个数是？）
- 时间推算（如：今天星期三，247 天后是星期几？）
- 简单概率或组合计算

你必须严格以 JSON 格式回复，不要输出任何其他内容：
{"question": "题目内容", "answer": "精确答案", "explanation": "解题思路"}"""
