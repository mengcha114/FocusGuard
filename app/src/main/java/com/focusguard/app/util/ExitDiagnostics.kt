package com.focusguard.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LogStore
import com.focusguard.app.ui.theme.AppearanceState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「上次进程为什么没了」的取证。
 *
 * ## 为什么需要
 * 用户反馈「选完图片会闪退 / 类闪退 / 像是被强制退出」，但崩溃日志里什么都没有。
 * 这类现象通常不是 Java 崩溃，而是系统把进程杀了（内存不足、ANR、被强停），
 * Java 层根本收不到任何异常——只靠猜测永远修不对。
 *
 * Android 11（API 30）起系统提供了 [ActivityManager.getHistoricalProcessExitReasons]，
 * 能读到真实退出原因。这里在每次启动时读一次、写入检测日志：
 * 下次闪退后打开应用，日志页顶部就能看到「上次退出：内存不足 / ANR / 被强停」。
 *
 * 只做记录，不做任何自动重启或掩盖处理。
 */
object ExitDiagnostics {

    private const val TAG = "ExitDiagnostics"
    private const val PREFS = "focus_guard_exit_diag"
    private const val KEY_LAST_REPORTED = "last_reported_ts"

    /** 记录上次进程退出原因。无新记录时静默返回。 */
    fun recordPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val infos = try {
            am.getHistoricalProcessExitReasons(app.packageName, 0, 10)
        } catch (e: Exception) {
            Log.w(TAG, "读取退出原因失败：${e.message}")
            return
        }
        if (infos.isEmpty()) return

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastReported = prefs.getLong(KEY_LAST_REPORTED, 0L)
        // 取「比上次报告过更新」的最近一条；没有新记录就不打扰
        val latest = infos
            .filter { it.timestamp > lastReported }
            .maxByOrNull { it.timestamp } ?: return

        val reason = reasonText(latest.reason)
        // 正常退出（自己 stopSelf / 系统正常回收）没有诊断价值，跳过
        if (latest.reason == REASON_EXIT_SELF) {
            prefs.edit().putLong(KEY_LAST_REPORTED, latest.timestamp).apply()
            return
        }

        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(latest.timestamp))
        val pickerAt = AppearanceState.pickerOpenedAt(app)
        val picking = pickerAt > 0L && latest.timestamp >= pickerAt
        val desc = latest.description?.takeIf { it.isNotBlank() }
        val text = buildString {
            append("上次运行被系统结束：$reason\n")
            append("时间：$time")
            append("　版本：v").append(com.focusguard.app.BuildConfig.VERSION_NAME)
            append("　机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append("\n")
            if (picking) {
                append("注意：退出时**正在选择背景图**（选择器已打开），")
                append("说明大概率是导入/返回过程中进程被回收，不是普通崩溃。\n")
            }
            if (desc != null) append("系统描述：").append(desc).append('\n')
            append("（Java 崩溃日志里没有记录，说明不是可捕获的异常）")
        }
        Log.w(TAG, text)
        runCatching {
            LogStore(app).addLog(
                DetectionLog(
                    classification = "NEUTRAL",
                    confidence = 1f,
                    reason = text,
                    action = "NONE",
                    source = "ERROR",
                    appLabel = ""
                )
            )
        }
        runCatching {
            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            File(dir, "exit_last.txt").writeText(text)
        }
        if (picking) runCatching { AppearanceState.clearPickerOpened(app) }
        prefs.edit().putLong(KEY_LAST_REPORTED, latest.timestamp).apply()
    }

    // 退出原因常量：直接写数值，避免 REASON_USER_REQUESTED(API 31) 之类的版本差异
    private const val REASON_EXIT_SELF = 1
    private const val REASON_SIGNALED = 2
    private const val REASON_LOW_MEMORY = 3
    private const val REASON_CRASH = 4
    private const val REASON_CRASH_NATIVE = 5
    private const val REASON_ANR = 6
    private const val REASON_INIT_FAILURE = 7
    private const val REASON_PERMISSION_CHANGE = 8
    private const val REASON_EXCESSIVE_RESOURCE = 9
    private const val REASON_USER_REQUESTED = 10
    private const val REASON_FREEZER = 11

    private fun reasonText(reason: Int): String = when (reason) {
        REASON_SIGNALED -> "被信号杀死（可能是省电策略或安全软件）"
        REASON_LOW_MEMORY -> "内存不足被系统回收（后台内存压力大）"
        REASON_CRASH -> "应用崩溃（Java 异常）"
        REASON_CRASH_NATIVE -> "原生崩溃（底层库/RenderEffect 等，无 Java 堆栈）"
        REASON_ANR -> "无响应（主线程卡顿超过系统阈值）"
        REASON_INIT_FAILURE -> "应用初始化失败"
        REASON_PERMISSION_CHANGE -> "权限变更导致进程重启"
        REASON_EXCESSIVE_RESOURCE -> "资源占用过高被系统处理"
        REASON_USER_REQUESTED -> "被用户或安全软件「强行停止」"
        REASON_FREEZER -> "被系统冻结（cached 进程冻结）"
        else -> "其他原因（code=$reason）"
    }
}
