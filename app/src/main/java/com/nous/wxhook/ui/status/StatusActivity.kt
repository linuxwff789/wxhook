package com.nous.wxhook.ui.status

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class StatusActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply { text = "wxhook"; textSize = 20f })
        statusText = TextView(this).apply { text = "..."; textSize = 14f; setPadding(0, 32, 0, 0) }
        layout.addView(statusText)
        setContentView(layout)
        handler.post { check() }
    }

    private fun su(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }

    private fun check() {
        val sb = StringBuilder()

        // Key
        var key: String? = null
        try {
            val hex = File("/data/local/tmp/.wechat_key").readText()
                .lines().find { it.startsWith("key=") }?.removePrefix("key=")
            if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        } catch (_: Exception) {}

        sb.appendLine("密钥: ${if (key != null) "✓ $key" else "✗ 未捕获"}")
        if (key == null) { statusText.text = sb.toString(); return }

        sb.appendLine()
        statusText.text = sb.toString()

        // Decrypt via named pipe (no copy needed)
        Thread {
            try {
                val pid = su("pidof com.tencent.mm")
                if (pid.isEmpty()) { sb.appendLine("微信未运行"); post(sb); return@Thread }

                val src = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
                val fifo = "/sdcard/Download/wxhook_db_fifo"

                sb.appendLine("连接数据库...")
                post(sb)

                // Create FIFO and start background reader
                su("rm -f $fifo; mkfifo $fifo; cat $src > $fifo &")

                // Run sqlcipher on FIFO
                val sql = "PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT '消息: '||count(*) FROM message;SELECT '会话: '||count(*) FROM rconversation;SELECT '群聊: '||count(*) FROM chatroom;"

                val sqlFile = "/sdcard/Download/q.sql"
                File(sqlFile).writeText(sql)
                su("chmod 666 $sqlFile")

                val out = su("/data/data/com.termux/files/usr/bin/sqlcipher $fifo < $sqlFile 2>&1")
                su("rm -f $sqlFile $fifo")

                sb.appendLine("\n=== 统计 ===")
                out.lines().filter { it.contains(":") }.forEach { sb.appendLine(it.trim()) }
                post(sb)
            } catch (e: Exception) {
                sb.appendLine("错误: ${e.message}")
                post(sb)
            }
        }.start()
    }

    private fun post(sb: StringBuilder) { handler.post { statusText.text = sb.toString() } }
}
