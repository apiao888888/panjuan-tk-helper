package com.tiktokassist.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.random.Random

object AccessibilityUtils {

    /**
     * 在屏幕上执行上滑手势（切换TikTok视频）
     */
    fun swipeUp(service: AccessibilityService, screenHeight: Int, screenWidth: Int, durationMs: Long = 300L) {
        val startX = screenWidth / 2f + Random.nextFloat() * 40 - 20
        val startY = screenHeight * 0.75f
        val endY = screenHeight * 0.25f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX + Random.nextFloat() * 10 - 5, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        service.dispatchGesture(gesture, null, null)
    }

    /**
     * 在指定坐标点击（模拟真人，加随机偏移）
     */
    fun tapAt(service: AccessibilityService, x: Float, y: Float) {
        val offsetX = x + Random.nextFloat() * 6 - 3
        val offsetY = y + Random.nextFloat() * 6 - 3

        val path = Path().apply { moveTo(offsetX, offsetY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80L + Random.nextLong(50)))
            .build()

        service.dispatchGesture(gesture, null, null)
    }

    /**
     * 点击某个 AccessibilityNodeInfo 节点
     */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // 如果节点本身不可点击，尝试父节点
        var parent = node.parent
        var maxDepth = 5
        while (parent != null && maxDepth-- > 0) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        return false
    }

    /**
     * 在节点中输入文字
     */
    fun typeText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return true
    }

    /**
     * 根据 content description 查找节点（支持部分匹配）
     */
    fun findNodeByDescription(
        root: AccessibilityNodeInfo?,
        description: String,
        partial: Boolean = true
    ): AccessibilityNodeInfo? {
        root ?: return null
        val desc = root.contentDescription?.toString() ?: ""
        val match = if (partial) desc.contains(description, ignoreCase = true)
        else desc.equals(description, ignoreCase = true)
        if (match) return root

        for (i in 0 until root.childCount) {
            val found = findNodeByDescription(root.getChild(i), description, partial)
            if (found != null) return found
        }
        return null
    }

    /**
     * 根据 text 查找节点
     */
    fun findNodeByText(
        root: AccessibilityNodeInfo?,
        text: String,
        partial: Boolean = true
    ): AccessibilityNodeInfo? {
        root ?: return null
        val nodeText = root.text?.toString() ?: ""
        val match = if (partial) nodeText.contains(text, ignoreCase = true)
        else nodeText.equals(text, ignoreCase = true)
        if (match) return root

        for (i in 0 until root.childCount) {
            val found = findNodeByText(root.getChild(i), text, partial)
            if (found != null) return found
        }
        return null
    }

    /**
     * 根据 viewId 资源名称查找节点
     */
    fun findNodeByViewId(
        root: AccessibilityNodeInfo?,
        viewIdPart: String
    ): AccessibilityNodeInfo? {
        root ?: return null
        val id = root.viewIdResourceName ?: ""
        if (id.contains(viewIdPart, ignoreCase = true)) return root

        for (i in 0 until root.childCount) {
            val found = findNodeByViewId(root.getChild(i), viewIdPart)
            if (found != null) return found
        }
        return null
    }

    /**
     * 找到所有包含指定文字的节点
     */
    fun findAllNodesByText(
        root: AccessibilityNodeInfo?,
        text: String,
        result: MutableList<AccessibilityNodeInfo> = mutableListOf()
    ): List<AccessibilityNodeInfo> {
        root ?: return result
        val nodeText = root.text?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true)) {
            result.add(root)
        }
        for (i in 0 until root.childCount) {
            findAllNodesByText(root.getChild(i), text, result)
        }
        return result
    }

    /**
     * 收集界面上所有可见文字（用于评论扫描）
     */
    fun collectAllTexts(
        root: AccessibilityNodeInfo?,
        texts: MutableList<String> = mutableListOf()
    ): List<String> {
        root ?: return texts
        val text = root.text?.toString()
        if (!text.isNullOrBlank()) texts.add(text)
        val desc = root.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) texts.add(desc)
        for (i in 0 until root.childCount) {
            collectAllTexts(root.getChild(i), texts)
        }
        return texts
    }

    /**
     * 获取节点的中心坐标
     */
    fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float> {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return Pair(rect.exactCenterX(), rect.exactCenterY())
    }

    /**
     * 随机延迟（模拟真人操作节奏）
     */
    suspend fun randomDelay(minMs: Long = 500, maxMs: Long = 1500) {
        val delay = minMs + Random.nextLong(maxMs - minMs)
        kotlinx.coroutines.delay(delay)
    }
}
