package com.tiktokassist.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tiktokassist.model.TargetSourceType
import com.tiktokassist.model.TaskConfig
import com.tiktokassist.model.TaskMode
import com.tiktokassist.model.TaskStats
import com.tiktokassist.utils.AccessibilityUtils
import com.tiktokassist.utils.PrefsManager
import com.tiktokassist.utils.UiDumper
import kotlinx.coroutines.*
import kotlin.random.Random

class TikTokAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "TikTokAssist"
        const val ACTION_UPDATE_STATS = "com.tiktokassist.UPDATE_STATS"
        const val ACTION_LOG = "com.tiktokassist.LOG"

        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.tiktok.musically"
        )

        var instance: TikTokAccessibilityService? = null
        var stats: TaskStats = TaskStats()
        val logLines = ArrayDeque<String>(50)   // 实时日志（最多50条）
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var taskJob: Job? = null
    private var config: TaskConfig = TaskConfig()
    private var screenWidth = 1080
    private var screenHeight = 2340
    private var currentPackage = ""

    // 暂停控制
    @Volatile private var isPaused = false

    // 评论区任务状态：是否已经导航到目标视频
    private var navigatedToTarget = false
    // 已处理过的评论用户文本（用于去重，避免重复关注/私信同一人）
    private val processedCommentSignatures = HashSet<String>()
    // 搜索结果中已处理过的视频卡片 bounds 签名（避免重复点同一个）
    private val processedSearchVideoSignatures = HashSet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        config = PrefsManager.loadConfig(this)
        addLog("✅ 无障碍服务已连接")
        broadcastServiceStatus(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        currentPackage = pkg
    }

    override fun onInterrupt() {
        addLog("⚠️ 服务被中断")
        stopTask()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopTask()
        broadcastServiceStatus(false)
        return super.onUnbind(intent)
    }

    // ==================== 任务控制 ====================

    fun startTask(mode: TaskMode) {
        config = PrefsManager.loadConfig(this)
        config.currentMode = mode
        isPaused = false
        navigatedToTarget = false
        processedCommentSignatures.clear()
        processedSearchVideoSignatures.clear()
        stats = TaskStats(currentMode = mode, startTime = System.currentTimeMillis())
        logLines.clear()
        addLog("▶ 启动：${mode.displayName}")
        if (mode in listOf(
                TaskMode.VIDEO_COMMENT_FOLLOW,
                TaskMode.VIDEO_COMMENT_DM,
                TaskMode.VIDEO_COMMENT_LIKE,
                TaskMode.VIDEO_COMMENT_REPLY
            )) {
            val src = config.targetSourceType.displayName
            val input = config.targetInput.ifBlank { config.targetUsername }
            addLog("🎯 来源: $src${if (input.isNotBlank()) " · $input" else ""}")
            if (config.commentMatchKeywords.isNotEmpty()) {
                addLog("🔍 评论关键词: ${config.commentMatchKeywords.joinToString("、")}")
            } else {
                addLog("🔍 评论关键词: (全部)")
            }
        }

        taskJob?.cancel()
        taskJob = serviceScope.launch {
            try {
                runTaskWithBatchControl(mode)
            } catch (e: CancellationException) {
                addLog("⏹ 任务已手动停止")
            } catch (e: Exception) {
                addLog("❌ 任务异常: ${e.message}")
                Log.e(TAG, "任务异常", e)
            } finally {
                broadcastStats(false)
            }
        }
    }

    fun pauseTask() {
        if (taskJob?.isActive == true && !isPaused) {
            isPaused = true
            addLog("⏸ 任务已暂停")
            broadcastTaskState()
        }
    }

    fun resumeTask() {
        if (isPaused) {
            isPaused = false
            addLog("▶ 任务已继续")
            broadcastTaskState()
        }
    }

    fun stopTask() {
        isPaused = false
        taskJob?.cancel()
        taskJob = null
        addLog("⏹ 任务停止")
        broadcastStats(false)
    }

    fun isTaskRunning() = taskJob?.isActive == true
    fun isTaskPaused() = isPaused

    /**
     * 抓取当前界面元素，给调试器用
     */
    fun dumpCurrentUi(): String {
        val root = rootInActiveWindow ?: return "❌ 无法获取当前窗口（请先切换到TikTok界面）"
        val nodes = UiDumper.dumpTree(root)
        return UiDumper.formatAsText(nodes)
    }

    fun dumpUiSummary(): String {
        val root = rootInActiveWindow ?: return "❌ 无法获取当前窗口"
        val nodes = UiDumper.dumpTree(root)
        val sb = StringBuilder()
        sb.append("📦 当前包名：$currentPackage\n")
        sb.append("🪟 屏幕尺寸：${screenWidth} x ${screenHeight}\n\n")
        sb.append(UiDumper.formatSummary(nodes))
        return sb.toString()
    }

    /** 在每个可中断点调用，暂停时挂起协程直到恢复 */
    private suspend fun checkPaused() {
        while (isPaused && currentCoroutineContext().isActive) {
            delay(300)
        }
    }

    // ==================== 批次控制包装 ====================

    private suspend fun runTaskWithBatchControl(mode: TaskMode) {
        var totalDone = 0
        var cycleCount = 0
        var batchCount = 0
        val batchSize = Random.nextInt(config.batchMinCount, config.batchMaxCount + 1)

        while (currentCoroutineContext().isActive && totalDone < config.totalTaskLimit) {
            // 暂停检查点：如果暂停则挂起等待
            checkPaused()
            if (!currentCoroutineContext().isActive) break

            // 执行单次任务
            val didWork = executeOneTask(mode)
            if (didWork) {
                totalDone++
                batchCount++
                stats.totalTasksDone = totalDone
                stats.batchCount = batchCount
                broadcastStats(true)

                // 单次操作间隔（模拟真人节奏）
                val interval = Random.nextInt(
                    config.actionIntervalMinSec,
                    config.actionIntervalMaxSec + 1
                ) * 1000L
                addLog("⏱ 等待 ${interval / 1000}s 后继续...")
                delay(interval)
                checkPaused()

                // 批次休息
                if (batchCount >= batchSize) {
                    cycleCount++
                    stats.cycleCount = cycleCount
                    batchCount = 0
                    val restSec = Random.nextInt(config.batchRestMinSec, config.batchRestMaxSec + 1)
                    addLog("💤 已完成 $batchSize 个任务，休息 ${restSec}s...")
                    delay(restSec * 1000L)
                    checkPaused()

                    // 循环次数停止检查
                    if (cycleCount >= config.cycleStopCount) {
                        addLog("🏁 已完成 $cycleCount 次循环，脚本停止")
                        break
                    }
                }
            } else {
                delay(2000)
            }
        }
        addLog("✅ 任务完成，共执行 $totalDone 次")
    }

    // ==================== 分发到各功能 ====================

    private suspend fun executeOneTask(mode: TaskMode): Boolean {
        return when (mode) {
            TaskMode.NURTURE_ACCOUNT -> doNurtureAccount()
            TaskMode.TARGET_FANS_FOLLOW -> doTargetFansAction(follow = true, dm = false)
            TaskMode.TARGET_FANS_DM -> doTargetFansAction(follow = false, dm = true)
            TaskMode.FOLLOWED_DM -> doFollowedDm()
            TaskMode.OWN_FANS_DM -> doOwnFansDm()
            TaskMode.OWN_FANS_FOLLOW_BACK -> doOwnFansFollowBack()
            TaskMode.VIDEO_COMMENT_FOLLOW -> doVideoCommentAction(follow = true, dm = false, like = false, reply = false)
            TaskMode.VIDEO_COMMENT_DM -> doVideoCommentAction(follow = false, dm = true, like = false, reply = false)
            TaskMode.VIDEO_COMMENT_LIKE -> doVideoCommentAction(follow = false, dm = false, like = true, reply = false)
            TaskMode.VIDEO_COMMENT_REPLY -> doVideoCommentAction(follow = false, dm = false, like = false, reply = true)
        }
    }

    // ==================== 功能1：养号 ====================

    private suspend fun doNurtureAccount(): Boolean {
        if (currentPackage !in TIKTOK_PACKAGES) return false
        val root = rootInActiveWindow ?: return false

        val watchMs = Random.nextLong(
            config.nurtureWatchMinSec * 1000L,
            config.nurtureWatchMaxSec * 1000L
        )
        delay(watchMs)

        val freshRoot = rootInActiveWindow ?: return false

        var didSomething = false

        if (config.nurtureAutoLike && shouldDo(config.nurtureLikeRate)) {
            if (tryLike(freshRoot)) {
                stats.likesGiven++
                addLog("❤️ 点赞 [总计: ${stats.likesGiven}]")
                didSomething = true
            }
        }

        if (config.nurtureAutoFavorite && shouldDo(config.nurtureFavoriteRate)) {
            if (tryFavorite(freshRoot)) {
                stats.favoritesAdded++
                addLog("⭐ 收藏 [总计: ${stats.favoritesAdded}]")
                didSomething = true
            }
        }

        if (config.nurtureAutoComment && shouldDo(config.nurtureCommentRate)) {
            if (tryComment(freshRoot, config.commentTemplates)) {
                stats.commentsPosted++
                addLog("💬 评论 [总计: ${stats.commentsPosted}]")
                didSomething = true
            }
        }

        // 滑到下一条视频
        delay(Random.nextLong(400, 800))
        AccessibilityUtils.swipeUp(this, screenHeight, screenWidth)
        stats.videosWatched++

        return true
    }

    // ==================== 功能2/3：某人粉丝关注/私信 ====================

    private suspend fun doTargetFansAction(follow: Boolean, dm: Boolean): Boolean {
        // 需要先导航到目标用户主页 -> 粉丝列表 -> 遍历
        val root = rootInActiveWindow ?: return false

        // 找第一个可操作的粉丝用户
        val userNode = findNextFanUserItem(root) ?: return false
        AccessibilityUtils.clickNode(userNode)
        delay(2000)

        val profileRoot = rootInActiveWindow ?: return false

        if (follow) {
            if (tryFollowUser(profileRoot)) {
                stats.usersFollowed++
                addLog("👤 关注用户 [总计: ${stats.usersFollowed}]")
            }
        }

        if (dm) {
            val sent = sendSuperDm(profileRoot)
            if (sent > 0) {
                stats.dmsSent += sent
                addLog("✉️ 发送私信×$sent [总计: ${stats.dmsSent}]")
            }
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(1000)
        return true
    }

    // ==================== 功能4：已关注私信 ====================

    private suspend fun doFollowedDm(): Boolean {
        // 进入「Following」列表 -> 找用户 -> 发超级话术
        val root = rootInActiveWindow ?: return false
        val userNode = findNextFanUserItem(root) ?: return false

        AccessibilityUtils.clickNode(userNode)
        delay(2000)

        val profileRoot = rootInActiveWindow ?: return false
        val sent = sendSuperDm(profileRoot)
        if (sent > 0) {
            stats.dmsSent += sent
            addLog("✉️ 已关注私信×$sent [总计: ${stats.dmsSent}]")
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(1000)
        return sent > 0
    }

    // ==================== 功能5：自己粉丝私信 ====================

    private suspend fun doOwnFansDm(): Boolean {
        val root = rootInActiveWindow ?: return false
        val userNode = findNextFanUserItem(root) ?: return false

        AccessibilityUtils.clickNode(userNode)
        delay(2000)

        val profileRoot = rootInActiveWindow ?: return false
        val sent = sendSuperDm(profileRoot)
        if (sent > 0) {
            stats.dmsSent += sent
            addLog("✉️ 粉丝私信×$sent [总计: ${stats.dmsSent}]")
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(1000)
        return sent > 0
    }

    // ==================== 功能6：自己粉丝回关 ====================

    private suspend fun doOwnFansFollowBack(): Boolean {
        val root = rootInActiveWindow ?: return false
        val userNode = findNextFanUserItem(root) ?: return false

        AccessibilityUtils.clickNode(userNode)
        delay(2000)

        val profileRoot = rootInActiveWindow ?: return false
        val followed = tryFollowUser(profileRoot)
        if (followed) {
            stats.usersFollowed++
            addLog("👤 回关粉丝 [总计: ${stats.usersFollowed}]")
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(1000)
        return followed
    }

    // ==================== 功能7/8/9/10：视频评论区（新版：搜索 + 关键词匹配）====================

    /**
     * 整体流程（一次"任务"对应处理完一个视频）：
     * 1. 首次进入：根据 targetSourceType 导航到目标
     *    - SEARCH_KEYWORD: 打开搜索 → 输入关键词 → 进入第一个视频
     *    - VIDEO_URL: 暂不实现深链，等价于 CURRENT_VIDEO
     *    - CURRENT_VIDEO: 直接处理当前视频
     * 2. 打开评论区
     * 3. 滑动扫描评论，找匹配关键词的；命中则点头像 → 关注/私信 → 返回
     * 4. 达到上限或扫完，关闭评论区 → 滑下一条视频
     */
    private suspend fun doVideoCommentAction(
        follow: Boolean, dm: Boolean, like: Boolean, reply: Boolean
    ): Boolean {
        if (currentPackage !in TIKTOK_PACKAGES) {
            addLog("⏳ 请先切到 TikTok 应用")
            delay(1500)
            return false
        }

        // 第一次进入时根据来源类型导航
        if (!navigatedToTarget) {
            if (!navigateToTarget()) {
                addLog("⚠️ 导航到目标失败，将处理当前视频")
            }
            navigatedToTarget = true
            delay(2000)
        }

        // 1. 打开评论区
        if (!openCommentPanel()) {
            addLog("⚠️ 找不到评论按钮，跳到下一个视频")
            goToNextVideoInTask()
            return true
        }

        // 2. 扫描评论 + 关键词匹配
        val processedThisVideo = scanCommentsAndAct(follow, dm, like, reply)
        addLog("📊 本视频处理评论 $processedThisVideo 条")

        // 3. 关闭评论区
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(1000)

        // 4. 跳到下一个视频
        goToNextVideoInTask()
        return true
    }

    /**
     * 根据 targetSourceType 决定如何切换到下一个视频:
     *
     * SEARCH_KEYWORD / USERNAME 模式 (主策略 = 上滑切下一条相关视频):
     *   - 在搜索结果点击进入第一个视频后, TikTok 会进入"搜索结果视频流"
     *     (顶部带"查找相关内容/搜索"框, 上滑可顺序切换下一条搜索相关视频)
     *   - 评论 panel 已在调用方关闭(BACK 1次), 当前停留在视频播放页, 直接上滑切下一条
     *   - 用 currentVideoSignature 跳过已处理的视频, 防止上滑卡在同一条
     *   - 上滑连续 5 次都还在已处理视频 → BACK 回搜索结果列表, 滚动后选下一个卡片
     *
     * CURRENT_VIDEO / VIDEO_URL 模式: 直接上滑切下一条 (主 Feed 行为)
     */
    private suspend fun goToNextVideoInTask() {
        val isSearchMode = config.targetSourceType == TargetSourceType.SEARCH_KEYWORD
            || config.targetSourceType == TargetSourceType.USERNAME

        if (!isSearchMode) {
            // 主 Feed: 直接上滑
            swipeUpInVideoArea()
            delay(3000)
            return
        }

        // 搜索模式: 上滑切下一条搜索相关视频
        repeat(5) { attempt ->
            addLog("⬆ 上滑切下一条 (#${attempt + 1})")
            swipeUpInVideoArea()
            delay(2800)

            val root = rootInActiveWindow ?: return
            val sig = currentVideoSignature(root)
            if (sig.isBlank()) {
                addLog("ℹ️ 视频签名为空, 视为新视频")
                return
            }
            if (!processedSearchVideoSignatures.contains(sig)) {
                processedSearchVideoSignatures.add(sig)
                addLog("✅ 进入新视频: ${sig.take(40)}")
                return
            }
            addLog("⚠️ 仍是已处理视频, 再滑")
        }

        // 上滑 5 次还在已处理视频 → BACK 回搜索结果, 滚动后选下一个
        addLog("⬅ 上滑无效, BACK 回搜索结果选下一个卡片")
        backToSearchResultsAndPickNext()
    }

    /**
     * 上滑切下一条视频, 起点/终点都在屏幕中央竖直方向上,
     * 避开右侧的"作者头像/关注/点赞按钮列" (那一列从右上往左滑会进作者主页).
     */
    private fun swipeUpInVideoArea() {
        val startX = screenWidth * 0.5f + Random.nextFloat() * 20 - 10
        val endX = startX + Random.nextFloat() * 10 - 5
        val startY = screenHeight * 0.78f
        val endY = screenHeight * 0.22f
        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 280))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 给当前视频播放页打稳定签名: 优先取作者名(@xxx) + 主要 hashtag.
     * 实测 TikTok 视频播放页底部有 "作者名 · 日期" + "#hashtag1 #hashtag2 ..." 等稳定文本.
     */
    private fun currentVideoSignature(root: AccessibilityNodeInfo): String {
        val texts = mutableListOf<String>()
        collectTextsRecursive(root, texts, maxDepth = 12, depth = 0)
        // 优先用 hashtag (#xxx) + @username 这种稳定标识
        val tags = texts.filter { it.startsWith("#") || it.startsWith("@") }.distinct().take(8)
        if (tags.isNotEmpty()) return tags.joinToString("|").take(200)
        // 兜底: 取所有非空短文本拼接 (像评论数 "116", 视频描述等)
        val sig = texts.filter { it.length in 2..40 }.distinct().take(15).joinToString("|").take(200)
        return sig
    }

    private suspend fun backToSearchResultsAndPickNext() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(2500)

        // 在搜索结果页选下一个未处理的视频卡片
        var root = rootInActiveWindow ?: return
        var candidates = mutableListOf<AccessibilityNodeInfo>()
        for (waitAttempt in 1..3) {
            candidates.clear()
            collectVideoResultCandidates(root, candidates)
            if (candidates.isNotEmpty()) break
            delay(1000)
            root = rootInActiveWindow ?: return
        }
        addLog("ℹ️ 搜索结果页找到 ${candidates.size} 个视频卡片 (已处理 ${processedSearchVideoSignatures.size})")
        var next = candidates.sortedWith(
            compareBy(
                { node ->
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    r.top / 200
                },
                { node ->
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    r.left
                }
            )
        ).firstOrNull { !processedSearchVideoSignatures.contains(nodeBoundsSignature(it)) }

        var attempts = 0
        while (next == null && attempts < 3) {
            addLog("ℹ️ 当前屏无新视频, 向下滑结果列表 (#${attempts + 1})")
            scrollSearchResults()
            delay(1500)
            root = rootInActiveWindow ?: return
            next = findNextUnprocessedVideo(root)
            attempts++
        }

        if (next == null) {
            addLog("⚠️ 找不到下一个搜索结果视频, 重置已处理列表")
            processedSearchVideoSignatures.clear()
            return
        }

        val sig = nodeBoundsSignature(next)
        processedSearchVideoSignatures.add(sig)
        val r = android.graphics.Rect()
        next.getBoundsInScreen(r)
        addLog("▶ tap 下一个视频卡片 [${r.left},${r.top}]")
        tapNodeCenter(next)
        delay(3500)
    }

    /**
     * 给视频卡片打稳定签名
     * Bounds 在搜索结果列表滚动后会变, 不能当签名(否则同一视频会被反复当成"新视频")
     * 用卡片内所有 TextView 的文字拼接 (作者名+日期+hashtag+播放数 等都是稳定的)
     */
    private fun nodeBoundsSignature(node: AccessibilityNodeInfo): String {
        val texts = mutableListOf<String>()
        collectTextsRecursive(node, texts, maxDepth = 6, depth = 0)
        val sig = texts.joinToString("|").take(200)
        return if (sig.isBlank()) {
            // 兜底: 用 bounds (但不稳定)
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            "bounds:${r.left},${r.top},${r.right},${r.bottom}"
        } else sig
    }

    private fun collectTextsRecursive(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxDepth: Int,
        depth: Int
    ) {
        node ?: return
        if (depth > maxDepth) return
        val t = node.text?.toString()?.trim() ?: ""
        if (t.isNotEmpty() && t.length < 80) out.add(t)
        val d = node.contentDescription?.toString()?.trim() ?: ""
        if (d.isNotEmpty() && d.length < 80 && d != t) out.add(d)
        for (i in 0 until node.childCount) {
            collectTextsRecursive(node.getChild(i), out, maxDepth, depth + 1)
        }
    }

    /** 找下一个未处理的视频卡片 */
    private fun findNextUnprocessedVideo(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectVideoResultCandidates(root, candidates)
        // 按 y/x 排序
        val sorted = candidates.sortedWith(
            compareBy(
                { node ->
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    r.top / 200
                },
                { node ->
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    r.left
                }
            )
        )
        return sorted.firstOrNull { !processedSearchVideoSignatures.contains(nodeBoundsSignature(it)) }
    }

    /** 在搜索结果页向下滑动列表（不是切视频） */
    private suspend fun scrollSearchResults() {
        val startY = screenHeight * 0.75f
        val endY = screenHeight * 0.30f
        val x = screenWidth * 0.5f
        val path = android.graphics.Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
        delay(1200)
    }

    /** 根据 targetSourceType 导航到目标。返回是否成功导航。 */
    private suspend fun navigateToTarget(): Boolean {
        val input = config.targetInput.ifBlank { config.targetUsername }
        return when (config.targetSourceType) {
            TargetSourceType.SEARCH_KEYWORD -> {
                if (input.isBlank()) {
                    addLog("⚠️ 未填写搜索关键词")
                    false
                } else {
                    navigateBySearchKeyword(input)
                }
            }
            TargetSourceType.USERNAME -> {
                if (input.isBlank()) false
                else navigateBySearchKeyword("@${input.removePrefix("@")}")
            }
            TargetSourceType.VIDEO_URL -> {
                addLog("ℹ️ 视频链接模式暂未自动跳转，建议先手动打开视频")
                false
            }
            TargetSourceType.CURRENT_VIDEO -> true
        }
    }

    /**
     * 通过搜索入口跳到关键词搜索结果，并打开第一个视频。
     *
     * 实测 TikTok 国际版 (com.ss.android.ugc.trill) 真实 UI（1080x2340）：
     * - 主 Feed 顶部右上角搜索图标: clickable ImageView, content-desc="",
     *   bounds=[926,95][1080,249] → 中心 (1003, 172)
     * - 搜索输入框: EditText, 自动 focused=true
     * - 搜索提交按钮: text="Search" 的 Button（**注意**：clickNode 走 ACTION_CLICK
     *   在这个按钮上常常无效，必须用 dispatchGesture 真人 tap 才生效）
     * - 视频缩略图卡片: class=RelativeLayout, clickable=true,
     *   宽约 525px (屏幕一半), 高 700-900px
     * - 视频页评论按钮: content-desc="Read or add comments. N comments",
     *   bounds=[904,1470][1080,1635] → 中心 (992, 1552)
     */
    private suspend fun navigateBySearchKeyword(keyword: String): Boolean {
        addLog("🔎 搜索关键词: $keyword")

        // 0. 确保在主 Feed（Home tab 选中）
        ensureOnHomeFeed()
        delay(800)

        // 1. 找搜索图标并 tap
        var root = rootInActiveWindow ?: return false
        val searchEntry = findTopRightSearchIcon(root)
        if (searchEntry != null) {
            addLog("📍 tap 搜索图标")
            tapNodeCenter(searchEntry)
        } else {
            addLog("⚠️ 找不到搜索图标，按屏幕右上角坐标 tap")
            AccessibilityUtils.tapAt(this, screenWidth * 0.93f, screenHeight * 0.074f)
        }
        delay(2500) // 等输入框 + 键盘弹出

        // 2. 找搜索输入框
        root = rootInActiveWindow ?: return false
        val searchInput = findEditableNode(root)
        if (searchInput == null) {
            addLog("⚠️ 找不到搜索输入框")
            return false
        }
        addLog("✏️ 输入关键词: $keyword")
        // 输入框可能已经自动 focus；保险起见再 tap 一下，但用 dispatchGesture（更稳）
        tapNodeCenter(searchInput)
        delay(400)
        // 先清空现有 hint/旧内容
        val clearArgs = Bundle()
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        delay(200)
        AccessibilityUtils.typeText(searchInput, keyword)
        delay(1200)

        // 3. tap 提交搜索按钮 — 关键：用 dispatchGesture 而不是 ACTION_CLICK
        root = rootInActiveWindow ?: return false
        val searchBtn = AccessibilityUtils.findNodeByText(root, "Search", false)
            ?: AccessibilityUtils.findNodeByText(root, "搜索", false)
        if (searchBtn != null) {
            addLog("🚀 tap Search 按钮")
            tapNodeCenter(searchBtn)
        } else {
            addLog("⚠️ 找不到 Search 按钮，按屏幕右上角坐标 tap")
            // Search 按钮通常在右上角 (屏幕宽 90%, 高 7%)
            AccessibilityUtils.tapAt(this, screenWidth * 0.90f, screenHeight * 0.068f)
        }
        delay(5000) // 等搜索结果加载（网络慢时需要更长）

        // 4. 强制切到 "视频"/"Videos" tab —— 默认"综合/Top" tab 第一项可能是用户卡片
        //    点了会进用户主页, 而不是视频播放页
        root = rootInActiveWindow ?: return false
        val videoTab = AccessibilityUtils.findNodeByText(root, "视频", false)
            ?: AccessibilityUtils.findNodeByText(root, "Videos", false)
        if (videoTab != null) {
            addLog("📂 切到「视频」tab")
            tapNodeCenter(videoTab)
            delay(2500)
            root = rootInActiveWindow ?: return false
        } else {
            addLog("ℹ️ 未找到「视频」tab, 使用默认 tab")
        }

        // 5. 重试找视频卡片
        var firstVideo: AccessibilityNodeInfo? = null
        for (attempt in 1..3) {
            firstVideo = findFirstSearchResultVideo(root)
            if (firstVideo != null) break
            addLog("⏳ 等待结果渲染 (${attempt}/3)")
            delay(2000)
            root = rootInActiveWindow ?: return false
        }
        if (firstVideo == null) {
            addLog("⚠️ 找不到搜索结果视频")
            return false
        }
        addLog("▶ tap 第一个视频")
        // 记录已处理, 下次 BACK 回搜索结果页时跳过这个卡片
        processedSearchVideoSignatures.add(nodeBoundsSignature(firstVideo))
        tapNodeCenter(firstVideo)
        delay(3500) // 等视频播放页加载
        addLog("✅ 已进入第一个视频")
        return true
    }

    /**
     * 用 dispatchGesture 模拟真人 tap 节点中心。
     * 对某些 TikTok 按钮（如 Search 提交按钮），ACTION_CLICK 不响应，
     * 必须用真人触摸 gesture 才生效。
     */
    private suspend fun tapNodeCenter(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        AccessibilityUtils.tapAt(this, cx, cy)
        // 等 gesture 派发完成
        delay(150)
        return true
    }

    /** 确保 TikTok 在主 Feed（Home tab）。如果不在则点底部 Home */
    private suspend fun ensureOnHomeFeed() {
        val root = rootInActiveWindow ?: return
        val homeBtn = AccessibilityUtils.findNodeByDescription(root, "Home", false)
        if (homeBtn != null && homeBtn.isClickable) {
            // 已经选中则不点；可以根据 selected 判断
            if (!homeBtn.isSelected) {
                AccessibilityUtils.clickNode(homeBtn)
                delay(1500)
            }
        }
    }

    /**
     * 找顶部右上角的搜索图标。TikTok 不给它 content-desc 也不给 text，所以靠位置 + 类型启发：
     * - 位置：x > 屏幕宽度 75%，y < 屏幕高度 15%
     * - class 包含 ImageView 或 Image
     * - clickable=true
     */
    private fun findTopRightSearchIcon(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectTopRightClickableImages(root, result)
        // 优先选择最右上的（x 最大、y 最小）
        return result.maxByOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            // 评分：越靠右上越高分
            (r.left - r.top * 2)
        }
    }

    private fun collectTopRightClickableImages(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        if (node.isClickable) {
            val cls = node.className?.toString() ?: ""
            if (cls.contains("ImageView") || cls.contains("Image")) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                val w = r.width()
                val h = r.height()
                // 在屏幕顶部右侧 + 大小看起来像图标（不是大块容器）
                if (r.left > screenWidth * 0.75f
                    && r.top < screenHeight * 0.15f
                    && w in 40..300
                    && h in 40..300
                ) {
                    out.add(node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectTopRightClickableImages(node.getChild(i), out)
        }
    }

    /**
     * 找搜索结果页第一个视频卡片。
     *
     * 实测特征（中文版 / 英文版都符合）：
     * - 宽度 ≈ 屏宽 / 2（两列网格）
     * - 高度 700~1100（竖向视频缩略图）
     * - clickable=true
     * - class 包含 FrameLayout / RelativeLayout / ViewGroup
     * - 在 y > 280 区域（跳过 tabs）
     */
    private fun findFirstSearchResultVideo(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectVideoResultCandidates(root, candidates)
        if (candidates.isEmpty()) return null

        // 排序: 先按 y, 再按 x。选第一行第一个（左上）
        return candidates.minWithOrNull(
            compareBy(
                { node ->
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    // 把 y 按 200px 分桶，相同桶里再按 x 排
                    r.top / 200
                },
                { node ->
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    r.left
                }
            )
        )
    }

    private fun collectVideoResultCandidates(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        if (node.isClickable) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val w = r.width()
            val h = r.height()
            val cls = node.className?.toString() ?: ""
            // 视频卡片严格特征：
            // - 宽约屏宽一半 (40%~60%)
            // - 高 600 ~ 1300（竖向视频缩略图）
            // - 不在 tab bar 区域（y > 280）
            // - 不顶到屏底（y < 屏高 95%）
            // - class 是布局容器（不是单纯 Button/TextView）
            if (w in (screenWidth * 40 / 100)..(screenWidth * 60 / 100)
                && h in 600..1300
                && r.top in 280..(screenHeight * 95 / 100)
                && (cls.contains("FrameLayout") || cls.contains("ViewGroup")
                    || cls.contains("RelativeLayout"))
            ) {
                out.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            collectVideoResultCandidates(node.getChild(i), out)
        }
    }

    private fun findEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isEditable) return root
        // class 检查兜底
        val cls = root.className?.toString() ?: ""
        if (cls == "android.widget.EditText") return root
        for (i in 0 until root.childCount) {
            val r = findEditableNode(root.getChild(i))
            if (r != null) return r
        }
        return null
    }

    /**
     * 打开评论面板。
     * 评论按钮 content-desc (实测):
     * - 英文版: "Read or add comments. N comments"
     * - 中文版: "阅读或添加评论。N 条评论"
     * bounds 大约在屏宽 92%, 高 58%-66% 区间
     */
    private suspend fun openCommentPanel(): Boolean {
        val root = rootInActiveWindow ?: return false
        val commentBtn = AccessibilityUtils.findNodeByDescription(root, "阅读或添加评论")
            ?: AccessibilityUtils.findNodeByDescription(root, "Read or add comment")
            ?: AccessibilityUtils.findNodeByDescription(root, "查看或添加评论")
            ?: AccessibilityUtils.findNodeByDescription(root, "comments")
            ?: AccessibilityUtils.findNodeByDescription(root, "Comment")
            ?: AccessibilityUtils.findNodeByDescription(root, "条评论")
            ?: AccessibilityUtils.findNodeByDescription(root, "评论")
            ?: AccessibilityUtils.findNodeByViewId(root, "comment_btn")
            ?: AccessibilityUtils.findNodeByViewId(root, "iv_comment")
        if (commentBtn == null) {
            addLog("⚠️ 找不到评论按钮节点, 按相对坐标 tap")
            // 兜底：评论按钮在右侧, 屏幕宽 92%, 高 62% 附近 (中英文版差异折中)
            AccessibilityUtils.tapAt(this, screenWidth * 0.92f, screenHeight * 0.62f)
            delay(2500)
            return true
        }
        addLog("💬 tap 评论按钮")
        tapNodeCenter(commentBtn)
        delay(2500)
        return true
    }

    private fun swipeToNextVideo() {
        AccessibilityUtils.swipeUp(this, screenHeight, screenWidth)
    }

    /**
     * 在评论面板里扫描多条评论：
     * - 收集评论 item 节点
     * - 取该评论文本，检查是否命中关键词
     * - 命中则按需要执行 like/reply/follow/dm
     * - 处理完 commentMaxPerVideo 条命中评论或滑到底部就返回
     * 返回本视频处理的命中评论数量
     */
    private suspend fun scanCommentsAndAct(
        follow: Boolean, dm: Boolean, like: Boolean, reply: Boolean
    ): Int {
        val keywords = config.commentMatchKeywords
        val maxPerVideo = config.commentMaxPerVideo.coerceAtLeast(1)
        var matched = 0
        var scrollAttempts = 0
        val maxScrollAttempts = 30  // 评论可能很多, 翻 30 次
        var lastFirstCommentSig = ""  // 上次第一条评论的签名
        var stagnantCount = 0  // 连续多少次滚动后内容没变

        while (matched < maxPerVideo && scrollAttempts < maxScrollAttempts
            && currentCoroutineContext().isActive) {
            checkPaused()
            val root = rootInActiveWindow ?: return matched

            val commentItems = collectCommentItems(root)
            if (commentItems.isEmpty()) {
                addLog("ℹ️ 暂未找到评论 item，向下滑评论列表")
                scrollCommentList()
                scrollAttempts++
                continue
            }

            var actedAny = false
            for (item in commentItems) {
                if (matched >= maxPerVideo) break

                // 提取评论文本（不含用户名）
                val text = extractCommentText(item)
                val signature = commentSignature(item, text)
                if (signature.isBlank() || processedCommentSignatures.contains(signature)) continue

                val hit = keywordHit(text, keywords)
                if (!hit) {
                    processedCommentSignatures.add(signature)
                    continue
                }

                addLog("🎯 命中评论: ${text.take(40)}")
                processedCommentSignatures.add(signature)
                matched++
                stats.keywordMatches++

                // 执行 like
                if (like) {
                    val likeNode = findCommentLikeBtn(item)
                    if (likeNode != null) {
                        AccessibilityUtils.clickNode(likeNode)
                        stats.likesGiven++
                        addLog("❤️ 点赞评论 [总计: ${stats.likesGiven}]")
                        delay(400)
                    }
                }

                // 执行 reply
                if (reply && config.replyTemplates.isNotEmpty()) {
                    doReplyToComment(item)
                }

                // 执行 follow / dm (点头像进个人主页)
                if (follow || dm) {
                    val avatar = findCommentAvatar(item)
                    if (avatar != null) {
                        val aRect = android.graphics.Rect()
                        avatar.getBoundsInScreen(aRect)
                        addLog("👆 tap 评论头像 [${aRect.centerX()},${aRect.centerY()}]")
                        tapNodeCenter(avatar)
                        delay(2500)
                        val profileRoot = rootInActiveWindow
                        if (profileRoot != null) {
                            // 先 dm (profile 底部直接输入), 再 follow
                            // 用户视频展示: 进 profile → 底部直接发"hi" → 已发送消息请求
                            if (dm) {
                                val sent = sendSuperDm(profileRoot)
                                if (sent > 0) {
                                    stats.dmsSent += sent
                                    addLog("✉️ 私信×$sent [总计: ${stats.dmsSent}]")
                                }
                            }
                            // 如果 sendSuperDm 没 BACK (profile 底部直接发模式), 这里再处理 follow
                            val afterDmRoot = rootInActiveWindow ?: profileRoot
                            if (follow && tryFollowUser(afterDmRoot)) {
                                stats.usersFollowed++
                                addLog("👤 关注 [总计: ${stats.usersFollowed}]")
                            }
                        }
                        // 返回评论区 (可能要 BACK 2 次: 聊天界面 → profile → 评论)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(800)
                        // 检测当前是不是在评论 panel, 不在就再 BACK
                        val r = rootInActiveWindow
                        if (r != null) {
                            val stillProfile = AccessibilityUtils.findNodeByText(r, "关注", false) != null
                                || AccessibilityUtils.findNodeByText(r, "粉丝", false) != null
                            if (stillProfile) {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                delay(800)
                            }
                        }
                    } else {
                        addLog("⚠️ 找不到该评论的头像")
                    }
                }

                actedAny = true
                broadcastStats(true)

                // 评论间小间隔（拟人）
                delay(800 + Random.nextLong(400))
            }

            if (matched >= maxPerVideo) break

            // 在滚动前记录这一屏的"内容指纹"(第一条评论文本) 用于检测是否到底
            val firstSig = commentItems.firstOrNull()?.let { extractCommentText(it) } ?: ""

            // 这一屏处理完了，向下滚动评论看更多
            scrollCommentList()
            scrollAttempts++
            if (!actedAny) delay(600) else delay(300)

            // 滚动后再 dump 看第一条评论变没变;
            // 如果连续 2 次都没变, 认为到底, 退出
            val rootAfter = rootInActiveWindow
            if (rootAfter != null) {
                val itemsAfter = collectCommentItems(rootAfter)
                val firstSigAfter = itemsAfter.firstOrNull()?.let { extractCommentText(it) } ?: ""
                if (firstSigAfter.isNotEmpty() && firstSigAfter == firstSig) {
                    stagnantCount++
                    if (stagnantCount >= 2) {
                        addLog("ℹ️ 评论已到底, 共处理命中 $matched 条")
                        break
                    }
                } else {
                    stagnantCount = 0
                }
                lastFirstCommentSig = firstSigAfter
            }
        }
        return matched
    }

    /**
     * 收集评论 list 里的每条评论 item 节点。
     * TikTok 的 view-id 被 R8 混淆，靠 id 不可靠。
     * 策略:
     * 1. 找页面里最大的 scrollable 节点（评论列表 RecyclerView）
     * 2. 取它的所有子节点作为 comment items (放宽过滤, 评论高度 > 30 即可)
     * 3. 实测发现部分评论 panel 子节点很多（含头像、用户名、评论文本、reply
     *    等独立子项），所以再用 viewId="title"（用户名节点） 反查"评论容器"作兜底
     */
    private fun collectCommentItems(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        // 策略1：找 scrollable 评论列表
        val list = findCommentList(root)
        if (list != null && list.childCount > 0) {
            val items = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0 until list.childCount) {
                val child = list.getChild(i) ?: continue
                val r = android.graphics.Rect()
                child.getBoundsInScreen(r)
                // 过滤掉空的或太小的项
                if (r.height() > 30) {
                    items.add(child)
                }
            }
            if (items.isNotEmpty()) {
                addLog("ℹ️ 评论列表找到 ${items.size} 项")
                return items
            }
        }

        // 策略2: 通过 rid 包含 "title" 的用户名节点反查评论容器
        // (实测评论用户名都用 rid=...:id/title)
        val titleNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodesByViewIdEnd(root, "title", titleNodes)
        if (titleNodes.isNotEmpty()) {
            val items = titleNodes.mapNotNull { tn ->
                // 取祖先节点作为该评论的容器（往上找 2-3 层）
                var p: AccessibilityNodeInfo? = tn.parent
                var depth = 0
                while (p != null && depth < 3) {
                    val r = android.graphics.Rect()
                    p.getBoundsInScreen(r)
                    if (r.height() > 80 && r.width() > screenWidth * 0.4) return@mapNotNull p
                    p = p.parent
                    depth++
                }
                tn
            }
            if (items.isNotEmpty()) {
                addLog("ℹ️ 用户名节点反查找到 ${items.size} 评论")
                return items
            }
        }

        // 策略3: viewId 启发式（旧兜底）
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectCommentItemsRecursive(root, result)
        return result
    }

    private fun collectNodesByViewIdEnd(
        node: AccessibilityNodeInfo?,
        suffix: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith("/$suffix")) out.add(node)
        for (i in 0 until node.childCount) {
            collectNodesByViewIdEnd(node.getChild(i), suffix, out)
        }
    }

    /**
     * 找评论列表节点：
     * 实测（中文版 TikTok）评论 panel 有 3 个 scrollable:
     *   1. X.05Wd [0,0,1080,2271]  — 整屏顶层容器(不要)
     *   2. ViewPager [0,0,1080,2136] — 视频+评论分页容器(不要)
     *   3. androidx.recyclerview.widget.RecyclerView [0,986,1080,2099] ✓ — 真评论列表
     * 优先级:
     * - class 含 "RecyclerView" / "ListView" / "Recycler"
     * - top > 屏幕高 25%(评论 panel 在下半部)
     * - 高度 > 屏幕高 25%
     */
    private fun findCommentList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectScrollableNodes(root, all)
        if (all.isEmpty()) return null

        // 优先级 1: 是 RecyclerView/ListView 类 + 位置在下半屏
        val recyclerInPanel = all.firstOrNull { node ->
            val cls = node.className?.toString() ?: ""
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            (cls.contains("RecyclerView") || cls.contains("ListView"))
                && r.top > screenHeight * 0.25
                && r.height() > screenHeight * 0.25
        }
        if (recyclerInPanel != null) return recyclerInPanel

        // 优先级 2: 任何 RecyclerView/ListView (不限位置)
        val anyRecycler = all.firstOrNull { node ->
            val cls = node.className?.toString() ?: ""
            cls.contains("RecyclerView") || cls.contains("ListView")
        }
        if (anyRecycler != null) return anyRecycler

        // 优先级 3: 在下半屏 + 大于屏高 25% 的最大 scrollable
        val inPanel = all.filter { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.top > screenHeight * 0.25 && r.height() > screenHeight * 0.25
        }.maxByOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.height()
        }
        if (inPanel != null) return inPanel

        // 兜底: 最大的 scrollable
        return all.maxByOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.height()
        }
    }

    private fun collectScrollableNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        if (node.isScrollable) out.add(node)
        for (i in 0 until node.childCount) {
            collectScrollableNodes(node.getChild(i), out)
        }
    }

    private fun collectCommentItemsRecursive(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith("comment_item")
            || id.endsWith("item_comment")
            || id.endsWith("comment_user")
            || id.endsWith("ll_comment_item")
        ) {
            out.add(node)
            return
        }
        for (i in 0 until node.childCount) {
            collectCommentItemsRecursive(node.getChild(i), out)
        }
    }

    /** 提取评论的纯文本 */
    private fun extractCommentText(item: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectTextsForComment(item, sb)
        return sb.toString().trim()
    }

    private fun collectTextsForComment(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        val id = node.viewIdResourceName ?: ""
        // 跳过用户名节点和时间/reply 等无关文本
        // 实测 TikTok 用户名 rid=...:id/title, 时间 rid=...:id/e7t, reply rid=...:id/e6l
        if (id.endsWith("user_name") || id.endsWith("tv_username")
            || id.endsWith("/title") || id.endsWith("/e7t") || id.endsWith("/e6l")
        ) {
            // 不递归到子节点，避免把这些区域的子文本算进去
            return
        }
        val txt = node.text?.toString()
        if (!txt.isNullOrBlank()) sb.append(txt).append(' ')
        for (i in 0 until node.childCount) {
            collectTextsForComment(node.getChild(i), sb)
        }
    }

    private fun commentSignature(item: AccessibilityNodeInfo, text: String): String {
        val rect = android.graphics.Rect()
        item.getBoundsInScreen(rect)
        // 用文本前40字符 + bounds 做 sig，避免同一条评论被重复处理
        return "${text.take(40)}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    private fun keywordHit(text: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true // 没设置关键词 = 全部命中
        val lower = text.lowercase()
        return if (config.commentRequireAll) {
            keywords.all { lower.contains(it.lowercase()) }
        } else {
            keywords.any { lower.contains(it.lowercase()) }
        }
    }

    /**
     * 滚动评论列表。先尝试通过 RecyclerView 的 ACTION_SCROLL_FORWARD（更稳定），
     * 失败则用手势。最后必须 delay 等 UI 刷新。
     */
    private suspend fun scrollCommentList() {
        val root = rootInActiveWindow
        val list = if (root != null) findCommentList(root) else null

        var actionOk = false
        if (list != null) {
            try {
                actionOk = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } catch (e: Exception) {
                actionOk = false
            }
        }

        if (!actionOk) {
            // 手势滚动：评论 panel 通常占屏幕下 65%
            // 从下往上扫（实际滑动 distance 要大一点）
            val startY = screenHeight * 0.85f
            val endY = screenHeight * 0.45f
            val x = screenWidth * 0.5f
            val path = android.graphics.Path().apply {
                moveTo(x, startY)
                lineTo(x, endY)
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 400))
                .build()
            dispatchGesture(gesture, null, null)
        }
        // 等手势 + 内容加载完成
        delay(1200)
    }

    private suspend fun doReplyToComment(item: AccessibilityNodeInfo) {
        val replyBtn = findCommentReplyBtn(item) ?: return
        AccessibilityUtils.clickNode(replyBtn)
        delay(800)
        val replyRoot = rootInActiveWindow
        val inputField = findCommentInput(replyRoot) ?: return
        AccessibilityUtils.clickNode(inputField)
        delay(400)
        AccessibilityUtils.typeText(inputField, config.replyTemplates.random())
        delay(500)
        val postBtn = AccessibilityUtils.findNodeByText(rootInActiveWindow, "Post", false)
            ?: AccessibilityUtils.findNodeByDescription(rootInActiveWindow, "Post")
        postBtn?.let {
            AccessibilityUtils.clickNode(it)
            stats.repliesSent++
            addLog("💬 回复评论 [总计: ${stats.repliesSent}]")
        }
        delay(800)
    }

    // ==================== 超级话术核心逻辑 ====================

    /**
     * 超级话术：向当前用户主页发送随机条数的私信，每条随机选取
     * @return 实际发送条数
     */
    /**
     * 给某用户发私信. 支持两种 TikTok profile 模式:
     *
     * 模式 A (陌生人 / 未关注用户, 实测中文版优先这个):
     *   - 用户主页底部直接有 "消息..." 输入框 + "向 xxx 发送消息请求" 提示
     *   - 直接 tap 输入框 → 输入文本 → 点发送 (飞机图标 或 "发送" 按钮)
     *   - 发送后显示 "已发送消息请求"
     *
     * 模式 B (互关 / 旧版 / 部分账号):
     *   - profile 上有 "消息" / "Message" 按钮 (在头像下方)
     *   - tap "消息" 按钮 → 进入聊天界面 → 输入框在底部 → 点发送
     */
    private suspend fun sendSuperDm(profileRoot: AccessibilityNodeInfo): Int {
        if (config.dmTemplates.isEmpty()) return 0

        // 决定本次发几条 (超级话术: 随机条数)
        val sendCount = if (config.superDmEnabled) {
            Random.nextInt(config.superDmMinCount, config.superDmMaxCount + 1)
        } else {
            1
        }

        // === 模式 A: profile 底部已经有消息输入框 (陌生人 DM 请求) ===
        val directInput = findProfileBottomInput(profileRoot)
        if (directInput != null) {
            addLog("✉️ profile 底部直接发送消息请求模式")
            val sent = doSendDmHere(directInput, sendCount)
            // 不 BACK, 留在 profile, 调用方会处理后续
            return sent
        }

        // === 模式 B: 点 "消息" 按钮进入聊天界面 ===
        val msgBtn = AccessibilityUtils.findNodeByText(profileRoot, "消息", false)
            ?: AccessibilityUtils.findNodeByText(profileRoot, "私信", false)
            ?: AccessibilityUtils.findNodeByText(profileRoot, "Message", false)
            ?: AccessibilityUtils.findNodeByDescription(profileRoot, "Message")
            ?: AccessibilityUtils.findNodeByDescription(profileRoot, "消息")
            ?: AccessibilityUtils.findNodeByViewId(profileRoot, "message_btn")
            ?: AccessibilityUtils.findNodeByViewId(profileRoot, "btn_message")

        if (msgBtn == null) {
            addLog("⚠️ profile 上找不到消息按钮 / 底部输入框")
            return 0
        }

        addLog("✉️ tap 消息按钮进入聊天")
        tapNodeCenter(msgBtn)
        delay(2200)

        val chatRoot = rootInActiveWindow ?: return 0
        val chatInput = findDmInputField(chatRoot)
        if (chatInput == null) {
            addLog("⚠️ 聊天界面找不到输入框")
            return 0
        }
        val sent = doSendDmHere(chatInput, sendCount)
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(800)
        return sent
    }

    /**
     * 在指定输入框节点上发送 N 条话术.
     * 输入框附近(通常右侧)有发送按钮(飞机图标 / "发送" 文字).
     */
    private suspend fun doSendDmHere(inputField: AccessibilityNodeInfo, sendCount: Int): Int {
        var sentCount = 0
        repeat(sendCount) { i ->
            val freshRoot = rootInActiveWindow ?: return sentCount
            // 每次重新找 inputField (avoid stale node)
            val freshInput = findDmInputField(freshRoot) ?: inputField
            tapNodeCenter(freshInput)
            delay(500 + Random.nextLong(200))

            val dmText = config.dmTemplates.random()
            AccessibilityUtils.typeText(freshInput, dmText)
            delay(700 + Random.nextLong(300))

            val sendBtn = findSendButton(rootInActiveWindow)
            if (sendBtn != null) {
                addLog("📤 tap 发送 (第${i + 1}/$sendCount 条)")
                tapNodeCenter(sendBtn)
                sentCount++
                delay(1000 + Random.nextLong(500))
            } else {
                addLog("⚠️ 找不到发送按钮 (第${i + 1}条)")
                return sentCount
            }
        }
        return sentCount
    }

    /**
     * 在用户 profile 页底部找直接发消息的输入框.
     * 特征: EditText / 包含 contentDescription="消息" / hint="消息..." / 位于屏幕下方 (y > 70%)
     */
    private fun findProfileBottomInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 优先: 屏幕下方的 EditText
        val editFields = mutableListOf<AccessibilityNodeInfo>()
        collectEditTextNodes(root, editFields)
        val bottomEdit = editFields.firstOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.top > screenHeight * 0.65f
        }
        if (bottomEdit != null) return bottomEdit
        // 兜底: 通过 desc/hint "消息"
        return AccessibilityUtils.findNodeByDescription(root, "消息", true)
            ?: AccessibilityUtils.findNodeByDescription(root, "Message", true)
    }

    private fun collectEditTextNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        val cls = node.className?.toString() ?: ""
        if (node.isEditable || cls == "android.widget.EditText") {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectEditTextNodes(node.getChild(i), out)
        }
    }

    // ==================== 基础操作 ====================

    private suspend fun tryLike(root: AccessibilityNodeInfo): Boolean {
        val likeBtn = AccessibilityUtils.findNodeByDescription(root, "Like")
            ?: AccessibilityUtils.findNodeByViewId(root, "iv_like")
            ?: AccessibilityUtils.findNodeByViewId(root, "like_btn")

        if (likeBtn != null) {
            val desc = likeBtn.contentDescription?.toString() ?: ""
            if (!desc.contains("Liked", ignoreCase = true)) {
                AccessibilityUtils.clickNode(likeBtn)
                delay(300)
                return true
            }
        }
        return false
    }

    private suspend fun tryFavorite(root: AccessibilityNodeInfo): Boolean {
        val favBtn = AccessibilityUtils.findNodeByDescription(root, "Add to Favorites")
            ?: AccessibilityUtils.findNodeByDescription(root, "Favorite")
            ?: AccessibilityUtils.findNodeByViewId(root, "iv_favorite")
        if (favBtn != null) {
            AccessibilityUtils.clickNode(favBtn)
            delay(300)
            return true
        }
        return false
    }

    private suspend fun tryComment(root: AccessibilityNodeInfo, templates: List<String>): Boolean {
        if (templates.isEmpty()) return false

        val commentBtn = AccessibilityUtils.findNodeByDescription(root, "Comment")
            ?: AccessibilityUtils.findNodeByViewId(root, "comment_btn")
        commentBtn ?: return false

        AccessibilityUtils.clickNode(commentBtn)
        delay(1500)

        val commentRoot = rootInActiveWindow ?: return false
        val inputField = findCommentInput(commentRoot) ?: return false

        AccessibilityUtils.clickNode(inputField)
        delay(400)
        AccessibilityUtils.typeText(inputField, templates.random())
        delay(500)

        val postBtn = AccessibilityUtils.findNodeByText(rootInActiveWindow, "Post", false)
            ?: AccessibilityUtils.findNodeByDescription(rootInActiveWindow, "Post")

        if (postBtn != null) {
            AccessibilityUtils.clickNode(postBtn)
            delay(600)
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(400)
            return true
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        return false
    }

    private suspend fun tryFollowUser(profileRoot: AccessibilityNodeInfo): Boolean {
        // 中英文兼容: 中文版用户主页关注按钮 text="关注", 已关注变 "已关注"
        val followBtn = AccessibilityUtils.findNodeByText(profileRoot, "关注", false)
            ?: AccessibilityUtils.findNodeByText(profileRoot, "Follow", false)
            ?: AccessibilityUtils.findNodeByDescription(profileRoot, "Follow")
            ?: AccessibilityUtils.findNodeByDescription(profileRoot, "关注")

        if (followBtn != null) {
            val text = followBtn.text?.toString() ?: ""
            val desc = followBtn.contentDescription?.toString() ?: ""
            // 已关注: Following / 已关注 / 互相关注
            if (text == "Follow" || text == "关注"
                || (!text.contains("Following", true) && !text.contains("已关注") && !text.contains("互相"))
            ) {
                addLog("👤 tap 关注按钮")
                tapNodeCenter(followBtn)
                delay(800)
                return true
            }
        }
        return false
    }

    // ==================== UI 元素查找 ====================

    private fun findNextFanUserItem(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByViewId(root, "user_list_item")
            ?: AccessibilityUtils.findNodeByViewId(root, "item_user")
            ?: AccessibilityUtils.findNodeByViewId(root, "follow_item")
            ?: AccessibilityUtils.findNodeByViewId(root, "iv_avatar")
    }

    private fun findFirstCommentUser(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByViewId(root, "comment_item")
            ?: AccessibilityUtils.findNodeByViewId(root, "comment_user")
    }

    /**
     * 在评论 item 节点里找用户头像.
     * 中文版 TikTok 头像 viewId 不一定是 iv_avatar, content-desc 也常是空, 所以加启发式:
     * 找评论 item 内最左侧 (x < 节点宽度 25%) 的可点击 ImageView, 尺寸接近 80-200px (圆头像).
     */
    private fun findCommentAvatar(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 优先 viewId / description
        val byId = AccessibilityUtils.findNodeByViewId(node, "iv_avatar")
            ?: AccessibilityUtils.findNodeByViewId(node, "avatar")
            ?: AccessibilityUtils.findNodeByDescription(node, "Avatar")
            ?: AccessibilityUtils.findNodeByDescription(node, "Profile")
            ?: AccessibilityUtils.findNodeByDescription(node, "头像")
        if (byId != null) return byId
        // 启发式: 评论 item 最左边的可点击 ImageView
        val itemRect = android.graphics.Rect()
        node.getBoundsInScreen(itemRect)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickableImagesIn(node, candidates)
        return candidates
            .filter { c ->
                val r = android.graphics.Rect()
                c.getBoundsInScreen(r)
                val w = r.width()
                val h = r.height()
                // 在 item 最左 25% 区域 + 尺寸像头像 + 宽高接近
                r.left < itemRect.left + itemRect.width() * 0.25
                    && w in 60..250 && h in 60..250
                    && kotlin.math.abs(w - h) < 20
            }
            .minByOrNull { c ->
                val r = android.graphics.Rect()
                c.getBoundsInScreen(r)
                r.left
            }
    }

    private fun collectClickableImagesIn(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        if (node.isClickable) {
            val cls = node.className?.toString() ?: ""
            if (cls.contains("ImageView") || cls.contains("Image")) {
                out.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            collectClickableImagesIn(node.getChild(i), out)
        }
    }

    private fun findCommentLikeBtn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByViewId(node, "comment_like")
            ?: AccessibilityUtils.findNodeByDescription(node, "Like comment")
    }

    private fun findCommentReplyBtn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByText(node, "Reply")
            ?: AccessibilityUtils.findNodeByViewId(node, "comment_reply")
    }

    private fun findCommentInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByViewId(root, "comment_input")
            ?: AccessibilityUtils.findNodeByViewId(root, "et_comment")
            ?: AccessibilityUtils.findNodeByDescription(root, "Add comment")
            ?: AccessibilityUtils.findNodeByDescription(root, "Add a comment")
    }

    /**
     * 找私信/聊天界面的文本输入框. 兼容:
     * - 聊天界面: EditText 在底部
     * - profile 底部消息请求模式: EditText hint="消息..." 在屏幕下方
     */
    private fun findDmInputField(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        // 先按 viewId / description
        val byId = AccessibilityUtils.findNodeByViewId(root, "et_input")
            ?: AccessibilityUtils.findNodeByViewId(root, "chat_input")
            ?: AccessibilityUtils.findNodeByViewId(root, "et_chat")
            ?: AccessibilityUtils.findNodeByViewId(root, "input")
            ?: AccessibilityUtils.findNodeByDescription(root, "Message", true)
            ?: AccessibilityUtils.findNodeByDescription(root, "消息", true)
        if (byId != null) return byId
        // 启发式: 屏幕下方 (y > 65%) 的 EditText
        val edits = mutableListOf<AccessibilityNodeInfo>()
        collectEditTextNodes(root, edits)
        return edits.firstOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.top > screenHeight * 0.65f
        } ?: edits.firstOrNull()
    }

    /**
     * 找发送按钮. 中文 TikTok 私信发送按钮:
     * - 通常在输入框右边 (飞机图标)
     * - 文本 "发送" / "Send"
     * - contentDescription = "Send" / "发送"
     * 启发式兜底: 找输入框, 然后在它右边/同一行附近找可点击的图标
     */
    private fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val byText = AccessibilityUtils.findNodeByDescription(root, "Send", false)
            ?: AccessibilityUtils.findNodeByDescription(root, "发送", false)
            ?: AccessibilityUtils.findNodeByText(root, "Send", false)
            ?: AccessibilityUtils.findNodeByText(root, "发送", false)
            ?: AccessibilityUtils.findNodeByViewId(root, "btn_send")
            ?: AccessibilityUtils.findNodeByViewId(root, "send_btn")
            ?: AccessibilityUtils.findNodeByViewId(root, "iv_send")
        if (byText != null) return byText
        // 启发式: 在 EditText 右边找可点击的小图标 (40-160px 的方形)
        val edits = mutableListOf<AccessibilityNodeInfo>()
        collectEditTextNodes(root, edits)
        val edit = edits.firstOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.top > screenHeight * 0.55f
        } ?: return null
        val editRect = android.graphics.Rect()
        edit.getBoundsInScreen(editRect)
        // 找在 edit 右边 ±20px 上下范围内的可点击图标
        val rightIcons = mutableListOf<AccessibilityNodeInfo>()
        collectClickableIconsNear(root, rightIcons, editRect)
        return rightIcons.firstOrNull()
    }

    private fun collectClickableIconsNear(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        nearRect: android.graphics.Rect
    ) {
        node ?: return
        if (node.isClickable) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val w = r.width()
            val h = r.height()
            // 在 EditText 右边 + 上下大致对齐 + 尺寸像图标
            if (r.left >= nearRect.right - 50
                && r.left < nearRect.right + 400
                && r.centerY() in (nearRect.centerY() - 100)..(nearRect.centerY() + 100)
                && w in 40..200
                && h in 40..200
            ) {
                out.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            collectClickableIconsNear(node.getChild(i), out, nearRect)
        }
    }

    // ==================== 工具方法 ====================

    private fun shouldDo(ratePct: Int) = Random.nextInt(100) < ratePct

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "[$time] $msg"
        Log.i(TAG, line)
        if (logLines.size >= 50) logLines.removeFirst()
        logLines.addLast(line)
        broadcastLog(line)
    }

    private fun broadcastStats(isRunning: Boolean) {
        stats.lastUpdateTime = System.currentTimeMillis()
        val intent = Intent(ACTION_UPDATE_STATS).apply {
            setPackage(packageName)
            putExtra("mode_name", stats.currentMode.displayName)
            putExtra("videos_watched", stats.videosWatched)
            putExtra("likes_given", stats.likesGiven)
            putExtra("comments_posted", stats.commentsPosted)
            putExtra("favorites_added", stats.favoritesAdded)
            putExtra("users_followed", stats.usersFollowed)
            putExtra("dms_sent", stats.dmsSent)
            putExtra("replies_sent", stats.repliesSent)
            putExtra("keyword_matches", stats.keywordMatches)
            putExtra("total_tasks_done", stats.totalTasksDone)
            putExtra("cycle_count", stats.cycleCount)
            putExtra("is_running", isRunning)
            putExtra("is_paused", isPaused)
        }
        sendBroadcast(intent)
    }

    private fun broadcastTaskState() {
        broadcastStats(isTaskRunning())
    }

    private fun broadcastLog(line: String) {
        val intent = Intent(ACTION_LOG).apply {
            setPackage(packageName)
            putExtra("log_line", line)
        }
        sendBroadcast(intent)
    }

    private fun broadcastServiceStatus(connected: Boolean) {
        val intent = Intent("com.tiktokassist.SERVICE_STATUS").apply {
            setPackage(packageName)
            putExtra("connected", connected)
        }
        sendBroadcast(intent)
    }
}
