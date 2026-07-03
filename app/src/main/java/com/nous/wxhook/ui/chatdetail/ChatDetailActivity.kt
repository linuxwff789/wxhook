package com.nous.wxhook.ui.chatdetail

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        layout.addView(TextView(this).apply { text = "聊天: $talker"; textSize = 20f })
        contentText = TextView(this).apply { text = "加载中..."; textSize = 12f; setPadding(0, 32, 0, 0) }
        layout.addView(contentText)
        scrollView.addView(layout)
        setContentView(scrollView)
        loadMessages()
    }

    private fun su(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }

    private fun loadMessages() {
        Thread {
            var key: String? = null
            try {
                val hex = File("/data/local/tmp/.wechat_key").readText()
                    .lines().find { it.startsWith("key=") }?.removePrefix("key=")
                if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            } catch (_: Exception) {}
            if (key == null) { handler.post { contentText.text = "未捕获密钥" }; return@Thread }

            val dbPath = "/sdcard/Download/EnMicroMsg.db"
            if (!File(dbPath).exists()) { handler.post { contentText.text = "数据库不存在" }; return@Thread }

            val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sql = "PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT type, content, createTime, isSend FROM message WHERE talker='$talker' ORDER BY createTime DESC LIMIT 200;"

            val sqlFile = "/sdcard/Download/q.sql"
            File(sqlFile).writeText(sql)
            su("chmod 666 $sqlFile")
            val output = su("/data/data/com.termux/files/usr/bin/sqlcipher $dbPath < $sqlFile 2>&1")
            su("rm -f $sqlFile")

            val sb = StringBuilder()
            val lines = output.lines().filter { it.contains("|") }
            sb.appendLine("共 ${lines.size} 条消息\n")
            for (line in lines.reversed()) {
                val parts = line.split("|")
                if (parts.size >= 4) {
                    val type = parts[0].trim().toIntOrNull() ?: 0
                    val content = parts[1].trim()
                    val timeMs = parts[2].trim().toLongOrNull() ?: 0
                    val isSend = parts[3].trim() == "1"
                    val time = timeFormat.format(Date(timeMs))
                    val dir = if (isSend) "→" else "←"
                    val typeDesc = when (type) { 1 -> "文本"; 3 -> "图片"; 34 -> "语音"; 43 -> "视频"; 49 -> "链接"; 10000 -> "系统"; else -> "type=$type" }
                    sb.appendLine("$time $dir [$typeDesc]")
                    sb.appendLine("  ${content.take(200)}")
                    sb.appendLine()
                }
            }
            if (lines.isEmpty()) sb.appendLine("无消息")
            handler.post { contentText.text = sb.toString() }
        }.start()
    }
}
