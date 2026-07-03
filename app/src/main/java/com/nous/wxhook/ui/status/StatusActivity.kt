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

        // Try to read DB directly via Java (no su needed)
        Thread {
            try {
                // Find WeChat PID by reading /proc
                val procDir = File("/proc")
                var wechatPid: String? = null
                for (dir in procDir.listFiles() ?: emptyArray()) {
                    if (dir.isDirectory && dir.name.all { it.isDigit() }) {
                        try {
                            val bytes = File(dir, "cmdline").readBytes()
                            val cmdline = String(bytes).trim('\u0000')
                            if (cmdline == "com.tencent.mm") {
                                wechatPid = dir.name
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (wechatPid == null) { sb.appendLine("微信未运行"); post(sb); return@Thread }

                val dbPath = "/proc/$wechatPid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
                val dbFile = File(dbPath)

                if (!dbFile.exists()) { sb.appendLine("数据库: ✗ 不存在"); post(sb); return@Thread }
                sb.appendLine("数据库: ✓ ${dbFile.length() / 1024 / 1024} MB (PID=$wechatPid)")
                sb.appendLine("复制中 (dd)...")
                post(sb)

                // Copy to /sdcard using Java streams
                val dstFile = File("/sdcard/Download/EnMicroMsg.db")
                dbFile.inputStream().use { input ->
                    dstFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                }
                sb.appendLine("复制完成: ${dstFile.length() / 1024 / 1024} MB")

                // Decrypt via sqlcipher (need su for this)
                // Actually, just show the copy is done
                sb.appendLine("\n数据库已复制到 /sdcard/Download/EnMicroMsg.db")
                sb.appendLine("可在终端解密:")
                sb.appendLine("echo \"PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT count(*) FROM message;\" | sqlcipher /sdcard/Download/EnMicroMsg.db")
                post(sb)
            } catch (e: Exception) {
                sb.appendLine("错误: ${e.message}")
                post(sb)
            }
        }.start()
    }

    private fun post(sb: StringBuilder) { handler.post { statusText.text = sb.toString() } }
}
