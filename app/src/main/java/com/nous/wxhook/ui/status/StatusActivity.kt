package com.nous.wxhook.ui.status

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class StatusActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply { text = "wxhook"; textSize = 20f })

        val sb = StringBuilder()

        // Key status
        var key: String? = null
        try {
            val hex = File("/data/local/tmp/.wechat_key").readText()
                .lines().find { it.startsWith("key=") }?.removePrefix("key=")
            if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        } catch (_: Exception) {}

        sb.appendLine("密钥: ${if (key != null) "✓ $key" else "✗ 未捕获"}")
        sb.appendLine()

        // DB status
        val dbPath = "/sdcard/Download/EnMicroMsg.db"
        val dbExists = File(dbPath).exists()
        sb.appendLine("数据库副本: ${if (dbExists) "✓" else "✗ (运行: su -c 'cp /proc/\$(pidof com.tencent.mm)/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db /sdcard/Download/EnMicroMsg.db')"}")

        if (dbExists && key != null) {
            sb.appendLine()
            sb.appendLine("解密命令:")
            sb.appendLine("echo \"PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT count(*) FROM message;\" | sqlcipher /sdcard/Download/EnMicroMsg.db")
        }

        layout.addView(TextView(this).apply {
            text = sb.toString()
            textSize = 14f
            setPadding(0, 32, 0, 0)
            setOnLongClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("sql", sb.toString()))
                true
            }
        })

        setContentView(layout)
    }
}
