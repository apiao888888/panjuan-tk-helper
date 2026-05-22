package com.tiktokassist.utils

import android.view.accessibility.AccessibilityNodeInfo

/** 匹配到的评论行 */
data class CommentMatch(
    val rowNode: AccessibilityNodeInfo,
    val avatarNode: AccessibilityNodeInfo,
    val commentText: String,
    val userKey: String
)

object CommentMatcher {

    /**
     * 在评论区查找第一条：评论含匹配关键词、且尚未处理过的用户
     */
    fun findFirstMatch(
        root: AccessibilityNodeInfo?,
        keywords: List<String>,
        processedKeys: Set<String>
    ): CommentMatch? {
        root ?: return null
        val rows = findCommentRows(root)
        for (row in rows) {
            val texts = AccessibilityUtils.collectAllTexts(row)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (texts.isEmpty()) continue

            val commentBody = texts.joinToString(" ")
            if (keywords.isNotEmpty()) {
                val hit = keywords.any { kw ->
                    kw.isNotBlank() && commentBody.contains(kw.trim(), ignoreCase = true)
                }
                if (!hit) continue
            }

            val avatar = findAvatarInRow(row) ?: continue
            val userKey = texts.firstOrNull()?.take(64) ?: commentBody.take(64)
            if (userKey in processedKeys) continue

            return CommentMatch(row, avatar, commentBody, userKey)
        }
        return null
    }

    private fun findCommentRows(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val byId = mutableListOf<AccessibilityNodeInfo>()
        collectByViewIdPart(root, "comment", byId)
        val filtered = byId.filter { node ->
            val texts = AccessibilityUtils.collectAllTexts(node)
            texts.any { it.length >= 2 }
        }
        if (filtered.isNotEmpty()) return filtered.distinctBy { nodeKey(it) }

        // 兜底：含头像且有多行文字的容器
        val fallback = mutableListOf<AccessibilityNodeInfo>()
        collectRowsWithAvatarAndText(root, fallback)
        return fallback.distinctBy { nodeKey(it) }
    }

    private fun collectByViewIdPart(
        node: AccessibilityNodeInfo?,
        part: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        val id = node.viewIdResourceName ?: ""
        if (id.contains(part, ignoreCase = true) && node.childCount > 0) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectByViewIdPart(node.getChild(i), part, out)
        }
    }

    private fun collectRowsWithAvatarAndText(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        node ?: return
        if (depth > 12) return

        val hasAvatar = findAvatarInRow(node) != null
        val texts = AccessibilityUtils.collectAllTexts(node)
            .filter { it.length >= 2 }
        if (hasAvatar && texts.size >= 2 && node.isVisibleToUser) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectRowsWithAvatarAndText(node.getChild(i), out, depth + 1)
        }
    }

    private fun findAvatarInRow(row: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByViewId(row, "iv_avatar")
            ?: AccessibilityUtils.findNodeByDescription(row, "Avatar")
            ?: AccessibilityUtils.findNodeByDescription(row, "Profile photo")
            ?: findSmallClickableImage(row)
    }

    private fun findSmallClickableImage(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if ((cls.contains("Image") || node.isClickable) && node.isVisibleToUser) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() in 24..120 && rect.height() in 24..120) return node
        }
        for (i in 0 until node.childCount) {
            val found = findSmallClickableImage(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return "${r.left},${r.top},${r.right},${r.bottom}"
    }
}
