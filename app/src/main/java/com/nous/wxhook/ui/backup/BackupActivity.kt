package com.nous.wxhook.ui.backup

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class BackupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply { text = "备份管理"; textSize = 20f })
        layout.addView(TextView(this).apply {
            text = """
                功能规划:
                - 手动创建数据库快照
                - 定时备份 (WorkManager)
                - 备份列表管理
                - 备份恢复
                - 备份导出

                待实现...
            """.trimIndent(); textSize = 14f; setPadding(0, 32, 0, 0)
        })
        setContentView(layout)
    }
}
