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

        val mergeBtn = Button(this).apply {
            text = "合并最近两个备份"
            setOnClickListener { mergeRecent() }
        }
        layout.addView(mergeBtn)

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

            val base = backups[0] // Latest
            val overlay = backups[1] // Second latest

            val result = MergeEngine.mergeDatabases(
                baseDbPath = base.path + "/EnMicroMsg.db",
                overlayDbPath = overlay.path + "/EnMicroMsg.db",
                outputPath = base.path + "/EnMicroMsg_merged.db",
                config = MergeEngine.MergeConfig(strategy = MergeEngine.MergeStrategy.NEWEST_WINS)
            )

            handler.post {
                val sb = StringBuilder()
                sb.appendLine("合并结果:")
                sb.appendLine("策略: NEWEST_WINS")
                sb.appendLine("源消息数: ${result.totalMessages}")
                sb.appendLine("插入消息数: ${result.mergedMessages}")
                sb.appendLine("去重数: ${result.duplicatesRemoved}")
                if (result.conflicts.isNotEmpty()) {
                    sb.appendLine("冲突: ${result.conflicts.size} 条")
                }
                sb.appendLine("输出: ${result.outputPath}")
                contentText.text = sb.toString()
            }
        }.start()
    }
}
