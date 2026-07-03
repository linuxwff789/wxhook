package com.nous.wxhook.ui.settings

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nous.wxhook.db.DbCleanup
import java.io.File

class SettingsActivity : Activity() {

    private lateinit var contentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }

        layout.addView(TextView(this).apply { text = "设置"; textSize = 20f })

        // 防撤回开关
        val prefs = getSharedPreferences("wxhook", Context.MODE_PRIVATE)
        val antiRecallEnabled = prefs.getBoolean("anti_recall", true)
        val recallCb = CheckBox(this).apply {
            text = "防撤回"
            isChecked = antiRecallEnabled
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("anti_recall", checked).apply()
                Toast.makeText(this@SettingsActivity, if (checked) "防撤回已开启" else "防撤回已关闭", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(recallCb)

        // 密钥信息
        layout.addView(TextView(this).apply { text = "\n密钥信息"; textSize = 14f; setPadding(0, 32, 0, 0) })
        contentText = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(16, 8, 0, 0) }
        layout.addView(contentText)

        // 手动清理按钮
        val cleanBtn = Button(this).apply {
            text = "清理旧数据库副本"
            setOnClickListener {
                val deleted = DbCleanup.cleanOldCopies()
                Toast.makeText(this@SettingsActivity, "清理了 $deleted 个文件", Toast.LENGTH_SHORT).show()
                refreshInfo()
            }
        }
        layout.addView(cleanBtn)

        // 刷新按钮
        val refreshBtn = Button(this).apply {
            text = "刷新"
            setOnClickListener { refreshInfo() }
        }
        layout.addView(refreshBtn)

        scrollView.addView(layout)
        setContentView(scrollView)
        refreshInfo()
    }

    private fun refreshInfo() {
        val sb = StringBuilder()
        // 密钥
        try {
            val keyFile = File("/data/local/tmp/.wechat_key")
            if (keyFile.exists()) {
                val content = keyFile.readText()
                val keyLine = content.lines().find { it.startsWith("key=") }
                sb.appendLine("  密钥: $keyLine")
                sb.appendLine("  时间: ${content.lines().find { it.startsWith("time=") } ?: "未知"}")
            } else {
                sb.appendLine("  密钥: 未捕获")
            }
        } catch (_: Exception) {
            sb.appendLine("  密钥: 读取失败")
        }
        sb.appendLine()
        // 磁盘信息
        sb.appendLine(DbCleanup.getDiskInfo())
        sb.appendLine()
        // 数据库状态
        val dbFile = File("/sdcard/Download/EnMicroMsg.db")
        sb.appendLine("数据库:")
        if (dbFile.exists()) {
            val sizeMB = dbFile.length() / 1024 / 1024
            sb.appendLine("  路径: /sdcard/Download/EnMicroMsg.db")
            sb.appendLine("  大小: ${sizeMB}MB")
        } else {
            sb.appendLine("  未复制")
        }
        sb.appendLine()
        sb.appendLine("路径:")
        sb.appendLine("  密钥: /data/local/tmp/.wechat_key")
        sb.appendLine("  数据库: /sdcard/Download/EnMicroMsg.db")
        sb.appendLine("  备份: app外部存储/wxhook_backups/")
        contentText.text = sb.toString()
    }
}