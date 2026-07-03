package com.nous.wxhook.ui.chatdetail

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nous.wxhook.db.MessageParser
import com.nous.wxhook.db.WeChatDbDecryptor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatDetailActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var contentText: TextView
    private var talker: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        talker = intent.getStringExtra("talker") ?: ""

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "聊天详情: $talker"
            textSize = 20f
        }
        layout.addView(title)

        contentText = TextView(this).apply {
            text = "加载中..."
            textSize = 12f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(contentText)

        scrollView.addView(layout)
        setContentView(scrollView)

        loadMessages()
    }

    private fun loadMessages() {
        Thread {
            val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
            val key = prefs.getString("last_key", null) ?: run {
                handler.post { contentText.text = "未捕获密钥" }
                return@Thread
            }

            val dbPath = "/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
            if (!File(dbPath).exists()) {
                handler.post { contentText.text = "数据库文件不存在" }
                return@Thread
            }

            try {
                val db = WeChatDbDecryptor.openDecryptedDb(dbPath) ?: run {
                    handler.post { contentText.text = "解密失败" }
                    return@Thread
                }

                val messages = WeChatDbDecryptor.queryMessages(db, talker, limit = 200)
                val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

                val sb = StringBuilder()
                sb.appendLine("共 ${messages.size} 条消息\n")

                for (msg in messages.reversed()) {
                    val time = timeFormat.format(Date(msg["createTime"] as Long))
                    val isSend = msg["isSend"] as Boolean
                    val type = msg["type"] as Int
                    val content = msg["content"] as? String

                    val parsed = MessageParser.parse(type, content)
                    val direction = if (isSend) "→" else "←"

                    sb.appendLine("$time $direction [${parsed.typeDesc}]")
                    if (parsed.title != null) {
                        sb.appendLine("  标题: ${parsed.title}")
                    }
                    if (parsed.content != null) {
                        sb.appendLine("  ${parsed.content.take(200)}")
                    }
                    sb.appendLine()
                }

                db.close()
                handler.post { contentText.text = sb.toString() }
            } catch (e: Exception) {
                handler.post { contentText.text = "错误: ${e.message}" }
            }
        }.start()
    }
}
