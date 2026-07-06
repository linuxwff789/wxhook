package com.nous.wxhook.ui.module

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nous.wxhook.db.BackupManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ModuleActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var backupBtn: Button
    private lateinit var incrBtn: Button
    private lateinit var pathInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var isBackingUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xFFF5F5F5.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "wxhook 模块"
            textSize = 22f; setTextColor(0xFF6200EE.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16))
        })

        // ── Status card ──
        root.addView(makeCardTitle("📊 状态"))
        val statusCard = makeCard()
        val statusText = TextView(this).apply { textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8)); typeface = Typeface.MONOSPACE }
        statusText.text = getStatusText()
        statusCard.addView(statusText)
        root.addView(statusCard)

        // ── Backup path ──
        root.addView(makeCardTitle("📁 备份路径"))
        val pathCard = makeCard()
        pathInput = EditText(this).apply {
            setText(BackupManager.BACKUP_DIR)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        pathCard.addView(pathInput)
        val savePathBtn = Button(this).apply {
            text = "保存路径"; textSize = 12f
            setOnClickListener {
                val path = pathInput.text.toString().trim()
                if (path.isNotEmpty()) {
                    File(path).mkdirs()
                    log("📁 路径已保存: $path")
                }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        pathCard.addView(savePathBtn)
        root.addView(pathCard)

        // ── Backup card ──
        root.addView(makeCardTitle("💾 备份"))
        val backupCard = makeCard()

        // 压缩开关
        val compressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        compressRow.addView(TextView(this).apply { text = "压缩备份 (gzip)"; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val compressSwitch = Switch(this).apply { isChecked = true }
        compressRow.addView(compressSwitch)
        backupCard.addView(compressRow)

        backupBtn = Button(this).apply {
            text = "全量备份 (DB + 附件)"
            setOnClickListener { doBackup(false, compressSwitch.isChecked) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) }
        }
        backupCard.addView(backupBtn)

        incrBtn = Button(this).apply {
            text = "增量备份 (仅新文件)"
            setOnClickListener { doBackup(true, compressSwitch.isChecked) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        backupCard.addView(incrBtn)
        root.addView(backupCard)

        // ── Records card ──
        root.addView(makeCardTitle("📋 备份记录"))
        val recordsCard = makeCard()
        logView = TextView(this).apply {
            textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(0xFF424242.toInt())
        }
        recordsCard.addView(logView)
        root.addView(recordsCard)

        // Load records
        refreshRecords()

        sv.addView(root)
        setContentView(sv)
    }

    private fun doBackup(incremental: Boolean, compress: Boolean = true) {
        if (isBackingUp) { log("⏳ 正在备份中..."); return }
        isBackingUp = true
        backupBtn.isEnabled = false; incrBtn.isEnabled = false
        backupBtn.text = "备份中..."; incrBtn.text = "备份中..."

        Thread {
            val result = if (incremental) {
                BackupHookLocal.doIncrementalBackup(pathInput.text.toString().trim())
            } else {
                BackupHookLocal.doFullBackup(pathInput.text.toString().trim())
            }

            handler.post {
                if (result.success) {
                    val sizeStr = "备份完成"
                    log("✅ ${result.message} ($sizeStr)")
                } else {
                    log("❌ ${result.message}")
                }
                refreshRecords()
                isBackingUp = false
                backupBtn.isEnabled = true; incrBtn.isEnabled = true
                backupBtn.text = "全量备份 (DB + 附件)"; incrBtn.text = "增量备份 (仅新文件)"
            }
        }.start()
    }

    private fun refreshRecords() {
        val records = BackupManager.getRecords()
        if (records.isEmpty()) {
            logView.text = "暂无备份记录"
            return
        }
        val sb = StringBuilder()
        records.take(10).forEach { r ->
            val time = BackupManager.formatTime(r.time)
            val size = BackupManager.formatSize(r.totalSize)
            val type = if (r.type == "full") "全量" else "增量"
            sb.appendLine("[$time] $type | $size | ${r.fileCount}文件")
            sb.appendLine("  ${r.message}")
        }
        logView.text = sb.toString()
    }

    private fun getStatusText(): String {
        val sb = StringBuilder()
        // Key
        try {
            val keyFile = File("/data/local/tmp/.wechat_key")
            if (keyFile.exists()) {
                val key = keyFile.readText().lines().find { it.startsWith("key=") } ?: "未知"
                sb.appendLine("  密钥: $key")
            } else {
                sb.appendLine("  密钥: 未捕获")
            }
        } catch (_: Exception) { sb.appendLine("  密钥: 读取失败") }

        // DB
        val dbFile = File("/sdcard/Download/EnMicroMsg.db")
        if (dbFile.exists()) {
            sb.appendLine("  数据库: ${BackupManager.formatSize(dbFile.length())}")
        } else {
            sb.appendLine("  数据库: 未复制")
        }

        // Backup info
        val info = BackupManager.getBackupInfo()
        sb.appendLine("  备份目录: ${info.optString("backupDir", "无")}")
        sb.appendLine("  备份文件: ${info.optInt("fileCount", 0)}个")
        sb.appendLine("  最后备份: ${BackupManager.formatTime(info.optLong("lastBackupTime", 0))}")

        return sb.toString()
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.text = "[$time] $msg\n${logView.text}"
    }

    private fun makeCardTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(0xFF424242.toInt())
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(6))
        }
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) }
            val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE); setStroke(1, 0xFFE0E0E0.toInt()) }
            background = bg
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
