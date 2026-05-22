# TikTok 辅助工具 APK

基于 Android AccessibilityService 的 TikTok 自动化辅助工具。

## 功能

| 功能 | 说明 |
|------|------|
| 🎥 自动刷视频 | 模拟真人上滑，随机观看时长 5~15 秒 |
| ❤️ 自动点赞 | 可配置点赞率（0~100%） |
| 💬 自动评论 | 从话术库随机选一条评论 |
| 🔍 关键词搜索 | 在 TikTok 搜索指定关键词视频 |
| 🔎 评论关键词扫描 | 在视频评论区扫描匹配词 |
| 👤 自动关注 | 关键词匹配后自动关注视频作者 |
| ✉️ 自动私信 | 关键词匹配后发送预设私信话术 |

## 项目结构

```
app/src/main/
├── java/com/tiktokassist/
│   ├── service/
│   │   └── TikTokAccessibilityService.kt   ← 核心自动化服务
│   ├── ui/
│   │   ├── MainActivity.kt                  ← 主界面
│   │   ├── TaskConfigActivity.kt            ← 任务配置
│   │   ├── MessageTemplateActivity.kt       ← 话术管理
│   │   └── adapter/KeywordAdapter.kt
│   ├── model/
│   │   └── TaskConfig.kt                    ← 配置数据模型
│   └── utils/
│       ├── AccessibilityUtils.kt            ← 无障碍操作工具类
│       └── PrefsManager.kt                  ← 本地配置持久化
└── res/
    ├── xml/accessibility_service_config.xml ← 无障碍服务注册
    └── layout/ values/ drawable/
```

## 编译方法

### 使用 Android Studio（推荐）

1. 安装 [Android Studio](https://developer.android.com/studio)
2. 打开本项目文件夹
3. 等待 Gradle 同步完成
4. 点击 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. APK 生成在 `app/build/outputs/apk/debug/`

### 使用命令行

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

## 使用方法

1. **安装 APK** 到 Android 手机
2. **开启无障碍服务**：
   - 打开手机「设置」→「无障碍」→「已安装的应用」
   - 找到「TikTok辅助服务」并开启
3. **配置任务**：
   - 进入「任务配置」设置点赞率、评论率、观看时长等
   - 添加「搜索关键词」（用于搜索目标用户视频）
   - 添加「评论匹配关键词」（用于筛选目标用户）
4. **配置话术**：
   - 进入「私信/评论话术管理」添加私信和评论内容
5. **启动任务**：
   - 点击「打开TikTok」切换到 TikTok 首页
   - 返回本应用，选择任务模式
   - 点击「开始任务」后切换回 TikTok

## 两种任务模式

### 模式一：刷视频模式
自动滑动 TikTok 首页 → 随机点赞 → 随机评论 → 继续下一条

### 模式二：搜索+私信模式
搜索关键词视频 → 进入视频 → 打开评论区 → 扫描评论词  
→ 关键词命中 → 点击作者头像 → 自动关注 → 发送预设私信

## 兼容的 TikTok 包名

- `com.zhiliaoapp.musically`（国际版）
- `com.ss.android.ugc.trill`（部分地区）
- `com.tiktok.musically`

## 注意事项

- 需要 Android 8.0（API 26）及以上
- 使用过程中请保持屏幕常亮（建议关闭自动锁屏）
- TikTok 界面元素可能随版本更新变化，如失效可在 `TikTokAccessibilityService.kt` 中更新元素描述符
- 建议操作延迟设置在 800ms~2000ms，避免被 TikTok 检测为机器人
