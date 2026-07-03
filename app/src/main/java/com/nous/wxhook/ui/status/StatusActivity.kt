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

        // Read key from .wechat_key file
        try {
            val keyFile = File("/data/local/tmp/.wechat_key")
            if (keyFile.exists()) {
                val content = keyFile.readText()
                val keyLine = content.lines().find { it.startsWith("key=") }
                if (keyLine != null) {
                    val hex = keyLine.removePrefix("key=")
                    lastKey = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                }
            }
        } catch (_: Exception) {}

        // Fallback: shared_prefs
        if (lastKey == null) {
            try {
                val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
                lastKey = prefs.getString("last_key", null)
            } catch (_: Exception) {}
        }

        sb.appendLine("=== XP 模块状态 ===")
        if (lastKey != null) {
            sb.appendLine("密钥: ✓ $lastKey")
            getSharedPreferences("wxhook", MODE_PRIVATE).edit()
                .putString("last_key", lastKey)
                .putInt("last_key_len", lastKey.length)
                .putLong("last_key_time", System.currentTimeMillis())
                .apply()
        } else {
            sb.appendLine("密钥: ✗ 未捕获")
            statusText.text = sb.toString()
            return
        }

        sb.appendLine("\n=== 数据库状态 ===")

        // Find DB path via /proc/PID/root
        Thread {
            try {
                val pid = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
                    .inputStream.bufferedReader().readText().trim().split("\n").firstOrNull()

                if (pid == null || pid.isEmpty()) {
                    handler.post { sb.appendLine("微信未运行"); statusText.text = sb.toString() }
                    return@Thread
                }

                val dbPath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"

                // Check if DB exists via root
                val checkProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -la $dbPath"))
                val checkOutput = checkProc.inputStream.bufferedReader().readText()
                checkProc.waitFor()

                if (!checkOutput.contains("EnMicroMsg.db")) {
                    handler.post { sb.appendLine("数据库: ✗ 不存在"); statusText.text = sb.toString() }
                    return@Thread
                }

                // Copy DB to accessible location for decryption
                val localDb = File(cacheDir, "EnMicroMsg.db")
                sb.appendLine("数据库: ✓ 存在 (通过 /proc/$pid/root 访问)")
                sb.appendLine("复制数据库到本地...")

                val copyProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp $dbPath ${localDb.absolutePath} && chmod 644 ${localDb.absolutePath}"))
                copyProc.waitFor()

                if (!localDb.exists() || localDb.length() == 0L) {
                    handler.post { sb.appendLine("复制失败"); statusText.text = sb.toString() }
                    return@Thread
                }

                sb.appendLine("本地副本: ${localDb.length() / 1024 / 1024} MB")
                sb.appendLine("尝试解密...")

                val db = WeChatDbDecryptor.openDecryptedDb(localDb.absolutePath)
                if (db != null) {
                    val stats = WeChatDbDecryptor.getStats(db)
                    sb.appendLine("\n=== 解密统计 ===")
                    stats.forEach { (table, count) -> sb.appendLine("$table: $count 条") }
                    db.close()
                } else {
                    sb.appendLine("解密失败")
                }

                // Cleanup
                localDb.delete()

                handler.post { statusText.text = sb.toString() }
            } catch (e: Exception) {
                handler.post { sb.appendLine("错误: ${e.message}"); statusText.text = sb.toString() }
            }
        }.start()
    }
}
