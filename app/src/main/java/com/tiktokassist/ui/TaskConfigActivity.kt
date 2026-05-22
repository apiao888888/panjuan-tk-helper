package com.tiktokassist.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.tiktokassist.databinding.ActivityTaskConfigBinding
import com.tiktokassist.model.TargetSourceType
import com.tiktokassist.model.TaskMode
import com.tiktokassist.ui.adapter.KeywordAdapter
import com.tiktokassist.utils.PrefsManager

class TaskConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskConfigBinding
    private var selectedMode: TaskMode = TaskMode.NURTURE_ACCOUNT
    private var selectedSource: TargetSourceType = TargetSourceType.SEARCH_KEYWORD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 自定义返回按钮
        binding.btnBack.setOnClickListener {
            saveConfig()
            finish()
        }

        setupModeSpinner()
        setupTargetSourceSpinner()
        loadConfig()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        saveConfig()
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        saveConfig()
    }

    // ==================== 功能选择下拉框 ====================

    private fun setupModeSpinner() {
        val modeNames = TaskMode.allNames()
        val adapter = ArrayAdapter(this, com.tiktokassist.R.layout.item_spinner_selected, modeNames).apply {
            setDropDownViewResource(com.tiktokassist.R.layout.item_spinner_dropdown)
        }
        binding.spinnerMode.adapter = adapter
        // 设置下拉框的背景为深色卡片色
        binding.spinnerMode.setPopupBackgroundResource(com.tiktokassist.R.color.card_dark)
        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedMode = TaskMode.fromIndex(pos + 1)
                updateTargetFieldVisibility()
                saveConfig()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateTargetFieldVisibility() {
        val needsTarget = selectedMode in listOf(
            TaskMode.TARGET_FANS_FOLLOW,
            TaskMode.TARGET_FANS_DM,
            TaskMode.VIDEO_COMMENT_FOLLOW,
            TaskMode.VIDEO_COMMENT_DM,
            TaskMode.VIDEO_COMMENT_LIKE,
            TaskMode.VIDEO_COMMENT_REPLY
        )
        binding.layoutTargetUsername.visibility = if (needsTarget) View.VISIBLE else View.GONE

        // 评论关键词区：只有「视频评论区」相关功能才需要
        val needsCommentKeyword = selectedMode in listOf(
            TaskMode.VIDEO_COMMENT_FOLLOW,
            TaskMode.VIDEO_COMMENT_DM,
            TaskMode.VIDEO_COMMENT_LIKE,
            TaskMode.VIDEO_COMMENT_REPLY
        )
        val keywordVis = if (needsCommentKeyword) View.VISIBLE else View.GONE
        binding.cardCommentKeyword.visibility = keywordVis
        binding.headerCommentKeyword.visibility = keywordVis

        // 根据功能限定目标来源选项
        updateTargetSourceOptions()

        // 养号功能才显示养号设置（连同标题一起显示/隐藏）
        val isNurture = selectedMode == TaskMode.NURTURE_ACCOUNT
        val nurtureVis = if (isNurture) View.VISIBLE else View.GONE
        binding.cardNurtureSettings.visibility = nurtureVis
        binding.headerNurture.visibility = nurtureVis
    }

    // ==================== 目标来源选择 ====================

    private fun setupTargetSourceSpinner() {
        // 初始填充全部选项，updateTargetSourceOptions 会按功能裁剪
        val names = TargetSourceType.values().map { it.displayName }
        val adapter = ArrayAdapter(this, com.tiktokassist.R.layout.item_spinner_selected, names).apply {
            setDropDownViewResource(com.tiktokassist.R.layout.item_spinner_dropdown)
        }
        binding.spinnerTargetSource.adapter = adapter
        binding.spinnerTargetSource.setPopupBackgroundResource(com.tiktokassist.R.color.card_dark)
        binding.spinnerTargetSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val visibleOptions = visibleTargetSources(selectedMode)
                selectedSource = visibleOptions.getOrNull(pos) ?: TargetSourceType.SEARCH_KEYWORD
                updateTargetInputHint()
                saveConfig()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun visibleTargetSources(mode: TaskMode): List<TargetSourceType> {
        return when (mode) {
            TaskMode.TARGET_FANS_FOLLOW,
            TaskMode.TARGET_FANS_DM ->
                // 某人粉丝：只能输入用户名
                listOf(TargetSourceType.USERNAME)
            TaskMode.VIDEO_COMMENT_FOLLOW,
            TaskMode.VIDEO_COMMENT_DM,
            TaskMode.VIDEO_COMMENT_LIKE,
            TaskMode.VIDEO_COMMENT_REPLY ->
                // 评论区：支持搜索关键词、视频链接、当前视频
                listOf(
                    TargetSourceType.SEARCH_KEYWORD,
                    TargetSourceType.VIDEO_URL,
                    TargetSourceType.CURRENT_VIDEO
                )
            else -> emptyList()
        }
    }

    private fun updateTargetSourceOptions() {
        val options = visibleTargetSources(selectedMode)
        if (options.isEmpty()) return
        val names = options.map { it.displayName }
        val adapter = ArrayAdapter(this, com.tiktokassist.R.layout.item_spinner_selected, names).apply {
            setDropDownViewResource(com.tiktokassist.R.layout.item_spinner_dropdown)
        }
        binding.spinnerTargetSource.adapter = adapter
        // 选中已保存的来源（若当前模式不支持则用第一个）
        val idx = options.indexOf(selectedSource).coerceAtLeast(0)
        binding.spinnerTargetSource.setSelection(idx)
        selectedSource = options[idx]
        updateTargetInputHint()
    }

    private fun updateTargetInputHint() {
        when (selectedSource) {
            TargetSourceType.SEARCH_KEYWORD -> {
                binding.tvTargetInputLabel.text = "搜索关键词"
                binding.etTargetUsername.hint = "例如：美女、穿搭、宠物"
                binding.tvTargetInputHelp.text = "脚本会自动打开 TikTok 搜索并遍历视频"
                binding.tvTargetInputHelp.visibility = View.VISIBLE
                binding.etTargetUsername.visibility = View.VISIBLE
            }
            TargetSourceType.USERNAME -> {
                binding.tvTargetInputLabel.text = "TikTok 用户名"
                binding.etTargetUsername.hint = "@username 或 username"
                binding.tvTargetInputHelp.text = "脚本会进入该用户主页/粉丝列表"
                binding.tvTargetInputHelp.visibility = View.VISIBLE
                binding.etTargetUsername.visibility = View.VISIBLE
            }
            TargetSourceType.VIDEO_URL -> {
                binding.tvTargetInputLabel.text = "视频链接"
                binding.etTargetUsername.hint = "tiktok.com/@xxx/video/123…"
                binding.tvTargetInputHelp.text = "脚本会打开该链接对应的视频"
                binding.tvTargetInputHelp.visibility = View.VISIBLE
                binding.etTargetUsername.visibility = View.VISIBLE
            }
            TargetSourceType.CURRENT_VIDEO -> {
                binding.tvTargetInputLabel.text = "当前已打开的视频"
                binding.tvTargetInputHelp.text = "请先在 TikTok 打开目标视频，再回来启动脚本"
                binding.tvTargetInputHelp.visibility = View.VISIBLE
                binding.etTargetUsername.visibility = View.GONE
            }
        }
    }

    // ==================== 加载配置 ====================

    private fun loadConfig() {
        val config = PrefsManager.loadConfig(this)

        // 功能选择
        selectedMode = config.currentMode
        binding.spinnerMode.setSelection(config.currentMode.index - 1)
        selectedSource = config.targetSourceType
        updateTargetFieldVisibility()

        // 目标输入框：优先用 targetInput，否则向后兼容 targetUsername
        binding.etTargetUsername.setText(
            config.targetInput.ifBlank { config.targetUsername }
        )

        // 评论关键词
        binding.etCommentKeywords.setText(config.commentMatchKeywords.joinToString("\n"))
        binding.etCommentMaxPerVideo.setText(config.commentMaxPerVideo.toString())

        // 超级话术
        binding.switchSuperDm.isChecked = config.superDmEnabled
        binding.etSuperDmMin.setText(config.superDmMinCount.toString())
        binding.etSuperDmMax.setText(config.superDmMaxCount.toString())
        updateSuperDmVisibility(config.superDmEnabled)

        // 任务节奏
        binding.etActionIntervalMin.setText(config.actionIntervalMinSec.toString())
        binding.etActionIntervalMax.setText(config.actionIntervalMaxSec.toString())
        binding.etBatchMin.setText(config.batchMinCount.toString())
        binding.etBatchMax.setText(config.batchMaxCount.toString())
        binding.etBatchRestMin.setText(config.batchRestMinSec.toString())
        binding.etBatchRestMax.setText(config.batchRestMaxSec.toString())
        binding.etCycleStop.setText(config.cycleStopCount.toString())
        binding.etTotalLimit.setText(config.totalTaskLimit.toString())

        // 养号设置
        binding.switchNurtureLike.isChecked = config.nurtureAutoLike
        binding.switchNurtureComment.isChecked = config.nurtureAutoComment
        binding.switchNurtureFavorite.isChecked = config.nurtureAutoFavorite
        binding.switchNurtureShare.isChecked = config.nurtureAutoShare
        binding.etLikeRate.setText(config.nurtureLikeRate.toString())
        binding.etCommentRate.setText(config.nurtureCommentRate.toString())
        binding.etFavoriteRate.setText(config.nurtureFavoriteRate.toString())
        binding.etShareRate.setText(config.nurtureShareRate.toString())
        binding.etWatchMin.setText(config.nurtureWatchMinSec.toString())
        binding.etWatchMax.setText(config.nurtureWatchMaxSec.toString())
    }

    // ==================== 监听器 ====================

    private fun setupListeners() {
        binding.switchSuperDm.setOnCheckedChangeListener { _, checked ->
            updateSuperDmVisibility(checked)
        }

        binding.btnEditDmTemplates.setOnClickListener {
            startActivity(android.content.Intent(this, MessageTemplateActivity::class.java))
        }

        binding.btnEditCommentTemplates.setOnClickListener {
            startActivity(android.content.Intent(this, MessageTemplateActivity::class.java)
                .putExtra("tab", "comment"))
        }

        binding.btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "✅ 配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSuperDmVisibility(enabled: Boolean) {
        binding.layoutSuperDmCount.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    // ==================== 保存配置 ====================

    private fun saveConfig() {
        val config = PrefsManager.loadConfig(this)

        config.currentMode = selectedMode
        config.targetSourceType = selectedSource
        val inputText = binding.etTargetUsername.text.toString().trim()
        config.targetInput = inputText
        config.targetUsername = inputText // 兼容旧逻辑
        config.targetVideoUrl = inputText // 兼容旧逻辑

        // 评论关键词（按行切分、去空、去重）
        val keywords = binding.etCommentKeywords.text.toString()
            .split('\n', ',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toMutableList()
        config.commentMatchKeywords = keywords
        config.commentMaxPerVideo = binding.etCommentMaxPerVideo.text.toString()
            .toIntOrNull()?.coerceIn(1, 100) ?: 5

        config.superDmEnabled = binding.switchSuperDm.isChecked
        config.superDmMinCount = binding.etSuperDmMin.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        config.superDmMaxCount = binding.etSuperDmMax.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 3

        config.actionIntervalMinSec = binding.etActionIntervalMin.text.toString().toIntOrNull() ?: 5
        config.actionIntervalMaxSec = binding.etActionIntervalMax.text.toString().toIntOrNull() ?: 10
        config.batchMinCount = binding.etBatchMin.text.toString().toIntOrNull() ?: 20
        config.batchMaxCount = binding.etBatchMax.text.toString().toIntOrNull() ?: 50
        config.batchRestMinSec = binding.etBatchRestMin.text.toString().toIntOrNull() ?: 300
        config.batchRestMaxSec = binding.etBatchRestMax.text.toString().toIntOrNull() ?: 600
        config.cycleStopCount = binding.etCycleStop.text.toString().toIntOrNull() ?: 10
        config.totalTaskLimit = binding.etTotalLimit.text.toString().toIntOrNull() ?: 999

        config.nurtureAutoLike = binding.switchNurtureLike.isChecked
        config.nurtureAutoComment = binding.switchNurtureComment.isChecked
        config.nurtureAutoFavorite = binding.switchNurtureFavorite.isChecked
        config.nurtureAutoShare = binding.switchNurtureShare.isChecked
        config.nurtureLikeRate = binding.etLikeRate.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 60
        config.nurtureCommentRate = binding.etCommentRate.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 20
        config.nurtureFavoriteRate = binding.etFavoriteRate.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 10
        config.nurtureShareRate = binding.etShareRate.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 5
        config.nurtureWatchMinSec = binding.etWatchMin.text.toString().toIntOrNull() ?: 5
        config.nurtureWatchMaxSec = binding.etWatchMax.text.toString().toIntOrNull() ?: 15

        PrefsManager.saveConfig(this, config)
    }
}
