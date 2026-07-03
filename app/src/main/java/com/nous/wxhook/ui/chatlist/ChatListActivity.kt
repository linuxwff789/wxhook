package com.nous.wxhook.ui.chatlist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nous.wxhook.ui.chatdetail.ChatDetailActivity
import java.io.File

class ChatListActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var contentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply { text = "聊天列表"; textSize = 20f })

        contentText = TextView(this).apply {
            text = "加载中..."
            textSize = 12f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(contentText)

        scrollView.addView(layout)
        setContentView(scrollView)

        loadConversations()
    }

    private fun su(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }

    private fun loadConversations() {
        Thread {
            // Read key
            var key: String? = null
            try {
                val hex = File("/data/local/tmp/.wechat_key").readText()
                    .lines().find { it.startsWith("key=") }?.removePrefix("key=")
                if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            } catch (_: Exception) {}

            if (key == null) {
                handler.post { contentText.text = "未捕获密钥" }
                return@Thread
            }

            val dbPath = "/sdcard/Download/EnMicroMsg.db"
            if (!File(dbPath).exists()) {
                handler.post { contentText.text = "数据库不存在，请先复制" }
                return@Thread
            }

            // Query via sqlcipher CLI
            val sql = "PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT rconversation_username, rconversation_nickname, rconversation_unReadCount FROM rconversation ORDER BY rconversation_time DESC LIMIT 100;"

            val sqlFile = "/sdcard/Download/q.sql"
            File(sqlFile).writeText(sql)
            su("chmod 666 $sqlFile")

            val output = su("/data/data/com.termux/files/usr/bin/sqlcipher $dbPath < $sqlFile 2>&1")
            su("rm -f $sqlFile")

            val sb = StringBuilder()
            val lines = output.lines().filter { it.contains("|") }

            sb.appendLine("共 ${lines.size} 个会话\n")

            for (line in lines) {
                val parts = line.split("|")
                if (parts.size >= 2) {
                    val username = parts[0].trim()
                    val nickname = parts[1].trim()
                    val unread = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0

                    sb.appendLine("$nickname ($username)")
                    if (unread > 0) sb.appendLine("  未读: $unread")
                }
            }

            if (lines.isEmpty()) {
                sb.appendLine("无会话数据")
            }

            handler.post { contentText.text = sb.toString() }
        }.start()
    }
}
