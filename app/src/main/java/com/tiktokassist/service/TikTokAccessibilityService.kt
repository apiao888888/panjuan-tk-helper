package com.tiktokassist.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tiktokassist.model.TaskConfig
import com.tiktokassist.model.TaskMode
import com.tiktokassist.model.TaskStats
import com.tiktokassist.utils.AccessibilityUtils
import com.tiktokassist.utils.PrefsManager
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
        stats = TaskStats(currentMode = mode, startTime = System.currentTimeMillis())
        logLines.clear()
        addLog("▶ 启动：${mode.displayName}")

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
        while (isPaused && isActive) {
            delay(300)
        }
    }

    // ==================== 批次控制包装 ====================

    private suspend fun runTaskWithBatchControl(mode: TaskMode) {
        var totalDone = 0
        var cycleCount = 0
        var batchCount = 0
        val batchSize = Random.nextInt(config.batchMinCount, config.batchMaxCount + 1)

        while (isActive && totalDone < config.totalTaskLimit) {
            // 暂停检查点：如果暂停则挂起等待
            checkPaused()
            if (!isActive) break

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

    // ==================== 功能7/8/9/10：视频评论区 ====================

    private suspend fun doVideoCommentAction(
        follow: Boolean, dm: Boolean, like: Boolean, reply: Boolean
    ): Boolean {
        if (currentPackage !in TIKTOK_PACKAGES) return false
        val root = rootInActiveWindow ?: return false

        // 打开评论区
        val commentBtn = AccessibilityUtils.findNodeByDescription(root, "Comment")
            ?: AccessibilityUtils.findNodeByViewId(root, "comment_btn")

        if (commentBtn != null) {
            AccessibilityUtils.clickNode(commentBtn)
            delay(1500)
        }

        val commentRoot = rootInActiveWindow ?: return false

        // 获取第一个未处理的评论用户节点
        val commentUserNode = findFirstCommentUser(commentRoot) ?: return false

        if (like) {
            // 点赞该条评论
            val likeNode = findCommentLikeBtn(commentUserNode)
            if (likeNode != null) {
                AccessibilityUtils.clickNode(likeNode)
                stats.likesGiven++
                addLog("❤️ 评论区点赞 [总计: ${stats.likesGiven}]")
            }
        }

        if (reply && config.replyTemplates.isNotEmpty()) {
            val replyBtn = findCommentReplyBtn(commentUserNode)
            if (replyBtn != null) {
                AccessibilityUtils.clickNode(replyBtn)
                delay(800)
                val replyRoot = rootInActiveWindow
                val inputField = findCommentInput(replyRoot)
                if (inputField != null) {
                    val replyText = config.replyTemplates.random()
                    AccessibilityUtils.clickNode(inputField)
                    delay(400)
                    AccessibilityUtils.typeText(inputField, replyText)
                    delay(500)
                    val postBtn = AccessibilityUtils.findNodeByText(rootInActiveWindow, "Post", false)
                        ?: AccessibilityUtils.findNodeByDescription(rootInActiveWindow, "Post")
                    postBtn?.let {
                        AccessibilityUtils.clickNode(it)
                        stats.repliesSent++
                        addLog("💬 评论区回复 [总计: ${stats.repliesSent}]")
                    }
                }
            }
        }

        if (follow || dm) {
            // 点击评论者头像进入主页
            val avatarNode = findCommentAvatar(commentUserNode)
            if (avatarNode != null) {
                AccessibilityUtils.clickNode(avatarNode)
                delay(2000)
                val profileRoot = rootInActiveWindow ?: return true

                if (follow) {
                    if (tryFollowUser(profileRoot)) {
                        stats.usersFollowed++
                        addLog("👤 评论区关注 [总计: ${stats.usersFollowed}]")
                    }
                }

                if (dm) {
                    val sent = sendSuperDm(profileRoot)
                    if (sent > 0) {
                        stats.dmsSent += sent
                        addLog("✉️ 评论区私信×$sent [总计: ${stats.dmsSent}]")
                    }
                }

                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(1000)
            }
        }

        // 关闭评论区
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(600)
        return true
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
