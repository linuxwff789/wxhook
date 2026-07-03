package com.nous.wxhook.ui.status

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.nous.wxhook.db.WeChatDbDecryptor
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

    private fun checkStatus() {
        val sb = StringBuilder()
        var lastKey: String? = null

        // Read key from WeChat's shared_prefs via /proc/PID/root
        try {
            val pid = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
                .inputStream.bufferedReader().readText().trim().split("\n").firstOrNull()
            if (pid != null && pid.isNotEmpty()) {
                val procPath = "/proc/$pid/root/data/data/com.tencent.mm/shared_prefs/wxhook_key.xml"
                val content = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $procPath"))
                    .inputStream.bufferedReader().readText()
                val match = Regex("name=\"key\">([^<]+)<").find(content)
                if (match != null) {
                    // Convert hex bytes to ASCII
                    val hex = match.groupValues[1]
                    lastKey = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                }
            }
        } catch (_: Exception) {}

        // Fallback: read from wxhook shared_prefs
        if (lastKey == null) {
            try {
                val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
                lastKey = prefs.getString("last_key", null)
            } catch (_: Exception) {}
        }

        sb.appendLine("=== XP 模块状态 ===")
        if (lastKey != null) {
            sb.appendLine("密钥: ✓ $lastKey")
            // Save to shared_prefs for other pages
            getSharedPreferences("wxhook", MODE_PRIVATE).edit()
                .putString("last_key", lastKey)
                .putInt("last_key_len", lastKey.length)
                .putLong("last_key_time", System.currentTimeMillis())
                .apply()
        } else {
            sb.appendLine("密钥: ✗ 未捕获")
        }

        sb.appendLine("\n=== 数据库状态 ===")
        val dbPath = "/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
        val dbFile = File(dbPath)
        if (dbFile.exists()) {
            sb.appendLine("数据库: ✓ 存在 (${dbFile.length() / 1024 / 1024} MB)")
            if (lastKey != null) {
                sb.appendLine("尝试解密...")
                Thread {
                    try {
                        val db = WeChatDbDecryptor.openDecryptedDb(dbPath)
                        if (db != null) {
                            val stats = WeChatDbDecryptor.getStats(db)
                            handler.post {
                                sb.appendLine("\n=== 解密统计 ===")
                                stats.forEach { (table, count) -> sb.appendLine("$table: $count 条") }
                                statusText.text = sb.toString()
                            }
                            db.close()
                        } else {
                            handler.post { sb.appendLine("解密失败"); statusText.text = sb.toString() }
                        }
                    } catch (e: Exception) {
                        handler.post { sb.appendLine("解密错误: ${e.message}"); statusText.text = sb.toString() }
                    }
                }.start()
            }
        } else {
            sb.appendLine("数据库: ✗ 不存在")
            statusText.text = sb.toString()
        }
    }
}
