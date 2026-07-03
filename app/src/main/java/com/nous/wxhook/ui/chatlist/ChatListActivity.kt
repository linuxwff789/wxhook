package com.nous.wxhook.ui.chatlist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nous.wxhook.db.WeChatDbDecryptor
import com.nous.wxhook.ui.chatdetail.ChatDetailActivity
import java.io.File

class ChatListActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper()
    private lateinit var contentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "聊天列表"
            textSize = 20f
        }
        layout.addView(title)

        contentText = TextView(this).apply {
            text = "加载中..."
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(contentText)

        scrollView.addView(layout)
        setContentView(scrollView)

        loadConversations()
    }

    private fun loadConversations() {
        Thread {
            val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
            val key = prefs.getString("last_key", null)

            if (key == null) {
                handler.post { contentText.text = "未捕获密钥，请先重启微信" }
                return@Thread
            }

            val dbPath = "/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
            if (!File(dbPath).exists()) {
                handler.post { contentText.text = "数据库文件不存在" }
                return@Thread
            }

            try {
                val db = WeChatDbDecryptor.openDecryptedDb(dbPath)
                if (db == null) {
                    handler.post { contentText.text = "数据库解密失败" }
                    return@Thread
                }

                val conversations = WeChatDbDecryptor.queryConversations(db)
                val contacts = WeChatDbDecryptor.queryContacts(db)
                val contactMap = contacts.associate { it["username"] as String to it["nickname"] as? String ?: "未知" }

                val sb = StringBuilder()
                sb.appendLine("共 ${conversations.size} 个会话\n")

                for (conv in conversations.take(100)) {
                    val username = conv["username"] as String
                    val displayName = contactMap[username] ?: username
                    val unread = conv["unreadCount"] as Int

                    sb.appendLine("$displayName ($username)")
                    if (unread > 0) sb.appendLine("  未读: $unread")
                }

                db.close()
                handler.post { contentText.text = sb.toString() }
            } catch (e: Exception) {
                handler.post { contentText.text = "错误: ${e.message}" }
            }
        }.start()
    }
}
