package com.nous.wxhook.ui.merge

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nous.wxhook.db.BackupManager
import com.nous.wxhook.db.MergeEngine
import java.io.File

class MergeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var contentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply { text = "数据合并"; textSize = 20f })

        layout.addView(Button(this).apply {
            text = "合并最近两个备份"
            setOnClickListener { mergeRecent() }
        })

        contentText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(contentText)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun mergeRecent() {
        contentText.text = "合并中..."
        Thread {
            val backups = BackupManager.listBackups(this)
            if (backups.size < 2) {
                handler.post { contentText.text = "需要至少 2 个备份才能合并" }
                return@Thread
            }

            val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
            val key = prefs.getString("last_key", null) ?: "e9cd2ae"

            val base = backups[0]
            val overlay = backups[1]

            val baseDb = File(base.path, "EnMicroMsg.db").absolutePath
            val overlayDb = File(overlay.path, "EnMicroMsg.db").absolutePath
            val outputDb = File(base.path, "EnMicroMsg_merged.db").absolutePath

            contentText.text = "合并中（命令行执行，可能需几秒）..."
            val result = MergeEngine.mergeDatabases(
                baseDbPath = baseDb,
                overlayDbPath = overlayDb,
                outputPath = outputDb,
                config = MergeEngine.MergeConfig(
                    strategy = MergeEngine.MergeStrategy.NEWEST_WINS,
                    key = key
                )
            )

            handler.post {
                val sb = StringBuilder()
                sb.appendLine("合并结果:")
                sb.appendLine("策略: NEWEST_WINS (同msgSvrID取最新)")
                sb.appendLine("总消息数: ${result.totalMessages}")
                sb.appendLine("新增插入: ${result.mergedMessages}")
                sb.appendLine("去重跳过: ${result.duplicatesRemoved}")
                if (result.conflicts.isNotEmpty()) {
                    sb.appendLine("冲突: ${result.conflicts.size} 条")
                }
                sb.appendLine("输出: ${result.outputPath}")
                contentText.text = sb.toString()
            }
        }.start()
    }
}