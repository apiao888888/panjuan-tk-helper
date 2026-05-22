package com.tiktokassist.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
            addLog("⚠️ 找不到评论按钮，滑下一条")
            swipeToNextVideo()
            return true
        }

        // 2. 扫描评论 + 关键词匹配
        val processedThisVideo = scanCommentsAndAct(follow, dm, like, reply)
        addLog("📊 本视频处理评论 $processedThisVideo 条")

        // 3. 关闭评论区
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(800)

        // 4. 滑到下一个视频
        swipeToNextVideo()
        return true
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
     * TikTok 国际版的实际 UI 结构（实测）：
     * - 顶部右上角放大镜：class=ImageView, clickable=true, content-desc="", 位置 [926,95][1080,249]
     * - 搜索输入框：class=EditText, 自动 focused=true
     * - 触发搜索按钮：右上角 text="Search" 的 Button
     * - 搜索结果通常默认在 "Top" tab，第一个视频卡片通常带视频缩略图
     */
    private suspend fun navigateBySearchKeyword(keyword: String): Boolean {
        addLog("🔎 搜索关键词: $keyword")

        // 确保在 Home tab（For You 页面）—— 找底部 nav 的 Home 按钮
        ensureOnHomeFeed()

        // 步骤1：从主页（For You）找搜索图标
        var root = rootInActiveWindow ?: return false
        val searchEntry = findTopRightSearchIcon(root)
        if (searchEntry == null) {
            addLog("⚠️ 找不到搜索图标，尝试点击右上角固定位置")
            // 兜底：直接点击屏幕右上角固定坐标（约屏幕宽 93%、高 7%）
            AccessibilityUtils.tapAt(this, screenWidth * 0.93f, screenHeight * 0.075f)
        } else {
            addLog("📍 找到搜索图标，点击")
            AccessibilityUtils.clickNode(searchEntry)
        }
        delay(2000)

        // 步骤2：找到搜索输入框
        root = rootInActiveWindow ?: return false
        val searchInput = findEditableNode(root)
        if (searchInput == null) {
            addLog("⚠️ 找不到搜索输入框")
            return false
        }
        addLog("✏️ 输入关键词")
        AccessibilityUtils.clickNode(searchInput)
        delay(500)
        AccessibilityUtils.typeText(searchInput, keyword)
        delay(1000)

        // 步骤3：触发搜索 - text="Search" 的 Button
        root = rootInActiveWindow ?: return false
        val searchBtn = AccessibilityUtils.findNodeByText(root, "Search", false)
            ?: AccessibilityUtils.findNodeByText(root, "搜索", false)
        if (searchBtn != null) {
            addLog("🚀 提交搜索")
            AccessibilityUtils.clickNode(searchBtn)
        } else {
            addLog("⚠️ 找不到提交搜索按钮，尝试模拟回车")
            // 兜底：通过 IME action 提交（EditText 的 IME_ACTION_SEARCH）
            searchInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        delay(3000)

        // 步骤4：在结果页找第一个视频卡片
        root = rootInActiveWindow ?: return false

        // 国际版 TikTok 搜索结果默认是 "Top" tab，里面有 "Videos" section
        // 先尝试切换到 Videos tab（如果存在）
        val videoTab = AccessibilityUtils.findNodeByText(root, "Videos", false)
        if (videoTab != null && videoTab.isClickable) {
            addLog("📂 切到 Videos tab")
            AccessibilityUtils.clickNode(videoTab)
            delay(1500)
            root = rootInActiveWindow ?: return false
        }

        val firstVideo = findFirstSearchResultVideo(root)
        if (firstVideo == null) {
            addLog("⚠️ 找不到搜索结果视频")
            return false
        }
        addLog("▶ 点击进入第一个视频")
        AccessibilityUtils.clickNode(firstVideo)
        delay(3000)
        addLog("✅ 已进入第一个视频")
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
     * 找搜索结果页里的第一个视频卡片。TikTok 搜索结果通常是 RecyclerView 里的卡片，
     * 每个卡片包含视频缩略图和标题。
     * 策略：找第一个 clickable=true 且在屏幕可见区域的 ImageView/容器（视频缩略图）。
     */
    private fun findFirstSearchResultVideo(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectVideoResultCandidates(root, candidates)
        // 选最靠上的那个（最先出现的搜索结果）
        return candidates.minByOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.top
        }
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
            // 视频卡片：宽度通常占屏幕 30%~50%，高宽比 1:1 ~ 16:9
            // 在可见区（y > 状态栏 200，y < 屏幕高度 85%）
            if (w in (screenWidth / 4)..(screenWidth * 3 / 4)
                && h in 300..1200
                && r.top in 200..(screenHeight * 85 / 100)
                && (cls.contains("FrameLayout") || cls.contains("ViewGroup")
                    || cls.contains("ImageView") || cls.contains("RelativeLayout"))
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
     * TikTok 国际版评论按钮的 content-desc 实际是 "Read or add comments. N comments"
     * 所以我们用宽松匹配（findNode 默认是 substring）。
     */
    private suspend fun openCommentPanel(): Boolean {
        val root = rootInActiveWindow ?: return false
        // findNodeByDescription 默认应该是 contains（如果不是，下面这个会拿不到）
        val commentBtn = AccessibilityUtils.findNodeByDescription(root, "comment")
            ?: AccessibilityUtils.findNodeByDescription(root, "Comment")
            ?: AccessibilityUtils.findNodeByDescription(root, "comments")
            ?: AccessibilityUtils.findNodeByDescription(root, "Read or add")
            ?: AccessibilityUtils.findNodeByViewId(root, "comment_btn")
            ?: AccessibilityUtils.findNodeByViewId(root, "iv_comment")
        if (commentBtn == null) {
            addLog("⚠️ 找不到评论按钮")
            return false
        }
        addLog("💬 打开评论区")
        AccessibilityUtils.clickNode(commentBtn)
        delay(2200) // 等评论面板动画 + 数据加载
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
        val maxScrollAttempts = 8

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

                // 执行 follow / dm（点头像进个人主页）
                if (follow || dm) {
                    val avatar = findCommentAvatar(item)
                    if (avatar != null) {
                        AccessibilityUtils.clickNode(avatar)
                        delay(2200)
                        val profileRoot = rootInActiveWindow
                        if (profileRoot != null) {
                            if (follow && tryFollowUser(profileRoot)) {
                                stats.usersFollowed++
                                addLog("👤 关注 [总计: ${stats.usersFollowed}]")
                            }
                            if (dm) {
                                val sent = sendSuperDm(profileRoot)
                                if (sent > 0) {
                                    stats.dmsSent += sent
                                    addLog("✉️ 私信×$sent [总计: ${stats.dmsSent}]")
                                }
                            }
                        }
                        // 返回评论区
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(1500)
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

            // 这一屏处理完了，向下滚动评论看更多
            scrollCommentList()
            scrollAttempts++
            // 如果这一屏一个也没命中也没新评论，多滚几次后退出
            if (!actedAny) delay(600) else delay(300)
        }
        return matched
    }

    /**
     * 收集评论 list 里的每条评论 item 节点。
     * TikTok 的 view-id 被 R8 混淆，靠 id 不可靠。新策略：
     * 1. 先找页面里最大的 scrollable 节点（评论列表 RecyclerView）
     * 2. 取它的直接子节点作为 comment items
     * 3. 兜底：用 viewId 启发式
     */
    private fun collectCommentItems(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        // 策略1：找评论列表 RecyclerView（最大的 scrollable 节点）
        val list = findCommentList(root)
        if (list != null && list.childCount > 0) {
            val items = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0 until list.childCount) {
                val child = list.getChild(i) ?: continue
                // 过滤掉太小的（可能是分隔符等）
                val r = android.graphics.Rect()
                child.getBoundsInScreen(r)
                if (r.height() > 80 && r.width() > screenWidth / 2) {
                    items.add(child)
                }
            }
            if (items.isNotEmpty()) return items
        }

        // 策略2：viewId 启发式（兜底）
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectCommentItemsRecursive(root, result)
        return result
    }

    /**
     * 找评论列表节点：
     * - scrollable = true
     * - 高度 > 屏幕高度 30%（必须是足够大的列表）
     * - 优先选位置靠下（在评论 panel 区域）的
     */
    private fun findCommentList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectScrollableNodes(root, all)
        if (all.isEmpty()) return null
        // 选 height 最大的那个，但要在屏幕下半部分（评论 panel 通常在下方）
        return all.filter { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.height() > screenHeight * 0.25
        }.maxByOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.height()
        } ?: all.maxByOrNull { node ->
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
        // 跳过用户名节点（避免把用户名当评论内容关键词命中）
        if (id.endsWith("user_name") || id.endsWith("tv_username")) return
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
    private suspend fun sendSuperDm(profileRoot: AccessibilityNodeInfo): Int {
        if (config.dmTemplates.isEmpty()) return 0

        // 点击私信按钮进入聊天
        val msgBtn = AccessibilityUtils.findNodeByDescription(profileRoot, "Message")
            ?: AccessibilityUtils.findNodeByText(profileRoot, "Message")
            ?: AccessibilityUtils.findNodeByViewId(profileRoot, "message_btn")
            ?: AccessibilityUtils.findNodeByViewId(profileRoot, "btn_message")

        if (msgBtn == null) {
            addLog("⚠️ 未找到私信按钮")
            return 0
        }

        AccessibilityUtils.clickNode(msgBtn)
        delay(2000)

        // 决定本次发几条（超级话术：随机条数）
        val sendCount = if (config.superDmEnabled) {
            Random.nextInt(config.superDmMinCount, config.superDmMaxCount + 1)
        } else {
            1
        }

        var sentCount = 0

        repeat(sendCount) { i ->
            val msgRoot = rootInActiveWindow ?: return sentCount

            val inputField = findDmInputField(msgRoot)
            if (inputField == null) {
                addLog("⚠️ 未找到输入框（第${i + 1}条）")
                return sentCount
            }

            AccessibilityUtils.clickNode(inputField)
            delay(400 + Random.nextLong(200))

            // 随机选一条话术
            val dmText = config.dmTemplates.random()
            AccessibilityUtils.typeText(inputField, dmText)
            delay(500 + Random.nextLong(300))

            // 点发送
            val sendBtn = findSendButton(rootInActiveWindow)
            if (sendBtn != null) {
                AccessibilityUtils.clickNode(sendBtn)
                sentCount++
                delay(800 + Random.nextLong(500))   // 每条之间短暂间隔
            } else {
                addLog("⚠️ 未找到发送按钮")
                return sentCount
            }
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(800)
        return sentCount
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
        val followBtn = AccessibilityUtils.findNodeByText(profileRoot, "Follow", false)
            ?: AccessibilityUtils.findNodeByDescription(profileRoot, "Follow")

        if (followBtn != null) {
            val text = followBtn.text?.toString() ?: ""
            val desc = followBtn.contentDescription?.toString() ?: ""
            if (!text.contains("Following", true) && !desc.contains("Following", true)) {
                AccessibilityUtils.clickNode(followBtn)
                delay(500)
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

    private fun findCommentAvatar(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByViewId(node, "iv_avatar")
            ?: AccessibilityUtils.findNodeByDescription(node, "Avatar")
            ?: AccessibilityUtils.findNodeByDescription(node, "Profile")
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

    private fun findDmInputField(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByViewId(root, "et_input")
            ?: AccessibilityUtils.findNodeByViewId(root, "chat_input")
            ?: AccessibilityUtils.findNodeByViewId(root, "input")
            ?: AccessibilityUtils.findNodeByDescription(root, "Message")
    }

    private fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return AccessibilityUtils.findNodeByDescription(root, "Send")
            ?: AccessibilityUtils.findNodeByText(root, "Send", false)
            ?: AccessibilityUtils.findNodeByViewId(root, "btn_send")
            ?: AccessibilityUtils.findNodeByViewId(root, "send_btn")
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
