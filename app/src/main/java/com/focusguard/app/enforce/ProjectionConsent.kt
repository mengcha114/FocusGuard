package com.focusguard.app.enforce

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 「自动点掉屏幕录制授权弹窗」。
 *
 * MediaProjection 的授权**无法跨重启保留**，重启后只能重新弹系统对话框让用户点。
 * 与其等用户手动点，不如由无障碍服务在弹窗出现时替他点「立即开始」——这是
 * 用户自己的设备、自己的功能、自己打开开关，属于自动化授权而非绕过。
 *
 * 只在被 [arm] 过（我们刚主动拉起授权框）时才动作，且 [DISARM_MS] 后自动失效，
 * 绝不去点其他系统弹窗。
 */
object ProjectionConsent {

    private const val DISARM_MS = 60_000L

    @Volatile private var armedAt = 0L

    /** 刚拉起系统录制授权框，允许自动点击（由 MainActivity 调用）。 */
    fun arm() {
        armedAt = System.currentTimeMillis()
    }

    fun disarm() {
        armedAt = 0L
    }

    fun isArmed(): Boolean =
        armedAt > 0L && System.currentTimeMillis() - armedAt < DISARM_MS

    /** 录制授权弹窗可能属于这些包（各 ROM 不同）。 */
    fun isConsentHost(pkg: String): Boolean =
        pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.google.android.permissioncontroller"

    /** 按钮文案白名单（中英 + 常见 ROM 说法）。 */
    private val BUTTON_TEXTS = listOf(
        "立即开始", "开始录制", "开始", "允许", "确定", "同意",
        "start now", "start recording", "start", "allow", "cast", "share", "ok"
    )

    private fun matches(node: AccessibilityNodeInfo?): Boolean {
        val text = node?.text?.toString()?.trim()?.lowercase() ?: return false
        return BUTTON_TEXTS.any { text == it || text.contains(it) }
    }

    /**
     * 在当前窗口里找「开始录制」的肯定按钮并点击。点了返回 true。
     * 优先用 `android.R.id.button1`（系统对话框的肯定按钮），找不到再按文案匹配。
     */
    fun clickPositive(root: AccessibilityNodeInfo?): Boolean {
        if (!isArmed() || root == null) return false
        val byId = root.findAccessibilityNodeInfosByViewId(
            "android:id/button1"
        )?.firstOrNull { it.isEnabled }
        if (byId != null && byId.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            disarm()
            return true
        }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 200) {
            val node = stack.removeLast()
            visited++
            if (matches(node) && node.isEnabled &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                disarm()
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }
}
