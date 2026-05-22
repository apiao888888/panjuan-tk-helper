package com.tiktokassist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tiktokassist.R
import com.tiktokassist.databinding.ActivityKeygenBinding
import com.tiktokassist.utils.LicenseManager

class KeygenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeygenBinding

    // 有效期选项
    private val validityOptions = listOf(
        "7天（试用）"    to 7,
        "30天（1个月）"  to 30,
        "90天（3个月）"  to 90,
        "180天（6个月）" to 180,
        "365天（1年）"   to 365,
        "永久授权"        to 365 * 50
    )
    private var selectedDays = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeygenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "🔑 判官TK 注册机"
            setDisplayHomeAsUpEnabled(true)
        }

        // 显示本机设备ID供参考
        val localDeviceId = LicenseManager.getDeviceId(this)
        binding.tvLocalDeviceId.text = "本机设备码：$localDeviceId"

        setupValiditySpinner()
        setupButtons()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun setupValiditySpinner() {
        val names = validityOptions.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerValidity.adapter = adapter
        binding.spinnerValidity.setSelection(1) // 默认30天
        binding.spinnerValidity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedDays = validityOptions[pos].second
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupButtons() {
        // 生成授权码
        binding.btnGenerate.setOnClickListener {
            val inputDeviceId = binding.etTargetDeviceId.text.toString().trim().uppercase()
            if (inputDeviceId.isEmpty()) {
                Toast.makeText(this, "请输入目标设备码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (inputDeviceId.length < 8) {
                Toast.makeText(this, "设备码格式不正确（至少8位）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val key = LicenseManager.generateKey(inputDeviceId, selectedDays)
            binding.tvGeneratedKey.text = key
            binding.cardResult.visibility = View.VISIBLE

            // 计算到期日
            val expiryEpoch = (System.currentTimeMillis() / 86400000L).toInt() + selectedDays
            val expiryDate = LicenseManager.epochDaysToDateString(expiryEpoch)
            val validDesc = LicenseManager.validDaysDescription(selectedDays)
            binding.tvKeyInfo.text = "设备码：$inputDeviceId\n有效期：$validDesc\n到期日：$expiryDate"
        }

        // 复制授权码
        binding.btnCopyKey.setOnClickListener {
            val key = binding.tvGeneratedKey.text.toString()
            if (key.isEmpty() || key == "-") return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("License Key", key))
            Toast.makeText(this, "✅ 授权码已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        // 使用本机设备ID快速填入
        binding.btnUseLocalDevice.setOnClickListener {
            binding.etTargetDeviceId.setText(LicenseManager.getDeviceId(this))
        }

        // 验证授权码（测试用）
        binding.btnVerify.setOnClickListener {
            val key = binding.tvGeneratedKey.text.toString()
            if (key.isEmpty()) return@setOnClickListener
            val info = LicenseManager.validateKey(this, key)
            val msg = when (info.result) {
                LicenseManager.LicenseResult.VALID ->
                    "✅ 有效授权\n到期：${LicenseManager.epochDaysToDateString(info.expiryEpochDays)}\n剩余：${info.daysRemaining}天"
                LicenseManager.LicenseResult.INVALID_DEVICE ->
                    "⚠️ 设备不匹配\n（此码是给其他设备生成的，当前验证使用本机设备ID）"
                LicenseManager.LicenseResult.EXPIRED ->
                    "❌ 已过期"
                else ->
                    "❌ 无效（${info.result}）"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("验证结果")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show()
        }
    }
}
