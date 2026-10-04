package com.focusguard.app.util

import android.content.Context
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
 * 只写几个短标记，开销可忽略；只保留最近两轮。
 */
object StartupTrace {

    private const val FILE = "startup_trace.txt"
    private const val PREV_FILE = "startup_trace_prev.txt"
    private const val DONE_MARK = "resume-ok"

    /** 上一轮「是否没跑完」——在 [begin] 截断文件**之前**算好，供 [lastRunCrashed] 使用。 */
    @Volatile
    private var prevCrashed: Boolean? = null

    private fun file(context: Context) = File(context.filesDir, FILE)
    private fun prevFile(context: Context) = File(context.filesDir, PREV_FILE)

    /** 新一轮启动：先判定上一轮（必须在截断之前），再清空轨迹。 */
    fun begin(context: Context) {
        runCatching {
            val f = file(context)
            val prev = if (f.exists()) f.readText() else ""
            // 必须先判、后截断：截断之后文件里只剩本轮刚写下的 app.onCreate/main.enter，
            // 若此时判定就会永远得到「没跑完」→ 每次打开都误进安全模式（实测事故）。
            prevCrashed = crashedVerdict(prev)
            if (prev.isNotBlank()) runCatching { prevFile(context).writeText(prev) }
            f.writeText(ts() + " app.onCreate\n")
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
     *
     * 用 [begin] 时算好的结果，**不要**在这里重新读文件：那时读到的已经是本轮内容，
     * 只会得到「本轮还没 resume-ok」这个必然为真的结论。
     */
    fun lastRunCrashed(context: Context): Boolean {
        prevCrashed?.let { return it }
        // 兜底：进程不是我们拉起来的（没走 begin）→ 用上次保存的上一轮内容判定。
        return runCatching {
            val p = prevFile(context)
            if (p.exists()) crashedVerdict(p.readText()) else false
        }.getOrDefault(false)
    }

    /**
     * 判定依据 = **最后一次「进入界面」之后，没有任何"终点"标记**。
     *
     * 终点标记：`resume-ok`（界面真起来了）/ `main.redirectLock`（有意交接给锁机页）/
     * `main.dhizukuRestart`（有意重启一次）/ `main.lockFallback`（兜底页顶住）。
     * 只起了服务、从未进入界面的一轮（开机守护拉起的进程）**不算异常**。
     * 卡顿（`main.blocked`）本身不算：后面只要又 resume 过就是恢复了的抖动。
     */
    private fun crashedVerdict(text: String): Boolean {
        if (text.isBlank()) return false
        val lines = text.lines().filter { it.isNotBlank() }
        val lastEnter = lines.indexOfLast { it.contains("main.enter") }
        if (lastEnter < 0) return false
        return lines.drop(lastEnter).none { line ->
            line.contains(DONE_MARK) || line.contains("main.redirectLock") ||
                line.contains("main.dhizukuRestart") || line.contains("main.lockFallback") ||
                line.contains("main.hideTask")
        }
    }

    /** 读取本轮轨迹全文（诊断页/崩溃弹窗展示用）。 */
    fun read(context: Context): String = runCatching {
        val f = file(context)
        if (f.exists()) f.readText() else ""
    }.getOrDefault("")

    /** 读取**上一轮**轨迹全文（出问题的那次，诊断用）。 */
    fun readPrev(context: Context): String = runCatching {
        val p = prevFile(context)
        if (p.exists()) p.readText() else ""
    }.getOrDefault("")

    private fun ts(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
