package com.nous.wxhook.ui.settings

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.nous.wxhook.db.DbCleanup

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply { text = "设置"; textSize = 20f })

        val sb = StringBuilder()
        sb.appendLine(DbCleanup.getDiskInfo())
        sb.appendLine()
        sb.appendLine("功能:")
        sb.appendLine("  - 密钥管理: /data/local/tmp/.wechat_key")
        sb.appendLine("  - 数据库: /sdcard/Download/EnMicroMsg.db")
        sb.appendLine("  - 备份: 手动复制到其他目录")
        sb.appendLine("  - 清理: 自动删除旧副本（保留2份）")
        sb.appendLine()
        sb.appendLine("XP 模块:")
        sb.appendLine("  - hook setCipherKey 捕获密钥")
        sb.appendLine("  - 写入 .wechat_key + ContentProvider")

        layout.addView(TextView(this).apply {
            text = sb.toString()
            textSize = 12f
            setPadding(0, 32, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        })

        setContentView(layout)
    }
}
