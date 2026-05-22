package com.tiktokassist.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tiktokassist.databinding.ActivityLicenseBinding
import com.tiktokassist.utils.LicenseManager

class LicenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicenseBinding
    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = LicenseManager.getDeviceId(this)

        // 检查是否已激活
        val info = LicenseManager.checkActivation(this)
        if (info.isValid) {
            goToMain()
            return
        }

        setupUI(info)
    }

    private fun setupUI(existingInfo: com.tiktokassist.utils.LicenseManager.LicenseInfo) {
        // 显示设备ID（供用户发给卖家）
        binding.tvDeviceId.text = deviceId
        binding.tvDeviceIdLabel.text = "您的设备码（发给卖家获取授权码）："

        // 复制设备ID
        binding.btnCopyDeviceId.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
            cm.setPrimaryClip(clip)
            Toast.makeText(this, "✅ 设备码已复制", Toast.LENGTH_SHORT).show()
        }

        // 如果已过期，显示过期信息
        if (existingInfo.result == LicenseManager.LicenseResult.EXPIRED) {
            val savedKey = LicenseManager.getSavedKey(this) ?: ""
            binding.etLicenseKey.setText(savedKey)
            binding.tvActivationStatus.text = "⚠️ 授权码已过期，请重新激活"
            binding.tvActivationStatus.setTextColor(0xFFFF9500.toInt())
            binding.tvActivationStatus.visibility = View.VISIBLE
        } else if (existingInfo.result == LicenseManager.LicenseResult.NOT_ACTIVATED) {
            binding.tvActivationStatus.text = "请输入授权码以激活判官TK助手"
            binding.tvActivationStatus.setTextColor(0xFF888888.toInt())
            binding.tvActivationStatus.visibility = View.VISIBLE
        }

        // 自动格式化输入（每5位加一个短横线）
        binding.etLicenseKey.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return
                isFormatting = true
                val clean = s.toString().replace("-", "").uppercase().take(20)
                val formatted = clean.chunked(5).joinToString("-")
                s.replace(0, s.length, formatted)
                isFormatting = false
            }
        })

        // 激活按钮
        binding.btnActivate.setOnClickListener {
            val key = binding.etLicenseKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "请输入授权码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activateLicense(key)
        }

        // 长按版本号进入注册机（隐藏入口）
        binding.tvVersion.setOnLongClickListener {
            showKeygenPasswordDialog()
            true
        }
    }

    private fun activateLicense(key: String) {
        binding.btnActivate.isEnabled = false
        binding.btnActivate.text = "验证中..."

        val info = LicenseManager.validateKey(this, key)

        when (info.result) {
            LicenseManager.LicenseResult.VALID -> {
                LicenseManager.saveActivation(this, key, info.expiryEpochDays)
                val expireStr = LicenseManager.epochDaysToDateString(info.expiryEpochDays)
                val remaining = LicenseManager.validDaysDescription(info.daysRemaining)
                showSuccessDialog(expireStr, remaining)
            }
            LicenseManager.LicenseResult.EXPIRED -> {
                showError("授权码已过期，请联系卖家续期")
            }
            LicenseManager.LicenseResult.INVALID_DEVICE -> {
                showError("设备不匹配！\n此授权码绑定在其他设备上，\n请联系卖家重新生成。")
            }
            LicenseManager.LicenseResult.INVALID_FORMAT -> {
                showError("授权码格式错误，请检查是否输入完整（共20位）")
            }
            LicenseManager.LicenseResult.INVALID_SIGNATURE -> {
                showError("授权码无效（签名错误），请联系卖家")
            }
            LicenseManager.LicenseResult.NOT_ACTIVATED -> {
                showError("授权码无效")
            }
        }

        binding.btnActivate.isEnabled = true
        binding.btnActivate.text = "激活"
    }

    private fun showSuccessDialog(expireDate: String, remaining: String) {
        AlertDialog.Builder(this)
            .setTitle("🎉 激活成功！")
            .setMessage("判官TK助手已成功激活\n\n有效期至：$expireDate\n剩余时长：$remaining\n\n感谢使用，祝引流顺利！")
            .setPositiveButton("开始使用") { _, _ -> goToMain() }
            .setCancelable(false)
            .show()
    }

    private fun showError(msg: String) {
        binding.tvActivationStatus.text = "❌ $msg"
        binding.tvActivationStatus.setTextColor(0xFFFF3B30.toInt())
        binding.tvActivationStatus.visibility = View.VISIBLE
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun showKeygenPasswordDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "管理员密码"
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("🔐 进入注册机")
            .setView(input)
            .setPositiveButton("确认") { _, _ ->
                val pwd = input.text.toString()
                if (pwd == "panjuan2026admin") {  // 管理员密码（可自行修改）
                    startActivity(Intent(this, KeygenActivity::class.java))
                } else {
                    Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
