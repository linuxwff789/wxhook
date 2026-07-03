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

        layout.addView(TextView(this).apply {
            text = "wxhook 状态检测"
            textSize = 20f
        })

        statusText = TextView(this).apply {
            text = "检测中..."
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(statusText)

        setContentView(layout)
        handler.post { checkStatus() }
    }

    private fun runSu(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.trim()
        } catch (_: Exception) { "" }
    }

    private fun checkStatus() {
        val sb = StringBuilder()
        var lastKey: String? = null

        // Read key
        try {
            val hex = File("/data/local/tmp/.wechat_key").readText()
                .lines().find { it.startsWith("key=") }?.removePrefix("key=")
            if (hex != null) {
                lastKey = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            }
        } catch (_: Exception) {}

        sb.appendLine("=== XP 模块状态 ===")
        sb.appendLine(if (lastKey != null) "密钥: ✓ $lastKey" else "密钥: ✗ 未捕获")

        if (lastKey == null) {
            statusText.text = sb.toString()
            return
        }

        sb.appendLine("\n=== 数据库状态 ===")

        // Check local DB copy
        val localDb = "/sdcard/Download/EnMicroMsg.db"
        val localSize = runSu("stat -c %s $localDb 2>/dev/null")

        if (localSize.isEmpty()) {
            sb.appendLine("本地数据库: ✗ 不存在")
            sb.appendLine("请先运行: su -c 'cp /proc/\$(pidof com.tencent.mm)/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db /sdcard/Download/EnMicroMsg.db'")
            statusText.text = sb.toString()
            return
        }

        sb.appendLine("本地数据库: ✓ ${localSize.toLong() / 1024 / 1024} MB")
        sb.appendLine("解密中...")
        statusText.text = sb.toString()

        // Decrypt in background thread
        Thread {
            try {
                val sql = "PRAGMA key = 'e9cd2ae';\n" +
                    "PRAGMA cipher_compatibility = 3;\n" +
                    "PRAGMA cipher_page_size = 1024;\n" +
                    "PRAGMA kdf_iter = 4000;\n" +
                    "PRAGMA cipher_use_hmac = OFF;\n" +
                    "SELECT 'message: ' || count(*) FROM message;\n" +
                    "SELECT 'rconversation: ' || count(*) FROM rconversation;\n" +
                    "SELECT 'chatroom: ' || count(*) FROM chatroom;\n"

                val sqlFile = "/sdcard/Download/wxhook_query.sql"
                File(sqlFile).writeText(sql)
                runSu("chmod 666 $sqlFile")

                val output = runSu("/data/data/com.termux/files/usr/bin/sqlcipher $localDb < $sqlFile 2>&1")
                runSu("rm $sqlFile")

                handler.post {
                    sb.appendLine("\n=== 解密统计 ===")
                    output.lines().filter { it.contains(":") }.forEach { sb.appendLine(it.trim()) }
                    statusText.text = sb.toString()
                }
            } catch (e: Exception) {
                handler.post {
                    sb.appendLine("错误: ${e.message}")
                    statusText.text = sb.toString()
                }
            }
        }.start()
    }
}
