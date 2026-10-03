package com.focusguard.app.enforce

import android.content.Context

/**
 * AI 对话里的「冻结 / 解冻应用」工具。
 *
 * 协议与 lock_phone / 备忘录工具一致（模型在回复末尾单独输出一行文本标记）：
 * - `__FREEZE__:<应用名或包名>`   立即冻结（需 Dhizuku 或 Shizuku 授权）
 * - `__UNFREEZE__:<应用名或包名>` 请求解冻；**必须由界面答题验证通过后才执行**
 *
 * 冻结用的是与「锁机期间冻结娱乐应用」同一套机制（DPM setPackagesSuspended /
 * `pm suspend`），但记录在独立的持久集合里：锁机结束不会顺带解冻它，
 * 只能通过答题、或设置页的「强制解冻」解除。
 */
object AppFreezeToolExecutor {

    private val freezePattern = Regex("""__FREEZE__:\s*([^\n]{1,40})""")
    private val unfreezePattern = Regex("""__UNFREEZE__:\s*([^\n]{1,40})""")

    data class Request(val unfreeze: Boolean, val query: String)

    /** 解析回复里的冻结 / 解冻请求（按出现顺序）。 */
    fun parse(reply: String): List<Request> =
        freezePattern.findAll(reply).map { Request(false, it.groupValues[1].trim()) }.toList() +
            unfreezePattern.findAll(reply).map { Request(true, it.groupValues[1].trim()) }.toList()

    /** 去掉协议标记（气泡里不显示这些文本）。 */
    fun stripMarkers(text: String): String = text
        .replace(Regex("""__FREEZE__:[^\n]*"""), "")
        .replace(Regex("""__UNFREEZE__:[^\n]*"""), "")

    /**
     * 应用名（模糊匹配）或包名 → (包名, 显示名)；找不到返回 null。
     *
     * 容错：模型常把说明文字写在同一行（例如 `__FREEZE__:哔哩哔哩 已冻结`），
     * 所以先去掉尾部标点，再做三级匹配 —— 完全相等 → 应用名包含查询词
     * → 反过来「查询词里包含应用名」（取最长的那个，最具体）。
     */
    fun resolve(context: Context, query: String): Pair<String, String>? {
        val q = query.trim().trimEnd('。', '，', ',', '.', '!', '！', '；', ';')
        if (q.isEmpty()) return null
        val pm = context.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val self = context.packageName
        var fuzzy: Pair<String, String>? = null
        var contained: Pair<String, String>? = null
        for (info in apps) {
            val pkg = info.packageName
            if (pkg == self) continue
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
            if (pkg.equals(q, true) || label.equals(q, true)) return pkg to label
            if (fuzzy == null && (label.contains(q, true) || pkg.contains(q, true))) {
                fuzzy = pkg to label
            }
            if (label.length >= 2 && q.contains(label, true)) {
                val best = contained
                if (best == null || label.length > best.second.length) contained = pkg to label
            }
        }
        return fuzzy ?: contained
    }

    /** 立即冻结。返回生效的途径（dhizuku/shizuku），失败返回 null。 */
    fun freeze(context: Context, pkg: String): String? =
        com.focusguard.app.enhance.LockPolicies.freezePersistent(context, setOf(pkg))

    /** 解冻（答题通过后调用）。 */
    fun unfreeze(context: Context, pkgs: Set<String>): Boolean =
        com.focusguard.app.enhance.LockPolicies.unfreezePersistent(context, pkgs)

    /** 已冻结应用的显示名（供提示词与结果说明）。 */
    fun frozenLabels(context: Context): List<String> {
        val pkgs = com.focusguard.app.enhance.LockPolicies.allFrozen(context)
        if (pkgs.isEmpty()) return emptyList()
        val pm = context.packageManager
        return pkgs.map { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg)
        }.sorted()
    }

    /** 注入系统提示词的协议说明（含当前冻结清单，便于模型对上用户说的名字）。 */
    fun toolInstruction(context: Context): String = buildString {
        append("\n你还拥有 freeze_app / unfreeze_app 工具：\n")
        append("· 用户要求冻结、限制、停用某个应用时，在回复末尾单独输出一行 __FREEZE__:<应用名>；\n")
        append("· 用户要求解冻某个应用时，输出一行 __UNFREEZE__:<应用名>。解冻必须先答题验证，")
        append("所以自然语言里要说明「需要先答对一道题」；\n")
        append("· 应用名直接写中文名即可（例如 __FREEZE__:哔哩哔哩），禁止输出 JSON 或函数调用格式；\n")
        append("· 一次可输出多行标记；不需要操作时不要输出。标记会被应用自动执行并从对话里隐藏。\n")
        val frozen = frozenLabels(context)
        append(
            if (frozen.isEmpty()) "· 当前没有冻结中的应用。"
            else "· 当前已冻结：" + frozen.joinToString("、") + "。"
        )
    }
}
