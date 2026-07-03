package com.nous.wxhook.ui.chatlist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.nous.wxhook.ui.chatdetail.ChatDetailActivity
import java.io.File
import java.util.UUID

class ChatListActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var headerText: TextView
    private lateinit var listView: ListView
    private data class Conversation(val username: String, val nickname: String, val unread: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        headerText = TextView(this).apply { text = "加载中..."; textSize = 16f }
        layout.addView(headerText)
        listView = ListView(this)
        layout.addView(listView)
        setContentView(layout)
        loadConversations()
    }

    private fun su(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }

    private fun loadConversations() {
        Thread {
            var key: String? = null
            try {
                val hex = File("/data/local/tmp/.wechat_key").readText()
                    .lines().find { it.startsWith("key=") }?.removePrefix("key=")
                if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            } catch (_: Exception) {}
            if (key == null) { handler.post { headerText.text = "未捕获密钥" }
                return@Thread }

            val dbPath = "/sdcard/Download/EnMicroMsg.db"
            if (!File(dbPath).exists()) { handler.post { headerText.text = "数据库不存在，请先在状态页复制" }
                return@Thread }

            val sqlFile = "/data/data/com.nous.wxhook/cache/q_${UUID.randomUUID()}.sql"
            try {
                File(sqlFile).writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT rconversation_username, rconversation_nickname, rconversation_unReadCount FROM rconversation ORDER BY rconversation_time DESC LIMIT 200;")
                su("chmod 666 $sqlFile")
                val output = su("/data/data/com.termux/files/usr/bin/sqlcipher $dbPath < $sqlFile 2>&1")
                su("rm -f $sqlFile")

                val conversations = mutableListOf<Conversation>()
                for (line in output.lines().filter { it.contains("|") }) {
                    val parts = line.split("|")
                    if (parts.size >= 2) {
                        conversations.add(Conversation(
                            username = parts[0].trim(),
                            nickname = parts[1].trim().ifEmpty { parts[0].trim() },
                            unread = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                        ))
                    }
                }

                handler.post {
                    headerText.text = "共 ${conversations.size} 个会话"
                    val labels = conversations.map { c ->
                        val unreadTag = if (c.unread > 0) " [$c.unread 未读]" else ""
                        "${c.nickname}$unreadTag"
                    }
                    val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                    listView.adapter = adapter
                    listView.setOnItemClickListener { _, _, position, _ ->
                        val conv = conversations[position]
                        val intent = Intent(this, ChatDetailActivity::class.java).apply {
                            putExtra("talker", conv.username)
                            putExtra("nickname", conv.nickname)
                        }
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                su("rm -f $sqlFile")
                handler.post { headerText.text = "错误: ${e.message}" }
            }
        }.start()
    }
}