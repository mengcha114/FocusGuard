package com.focusguard.app.util

import android.content.Context
import android.util.Log
import com.focusguard.app.data.DetectionLog
import com.focusguard.app.data.LogStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录：闪退的真实原因不能靠猜。
 *
 * 安装全局未捕获异常处理，把堆栈写两处：
 * 1. `LogStore`（检测日志页能看到、可复制）——下次打开应用即可看到；
 * 2. 外部私有目录 `crash_last.txt`——即使应用起不来也能用文件管理器取。
 *
 * 注意：只记录，不做任何"自动重启"之类的小动作，避免掩盖问题。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(app: Context, thread: Thread, error: Throwable) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val stack = error.stackTraceToString().lines().take(12).joinToString("\n")
        val text = "v" + com.focusguard.app.BuildConfig.VERSION_NAME +
            " (vc" + com.focusguard.app.BuildConfig.VERSION_CODE + ") · " +
            android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
            " · Android " + android.os.Build.VERSION.RELEASE + "\n" +
            "[$time] ${thread.name}: ${error.javaClass.simpleName}: ${error.message}\n$stack"
        Log.e(TAG, "捕获崩溃：\n$text")
        // ① 检测日志（界面里可见可复制）
        runCatching {
            LogStore(app).addLog(
                DetectionLog(
                    classification = "NEUTRAL",
                    confidence = 1f,
                    reason = "应用崩溃：$text",
                    action = "NONE",
                    source = "ERROR",
                    appLabel = ""
                )
            )
        }
        // ② 剪贴板：崩溃后长按任意输入框就能粘贴出来（最省事的一条）
        // 注意 Android 10+ 限制后台访问剪贴板，这里属尽力而为；失败也不影响其他两路记录
        runCatching {
            val cm = app.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(
                android.content.ClipData.newPlainText("FocusGuard 崩溃原因", text.take(2000))
            )
        }
        // ③ 外部文件（应用起不来时也能取）
        runCatching {
            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            File(dir, "crash_last.txt").writeText(text)
        }
    }
}
