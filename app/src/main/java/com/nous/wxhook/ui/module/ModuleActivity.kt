package com.nous.wxhook.ui.module

import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ModuleActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var backupBtn: Button
    private lateinit var scheduleSwitch: SwitchMaterial
    private lateinit var intervalText: EditText
    private lateinit var logView: TextView
    private var isBackingUp = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val vert = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(24, 24, 24, 24)
        }
        root.addView(vert)
        setContentView(root)
        supportActionBar?.title = "wxhook 模块"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // ── Status card ──
        vert.addView(makeTitle("📊 状态信息"))
        statusText = TextView(this).apply { textSize = 14f; setTextColor(0xDE000000.toInt()); setPadding(0, 8, 0, 16) }
        vert.addView(statusText)

        // ── Backup card ──
        vert.addView(makeTitle("💾 备份"))
        val backupDesc = TextView(this).apply {
            text = "全量备份: DB + 图片/语音/视频/附件\n增量备份: 仅新文件"
            textSize = 13f; setTextColor(0x8A000000.toInt())
        }
        vert.addView(backupDesc)
        backupBtn = Button(this).apply {
            text = "立即备份"; setOnClickListener { doBackup() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 12, 0, 24) }
        }
        vert.addView(backupBtn)

        // ── Scheduled backup card ──
        vert.addView(makeTitle("⏰ 定时备份"))
        val schedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
        schedRow.addView(TextView(this).apply { text = "启用定时备份"; textSize = 15f })
        scheduleSwitch = SwitchMaterial(this)
        schedRow.addView(scheduleSwitch)
        vert.addView(schedRow)
        val intervalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 24) }
        intervalRow.addView(TextView(this).apply { text = "间隔（分钟）: "; textSize = 14f })
        intervalText = EditText(this).apply {
            setText("60"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        intervalRow.addView(intervalText)
        vert.addView(intervalRow)

        // ── Log ──
        vert.addView(makeTitle("📋 日志"))
        logView = TextView(this).apply {
            textSize = 11f; setTextColor(0x8A000000.toInt()); setPadding(0, 8, 0, 48)
            text = "[${now()}] 模块就绪"
        }
        vert.addView(logView)

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun makeTitle(s: String) = TextView(this).apply {
        text = s; textSize = 16f; setTextColor(0xFF6200EE.toInt()); setPadding(0, 16, 0, 4)
    }

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun getDbSizeMB(): Long {
        val f = File("/sdcard/Download/EnMicroMsg.db")
        return if (f.exists()) f.length() / (1024 * 1024) else 0
    }

    private fun refreshStatus() {
        val pid = execCmd("pidof com.tencent.mm")
        val wpid = execCmd("pidof com.nous.wxhook")
        val db = File("/sdcard/Download/EnMicroMsg.db")
        val key = File("/data/local/tmp/.wechat_key")
        statusText.text = buildString {
            appendLine("📱 微信进程: ${if (pid.isNotBlank()) "运行中 (PID=$pid)" else "未运行"}")
            appendLine("🔧 wxhook进程: ${if (wpid.isNotBlank()) "运行中" else "未运行"}")
            appendLine("🔑 密钥文件: ${if (key.exists()) "✅ 存在" else "❌ 缺失"}")
            appendLine("🗄 数据库: ${if (db.exists()) "✅ ${db.length()/(1024*1024)}MB" else "❌ 不存在"}")
            appendLine("📅 最后备份: ${getLastBackup()}")
        }
    }

    private fun getLastBackup(): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = dir.listFiles { f -> f.name.startsWith("EnMicroMsg") && f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() } ?: return "无"
        if (files.isEmpty()) return "无"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(files.first().lastModified()))
    }

    private fun doBackup() {
        if (isBackingUp) { log("⏳ 正在备份中..."); return }
        isBackingUp = true; backupBtn.isEnabled = false; backupBtn.text = "备份中..."
        Thread {
            try {
                val src = "/sdcard/Download/EnMicroMsg.db"
                val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dst = "/sdcard/Download/EnMicroMsg_${tag}.db"
                val r = execCmd("cp '$src' '$dst' && echo ok")
                val size = File(dst).length() / (1024 * 1024)
                handler.post {
                    if (r.contains("ok")) {
                        log("✅ 备份完成: ${dst.split("/").last()} (${size}MB)")
                        refreshStatus()
                    } else log("❌ 备份失败: $r")
                }
            } catch (e: Exception) { handler.post { log("❌ 备份异常: ${e.message}") } }
            isBackingUp = false; handler.post { backupBtn.isEnabled = true; backupBtn.text = "立即备份" }
        }.start()
    }

    private fun log(msg: String) { logView.text = "[${now()}] $msg\n${logView.text}" }

    private fun execCmd(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }
    }
}