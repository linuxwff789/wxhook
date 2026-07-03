package com.nous.wxhook

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "wxhook 管理面板"
            textSize = 24f
        }
        layout.addView(title)

        val modules = listOf(
            "状态检测" to "com.nous.wxhook.ui.status.StatusActivity",
            "聊天列表" to "com.nous.wxhook.ui.chatlist.ChatListActivity",
            "搜索" to "com.nous.wxhook.ui.search.SearchActivity",
            "备份管理" to "com.nous.wxhook.ui.backup.BackupActivity",
            "数据合并" to "com.nous.wxhook.ui.merge.MergeActivity",
            "设置" to "com.nous.wxhook.ui.settings.SettingsActivity",
        )

        modules.forEach { (label, cls) ->
            val btn = Button(this).apply {
                text = label
                setOnClickListener {
                    try {
                        val clazz = Class.forName(cls)
                        startActivity(android.content.Intent(this@MainActivity, clazz))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this@MainActivity, "功能开发中", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            layout.addView(btn)
        }

        val version = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            textSize = 12f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(version)

        setContentView(layout)
    }
}
