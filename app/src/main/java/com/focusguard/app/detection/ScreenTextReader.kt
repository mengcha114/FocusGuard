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

    /** 返回当前屏幕文字，无障碍服务未启用时返回 null。 */
    fun readCurrentScreenText(): String? {
        val service = GuardAccessibilityService.instance ?: return null
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return null

        val builder = StringBuilder()
        var visited = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (visited >= MAX_NODES) return
            if (builder.length >= MAX_TEXT_LENGTH) return
            visited++

            node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                builder.append(it).append(' ')
            }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                builder.append(it).append(' ')
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        return try {
            traverse(root)
            builder.toString().trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
    }
}
