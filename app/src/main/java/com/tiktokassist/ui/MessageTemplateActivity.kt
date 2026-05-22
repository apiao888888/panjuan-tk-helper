package com.tiktokassist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tiktokassist.R
import com.tiktokassist.databinding.ActivityMessageTemplateBinding
import com.tiktokassist.databinding.ItemTemplateBinding
import com.tiktokassist.utils.PrefsManager

class MessageTemplateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessageTemplateBinding
    private lateinit var dmAdapter: TemplateAdapter
    private lateinit var commentAdapter: TemplateAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageTemplateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "话术管理"
            setDisplayHomeAsUpEnabled(true)
        }

        loadTemplates()
        setupRecyclerViews()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        saveTemplates()
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        saveTemplates()
    }

    private fun loadTemplates() {
        val config = PrefsManager.loadConfig(this)
        dmAdapter = TemplateAdapter(config.dmTemplates.toMutableList())
        commentAdapter = TemplateAdapter(config.commentTemplates.toMutableList())
    }

    private fun setupRecyclerViews() {
        binding.rvDmTemplates.apply {
            layoutManager = LinearLayoutManager(this@MessageTemplateActivity)
            adapter = dmAdapter
        }

        binding.rvCommentTemplates.apply {
            layoutManager = LinearLayoutManager(this@MessageTemplateActivity)
            adapter = commentAdapter
        }
    }

    private fun setupListeners() {
        binding.btnAddDmTemplate.setOnClickListener {
            showAddTemplateDialog(isDm = true)
        }

        binding.btnAddCommentTemplate.setOnClickListener {
            showAddTemplateDialog(isDm = false)
        }
    }

    private fun showAddTemplateDialog(isDm: Boolean) {
        val input = EditText(this).apply {
            hint = if (isDm) "输入私信话术内容..." else "输入评论话术内容..."
            minLines = 3
            maxLines = 6
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isDm) "添加私信话术" else "添加评论话术")
            .setMessage(if (isDm) "设置发送给目标用户的私信模板（系统会随机选择一条发送）"
                       else "设置自动发表的评论模板（系统会随机选择一条发表）")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (isDm) dmAdapter.addTemplate(text)
                    else commentAdapter.addTemplate(text)
                    saveTemplates()
                    Toast.makeText(this, "话术已添加", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "话术内容不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveTemplates() {
        val config = PrefsManager.loadConfig(this)
        config.dmTemplates = dmAdapter.getTemplates()
        config.commentTemplates = commentAdapter.getTemplates()
        PrefsManager.saveConfig(this, config)
        Toast.makeText(this, "话术已保存", Toast.LENGTH_SHORT).show()
    }

    // ==================== RecyclerView Adapter ====================

    inner class TemplateAdapter(
        private val templates: MutableList<String>
    ) : RecyclerView.Adapter<TemplateAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemTemplateBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemTemplateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val template = templates[position]
            holder.binding.tvTemplateContent.text = template
            holder.binding.tvTemplateIndex.text = "#${position + 1}"

            holder.binding.btnEdit.setOnClickListener {
                showEditDialog(position)
            }

            holder.binding.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@MessageTemplateActivity)
                    .setTitle("删除话术")
                    .setMessage("确认删除这条话术？")
                    .setPositiveButton("删除") { _, _ ->
                        templates.removeAt(position)
                        notifyItemRemoved(position)
                        notifyItemRangeChanged(position, templates.size)
                        saveTemplates()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        override fun getItemCount() = templates.size

        fun addTemplate(text: String) {
            templates.add(text)
            notifyItemInserted(templates.size - 1)
        }

        fun getTemplates(): MutableList<String> = templates

        private fun showEditDialog(position: Int) {
            val input = EditText(this@MessageTemplateActivity).apply {
                setText(templates[position])
                minLines = 3
                maxLines = 6
                setPadding(40, 20, 40, 20)
            }
            AlertDialog.Builder(this@MessageTemplateActivity)
                .setTitle("编辑话术")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty()) {
                        templates[position] = text
                        notifyItemChanged(position)
                        saveTemplates()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
