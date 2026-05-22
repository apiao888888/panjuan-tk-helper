package com.tiktokassist.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tiktokassist.R
import com.tiktokassist.databinding.ActivityMainBinding
import com.tiktokassist.service.FloatingWindowService
import com.tiktokassist.service.TikTokAccessibilityService
import com.tiktokassist.utils.PrefsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1001
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                TikTokAccessibilityService.ACTION_UPDATE_STATS -> updateStatsUI(intent)
                TikTokAccessibilityService.ACTION_LOG -> appendLog(intent.getStringExtra("log_line") ?: "")
                "com.tiktokassist.SERVICE_STATUS" -> updateServiceStatusUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        registerReceivers()
    }

    override fun onResume() {
        super.onResume()
        // 授权检查：未激活或已过期则返回激活界面
        val licenseInfo = com.tiktokassist.utils.LicenseManager.checkActivation(this)
        if (!licenseInfo.isValid) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }
        // 授权到期提醒
        if (licenseInfo.daysRemaining in 1..7) {
            android.widget.Toast.makeText(
                this,
                "⚠️ 授权码还有 ${licenseInfo.daysRemaining} 天到期，请及时续费",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        updateServiceStatusUI()
        updateFloatButtonState()
        updateStartButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingWindow()
            } else {
                Toast.makeText(this, "悬浮窗权限未授予", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupUI() {
        binding.btnEnableAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnOpenTiktok.setOnClickListener { openTikTok() }
        binding.btnTaskConfig.setOnClickListener {
            startActivity(Intent(this, TaskConfigActivity::class.java))
        }
        binding.btnMessageTemplates.setOnClickListener {
            startActivity(Intent(this, MessageTemplateActivity::class.java))
        }

        // 长按版本号 → 进入调试探针（隐藏入口）
        binding.tvAppVersion.setOnLongClickListener {
            startActivity(Intent(this, DebugProbeActivity::class.java))
            true
        }
        binding.btnToggleFloat.setOnClickListener {
            if (FloatingWindowService.isRunning) {
                stopService(Intent(this, FloatingWindowService::class.java))
                updateFloatButtonState()
            } else {
                requestAndStartFloatingWindow()
            }
        }

        // 启动/暂停恢复/停止 逻辑
        binding.btnStartTask.setOnClickListener {
            val service = TikTokAccessibilityService.instance
            if (service == null) {
                if (!isAccessibilityEnabled()) showAccessibilityDialog()
                else Toast.makeText(this, "无障碍服务未连接，请等待片刻", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            when {
                // 暂停中 → 点击继续
                service.isTaskRunning() && service.isTaskPaused() -> {
                    service.resumeTask()
                    Toast.makeText(this, "▶ 脚本已继续", Toast.LENGTH_SHORT).show()
                }
                // 运行中 → 点击暂停
                service.isTaskRunning() && !service.isTaskPaused() -> {
                    service.pauseTask()
                    Toast.makeText(this, "⏸ 脚本已暂停", Toast.LENGTH_SHORT).show()
                }
                // 已停止 → 启动
                else -> {
                    val config = PrefsManager.loadConfig(this)
                    service.startTask(config.currentMode)
                    Toast.makeText(this, "▶ ${config.currentMode.displayName} 已启动，请切换到TikTok", Toast.LENGTH_LONG).show()
                }
            }
            updateStartButtonState()
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(TikTokAccessibilityService.ACTION_UPDATE_STATS)
            addAction(TikTokAccessibilityService.ACTION_LOG)
            addAction("com.tiktokassist.SERVICE_STATUS")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    // ==================== UI 更新 ====================

    private fun updateServiceStatusUI() {
        if (isAccessibilityEnabled()) {
            binding.tvServiceStatus.text = "✅ 无障碍服务已开启"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
            binding.btnEnableAccessibility.text = "✅ 服务已启用"
            binding.cardServiceStatus.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.card_green))
        } else {
            binding.tvServiceStatus.text = "❌ 请先开启无障碍服务"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
            binding.btnEnableAccessibility.text = "🔧 开启无障碍服务"
            binding.cardServiceStatus.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.card_red))
        }

        // 显示当前选择的功能
        val config = PrefsManager.loadConfig(this)
        binding.tvCurrentMode.text = "当前功能：${config.currentMode.displayName}"
    }

    private fun updateStartButtonState() {
        val service = TikTokAccessibilityService.instance
        val config = PrefsManager.loadConfig(this)
        when {
            service?.isTaskRunning() == true && service.isTaskPaused() -> {
                binding.btnStartTask.text = "▶  点击继续 | ${config.currentMode.displayName}"
                binding.btnStartTask.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.accent_orange)
            }
            service?.isTaskRunning() == true -> {
                binding.btnStartTask.text = "⏸  点击暂停 | ${config.currentMode.displayName}"
                binding.btnStartTask.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.btn_stop)
            }
            else -> {
                binding.btnStartTask.text = "▶  启动脚本 | ${config.currentMode.displayName}"
                binding.btnStartTask.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.btn_start)
            }
        }
    }

    private fun updateStatsUI(intent: Intent) {
        val modeName = intent.getStringExtra("mode_name") ?: ""
        val videos = intent.getIntExtra("videos_watched", 0)
        val likes = intent.getIntExtra("likes_given", 0)
        val comments = intent.getIntExtra("comments_posted", 0)
        val follows = intent.getIntExtra("users_followed", 0)
        val dms = intent.getIntExtra("dms_sent", 0)
        val replies = intent.getIntExtra("replies_sent", 0)
        val total = intent.getIntExtra("total_tasks_done", 0)
        val cycle = intent.getIntExtra("cycle_count", 0)
        val isRunning = intent.getBooleanExtra("is_running", false)

        binding.tvStatsLine1.text = "视频: $videos  |  点赞: $likes  |  评论: $comments  |  收藏: ${TikTokAccessibilityService.stats.favoritesAdded}"
        binding.tvStatsLine2.text = "关注: $follows  |  私信: $dms  |  回复: $replies"
        binding.tvStatsLine3.text = "总任务: $total  |  循环次数: $cycle  |  模式: $modeName"

        val isPaused = intent.getBooleanExtra("is_paused", false)
        if (!isRunning) updateStartButtonState()
        else if (isPaused) {
            binding.btnStartTask.text = "⏸ 脚本暂停中 - 点击继续"
            binding.btnStartTask.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.accent_orange)
        }
    }

    private fun appendLog(line: String) {
        val current = binding.tvLog.text.toString()
        val lines = current.split("\n").takeLast(30)
        binding.tvLog.text = (lines + line).joinToString("\n")
        // 自动滚动到底部
        binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    // ==================== 悬浮窗 ====================

    private fun requestAndStartFloatingWindow() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("悬浮窗让您在TikTok界面上直接控制脚本，无需切换应用。\n\n请在下一页面开启「显示在其他应用上层」权限。")
                .setPositiveButton("去授权") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            startFloatingWindow()
        }
    }

    private fun startFloatingWindow() {
        startService(Intent(this, FloatingWindowService::class.java))
        updateFloatButtonState()
        Toast.makeText(this, "悬浮控制台已开启", Toast.LENGTH_SHORT).show()
    }

    private fun updateFloatButtonState() {
        if (FloatingWindowService.isRunning) {
            binding.btnToggleFloat.text = "🟢 关闭悬浮控制台"
            binding.btnToggleFloat.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.btn_stop)
        } else {
            binding.btnToggleFloat.text = "🪟 开启悬浮控制台"
            binding.btnToggleFloat.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.accent_blue)
        }
    }

    // ==================== 工具 ====================

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请找到「TikTok辅助」并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要开启无障碍服务")
            .setMessage("本应用需要无障碍服务权限才能自动操作TikTok。\n\n请点击「去开启」，在无障碍设置中找到「TikTok辅助服务」并开启。")
            .setPositiveButton("去开启") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openTikTok() {
        val pkgs = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.tiktok.musically")
        for (pkg in pkgs) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) { startActivity(intent); return }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.zhiliaoapp.musically")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/download")))
        }
    }
}
