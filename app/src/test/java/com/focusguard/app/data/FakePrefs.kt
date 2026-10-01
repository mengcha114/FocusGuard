package com.focusguard.app.data

import android.content.SharedPreferences

/** 内存版 SharedPreferences（单元测试用）。 */
class FakePrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removed.add(key); return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(pending)
            return true
        }
        override fun apply() { commit() }
    }
}

/** 可控时钟：模拟改系统时间、设备睡眠、进程被杀、重启。 */
class FakeClock(
    var elapsedMs: Long = 1_000_000L,
    var uptimeMs: Long = 1_000_000L,
    var wallMs: Long = 1_700_000_000_000L,
    var boot: Int = 1
) : LockClock {
    override fun elapsed() = elapsedMs
    override fun uptime() = uptimeMs
    override fun wall() = wallMs
    override fun bootCount() = boot

    /** 设备清醒地流逝。 */
    fun advance(ms: Long) {
        elapsedMs += ms; uptimeMs += ms; wallMs += ms
    }

    /** 深度睡眠（uptime 不走）。 */
    fun sleep(ms: Long) {
        elapsedMs += ms; wallMs += ms
    }

    /** 重启：单调时钟归零后再走 [afterBootMs]，墙钟经过 [offMs] 关机时间。 */
    fun reboot(offMs: Long, afterBootMs: Long = 30_000L) {
        wallMs += offMs + afterBootMs
        elapsedMs = afterBootMs
        uptimeMs = afterBootMs
        boot += 1
    }
}
