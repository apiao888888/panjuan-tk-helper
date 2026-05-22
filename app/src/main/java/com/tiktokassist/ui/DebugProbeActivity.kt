package com.tiktokassist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tiktokassist.databinding.ActivityDebugProbeBinding
import com.tiktokassist.service.TikTokAccessibilityService
import java.io.File

class DebugProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugProbeBinding
    private var lastDumpText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "🔍 元素探针（调试用）"
            setDisplayHomeAsUpEnabled(true)
        }

        binding.tvHint.text = """
            🎯 操作步骤：
            
            ① 先打开 TikTok，停留在你想分析的页面
               （例如：评论区、用户主页、粉丝列表）
            
            ② 切回本应用，点击下面的「📸 抓取界面」按钮
               系统会暂留3秒等你切换回 TikTok
            
            ③ 抓取结果会显示所有可点击/可编辑元素的
               真实 viewId / contentDescription / 坐标
            
            ④ 把结果发给开发者，即可定位真实元素
        """.trimIndent()

        binding.btnQuickDump.setOnClickListener { quickDump() }
        binding.btnDelayedDump.setOnClickListener { delayedDump() }
        binding.btnCopy.setOnClickListener { copyResult() }
        binding.btnSaveFile.setOnClickListener { saveToFile() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** 立即抓取（用于抓取本App内部界面） */
    private fun quickDump() {
        val service = TikTokAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "无障碍服务未连接，请先开启", Toast.LENGTH_LONG).show()
            return
        }
        val result = service.dumpUiSummary()
        showResult(result)
    }

    /** 延迟3秒抓取（让用户先切换到 TikTok） */
    private fun delayedDump() {
        val service = TikTokAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "无障碍服务未连接，请先开启", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnDelayedDump.isEnabled = false
        val startTime = 3
        binding.tvResult.text = "⏳ ${startTime}秒后抓取界面，请切换到 TikTok ..."

        var counter = startTime
        val handler = android.os.Handler(mainLooper)
        val task = object : Runnable {
            override fun run() {
                counter--
                if (counter > 0) {
                    binding.tvResult.text = "⏳ ${counter}秒后抓取界面，请切换到 TikTok ..."
                    handler.postDelayed(this, 1000)
                } else {
                    // 抓取
                    val detailed = service.dumpCurrentUi()
                    val summary = service.dumpUiSummary()
                    lastDumpText = "$summary\n\n=== 详细节点树 ===\n\n$detailed"
                    binding.tvResult.text = lastDumpText
                    binding.btnDelayedDump.isEnabled = true
                    binding.btnCopy.isEnabled = true
                    binding.btnSaveFile.isEnabled = true
                    Toast.makeText(this@DebugProbeActivity, "✅ 抓取完成", Toast.LENGTH_SHORT).show()
                }
            }
        }
        handler.postDelayed(task, 1000)

        // 自动切回 TikTok
        try {
            val tiktokPkgs = listOf(
                "com.zhiliaoapp.musically",
                "com.ss.android.ugc.trill",
                "com.tiktok.musically"
            )
            for (pkg in tiktokPkgs) {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) { startActivity(intent); break }
            }
        } catch (e: Exception) { /* 忽略，用户手动切 */ }
    }

    private fun showResult(text: String) {
        lastDumpText = text
        binding.tvResult.text = text
        binding.btnCopy.isEnabled = true
        binding.btnSaveFile.isEnabled = true
    }

    private fun copyResult() {
        if (lastDumpText.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UI Dump", lastDumpText))
        Toast.makeText(this, "✅ 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun saveToFile() {
        if (lastDumpText.isEmpty()) return
        try {
            val dir = File(getExternalFilesDir(null), "ui_dumps")
            if (!dir.exists()) dir.mkdirs()
            val fileName = "tiktok_dump_${System.currentTimeMillis()}.txt"
            val file = File(dir, fileName)
            file.writeText(lastDumpText)
            Toast.makeText(this, "✅ 已保存：${file.absolutePath}", Toast.LENGTH_LONG).show()

            // 分享出去
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享元素抓取结果"))
            } catch (e: Exception) {
                // 没配 FileProvider 不分享也行
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
