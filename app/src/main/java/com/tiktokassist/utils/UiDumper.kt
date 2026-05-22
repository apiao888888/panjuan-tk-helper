package com.tiktokassist.utils

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UI 元素抓取工具：把当前界面的所有可交互节点导出成可读文本
 * 用于调试时分析 TikTok 真实的 viewId / contentDescription / 坐标
 */
object UiDumper {

    data class DumpedNode(
        val depth: Int,
        val className: String,
        val viewId: String,
        val text: String,
        val description: String,
        val isClickable: Boolean,
        val isFocusable: Boolean,
        val isEditable: Boolean,
        val bounds: Rect
    ) {
        fun toFormatted(): String {
            val indent = "  ".repeat(depth)
            val flags = buildString {
                if (isClickable) append("[CLICK]")
                if (isEditable) append("[EDIT]")
                if (isFocusable) append("[FOCUS]")
            }
            val classSimple = className.substringAfterLast('.')
            val parts = mutableListOf<String>()
            if (viewId.isNotEmpty()) parts.add("id=${viewId.substringAfterLast('/')}")
            if (text.isNotEmpty()) parts.add("text=\"${text.take(40)}\"")
            if (description.isNotEmpty()) parts.add("desc=\"${description.take(40)}\"")
            parts.add("@${bounds.toShortString()}")
            return "$indent$classSimple $flags ${parts.joinToString(" ")}"
        }
    }

    /**
     * 递归抓取整个节点树
     */
    fun dumpTree(root: AccessibilityNodeInfo?): List<DumpedNode> {
        val result = mutableListOf<DumpedNode>()
        if (root != null) collectNode(root, 0, result)
        return result
    }

    private fun collectNode(node: AccessibilityNodeInfo, depth: Int, out: MutableList<DumpedNode>) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // 过滤掉完全没信息的空节点（减少噪音）
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        val hasInfo = text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty() ||
                node.isClickable || node.isEditable

        if (hasInfo) {
            out.add(DumpedNode(
                depth = depth,
                className = node.className?.toString() ?: "View",
                viewId = viewId,
                text = text,
                description = desc,
                isClickable = node.isClickable,
                isFocusable = node.isFocusable,
                isEditable = node.isEditable,
                bounds = rect
            ))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNode(child, depth + 1, out)
        }
    }

    /**
     * 只保留可点击/可编辑的节点（最常用）
     */
    fun dumpClickableOnly(root: AccessibilityNodeInfo?): List<DumpedNode> {
        return dumpTree(root).filter { it.isClickable || it.isEditable }
    }

    /**
     * 转成完整文本（用于复制/导出）
     */
    fun formatAsText(nodes: List<DumpedNode>): String {
        val sb = StringBuilder()
        sb.append("=== UI Element Dump ===\n")
        sb.append("Total nodes: ${nodes.size}\n")
        sb.append("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
            java.util.Locale.getDefault()).format(java.util.Date())}\n\n")
        nodes.forEach { sb.append(it.toFormatted()).append("\n") }
        return sb.toString()
    }

    /**
     * 转成易读的摘要（只显示关键节点）
     */
    fun formatSummary(nodes: List<DumpedNode>): String {
        val sb = StringBuilder()
        sb.append("📱 可点击元素 (${nodes.count { it.isClickable }})\n\n")
        nodes.filter { it.isClickable }.forEachIndexed { i, n ->
            sb.append("#${i + 1} ")
            if (n.viewId.isNotEmpty()) sb.append("[id=${n.viewId.substringAfterLast('/')}] ")
            if (n.description.isNotEmpty()) sb.append("desc=\"${n.description.take(30)}\" ")
            if (n.text.isNotEmpty()) sb.append("text=\"${n.text.take(30)}\" ")
            sb.append("@${n.bounds.centerX()},${n.bounds.centerY()}\n")
        }

        sb.append("\n✏️ 输入框 (${nodes.count { it.isEditable }})\n\n")
        nodes.filter { it.isEditable }.forEachIndexed { i, n ->
            sb.append("#${i + 1} ")
            if (n.viewId.isNotEmpty()) sb.append("[id=${n.viewId.substringAfterLast('/')}] ")
            if (n.description.isNotEmpty()) sb.append("desc=\"${n.description}\" ")
            sb.append("@${n.bounds.centerX()},${n.bounds.centerY()}\n")
        }
        return sb.toString()
    }
}
