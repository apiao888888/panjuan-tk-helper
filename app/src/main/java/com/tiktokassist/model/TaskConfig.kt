package com.tiktokassist.model

// ==================== 10 种功能模式 ====================
enum class TaskMode(val displayName: String, val index: Int) {
    NURTURE_ACCOUNT("功能1_养号功能", 1),
    TARGET_FANS_FOLLOW("功能2_某人粉丝关注", 2),
    TARGET_FANS_DM("功能3_某人粉丝私信", 3),
    FOLLOWED_DM("功能4_已关注私信", 4),
    OWN_FANS_DM("功能5_自己粉丝私信", 5),
    OWN_FANS_FOLLOW_BACK("功能6_自己粉丝回关", 6),
    VIDEO_COMMENT_FOLLOW("功能7_视频评论区关注", 7),
    VIDEO_COMMENT_DM("功能8_视频评论区私信", 8),
    VIDEO_COMMENT_LIKE("功能9_视频评论区点赞", 9),
    VIDEO_COMMENT_REPLY("功能10_视频评论区回复", 10);

    companion object {
        fun fromIndex(index: Int) = values().firstOrNull { it.index == index } ?: NURTURE_ACCOUNT
        fun allNames() = values().map { it.displayName }
    }
}

// ==================== 目标来源类型 ====================
enum class TargetSourceType(val displayName: String) {
    SEARCH_KEYWORD("搜索关键词"),       // 输入关键词，脚本去 TikTok 搜索并遍历结果
    USERNAME("TikTok 用户名"),         // 进入指定用户主页/粉丝列表
    VIDEO_URL("视频链接"),             // 直接打开某个视频
    CURRENT_VIDEO("当前已打开的视频");  // 不导航，直接处理当前视频
}

// ==================== 任务总配置 ====================
data class TaskConfig(

    // 当前选择的功能
    var currentMode: TaskMode = TaskMode.NURTURE_ACCOUNT,

    // 目标来源类型（搜索关键词 / 用户名 / 视频链接 / 当前视频）
    var targetSourceType: TargetSourceType = TargetSourceType.SEARCH_KEYWORD,

    // 用户输入的目标内容（按 targetSourceType 解释）
    var targetInput: String = "",          // 搜索关键词 或 用户名 或 视频链接

    // 旧字段（保留兼容已有持久化数据）
    var targetUsername: String = "",        // 某人TikTok用户名
    var targetVideoUrl: String = "",        // 目标视频链接或关键词

    // ==================== 评论关键词匹配（功能7~10）====================
    // 评论里包含这些关键词的用户，才会被关注/私信/点赞/回复；空则全部处理
    var commentMatchKeywords: MutableList<String> = mutableListOf(
        "vu", "wechat", "微信", "想要", "需要", "加我", "dm", "私信"
    ),
    // 每个视频最多处理多少条匹配评论后就翻下一个视频
    var commentMaxPerVideo: Int = 5,
    // 是否需要全部命中关键词（true）还是命中任一即可（false）
    var commentRequireAll: Boolean = false,

    // ==================== 私信话术 ====================
    // 普通私信话术（随机选1条）
    var dmTemplates: MutableList<String> = mutableListOf(
        "Hey! Love your content, let's connect! 🔥",
        "Hi there! Saw your profile and thought we should chat!",
        "Hey! Your content is amazing, would love to collaborate!"
    ),

    // 回复话术（评论区回复用）
    var replyTemplates: MutableList<String> = mutableListOf(
        "Great comment! 🙌",
        "Totally agree with you! 💯",
        "Thanks for sharing! ❤️"
    ),

    // 超级话术开关
    var superDmEnabled: Boolean = true,
    // 超级话术：每个目标发送的最少/最多条数
    var superDmMinCount: Int = 1,
    var superDmMaxCount: Int = 3,

    // ==================== 养号功能设置 ====================
    var nurtureAutoLike: Boolean = true,
    var nurtureAutoComment: Boolean = true,
    var nurtureAutoFavorite: Boolean = false,
    var nurtureAutoShare: Boolean = false,
    var nurtureLikeRate: Int = 60,          // 点赞概率 0-100
    var nurtureCommentRate: Int = 20,       // 评论概率 0-100
    var nurtureFavoriteRate: Int = 10,      // 收藏概率 0-100
    var nurtureShareRate: Int = 5,          // 分享概率 0-100
    var nurtureWatchMinSec: Int = 5,        // 单视频最少看几秒
    var nurtureWatchMaxSec: Int = 15,       // 单视频最多看几秒

    // 评论话术（养号用，不同于回复话术）
    var commentTemplates: MutableList<String> = mutableListOf(
        "Amazing content! 🔥",
        "Love this! ❤️",
        "This is so good 👏",
        "Keep it up! 💪",
        "Wow incredible 😍"
    ),

    // ==================== 任务节奏控制 ====================
    // 单次操作间隔（对同一目标完成操作后，等待多久操作下一个）
    var actionIntervalMinSec: Int = 5,
    var actionIntervalMaxSec: Int = 10,

    // 批次设置：每执行 X~Y 个任务后休息 A~B 秒
    var batchMinCount: Int = 20,
    var batchMaxCount: Int = 50,
    var batchRestMinSec: Int = 300,
    var batchRestMaxSec: Int = 600,

    // 循环次数：每循环 N 次后停止脚本
    var cycleStopCount: Int = 10,

    // 总任务数量上限（达到后停止）
    var totalTaskLimit: Int = 999,

    // ==================== 任务运行状态（不持久化）====================
    @Transient var isRunning: Boolean = false
)

// ==================== 运行时统计 ====================
data class TaskStats(
    var currentMode: TaskMode = TaskMode.NURTURE_ACCOUNT,
    var videosWatched: Int = 0,
    var likesGiven: Int = 0,
    var commentsPosted: Int = 0,
    var favoritesAdded: Int = 0,
    var usersFollowed: Int = 0,
    var dmsSent: Int = 0,
    var repliesSent: Int = 0,
    var keywordMatches: Int = 0,
    var totalTasksDone: Int = 0,    // 本次任务完成总数
    var batchCount: Int = 0,        // 当前批次计数
    var cycleCount: Int = 0,        // 循环次数
    var startTime: Long = 0L,
    var lastUpdateTime: Long = 0L
)
