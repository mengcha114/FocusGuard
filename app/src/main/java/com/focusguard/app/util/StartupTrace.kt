package com.focusguard.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动轨迹 + 安全模式判定。
 *
 * 背景：用户反馈「清掉后台后，一打开就闪退，且取不到任何崩溃日志」——
 * 这种「没有 Java 堆栈」的死法（被系统杀、ANR、native、启动期强停）不会走
 * 未捕获异常处理器，所以需要**逐步打点**：把启动过程中每一步写进文件，
 * 下次启动时看上一轮是否跑完 → 就能知道死在哪一步，并且自动降级为安全模式打开。
 *
 * 只写几个短标记，开销可忽略；只保留最近一轮。
 */
object StartupTrace {

    private const val TAG = "StartupTrace"
    private const val FILE = "startup_trace.txt"
    private const val DONE_MARK = "resume-ok"

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** 新一轮启动：清空轨迹（上一轮的内容已被 [lastRunCrashed] 读取）。 */
    fun begin(context: Context) {
        runCatching {
            file(context).writeText(
                ts() + " app.onCreate\n"
            )
        }
    }

    /** 记录一个启动步骤。 */
    fun mark(context: Context, step: String) {
        runCatching {
            file(context).appendText(ts() + " " + step + "\n")
        }
    }

    /** 界面真正跑到 onResume：标记本轮启动成功。 */
    fun markHealthy(context: Context) = mark(context, DONE_MARK)

    /**
     * 上一轮启动是否**没跑完**（说明启动过程中进程被中断/杀掉/崩了）。
     * 判定依据：轨迹文件存在、非空、且不含 [DONE_MARK]。
     */
    fun lastRunCrashed(context: Context): Boolean = runCatching {
        val f = file(context)
        if (!f.exists()) return false
        val text = f.readText()
        // 只有「确实尝试进入界面却没跑完」才算异常。
        // 关键：开机时守护服务也会拉起进程、写 app.onCreate，但它**不会走 MainActivity**，
        // 自然也没有 resume-ok —— 那不是崩溃，绝不能因此进安全模式（否则重启后每次都误进）。
        val triedUi = text.contains("main.enter")
        text.isNotBlank() && triedUi &&
            (!text.contains(DONE_MARK) || text.contains("main.blocked"))
    }.getOrDefault(false)

    /** 读取轨迹全文（诊断页/崩溃弹窗展示用）。 */
    fun read(context: Context): String = runCatching {
        val f = file(context)
        if (f.exists()) f.readText() else ""
    }.getOrDefault("")

    private fun ts(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
