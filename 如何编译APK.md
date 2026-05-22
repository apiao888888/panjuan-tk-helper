# 🚀 如何编译 APK（云端零环境零下载方案）

无需在你电脑上安装任何编译工具，全程在 GitHub 网页操作，约 **10 分钟**拿到 APK。

---

## 准备工作

### 1. 注册一个 GitHub 账号（如果还没有）

打开 https://github.com/signup ，用邮箱注册，**免费**。

### 2. 安装 Git for Windows（可选，但推荐）

下载：https://git-scm.com/download/win  
安装时一路下一步即可。

> 不想装 Git？也行！我下面会教你用 GitHub 网页直接上传文件夹。

---

## 方法 A：用 GitHub 网页上传（最简单，零工具）

### 第 1 步：创建一个新仓库

1. 登录 GitHub，点右上角 **+** → **New repository**
2. 仓库名填：`panjuan-tk-helper`
3. 选 **Private**（私有，别人看不到你的代码）
4. 点 **Create repository**

### 第 2 步：上传整个项目

1. 在新仓库页面点 **uploading an existing file**
2. **直接把整个「海外抖音apk」文件夹**拖进网页
3. 等所有文件传完
4. 滚动到底部，输入提交说明 `初始化项目`
5. 点 **Commit changes**

### 第 3 步：触发编译

1. 上传完后，在仓库顶部点 **Actions** 标签
2. 如果提示「I understand my workflows, go ahead and enable them」，点同意
3. 等待 5~10 分钟，会看到一个绿色✅的任务

### 第 4 步：下载 APK

1. 点击那个绿色的任务
2. 滚动到底部 **Artifacts** 区域
3. 点击 **判官TK助手-debug** 下载（是个 zip）
4. 解压 zip，里面就是 **app-debug.apk** —— 这就是你要的 APK！

### 第 5 步：装到手机

1. 把 APK 传到手机（微信文件传输/QQ/U盘都行）
2. 手机打开 APK 文件，按提示安装
3. 第一次安装可能需要打开「**允许安装未知来源应用**」

---

## 方法 B：用 Git 命令行（更专业）

如果你装了 Git，可以这样：

```bash
# 进入项目目录
cd "c:\Users\ZhuanZ（无密码）\Desktop\海外抖音apk"

# 初始化 Git
git init
git add .
git commit -m "初始化判官TK助手项目"

# 关联到 GitHub 仓库（把 URL 换成你自己仓库的）
git branch -M main
git remote add origin https://github.com/你的用户名/panjuan-tk-helper.git
git push -u origin main
```

推送完成后，GitHub Actions 会**自动开始编译**。

---

## 编译完成后

每次你**改动代码并 push**，GitHub Actions 都会自动重新编译出新版 APK。

### 重新触发编译

不改代码也想重新编译？  
进 **Actions** → 选择 `编译判官TK助手 APK` → 点右侧 **Run workflow**

---

## 编译失败怎么办？

进入失败的任务，点击红色的步骤查看报错日志。把日志截图发给我，我立刻帮你修。

### 常见错误

| 报错 | 解决 |
|------|------|
| `gradle-wrapper.jar not found` | 我已经在 workflow 里加了自动下载，不用管 |
| `Unable to resolve dependency` | 网络问题，重试一次 |
| `License not accepted` | workflow 已经处理了 |

---

## 关于签名（重要）

刚编出来的 APK 是 **Debug 版（调试版）**，能装能用但有以下限制：

- ❌ Google Play 不能上架（需要 release 版+签名）
- ✅ 自己手机装、发给客户用 **完全没问题**
- ⚠️ Debug 版可能被某些杀毒软件误报

如果想要**正式发布版**：
1. 我可以帮你写一个生成签名密钥的脚本
2. 你把签名密钥保存到 GitHub Secrets
3. workflow 自动用密钥签名 Release APK

需要的话告诉我。

---

## 项目目录说明

```
海外抖音apk/
├── .github/workflows/
│   └── build-apk.yml          ← GitHub 自动编译脚本
├── app/
│   ├── build.gradle           ← 项目依赖配置
│   ├── proguard-rules.pro     ← 代码混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/tiktokassist/
│       │   ├── service/       ← 无障碍服务 + 悬浮窗服务
│       │   ├── ui/            ← 所有界面（主页/激活/注册机/调试器）
│       │   ├── model/         ← 数据模型（10种功能枚举/配置类）
│       │   └── utils/         ← 工具类（授权/UI抓取/配置存储）
│       └── res/
│           ├── layout/        ← XML 布局文件
│           ├── drawable/      ← 图标背景
│           └── values/        ← 颜色/字符串/主题
├── build.gradle               ← 根 build 配置
├── settings.gradle            ← Gradle 项目设置
├── gradle.properties          ← Gradle 全局参数
├── gradlew / gradlew.bat      ← Gradle 启动脚本
└── .gitignore                 ← 不上传的文件清单
```

---

## 还有问题？

把报错截图或描述发给我，我立刻分析。
