package com.focusguard.app.detection

import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.app.access.GuardAccessibilityService

/**
 * 通过无障碍服务读取当前屏幕上的可见文字。
 *
 * 这是三级检测策略中的第二级：零 token 成本，
 * 用于在调用视觉大模型之前先尝试用文字判断内容倾向。
 */
object ScreenTextReader {

    private const val MAX_NODES = 400
    private const val MAX_TEXT_LENGTH = 2000

    /**
     * 当前屏幕的结构化信息。
     *
     * [hasPasswordField] 是「用户正在输入密码」的强信号——即使页面没有任何文字标签
     * （纯图标按钮、画布渲染），无障碍树里密码输入框的 isPassword 仍为 true，因此
     * 隐私判定不必依赖文字是否存在，也就不会把「纯图形的网页小游戏」误当成敏感页面。
     */
    data class ScreenInfo(
        val text: String,
        val hasPasswordField: Boolean,
        val hasEditableField: Boolean
    )

    /** 返回当前屏幕文字，无障碍服务未启用时返回 null。 */
    fun readCurrentScreenText(): String? = readScreenInfo()?.text?.takeIf { it.isNotBlank() }

    /** 返回当前屏幕的结构化信息；无障碍不可用时返回 null。 */
    fun readScreenInfo(): ScreenInfo? {
        val service = GuardAccessibilityService.instance ?: return null
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return null

        val builder = StringBuilder()
        var visited = 0
        var passwordField = false
        var editableField = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (visited >= MAX_NODES) return
            visited++

            if (node.isPassword) passwordField = true
            val cls = node.className?.toString().orEmpty()
            if (node.isEditable || cls.contains("EditText") || cls.contains("TextField")) {
                editableField = true
            }
            if (builder.length < MAX_TEXT_LENGTH) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                    builder.append(it).append(' ')
                }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                    builder.append(it).append(' ')
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        return try {
            traverse(root)
            ScreenInfo(
                text = builder.toString().trim(),
                hasPasswordField = passwordField,
                hasEditableField = editableField
            )
        } catch (e: Exception) {
            null
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
    }
}
