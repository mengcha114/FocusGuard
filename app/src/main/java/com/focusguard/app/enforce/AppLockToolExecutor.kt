package com.focusguard.app.enforce

import android.content.Context
import com.focusguard.app.data.AppBlockStore
import com.focusguard.app.data.LockState
import com.focusguard.app.data.Settings
import com.focusguard.app.usage.AppUsageRule
import com.focusguard.app.usage.UsageRuleStore

/**
 * AI 对话里的「锁住应用 / 解除封锁 / 设置每日上限」工具。
 *
 * 协议（回复末尾单独一行）：
 * - `__LOCK_APP__:<应用名>|<分钟>`     立即锁住该应用：能启动，但一打开就被全屏挡回；分钟可省，默认用设置里的封锁时长
 * - `__UNLOCK_APP__:<应用名>`          解除应用封锁；**需要答题验证**
 * - `__SET_APP_LIMIT__:<应用名>|<分钟>` 设每日最多使用时长；调小（收紧）直接生效，调大需要答题
 *
 * 与「冻结」的区别：冻结是让应用直接起不来（系统级挂起），
 * 锁住是应用能启动、一打开就被挡回（可配每日上限）。
 */
object AppLockToolExecutor {

    private val lockPattern = Regex("""__LOCK_APP__:\s*([^\n|]{1,40})(?:\|\s*(\d{1,4}))?""")
    private val unlockPattern = Regex("""__UNLOCK_APP__:\s*([^\n|]{1,40})""")
    private val limitPattern = Regex("""__SET_APP_LIMIT__:\s*([^\n|]{1,40})\|\s*(\d{1,4})""")

    /** kind: lock / unlock / limit */
    data class Request(val kind: String, val query: String, val minutes: Int?)

    fun parse(reply: String): List<Request> =
        lockPattern.findAll(reply).map {
            Request("lock", it.groupValues[1].trim(), it.groupValues[2].toIntOrNull())
        }.toList() +
            limitPattern.findAll(reply).map {
                Request("limit", it.groupValues[1].trim(), it.groupValues[2].toIntOrNull())
            }.toList() +
            unlockPattern.findAll(reply).map {
                Request("unlock", it.groupValues[1].trim(), null)
            }.toList()

    fun stripMarkers(text: String): String = text
        .replace(Regex("""__LOCK_APP__:[^\n]*"""), "")
        .replace(Regex("""__UNLOCK_APP__:[^\n]*"""), "")
        .replace(Regex("""__SET_APP_LIMIT__:[^\n]*"""), "")

    fun labelOf(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)

    /** 锁住结果：是否当场弹了封锁页（用户不在该应用时只登记，等他打开再挡）。 */
    data class LockResult(val label: String, val minutes: Int, val shownNow: Boolean)

    /**
     * 立即锁住应用（登记封锁；用户此刻正在该应用时再当场弹页）。
     *
     * 必须复核「目标是否仍是当前前台」：AI 对话里说完到执行之间可能已经过了几秒，
     * 用户退出了该应用 —— 那时弹页会让封锁页盖在他正用的应用上（用户报告的缺陷）。
     */
    fun lockApp(context: Context, pkg: String, minutes: Int?): LockResult {
        val label = labelOf(context, pkg)
        val mins = (minutes ?: Settings(context).appBlockMinutes).coerceIn(1, 24 * 60)
        val until = System.currentTimeMillis() + mins * 60_000L
        AppBlockStore(context).block(pkg, until)
        // 守护没在跑的话，封锁页被划掉就没人补拉了
        com.focusguard.app.service.LockGuardService.ensureRunning(context)
        com.focusguard.app.service.GuardWatchdogWorker.schedule(context)
        val shownNow = com.focusguard.app.service.ForegroundAppDetector.isForeground(context, pkg) ||
            com.focusguard.app.access.GuardAccessibilityService.instance?.liveWindowPackage() == pkg
        if (shownNow) {
            AppBlockActivity.show(
                context = context,
                packageName = pkg,
                appLabel = label,
                usedMinutes = 0,
                limitMinutes = mins,
                blockUntil = until
            )
        }
        return LockResult(label, mins, shownNow)
    }

    /**
     * 答题解封：解除临时封锁，并把「今日已用时长」回退 [minutes] 分钟
     * —— 等于通过答题再换一段使用时间。封锁页上的「答题解封」走这里。
     */
    fun grantExtraTime(context: Context, pkg: String, minutes: Int) {
        val store = UsageRuleStore.shared(context)
        AppBlockStore(context).clear(pkg)
        val back = minutes.coerceAtLeast(1) * 60L
        val used = store.getTodaySeconds(pkg)
        store.addSeconds(pkg, -minOf(used, back))
        AppBlockActivity.dismissIfShowing(pkg)
    }

    /** 解除应用封锁（答题通过后调用）。 */
    fun unlockApp(context: Context, pkg: String) {
        AppBlockStore(context).clear(pkg)
        AppBlockActivity.dismissIfShowing(pkg)
    }

    /** 每日上限属于收紧（调小 / 首次设置）返回 true；调大属于放宽，需要答题。 */
    fun isLimitTightening(context: Context, pkg: String, minutes: Int): Boolean {
        val old = UsageRuleStore.shared(context).getRule(pkg)?.hardBlockMinutes
        return old == null || minutes <= old
    }

    /** 写入每日上限（放宽时在答题通过后调用）。 */
    fun applyLimit(context: Context, pkg: String, minutes: Int) {
        val store = UsageRuleStore.shared(context)
        val mins = minutes.coerceIn(1, 24 * 60)
        // 规则要求 hardBlock >= trigger，上限被调小到低于原触发线时丢掉触发线
        val trigger = store.getRule(pkg)?.triggerMinutes?.takeIf { it <= mins }
        store.setRule(AppUsageRule(packageName = pkg, triggerMinutes = trigger, hardBlockMinutes = mins))
    }

    fun toolInstruction(): String = buildString {
        append("\n你还拥有应用管控工具：\n")
        append("· 用户要求「锁住/禁用/别让我打开」某个应用但不想让它消失 → 在回复末尾输出 __LOCK_APP__:<应用名>|<分钟>（分钟省略则用设置里的默认封锁时长）；\n")
        append("· 用户要求解除应用封锁 → 输出 __UNLOCK_APP__:<应用名>，这需要用户答题验证，回复里要说明；\n")
        append("· 用户要求给应用设每日使用上限 → 输出 __SET_APP_LIMIT__:<应用名>|<分钟>（调小直接生效，调大需答题）；\n")
        append("· 应用名写中文名即可，禁止输出 JSON 或函数调用格式。\n")
    }

    /** 当前管控状态摘要：注入提示词，让模型能回答「我现在被锁了什么」。 */
    fun statusSummary(context: Context): String = buildString {
        val lock = LockState(context)
        append("\n【当前状态】\n")
        if (lock.isLocked) {
            val totalMin = (lock.remainingMs / 60_000).toInt()
            append("· 正在锁机，剩余约 ${totalMin / 60} 小时 ${totalMin % 60} 分钟")
            append(if (lock.shouldBlockNow) "（生效中）\n" else "（暂停中）\n")
        } else {
            append("· 当前没有锁机\n")
        }
        val frozen = AppFreezeToolExecutor.frozenLabels(context)
        append(if (frozen.isEmpty()) "· 没有冻结中的应用\n" else "· 已冻结：" + frozen.joinToString("、") + "\n")
        val blocks = AppBlockStore(context).activeBlocks()
        append(
            if (blocks.isEmpty()) "· 没有被锁住的应用\n"
            else "· 已锁住：" + blocks.keys.joinToString("、") { labelOf(context, it) } + "\n"
        )
        val store = UsageRuleStore.shared(context)
        val rules = store.allRules()
        if (rules.isEmpty()) {
            append("· 没有设置使用时长上限的应用\n")
        } else {
            append("· 使用时长上限：")
            append(
                rules.joinToString("、") { rule ->
                    val used = (store.getTodaySeconds(rule.packageName) / 60).toInt()
                    val label = labelOf(context, rule.packageName)
                    val limit = rule.hardBlockMinutes
                    if (limit != null) "$label 今日 $used/$limit 分钟" else "$label 今日 $used 分钟"
                }
            )
            append("\n")
        }
    }
}
