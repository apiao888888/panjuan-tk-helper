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
import com.tiktokassist.model.TaskMode
import com.tiktokassist.ui.adapter.KeywordAdapter
import com.tiktokassist.utils.PrefsManager

class TaskConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskConfigBinding
    private var selectedMode: TaskMode = TaskMode.NURTURE_ACCOUNT
    private lateinit var commentKeywordAdapter: KeywordAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 自定义返回按钮
        binding.btnBack.setOnClickListener {
            saveConfig()
            finish()
        }

        setupCommentKeywordList()
        setupModeSpinner()
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

    private fun setupCommentKeywordList() {
        commentKeywordAdapter = KeywordAdapter(mutableListOf()) { keyword ->
            commentKeywordAdapter.removeKeyword(keyword)
            saveConfig()
        }
        binding.rvCommentKeywords.layoutManager = LinearLayoutManager(this)
        binding.rvCommentKeywords.adapter = commentKeywordAdapter
    }

    private fun updateTargetFieldVisibility() {
        val needsUsername = selectedMode in listOf(
            TaskMode.TARGET_FANS_FOLLOW,
            TaskMode.TARGET_FANS_DM
        )
        val needsVideoSearch = selectedMode in listOf(
            TaskMode.VIDEO_COMMENT_FOLLOW,
            TaskMode.VIDEO_COMMENT_DM,
            TaskMode.VIDEO_COMMENT_LIKE,
            TaskMode.VIDEO_COMMENT_REPLY
        )
        binding.layoutTargetUsername.visibility = if (needsUsername) View.VISIBLE else View.GONE
        binding.layoutVideoSearch.visibility = if (needsVideoSearch) View.VISIBLE else View.GONE

        // 养号功能才显示养号设置（连同标题一起显示/隐藏）
        val isNurture = selectedMode == TaskMode.NURTURE_ACCOUNT
        val nurtureVis = if (isNurture) View.VISIBLE else View.GONE
        binding.cardNurtureSettings.visibility = nurtureVis
        binding.headerNurture.visibility = nurtureVis
    }

    // ==================== 加载配置 ====================

    private fun loadConfig() {
        val config = PrefsManager.loadConfig(this)

        // 功能选择
        selectedMode = config.currentMode
        binding.spinnerMode.setSelection(config.currentMode.index - 1)
        updateTargetFieldVisibility()

        // 目标账号 / 视频搜索
        binding.etTargetUsername.setText(config.targetUsername)
        binding.etSearchKeyword.setText(config.searchKeyword)
        val kwList = commentKeywordAdapter.getKeywords()
        kwList.clear()
        kwList.addAll(config.commentMatchKeywords)
        commentKeywordAdapter.notifyDataSetChanged()

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

        binding.btnAddCommentKeyword.setOnClickListener {
            showAddKeywordDialog()
        }

        binding.btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "✅ 配置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddKeywordDialog() {
        val input = EditText(this).apply {
            hint = "例如：合作、咨询、怎么买"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("添加评论匹配关键词")
            .setMessage("评论内容包含该词时，才会对该用户关注/私信")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isNotEmpty()) {
                    commentKeywordAdapter.addKeyword(kw)
                    saveConfig()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateSuperDmVisibility(enabled: Boolean) {
        binding.layoutSuperDmCount.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    // ==================== 保存配置 ====================

    private fun saveConfig() {
        val config = PrefsManager.loadConfig(this)

        config.currentMode = selectedMode
        config.targetUsername = binding.etTargetUsername.text.toString().trim()
        config.searchKeyword = binding.etSearchKeyword.text.toString().trim()
        config.commentMatchKeywords = commentKeywordAdapter.getKeywords()

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
