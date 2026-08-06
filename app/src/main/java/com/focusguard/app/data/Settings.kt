package com.focusguard.app.data

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "focus_guard_settings"
        
        // API settings
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        
        // Detection settings
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_CONFIDENCE_THRESHOLD = "confidence_threshold"
        private const val KEY_CONSECUTIVE_VIOLATIONS = "consecutive_violations"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_LOCK_MINUTES_ON_VIOLATION = "lock_minutes_on_violation"
        private const val KEY_UNLOCK_QUESTION_COUNT = "unlock_question_count"

        // Token 节约系统开关
        private const val KEY_TOKEN_SAVING_ENABLED = "token_saving_enabled"
        private const val KEY_SCREEN_HASH_DEDUP = "screen_hash_dedup"
        private const val KEY_SCREEN_TEXT_PREFILTER = "screen_text_prefilter"
        private const val KEY_DECISION_CACHE_ENABLED = "decision_cache_enabled"
        private const val KEY_ADAPTIVE_INTERVAL = "adaptive_interval"
        private const val KEY_DAILY_CALL_LIMIT = "daily_call_limit"
        
        // Enforcement mode
        private const val KEY_ENFORCEMENT_MODE = "enforcement_mode"
        
        // Service state
        private const val KEY_SERVICE_RUNNING = "service_running"
        
        // Permission states
        private const val KEY_SCREEN_CAPTURE_GRANTED = "screen_capture_granted"
        private const val KEY_OVERLAY_GRANTED = "overlay_granted"
        private const val KEY_ACCESSIBILITY_GRANTED = "accessibility_granted"
    }
    
    enum class EnforcementMode(val value: Int) {
        LOCK(0),
        EXIT(1),
        WARN(2);
        
        companion object {
            fun fromValue(value: Int): EnforcementMode {
                return entries.firstOrNull { it.value == value } ?: LOCK
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // API settings
    var apiBaseUrl: String
        get() = prefs.getString(KEY_API_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString(KEY_API_BASE_URL, value).apply()
    
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()
    
    var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString(KEY_MODEL_NAME, value).apply()
    
    // Detection settings
    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL_MINUTES, 3)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_MINUTES, value).apply()
    
    var confidenceThreshold: Float
        get() = prefs.getFloat(KEY_CONFIDENCE_THRESHOLD, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_CONFIDENCE_THRESHOLD, value).apply()
    
    var consecutiveViolations: Int
        get() = prefs.getInt(KEY_CONSECUTIVE_VIOLATIONS, 2)
        set(value) = prefs.edit().putInt(KEY_CONSECUTIVE_VIOLATIONS, value).apply()
    
    var whitelist: String
        get() = prefs.getString(KEY_WHITELIST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WHITELIST, value).apply()

    /** AI 判定违规后自动锁机的时长（分钟）。 */
    var lockMinutesOnViolation: Int
        get() = prefs.getInt(KEY_LOCK_MINUTES_ON_VIOLATION, 10)
        set(value) = prefs.edit().putInt(KEY_LOCK_MINUTES_ON_VIOLATION, value).apply()

    /** 解锁需要连续答对的题目数量。 */
    var unlockQuestionCount: Int
        get() = prefs.getInt(KEY_UNLOCK_QUESTION_COUNT, 2)
        set(value) = prefs.edit().putInt(KEY_UNLOCK_QUESTION_COUNT, value).apply()

    // ── Token 节约系统 ───────────────────────────────────────────────

    /**
     * Token 节约总开关。关闭后每次检测都直接调用视觉大模型，
     * 不做任何本地过滤。默认开启。
     */
    var tokenSavingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOKEN_SAVING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TOKEN_SAVING_ENABLED, value).apply()

    /**
     * 感知哈希去重：画面与上次几乎相同时直接复用上次判定结论，
     * 不截图、不调用 AI。默认开启。
     */
    var screenHashDedupEnabled: Boolean
        get() = tokenSavingEnabled && prefs.getBoolean(KEY_SCREEN_HASH_DEDUP, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_HASH_DEDUP, value).apply()

    /**
     * 屏幕文字预过滤：通过无障碍服务读取屏幕文字，命中关键词规则时
     * 不消耗 token 直接定论。默认开启（需要无障碍权限）。
     */
    var screenTextPrefilterEnabled: Boolean
        get() = tokenSavingEnabled && prefs.getBoolean(KEY_SCREEN_TEXT_PREFILTER, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_TEXT_PREFILTER, value).apply()

    /**
     * 判定结果缓存：对历史上见过的相似画面复用大模型结论，
     * TTL 6 小时，避免同一内容反复付费。默认开启。
     */
    var decisionCacheEnabled: Boolean
        get() = tokenSavingEnabled && prefs.getBoolean(KEY_DECISION_CACHE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DECISION_CACHE_ENABLED, value).apply()

    /**
     * 自适应检测间隔：连续学习状态时自动延长间隔（最多 4×），
     * 发现娱乐迹象时立刻缩短到最小值。默认开启。
     */
    var adaptiveIntervalEnabled: Boolean
        get() = tokenSavingEnabled && prefs.getBoolean(KEY_ADAPTIVE_INTERVAL, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_INTERVAL, value).apply()

    /**
     * 每日 AI 调用次数上限。0 表示不限制。默认 120 次。
     * 达到上限后退化为纯本地判定，功能不中断但精度下降。
     */
    var dailyCallLimit: Int
        get() = prefs.getInt(KEY_DAILY_CALL_LIMIT, 120)
        set(value) = prefs.edit().putInt(KEY_DAILY_CALL_LIMIT, value.coerceAtLeast(0)).apply()

    // Enforcement mode
    var enforcementMode: EnforcementMode
        get() = EnforcementMode.fromValue(prefs.getInt(KEY_ENFORCEMENT_MODE, 0))
        set(value) = prefs.edit().putInt(KEY_ENFORCEMENT_MODE, value.value).apply()
    
    // Service state
    var serviceRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()
    
    // Permission states
    var screenCaptureGranted: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_CAPTURE_GRANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_CAPTURE_GRANTED, value).apply()
    
    var overlayGranted: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_GRANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_GRANTED, value).apply()
    
    var accessibilityGranted: Boolean
        get() = prefs.getBoolean(KEY_ACCESSIBILITY_GRANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_ACCESSIBILITY_GRANTED, value).apply()
}
