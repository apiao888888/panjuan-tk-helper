package com.tiktokassist.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.tiktokassist.R
import com.tiktokassist.utils.PrefsManager
import kotlin.math.abs

class FloatingWindowService : Service() {

    companion object {
        const val ACTION_HIDE = "com.tiktokassist.FLOAT_HIDE"
        var isRunning = false
    }

    // 脚本三种状态
    private enum class ScriptState { STOPPED, RUNNING, PAUSED }
    private var scriptState = ScriptState.STOPPED

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isExpanded = false

    // 拖动
    private var initX = 0; private var initY = 0
    private var initTouchX = 0f; private var initTouchY = 0f
    private var isDragging = false

    private val layoutParams: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 200
        }
    }

    // 悬浮窗里显示的最近日志（最多 30 条）
    private val recentLogs = ArrayDeque<String>(30)

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                TikTokAccessibilityService.ACTION_UPDATE_STATS -> syncStateFromBroadcast(intent)
                TikTokAccessibilityService.ACTION_LOG -> {
                    val line = intent.getStringExtra("log_line") ?: return
                    appendLogToFloat(line)
                }
            }
        }
    }

    private fun appendLogToFloat(line: String) {
        if (recentLogs.size >= 30) recentLogs.removeFirst()
        recentLogs.addLast(line)
        val tv = floatView?.findViewById<TextView>(R.id.tvFloatLog) ?: return
        tv.text = recentLogs.joinToString("\n")
        // 自动滚动到底部
        floatView?.findViewById<ScrollView>(R.id.scrollFloatLog)?.post {
            floatView?.findViewById<ScrollView>(R.id.scrollFloatLog)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 关键：Service 默认 context 不会被 Material 主题校验通过，
        // 必须用 ContextThemeWrapper 显式包一层 MaterialComponents 主题，
        // 否则 MaterialButton 在 inflate 时会抛 IllegalArgumentException 导致 service 闪退。
        val themedContext = ContextThemeWrapper(this, R.style.Theme_TikTokAssistant)
        floatView = LayoutInflater.from(themedContext).inflate(R.layout.float_window, null)
        windowManager.addView(floatView, layoutParams)
        setupTouchListener()
        setupButtons()
        registerReceivers()
        showCollapsed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        floatView?.let { windowManager.removeView(it) }
        try { unregisterReceiver(statsReceiver) } catch (_: Exception) {}
    }

    // ==================== 展开 / 收起 ====================

    private fun showCollapsed() {
        isExpanded = false
        floatView?.findViewById<View>(R.id.layoutCollapsed)?.visibility = View.VISIBLE
        floatView?.findViewById<View>(R.id.layoutExpanded)?.visibility = View.GONE
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        floatView?.let { windowManager.updateViewLayout(it, layoutParams) }
    }

    private fun showExpanded() {
        isExpanded = true
        floatView?.findViewById<View>(R.id.layoutCollapsed)?.visibility = View.GONE
        floatView?.findViewById<View>(R.id.layoutExpanded)?.visibility = View.VISIBLE
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        floatView?.let { windowManager.updateViewLayout(it, layoutParams) }
        // 展开时把无障碍服务里的日志拉过来显示
        syncLogsFromService()
        refreshUI()
    }

    private fun syncLogsFromService() {
        recentLogs.clear()
        // logLines 是无障碍服务里维护的环形日志（最多 50 条），取最近 30 条
        TikTokAccessibilityService.logLines.takeLast(30).forEach { recentLogs.addLast(it) }
        val tv = floatView?.findViewById<TextView>(R.id.tvFloatLog) ?: return
        tv.text = if (recentLogs.isEmpty()) "等待启动..." else recentLogs.joinToString("\n")
        floatView?.findViewById<ScrollView>(R.id.scrollFloatLog)?.post {
            floatView?.findViewById<ScrollView>(R.id.scrollFloatLog)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ==================== 拖动 ====================
    //
    // 关键：把 OnTouchListener 设在「悬浮球」和「展开面板标题栏」上，而不是 root view，
    // 这样展开态里的子按钮（▶ ⏸ ⏹ 等）能正常 click。
    // 同时 ACTION_DOWN 必须返回 true，否则后续 MOVE/UP 不会到这个 listener。

    private fun makeDragTapListener(onTap: () -> Unit): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x; initY = layoutParams.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    isDragging = false
                    true // 必须返回 true 才能继续收到 MOVE/UP
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initTouchX).toInt()
                    val dy = (event.rawY - initTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) isDragging = true
                    if (isDragging) {
                        layoutParams.x = initX + dx
                        layoutParams.y = initY + dy
                        floatView?.let { windowManager.updateViewLayout(it, layoutParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) onTap()
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupTouchListener() {
        // 收起态：点小球 → 展开；按住拖动 → 移动位置
        floatView?.findViewById<View>(R.id.layoutCollapsed)
            ?.setOnTouchListener(makeDragTapListener { showExpanded() })

        // 展开态：标题栏可拖动（不响应 tap，按钮自己点）
        floatView?.findViewById<View>(R.id.tvPanelTitle)
            ?.setOnTouchListener(makeDragTapListener { /* 标题栏 tap 不做事 */ })
    }

    // ==================== 三按钮逻辑 ====================

    private fun setupButtons() {
        // 收起面板
        floatView?.findViewById<View>(R.id.btnCollapse)?.setOnClickListener {
            showCollapsed()
        }

        // 关闭悬浮窗（不停止脚本）
        floatView?.findViewById<View>(R.id.btnCloseFloat)?.setOnClickListener {
            stopSelf()
        }

        // ▶ 开启
        floatView?.findViewById<MaterialButton>(R.id.btnFloatStart)?.setOnClickListener {
            val service = TikTokAccessibilityService.instance
            when (scriptState) {
                ScriptState.STOPPED -> {
                    if (service == null) return@setOnClickListener
                    val config = PrefsManager.loadConfig(this)
                    service.startTask(config.currentMode)
                    scriptState = ScriptState.RUNNING
                    refreshUI()
                }
                ScriptState.PAUSED -> {
                    // 已暂停时，▶ 也可以恢复
                    service?.resumeTask()
                    scriptState = ScriptState.RUNNING
                    refreshUI()
                }
                ScriptState.RUNNING -> {
                    // 已在运行，点开启无效（按钮此时是灰色不可点击）
                }
            }
        }

        // ⏸ 暂停 / ▶ 继续
        floatView?.findViewById<MaterialButton>(R.id.btnFloatPause)?.setOnClickListener {
            val service = TikTokAccessibilityService.instance ?: return@setOnClickListener
            when (scriptState) {
                ScriptState.RUNNING -> {
                    service.pauseTask()
                    scriptState = ScriptState.PAUSED
                    refreshUI()
                }
                ScriptState.PAUSED -> {
                    service.resumeTask()
                    scriptState = ScriptState.RUNNING
                    refreshUI()
                }
                ScriptState.STOPPED -> { /* 停止状态不响应 */ }
            }
        }

        // ⏹ 停止
        floatView?.findViewById<MaterialButton>(R.id.btnFloatStop)?.setOnClickListener {
            val service = TikTokAccessibilityService.instance ?: return@setOnClickListener
            service.stopTask()
            scriptState = ScriptState.STOPPED
            refreshUI()
        }

        // 🔍 探针：抓取当前 TikTok 界面元素 → 写文件 + 复制到剪贴板
        floatView?.findViewById<MaterialButton>(R.id.btnFloatProbe)?.setOnClickListener {
            val service = TikTokAccessibilityService.instance
            if (service == null) {
                android.widget.Toast.makeText(this, "无障碍服务未连接", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 收起悬浮窗以免遮挡（其实不挡，因为它已经在前台）
            showCollapsed()

            // 延迟1.5秒抓取，给用户时间收起面板
            android.os.Handler(mainLooper).postDelayed({
                val summary = service.dumpUiSummary()
                val detailed = service.dumpCurrentUi()
                val fullText = "$summary\n\n=== 详细节点树 ===\n\n$detailed"

                // 写入剪贴板
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("UI Dump", fullText))

                // 写入文件
                try {
                    val dir = java.io.File(getExternalFilesDir(null), "ui_dumps")
                    if (!dir.exists()) dir.mkdirs()
                    val file = java.io.File(dir, "dump_${System.currentTimeMillis()}.txt")
                    file.writeText(fullText)
                    android.widget.Toast.makeText(this,
                        "✅ 已抓取${fullText.lines().size}行元素\n📋 已复制到剪贴板\n💾 文件：${file.name}",
                        android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this,
                        "✅ 已复制到剪贴板（${fullText.length}字符）",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }, 1500)
        }

        // 打开主界面
        floatView?.findViewById<MaterialButton>(R.id.btnFloatOpenApp)?.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    // ==================== UI 刷新 ====================

    private fun refreshUI() {
        val startBtn = floatView?.findViewById<MaterialButton>(R.id.btnFloatStart)
        val pauseBtn = floatView?.findViewById<MaterialButton>(R.id.btnFloatPause)
        val stopBtn = floatView?.findViewById<MaterialButton>(R.id.btnFloatStop)
        val titleTv = floatView?.findViewById<TextView>(R.id.tvPanelTitle)
        val bubbleIcon = floatView?.findViewById<TextView>(R.id.tvBubbleIcon)
        val dot = floatView?.findViewById<View>(R.id.indicatorDot)
        val dotExpanded = floatView?.findViewById<View>(R.id.indicatorExpanded)

        // 当前功能名称
        val config = PrefsManager.loadConfig(this)
        floatView?.findViewById<TextView>(R.id.tvCurrentMode)?.text =
            "功能：${config.currentMode.displayName}"

        when (scriptState) {
            ScriptState.STOPPED -> {
                titleTv?.text = "判官TK助手 · 已停止"
                titleTv?.setTextColor(0xFFAAAAAA.toInt())
                bubbleIcon?.text = "TK"
                dot?.visibility = View.GONE
                dotExpanded?.visibility = View.GONE

                // 开启：可用（绿）
                startBtn?.isEnabled = true; startBtn?.alpha = 1f
                startBtn?.text = "▶"
                startBtn?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_start)

                // 暂停：不可用（灰）
                pauseBtn?.isEnabled = false; pauseBtn?.alpha = 0.3f
                pauseBtn?.text = "⏸"

                // 停止：不可用（灰）
                stopBtn?.isEnabled = false; stopBtn?.alpha = 0.3f
            }

            ScriptState.RUNNING -> {
                titleTv?.text = "判官TK助手 · 运行中"
                titleTv?.setTextColor(0xFF34C759.toInt())
                bubbleIcon?.text = "▶"
                dot?.visibility = View.VISIBLE
                dot?.background = ContextCompat.getDrawable(this, R.drawable.bg_indicator_green)
                dotExpanded?.visibility = View.VISIBLE
                dotExpanded?.background = ContextCompat.getDrawable(this, R.drawable.bg_indicator_green)

                // 开启：不可用（已在运行）
                startBtn?.isEnabled = false; startBtn?.alpha = 0.3f
                startBtn?.text = "▶"

                // 暂停：可用（橙）
                pauseBtn?.isEnabled = true; pauseBtn?.alpha = 1f
                pauseBtn?.text = "⏸"
                pauseBtn?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_orange)

                // 停止：可用（红）
                stopBtn?.isEnabled = true; stopBtn?.alpha = 1f
            }

            ScriptState.PAUSED -> {
                titleTv?.text = "判官TK助手 · 已暂停"
                titleTv?.setTextColor(0xFFFF9500.toInt())
                bubbleIcon?.text = "⏸"
                dot?.visibility = View.VISIBLE
                dot?.background = ContextCompat.getDrawable(this, R.drawable.bg_indicator_orange)
                dotExpanded?.visibility = View.VISIBLE
                dotExpanded?.background = ContextCompat.getDrawable(this, R.drawable.bg_indicator_orange)

                // 开启（即继续）：可用（绿）
                startBtn?.isEnabled = true; startBtn?.alpha = 1f
                startBtn?.text = "▶"
                startBtn?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_start)

                // 暂停按钮变为"继续"（橙变蓝）
                pauseBtn?.isEnabled = true; pauseBtn?.alpha = 1f
                pauseBtn?.text = "▶▶"
                pauseBtn?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_blue)

                // 停止：可用
                stopBtn?.isEnabled = true; stopBtn?.alpha = 1f
            }
        }
    }

    // ==================== 广播接收 ====================

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(TikTokAccessibilityService.ACTION_UPDATE_STATS)
            addAction(TikTokAccessibilityService.ACTION_LOG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statsReceiver, filter)
        }
    }

    private fun syncStateFromBroadcast(intent: Intent) {
        val isRunning = intent.getBooleanExtra("is_running", false)
        val isPaused = intent.getBooleanExtra("is_paused", false)

        // 同步状态机
        scriptState = when {
            !isRunning -> ScriptState.STOPPED
            isPaused -> ScriptState.PAUSED
            else -> ScriptState.RUNNING
        }

        // 更新统计文字
        val videos = intent.getIntExtra("videos_watched", 0)
        val likes = intent.getIntExtra("likes_given", 0)
        val comments = intent.getIntExtra("comments_posted", 0)
        val follows = intent.getIntExtra("users_followed", 0)
        val dms = intent.getIntExtra("dms_sent", 0)
        floatView?.findViewById<TextView>(R.id.tvFloatStats)?.text =
            "视频$videos  ❤$likes  💬$comments  👤$follows  ✉$dms"

        if (isExpanded) refreshUI()

        // 收起态也更新悬浮球图标和指示灯
        val bubbleIcon = floatView?.findViewById<TextView>(R.id.tvBubbleIcon)
        val dot = floatView?.findViewById<View>(R.id.indicatorDot)
        when (scriptState) {
            ScriptState.RUNNING -> {
                bubbleIcon?.text = "▶"
                dot?.visibility = View.VISIBLE
                dot?.background = ContextCompat.getDrawable(this, R.drawable.bg_indicator_green)
            }
            ScriptState.PAUSED -> {
                bubbleIcon?.text = "⏸"
                dot?.visibility = View.VISIBLE
                dot?.background = ContextCompat.getDrawable(this, R.drawable.bg_indicator_orange)
            }
            ScriptState.STOPPED -> {
                bubbleIcon?.text = "TK"
                dot?.visibility = View.GONE
            }
        }
    }
}
