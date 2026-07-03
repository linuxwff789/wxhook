package com.nous.wxhook.ui.status

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.nous.wxhook.db.DbCleanup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private fun findWeChatPid(): String? {
        // Method 1: Try reading /proc directly (works on most devices)
        try {
            val procDir = File("/proc")
            for (dir in procDir.listFiles() ?: emptyArray()) {
                if (dir.isDirectory && dir.name.all { it.isDigit() }) {
                    try {
                        val cmdlineFile = File(dir, "cmdline")
                        if (cmdlineFile.canRead()) {
                            val bytes = cmdlineFile.readBytes()
                            val cmdline = String(bytes).trim('\u0000')
                            if (cmdline == "com.tencent.mm") return dir.name
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // Method 2: Check common PIDs (fallback)
        try {
            val commonPids = listOf("1", "2", "3") + (1000..50000).map { it.toString() }
            for (pid in commonPids) {
                val cmdlineFile = File("/proc/$pid/cmdline")
                if (cmdlineFile.exists() && cmdlineFile.canRead()) {
                    val bytes = cmdlineFile.readBytes()
                    val cmdline = String(bytes).trim('\u0000')
                    if (cmdline == "com.tencent.mm") return pid
                }
            }
        } catch (_: Exception) {}

        return null
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

        Thread {
            try {
                val pid = findWeChatPid()
                if (pid == null) { sb.appendLine("微信未运行"); post(sb); return@Thread }

                val dbPath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
                val dbFile = File(dbPath)

                if (!dbFile.exists()) { sb.appendLine("数据库: ✗ 不存在"); post(sb); return@Thread }
                val dbSize = dbFile.length()
                sb.appendLine("数据库: ✓ ${dbSize / 1024 / 1024} MB (PID=$pid)")

                // Clean old copies first
                DbCleanup.cleanOldCopies()
                sb.appendLine(DbCleanup.getDiskInfo())

                // Check existing copy
                val dstFile = File("/sdcard/Download/EnMicroMsg.db")
                if (dstFile.exists() && dstFile.length() == dbSize) {
                    sb.appendLine("本地副本: ✓ 已存在")
                } else {
                    // Clean old copy if exists
                    if (dstFile.exists()) {
                        dstFile.delete()
                        sb.appendLine("清理旧副本...")
                    }

                    sb.appendLine("复制中...")
                    post(sb)

                    dbFile.inputStream().use { input ->
                        dstFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 1024 * 1024)
                        }
                    }
                    sb.appendLine("复制完成: ${dstFile.length() / 1024 / 1024} MB")
                }

                sb.appendLine("\n解密命令:")
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
