package com.focusguard.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃记录器。
 *
 * 之前"点某个页面就闪退"很难查：`setContent` 里的 try/catch 兜不住渲染期崩溃
 * （渲染在后续帧里异步发生），而连电脑看 logcat 又不方便。
 *
 * 这里挂一个全局未捕获异常处理器：崩溃时把堆栈写进私有文件，下次启动由
 * [takePending] 取出、写进「检测日志」，用户在 App 里就能看到崩溃原因。
 */
object CrashRecorder {

    private const val TAG = "CrashRecorder"
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 6000

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("线程：").append(thread.name).append('\n')
                    append(sw.toString())
                }
                File(app.filesDir, FILE_NAME).writeText(text)
                Log.e(TAG, "已记录崩溃", throwable)
            }
            // 交回系统默认处理，保证进程正常终止
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 取出并清除上次崩溃记录；没有则返回 null。 */
    fun takePending(context: Context): String? = runCatching {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (!f.exists()) return null
        val text = f.readText()
        f.delete()
        text.take(MAX_CHARS).ifBlank { null }
    }.getOrNull()
}
