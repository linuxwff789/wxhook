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

        Thread {
            try {
                val pid = runSu("pidof com.tencent.mm")
                if (pid.isEmpty()) {
                    handler.post { sb.appendLine("微信未运行"); statusText.text = sb.toString() }
                    return@Thread
                }

                val srcDb = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
                val localDb = "/sdcard/Download/EnMicroMsg.db"

                val size = runSu("stat -c %s $srcDb 2>/dev/null")
                if (size.isEmpty()) {
                    handler.post { sb.appendLine("数据库: ✗ 不存在"); statusText.text = sb.toString() }
                    return@Thread
                }

                sb.appendLine("数据库: ✓ ${size.toLong() / 1024 / 1024} MB")

                // Copy if needed
                val localSize = runSu("stat -c %s $localDb 2>/dev/null")
                if (localSize != size) {
                    sb.appendLine("复制到 /sdcard/Download...")
                    handler.post { statusText.text = sb.toString() }
                    runSu("cp $srcDb $localDb && chmod 666 $localDb")
                    sb.appendLine("复制完成")
                } else {
                    sb.appendLine("使用本地副本")
                }

                sb.appendLine("解密中...")
                handler.post { statusText.text = sb.toString() }

                // Decrypt via su + sqlcipher
                val sql = """PRAGMA key = 'e9cd2ae';
PRAGMA cipher_compatibility = 3;
PRAGMA cipher_page_size = 1024;
PRAGMA kdf_iter = 4000;
PRAGMA cipher_use_hmac = OFF;
SELECT 'message: ' || count(*) FROM message;
SELECT 'rconversation: ' || count(*) FROM rconversation;
SELECT 'chatroom: ' || count(*) FROM chatroom;"""

                val sqlFile = "/sdcard/Download/query.sql"
                runSu("echo '${sql.replace("'", "'\\''")}' > $sqlFile")
                val output = runSu("/data/data/com.termux/files/usr/bin/sqlcipher $localDb < $sqlFile 2>&1")
                runSu("rm $sqlFile")

                sb.appendLine("\n=== 解密统计 ===")
                output.lines().filter { it.contains(":") }.forEach { sb.appendLine(it.trim()) }

                handler.post { statusText.text = sb.toString() }
            } catch (e: Exception) {
                handler.post { sb.appendLine("错误: ${e.message}"); statusText.text = sb.toString() }
            }
        }.start()
    }
}
