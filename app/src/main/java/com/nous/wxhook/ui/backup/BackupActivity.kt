package com.nous.wxhook.ui.backup

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nous.wxhook.db.BackupManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var contentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply { text = "备份管理"; textSize = 20f })

        val createBtn = Button(this).apply {
            text = "创建备份"
            setOnClickListener { createBackup() }
        }
        layout.addView(createBtn)

        val refreshBtn = Button(this).apply {
            text = "刷新列表"
            setOnClickListener { loadBackups() }
        }
        layout.addView(refreshBtn)

        contentText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(contentText)

        scrollView.addView(layout)
        setContentView(scrollView)

        loadBackups()
    }

    private fun createBackup() {
        contentText.text = "创建中..."
        Thread {
            val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
            val key = prefs.getString("last_key", null) ?: "e9cd2ae"

            val result = BackupManager.createBackup(this, key, "manual backup")
            handler.post {
                if (result != null) {
                    contentText.text = "备份创建成功\n路径: ${result.path}\n大小: ${result.fileSize} bytes"
                    loadBackups()
                } else {
                    contentText.text = "备份创建失败（需要 root）"
                }
            }
        }.start()
    }

    private fun loadBackups() {
        Thread {
            val backups = BackupManager.listBackups(this)
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            val sb = StringBuilder()
            sb.appendLine("共 ${backups.size} 个备份\n")

            for (backup in backups) {
                val time = timeFormat.format(Date(backup.timestamp))
                sb.appendLine("📦 $time")
                sb.appendLine("   大小: ${backup.fileSize / 1024} KB")
                sb.appendLine("   路径: ${backup.path}")
                if (backup.notes != null) sb.appendLine("   备注: ${backup.notes}")
                sb.appendLine()
            }

            if (backups.isEmpty()) {
                sb.appendLine("暂无备份")
            }

            handler.post { contentText.text = sb.toString() }
        }.start()
    }
}
