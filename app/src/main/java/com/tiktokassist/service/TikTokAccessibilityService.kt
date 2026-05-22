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
    // 连续找不到评论按钮的次数 (用于触发强制重新搜索)
    private var consecutiveCommentPanelFails = 0

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
        // 必须在 TikTok 才操作. 用 rootInActiveWindow.packageName 严格检查,
        // 不依赖 onAccessibilityEvent 更新的 currentPackage(可能滞后).
        if (!ensureInTikTok()) {
            addLog("⏳ 等待回到 TikTok...")
            delay(2000)
            return false
        }

        val isSearchMode = config.targetSourceType == TargetSourceType.SEARCH_KEYWORD
            || config.targetSourceType == TargetSourceType.USERNAME

        // 第一次进入时根据来源类型导航
        if (!navigatedToTarget) {
            if (!navigateToTarget()) {
                addLog("⚠️ 导航到目标失败，将处理当前视频")
            }
            navigatedToTarget = true
            delay(2000)
        }

        // 1. 打开评论区 (openCommentPanel 内部 retry 8s 等待视频页加载)
        if (!openCommentPanel()) {
            addLog("⚠️ 找不到评论按钮，跳到下一个视频")
            // 失败 = 当前可能在搜索结果列表/主 Feed/作者主页, 不应继续, 跳下一个
            // 如果连续多次失败, 强制重新搜索
            consecutiveCommentPanelFails++
            if (isSearchMode && consecutiveCommentPanelFails >= 2) {
                addLog("⚠️ 连续 ${consecutiveCommentPanelFails} 次找不到评论, 强制重新搜索")
                navigatedToTarget = false
                consecutiveCommentPanelFails = 0
            }
            goToNextVideoInTask()
            return true
        }
        consecutiveCommentPanelFails = 0

        // 2. 扫描评论 + 关键词匹配
        val processedThisVideo = scanCommentsAndAct(follow, dm, like, reply)
        addLog("📊 本视频处理评论 $processedThisVideo 条")

        // 3. 关闭评论区 (只关 panel, 不要把视频也关了)
        // 先检查当前还在不在 TikTok, 在 → BACK 关 panel; 不在 → 跳过 BACK 等下一轮 ensureInTikTok 处理
        if (isInTiktok()) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1000)
        }

        // 4. 跳到下一个视频
        goToNextVideoInTask()
        return true
    }

    /** 当前 rootInActiveWindow 的 packageName 是否是 TikTok */
    private fun isInTiktok(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        return pkg in TIKTOK_PACKAGES
    }

    /**
     * 确保 TikTok 在前台. 如果不是, 主动拉起 TikTok.
     * 返回 true 表示当前已在 TikTok, 否则 false (拉起失败).
     *
     * 注意: launcher intent 会进 TikTok 主 Feed, 不是搜索结果页.
     * 调用方如果是搜索/用户名相关任务, 必须重置 navigatedToTarget=false 重新搜索.
     */
    private suspend fun ensureInTikTok(): Boolean {
        if (isInTiktok()) return true
        val pkg = rootInActiveWindow?.packageName?.toString() ?: "(null)"
        addLog("⚠️ 当前不在 TikTok (pkg=$pkg), 拉起 TikTok")
        for (p in TIKTOK_PACKAGES) {
            val intent = packageManager.getLaunchIntentForPackage(p)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(intent)
                    delay(2500)
                    if (isInTiktok()) {
                        addLog("✅ 已拉回 TikTok (落到主 Feed, 搜索任务会重新搜索)")
                        // 关键: 拉回 TikTok 后落到主 Feed, 搜索状态丢失. 强制重置.
                        navigatedToTarget = false
                        return true
                    }
                } catch (e: Exception) {
                    addLog("⚠️ 启动 $p 失败: ${e.message}")
                }
            }
        }
        addLog("❌ 没装 TikTok, 或拉起失败")
        return false
    }

    /**
     * 发完私信回评论 panel. 模式 A (profile 底部直发) 当前在 profile/聊天请求页;
     * 模式 B (走消息按钮进聊天) 内部已 BACK 1 次回到 profile.
     * 循环 BACK 直到 (a) 当前页面看起来是评论 panel, 或 (b) 退出了 TikTok 就立刻拉回,
     * 或 (c) BACK 满 4 次还没回到评论 panel 就退出.
     */
    private suspend fun backToCommentPanel() {
        for (back in 1..4) {
            // 退出 TikTok 立刻拉回
            if (!isInTiktok()) {
                addLog("⚠️ BACK 退出了 TikTok, 拉回")
                ensureInTikTok()
                return
            }
            val r = rootInActiveWindow ?: return
            // 评论 panel 标志: "条评论"/"添加评论" 文本
            val isCommentPanel = AccessibilityUtils.findNodeByText(r, "添加评论", true) != null
                || AccessibilityUtils.findNodeByText(r, "条评论", true) != null
                || AccessibilityUtils.findNodeByText(r, "Add comment", true) != null
                || AccessibilityUtils.findNodeByText(r, "comments", true) != null
            if (isCommentPanel) {
                addLog("✅ 回到评论 panel (BACK $back 次)")
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(700)
        }
        addLog("⚠️ BACK 4 次仍未回到评论 panel, 继续后续逻辑")
    }

    /**
     * 根据 targetSourceType 决定如何切换到下一个视频:
     *
     * SEARCH_KEYWORD / USERNAME 模式 (用户期望的流程):
     *   - 评论扫完, 关评论 panel, 然后 BACK 回搜索结果列表
     *   - 在列表里按 (y, x) 顺序找下一个未处理的视频卡片, tap 进入
     *   - 如果当前屏无新视频, 向下滚动列表
     *   - 直到处理完所有可见视频 (滚 5 屏还没新视频, 任务终止)
     *
     * CURRENT_VIDEO / VIDEO_URL 模式: 上滑切下一条 (主 Feed 行为)
     */
    private suspend fun goToNextVideoInTask() {
        val isSearchMode = config.targetSourceType == TargetSourceType.SEARCH_KEYWORD
            || config.targetSourceType == TargetSourceType.USERNAME

        if (!isSearchMode) {
            // 主 Feed: 直接上滑
            if (!ensureInTikTok()) return
            swipeUpInVideoArea()
            delay(3000)
            return
        }

        // 搜索模式: BACK 回搜索结果列表选下一个卡片
        if (!ensureInTikTok()) {
            addLog("❌ 无法回到 TikTok, 跳过本轮切视频")
            return
        }
        backToSearchResultsAndPickNext()
    }

    /** 检测签名文字是不是判官TK助手主界面/Launcher 等非 TikTok 页面 */
    private fun looksLikeOwnApp(sig: String): Boolean {
        if (sig.isBlank()) return false
        val markers = listOf(
            "判官TK助手", "判官", "无障碍服务", "服务已启用", "免责声明",
            "启动脚本", "开启悬浮", "脚本设置", "话术管理", "实时统计", "运行日志",
            "本作品仅供学习"
        )
        return markers.any { sig.contains(it) }
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

    /**
     * 当前在视频播放页 → BACK 回搜索结果列表 → 按顺序找下一个未处理的视频 tap 进入.
     * 若 BACK 跑出了 TikTok (回到了判官TK助手主界面/launcher), 自动拉回 TikTok 重试.
     */
    private suspend fun backToSearchResultsAndPickNext() {
        // BACK 最多 3 次直到看到搜索结果网格 (视频卡片), 防御 backstack 不一致
        var foundList = false
        for (backAttempt in 1..3) {
            if (!isInTiktok()) {
                addLog("⚠️ BACK 退出了 TikTok, 拉回")
                if (!ensureInTikTok()) return
                // 拉回 TikTok 后, 它可能直接到了主 Feed 而不是搜索结果. 需要重新搜索
                addLog("ℹ️ 重新触发搜索流程")
                navigatedToTarget = false
                return
            }
            addLog("⬅ BACK (#$backAttempt) 回搜索结果列表")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(2000)

            val root = rootInActiveWindow ?: continue
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectVideoResultCandidates(root, candidates)
            if (candidates.isNotEmpty()) {
                addLog("✅ 搜索结果列表加载 (${candidates.size} 个卡片)")
                foundList = true
                break
            }
        }

        if (!foundList) {
            addLog("⚠️ BACK 多次后未识别到搜索结果列表, 尝试拉回 TikTok 重新搜索")
            navigatedToTarget = false
            return
        }

        // 当前在搜索结果列表. 找下一个未处理的视频
        var root = rootInActiveWindow ?: return
        var next = findNextUnprocessedVideo(root)

        var scrollAttempts = 0
        while (next == null && scrollAttempts < 5) {
            addLog("ℹ️ 当前屏无新视频, 向下滑结果列表 (#${scrollAttempts + 1})")
            scrollSearchResults()
            delay(1500)
            if (!isInTiktok()) {
                addLog("⚠️ 滚动列表时离开 TikTok, 终止")
                return
            }
            root = rootInActiveWindow ?: return
            next = findNextUnprocessedVideo(root)
            scrollAttempts++
        }

        if (next == null) {
            addLog("🏁 搜索结果列表已全部处理完毕, 任务结束")
            // 标记任务完成 (不再继续, 由外层 runTask 处理)
            return
        }

        val sig = nodeBoundsSignature(next)
        processedSearchVideoSignatures.add(sig)
        val r = android.graphics.Rect()
        next.getBoundsInScreen(r)
        addLog("▶ tap 下一个视频卡片 [${r.left},${r.top}] (累计已处理 ${processedSearchVideoSignatures.size})")
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
        collectTextsRecursive(node, texts, maxDepth = 10, depth = 0)
        val sig = texts.joinToString("|").take(200)
        return if (sig.isBlank()) {
            // 兜底: 用 屏幕中心点的 行/列 桶位 (滚动前稳定; 滚动后会变, 但滚动后我们已重新扫一屏)
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val col = (r.exactCenterX() / (screenWidth / 2f)).toInt() // 0=左列 1=右列
            val row = (r.exactCenterY() / 300f).toInt()
            "gridpos:c${col}_r${row}"
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
        val raw = mutableListOf<AccessibilityNodeInfo>()
        collectVideoResultCandidates(root, raw)
        val candidates = dedupeVideoCandidates(raw)
        val sorted = candidates.sortedWith(sortByGridPosition())
        // 打印前 4 个候选
        for ((idx, n) in sorted.withIndex().take(4)) {
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            val sig = nodeBoundsSignature(n).take(30).replace("\n", " ")
            val done = if (processedSearchVideoSignatures.contains(nodeBoundsSignature(n))) "✓已处理" else "✗未处理"
            addLog("🔎 卡片#${idx + 1} [${r.left},${r.top}] ${done} sig=${sig}")
        }
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
        val raw = mutableListOf<AccessibilityNodeInfo>()
        collectVideoResultCandidates(root, raw)
        val candidates = dedupeVideoCandidates(raw)
        if (candidates.isEmpty()) {
            addLog("ℹ️ findFirstSearchResultVideo: 0 候选 (原始 ${raw.size})")
            return null
        }
        val sorted = candidates.sortedWith(sortByGridPosition())
        // 打印前 4 个候选, 便于诊断
        for ((idx, n) in sorted.withIndex().take(4)) {
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            addLog("🔎 候选#${idx + 1} [${r.left},${r.top},${r.right},${r.bottom}] ${r.width()}x${r.height()}")
        }
        return sorted.first()
    }

    /** 网格位置排序: 先按行 (y 桶), 再按列 (x). 行桶 = 300px (避免同一行两个卡片偏差被分桶到不同行) */
    private fun sortByGridPosition(): Comparator<AccessibilityNodeInfo> {
        return compareBy(
            { node ->
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                r.top / 300
            },
            { node ->
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                r.left
            }
        )
    }

    private fun collectVideoResultCandidates(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        // 注意: 不强制 isClickable=true!
        // TikTok 的视频卡片自身常常 isClickable=false, 真正可点的是其父或子节点
        // 我们只按 几何特征 (大小+位置) 收集, 用 tapNodeCenter (坐标 tap) 来点击, 不依赖 ACTION_CLICK
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        val w = r.width()
        val h = r.height()
        val cls = node.className?.toString() ?: ""
        // 视频卡片几何特征:
        // - 宽 ~ 屏宽一半 (38%~62%)
        // - 高 500 ~ 1400 (竖向视频缩略图)
        // - 不在 tab bar 区域 (y_top > 200, 放宽一些)
        // - 不顶到屏底 (y_bottom < 屏高 95%)
        // - class 是布局容器
        if (node.isVisibleToUser
            && w in (screenWidth * 38 / 100)..(screenWidth * 62 / 100)
            && h in 500..1400
            && r.top in 200..(screenHeight * 95 / 100)
            && r.bottom <= (screenHeight * 96 / 100)
            && (cls.contains("FrameLayout") || cls.contains("ViewGroup")
                || cls.contains("RelativeLayout") || cls.contains("LinearLayout"))
        ) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectVideoResultCandidates(node.getChild(i), out)
        }
    }

    /**
     * 同一视频卡片父子容器都符合"卡片"特征时, 会被收集多次.
     * 这里按中心点 (x_bucket, y_bucket) 去重, 优先保留 面积最小的 (更内层的, 通常是真正的卡片本体).
     * 桶大小 = 屏宽 / 4 (粗略两列网格区分)
     */
    private fun dedupeVideoCandidates(
        candidates: List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        if (candidates.size <= 1) return candidates
        val bucketX = (screenWidth / 4).coerceAtLeast(100)
        val bucketY = 250
        val grouped = mutableMapOf<Pair<Int, Int>, AccessibilityNodeInfo>()
        for (n in candidates) {
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            val key = (r.exactCenterX().toInt() / bucketX) to (r.exactCenterY().toInt() / bucketY)
            val cur = grouped[key]
            if (cur == null) {
                grouped[key] = n
            } else {
                // 选 面积更小的 (内层卡片)
                val rc = android.graphics.Rect()
                cur.getBoundsInScreen(rc)
                val areaCur = rc.width() * rc.height()
                val areaNew = r.width() * r.height()
                if (areaNew < areaCur) grouped[key] = n
            }
        }
        return grouped.values.toList()
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
        // TikTok 视频播放页加载需要 3-6s, 评论按钮节点挂载时间不定.
        // 这里 retry 找节点最多 8s (16 次 × 500ms)
        var commentBtn: AccessibilityNodeInfo? = null
        for (attempt in 1..16) {
            val root = rootInActiveWindow
            if (root != null) {
                commentBtn = findCommentButtonNode(root)
                if (commentBtn != null) break
            }
            // 第 3 次没找到, 输出一次进度日志, 不要刷屏
            if (attempt == 3) addLog("⏳ 等待视频页评论按钮挂载...")
            delay(500)
        }
        if (commentBtn == null) {
            // 8 秒后仍找不到节点. 判断当前是否在 TikTok 视频播放页 (package 正确):
            // 若在 TikTok 包内, 很可能只是节点描述不匹配 → 用坐标兜底 tap 右侧评论按钮位置
            // 若已离开 TikTok → 直接返回 false
            if (!isInTiktok()) {
                addLog("⚠️ 8s 未找到评论按钮且已离开 TikTok, 跳过")
                return false
            }
            addLog("⚠️ 8s 未找到评论按钮节点, 用坐标兜底 tap 评论区域")
            // 评论按钮实测坐标: x≈90%, y≈61% (1080x2340 设备实测: center=(970,1452))
            AccessibilityUtils.tapAt(this, screenWidth * 0.90f, screenHeight * 0.61f)
            delay(2500)
            // 检查是否打开了评论面板 (有 collectCommentItems 能识别的 scrollable)
            val root2 = rootInActiveWindow
            if (root2 != null) {
                val items = collectCommentItems(root2)
                if (items.isEmpty()) {
                    addLog("⚠️ 坐标 tap 后仍未打开评论面板, 跳过本视频")
                    return false
                }
                addLog("💬 坐标 tap 后评论面板已打开 (${items.size} 条)")
            }
            return true
        }
        addLog("💬 tap 评论按钮")
        tapNodeCenter(commentBtn)
        delay(2500)
        return true
    }

    /**
     * 找评论按钮. TikTok 视频播放页右侧操作栏:
     *   头像 (上) → 点赞 → 评论 → 收藏 → 分享 (下)
     * 评论按钮特征:
     *   - content-description = "阅读或添加评论" / "Read or add comment" / "查看或添加评论"
     *   - 或者 description 是 "N 条评论" / "Comment" / "comments"
     *   - 必须 clickable=true
     *   - 必须在屏幕 右侧 (x_center > 屏宽 70%) + 中下部 (y_center > 屏高 40%)
     * 严格的位置约束防止误点到 顶部头像/标题/作者名 等其他位置.
     */
    private fun findCommentButtonNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 优先匹配完整描述 + 必须 clickable + 必须在右侧操作栏区域
        val descCandidates = listOf(
            "阅读或添加评论", "Read or add comment", "查看或添加评论",
            "条评论", "条留言"
        )
        for (d in descCandidates) {
            val node = AccessibilityUtils.findNodeByDescription(root, d) ?: continue
            if (isValidCommentButton(node)) return node
        }
        // 兜底: 模糊匹配 "comment"/"评论", 但仍然要求 clickable + 位置正确
        val softCandidates = listOf("comments", "Comment", "评论")
        for (d in softCandidates) {
            val node = AccessibilityUtils.findNodeByDescription(root, d) ?: continue
            if (isValidCommentButton(node)) return node
        }
        return AccessibilityUtils.findNodeByViewId(root, "comment_btn")
            ?: AccessibilityUtils.findNodeByViewId(root, "iv_comment")
    }

    /**
     * 验证: 节点是右侧操作栏的评论按钮.
     * 实测: 1080×2340 屏, 评论按钮 center=(970,1452) → x=89.8%, y=62.1%
     * 放宽到: x > 55%, y 在 35%~92% 之间 (允许按钮随视频内容上下漂移)
     */
    private fun isValidCommentButton(node: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        return cx > screenWidth * 0.55f
            && cy > screenHeight * 0.35f
            && cy < screenHeight * 0.92f
    }

    /**
     * 检测当前页面是不是 TikTok 视频播放页(而不是搜索结果列表/主 Feed/作者主页等).
     * 特征: 有"评论"按钮节点 + 屏幕右侧有竖向操作栏 (赞/评/分享).
     * 注意: 视频页加载慢, 调用方应留够等待时间 (3-8s), 不要立即调.
     */
    private fun isOnVideoDetailPage(): Boolean {
        val root = rootInActiveWindow ?: return false
        return findCommentButtonNode(root) != null
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
        val maxScrollAttempts = 60  // 评论可能很多, 翻 60 屏 (~400 条) 兜底
        var lastFirstCommentSig = ""  // 上次第一条评论的签名
        var stagnantCount = 0  // 连续多少次滚动后内容没变 (>=2 视为到底)

        // 用户期望: 扫完当前视频的所有评论 (matched 达到上限只是不再触发私信, 不退出循环)
        // 主退出条件: 评论滚到底 (stagnantCount >= 2)
        // 安全网: maxScrollAttempts (60 屏评论, ≈ 360 条以上)
        while (scrollAttempts < maxScrollAttempts && currentCoroutineContext().isActive) {
            checkPaused()
            val root = rootInActiveWindow ?: return matched

            // 关键: 退出 TikTok 立即停止扫描, 不要在判官TK助手主界面/launcher 上误识别评论
            if (!isInTiktok()) {
                addLog("⚠️ 评论扫描中离开了 TikTok, 停止当前视频扫描")
                return matched
            }

            val commentItems = collectCommentItems(root)
            if (commentItems.isEmpty()) {
                addLog("ℹ️ 暂未找到评论 item，向下滑评论列表")
                scrollCommentList()
                scrollAttempts++
                continue
            }

            var actedAny = false
            var newCommentsThisRound = 0
            var hitsThisRound = 0
            for (item in commentItems) {
                // 提取评论文本（不含用户名）
                val text = extractCommentText(item)
                val signature = commentSignature(item, text)
                if (signature.isBlank() || processedCommentSignatures.contains(signature)) continue
                newCommentsThisRound++
                // 调试: 输出前 3 条新评论的文本 (避免刷屏)
                if (newCommentsThisRound <= 3) {
                    addLog("📝 评论: ${text.take(50)}")
                }

                val hit = keywordHit(text, keywords)
                if (!hit) {
                    processedCommentSignatures.add(signature)
                    continue
                }
                hitsThisRound++

                // 防御: 命中文本如果是判官TK助手主界面的元素, 跳过
                if (looksLikeOwnApp(text)) {
                    addLog("⚠️ 命中文本来自非 TikTok 页面, 跳过: ${text.take(30)}")
                    processedCommentSignatures.add(signature)
                    continue
                }

                // 达到单视频私信上限: 标记为已处理但不触发动作, 继续扫剩余评论
                if (matched >= maxPerVideo) {
                    addLog("⏸ 已达本视频上限($maxPerVideo), 跳过命中评论但继续扫: ${text.take(30)}")
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
                        // 返回评论 panel: 循环 BACK 直到检测到评论 panel 标志, 最多 4 次
                        // 关键: 每次 BACK 后检查 packageName, 退出了 TikTok 立刻停止 + 重启 TikTok
                        backToCommentPanel()
                    } else {
                        addLog("⚠️ 找不到该评论的头像")
                    }
                }

                actedAny = true
                broadcastStats(true)

                // 评论间小间隔（拟人）
                delay(800 + Random.nextLong(400))
            }

            // 不再因 matched 达到 maxPerVideo 而退出, 继续扫到底
            // 在滚动前记录这一屏的"内容指纹"(第一条评论文本) 用于检测是否到底
            val firstSig = commentItems.firstOrNull()?.let { extractCommentText(it) } ?: ""
            if (newCommentsThisRound > 0) {
                addLog("📊 本屏新评论 ${newCommentsThisRound} 条, 命中 ${hitsThisRound} 条 (累计扫${processedCommentSignatures.size})")
            }

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
    /**
     * 从评论 item 中提取评论正文.
     * TikTok 的 viewId 全部被 R8 混淆 (旧版按 id 过滤完全失效, 漏掉所有文本!)
     * 现在策略:
     *   1. 递归收集所有非空 text
     *   2. 过滤掉明显的无关项: 短 (≤2 字符)、纯数字 (点赞数)、纯日期、Reply/回复/查看更多 等
     *   3. 拼成评论正文字符串
     */
    private fun extractCommentText(item: AccessibilityNodeInfo): String {
        val pieces = mutableListOf<String>()
        collectTextsRaw(item, pieces)
        // 过滤无关项
        val filtered = pieces
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot { it.matches(Regex("^\\d+[wkmKMW天小时分秒前]*$")) }  // 纯数字/带单位 (点赞数/时间)
            .filterNot { it.matches(Regex("^\\d{1,2}[-/]\\d{1,2}$")) }       // 日期如 04-17 / 4/17
            .filterNot { it.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) }        // 日期如 2025-04-17
            .filterNot { it.equals("Reply", true) || it == "回复" || it == "回覆" }
            .filterNot { it.contains("View") && it.contains("more") }          // View N more replies
            .filterNot { it.contains("查看") && (it.contains("回复") || it.contains("条")) }
            .filterNot { it == "Translate" || it == "翻译" || it == "查看翻译" }
        return filtered.joinToString(" ").trim()
    }

    private fun collectTextsRaw(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) out.add(t)
        for (i in 0 until node.childCount) {
            collectTextsRaw(node.getChild(i), out)
        }
    }

    /**
     * 评论签名: 只用 text, 不用 bounds!
     * 因为滚动后同一条评论的 bounds 会变, 用 bounds 会让同一条评论被反复"视为新评论"处理.
     */
    private fun commentSignature(item: AccessibilityNodeInfo, text: String): String {
        return text.take(60)
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

        // 启发式: 评论 item 最左边的 ImageView (无论是否 clickable)
        // 实测: TikTok 评论头像 isClickable=false, 必须用坐标手势 tap (tapNodeCenter)
        val itemRect = android.graphics.Rect()
        node.getBoundsInScreen(itemRect)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectAllImagesIn(node, candidates)
        return candidates
            .filter { c ->
                val r = android.graphics.Rect()
                c.getBoundsInScreen(r)
                val w = r.width()
                val h = r.height()
                // 在评论 item 最左侧 (绝对坐标 x < 200 且在 item 左 30% 内)
                // 尺寸像头像 (60-200px 正方形)
                r.left < 200
                    && r.left < itemRect.left + itemRect.width() * 0.30
                    && w in 60..200 && h in 60..200
                    && kotlin.math.abs(w - h) < 30
            }
            .minByOrNull { c ->
                val r = android.graphics.Rect()
                c.getBoundsInScreen(r)
                r.left
            }
    }

    /** 递归收集所有 ImageView 节点（不限 clickable） */
    private fun collectAllImagesIn(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        val cls = node.className?.toString() ?: ""
        if (cls.contains("ImageView") || cls.contains("Image")) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectAllImagesIn(node.getChild(i), out)
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
