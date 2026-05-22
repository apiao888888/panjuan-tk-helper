package com.tiktokassist.utils

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * TikTok 界面导航：搜索关键词 → 打开视频 → 评论区滚动 → 下一个视频
 */
object TikTokNavigator {

    private val SEARCH_TAB_HINTS = listOf("Search", "Discover", "搜索", "发现")
    private val SEARCH_INPUT_HINTS = listOf(
        "Search", "Search users", "Search videos", "搜索", "搜索用户", "搜索视频"
    )
    private val VIDEOS_TAB_HINTS = listOf("Videos", "Video", "视频")
    private val COMMENT_PANEL_HINTS = listOf(
        "Add comment", "Add a comment", "评论", "Comments"
    )

    suspend fun performSearch(
        service: AccessibilityService,
        keyword: String,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (keyword.isBlank()) return false

        var root = service.rootInActiveWindow ?: return false

        // 1. 点底部「搜索/发现」
        if (!tapSearchTab(root)) {
            // 部分版本搜索在顶部
            val topSearch = AccessibilityUtils.findNodeByDescription(root, "Search")
                ?: AccessibilityUtils.findNodeByText(root, "Search")
            if (topSearch != null) AccessibilityUtils.clickNode(topSearch) else return false
            delay(1200)
            root = service.rootInActiveWindow ?: return false
        } else {
            delay(1200)
            root = service.rootInActiveWindow ?: return false
        }

        // 2. 点搜索框并输入
        val searchInput = findSearchInput(root) ?: return false
        AccessibilityUtils.clickNode(searchInput)
        delay(600)
        val inputAfterClick = service.rootInActiveWindow?.let { findSearchInput(it) } ?: searchInput
        AccessibilityUtils.typeText(inputAfterClick, keyword)
        delay(800)

        // 3. 提交搜索（回车或搜索按钮）
        val afterType = service.rootInActiveWindow ?: return false
        val searchBtn = AccessibilityUtils.findNodeByText(afterType, "Search", false)
            ?: AccessibilityUtils.findNodeByDescription(afterType, "Search")
        if (searchBtn != null) {
            AccessibilityUtils.clickNode(searchBtn)
        } else {
            inputAfterClick.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
        }
        delay(2500)

        // 4. 尽量切到「视频」标签
        val resultsRoot = service.rootInActiveWindow ?: return false
        for (hint in VIDEOS_TAB_HINTS) {
            val tab = AccessibilityUtils.findNodeByText(resultsRoot, hint, false)
                ?: AccessibilityUtils.findNodeByDescription(resultsRoot, hint, false)
            if (tab != null) {
                AccessibilityUtils.clickNode(tab)
                delay(1500)
                break
            }
        }
        return true
    }

    /** 在搜索结果中打开第一个视频（或点屏幕中部进入播放） */
    suspend fun openFirstVideoFromSearch(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        delay(800)
        val root = service.rootInActiveWindow ?: return false

        // 优先点看起来像视频封面的可点击区域（排除底部导航）
        val videoCell = findFirstVideoThumbnail(root, screenHeight)
        if (videoCell != null) {
            AccessibilityUtils.clickNode(videoCell)
            delay(2000)
            return true
        }

        // 兜底：点结果区域中部
        AccessibilityUtils.tapAt(
            service,
            screenWidth * 0.5f,
            screenHeight * 0.42f
        )
        delay(2000)
        return true
    }

    fun isCommentPanelOpen(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        for (hint in COMMENT_PANEL_HINTS) {
            if (AccessibilityUtils.findNodeByDescription(root, hint) != null) return true
            if (AccessibilityUtils.findNodeByText(root, hint) != null) return true
        }
        return AccessibilityUtils.findNodeByViewId(root, "comment_input") != null
            || AccessibilityUtils.findNodeByViewId(root, "et_comment") != null
    }

    fun openCommentPanel(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val commentBtn = AccessibilityUtils.findNodeByDescription(root, "Comment")
            ?: AccessibilityUtils.findNodeByViewId(root, "comment_btn")
            ?: AccessibilityUtils.findNodeByText(root, "Comment", false)
        commentBtn ?: return false
        return AccessibilityUtils.clickNode(commentBtn)
    }

    fun scrollCommentList(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ) {
        // 在评论区左侧区域上滑，加载更多评论
        val startX = screenWidth * 0.35f
        val startY = screenHeight * 0.72f
        val endY = screenHeight * 0.35f
        swipe(service, startX, startY, startX, endY, 350)
    }

    fun swipeToNextVideo(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ) {
        AccessibilityUtils.swipeUp(service, screenHeight, screenWidth, 400)
    }

    // ==================== 内部 ====================

    private fun tapSearchTab(root: AccessibilityNodeInfo): Boolean {
        for (hint in SEARCH_TAB_HINTS) {
            val node = AccessibilityUtils.findNodeByDescription(root, hint, false)
                ?: AccessibilityUtils.findNodeByText(root, hint, false)
            if (node != null && isLikelyBottomNav(node)) {
                return AccessibilityUtils.clickNode(node)
            }
        }
        return false
    }

    private fun isLikelyBottomNav(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        // 底部导航一般在屏幕下方 15%
        return rect.bottom > 0
    }

    private fun findSearchInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findEditableNode(root)
            ?: SEARCH_INPUT_HINTS.firstNotNullOfOrNull { hint ->
                AccessibilityUtils.findNodeByText(root, hint)?.let { parent ->
                    findEditableNode(parent) ?: findEditableInParentChain(parent)
                }
            }
    }

    private fun findEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isEditable || root.className?.toString()?.contains("EditText") == true) {
            return root
        }
        for (i in 0 until root.childCount) {
            val found = findEditableNode(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findEditableInParentChain(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = node
        var depth = 0
        while (p != null && depth++ < 6) {
            if (p.isEditable) return p
            for (i in 0 until p.childCount) {
                val found = findEditableNode(p.getChild(i))
                if (found != null) return found
            }
            p = p.parent
        }
        return null
    }

    private fun findFirstVideoThumbnail(
        root: AccessibilityNodeInfo,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickableInUpperArea(root, screenHeight, candidates)
        return candidates
            .filter { it.isVisibleToUser }
            .maxByOrNull { node ->
                val r = Rect()
                node.getBoundsInScreen(r)
                r.width() * r.height()
            }
    }

    private fun collectClickableInUpperArea(
        node: AccessibilityNodeInfo?,
        screenHeight: Int,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val inContentArea = rect.top > screenHeight * 0.12 && rect.bottom < screenHeight * 0.88
        if (inContentArea && (node.isClickable || node.className?.toString()?.contains("Image") == true)) {
            if (rect.width() > 80 && rect.height() > 80) out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectClickableInUpperArea(node.getChild(i), screenHeight, out)
        }
    }

    private fun swipe(
        service: AccessibilityService,
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long
    ) {
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)
            )
            .build()
        service.dispatchGesture(gesture, null, null)
    }
}
