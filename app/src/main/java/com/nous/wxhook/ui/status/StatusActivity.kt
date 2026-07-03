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

        val title = TextView(this).apply {
            text = "wxhook 状态检测"
            textSize = 20f
        }
        layout.addView(title)

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

        // Try to read key from multiple sources
        var lastKey: String? = null
        var lastKeyTime: Long = 0

        // Source 1: /data/local/tmp/.wechat_key (written by XP module)
        try {
            val keyFile = File("/data/local/tmp/.wechat_key")
            if (keyFile.exists()) {
                val content = keyFile.readText()
                lastKey = content.lines().find { it.startsWith("key=") }?.removePrefix("key=")
                lastKeyTime = System.currentTimeMillis()
            }
        } catch (_: Exception) {}

        // Source 2: WeChat's shared_prefs (XP module writes here)
        if (lastKey == null) {
            try {
                val prefsFile = File("/data/data/com.tencent.mm/shared_prefs/wechat_key.xml")
                if (prefsFile.exists()) {
                    val content = prefsFile.readText()
                    val match = Regex("name=\"key\">([^<]+)<").find(content)
                    if (match != null) {
                        lastKey = match.groupValues[1]
                        lastKeyTime = System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {}
        }

        // Source 3: wxhook's own shared_prefs (fallback)
        if (lastKey == null) {
            try {
                val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
                lastKey = prefs.getString("last_key", null)
                lastKeyTime = prefs.getLong("last_key_time", 0)
            } catch (_: Exception) {}
        }

        sb.appendLine("=== XP 模块状态 ===")
        if (lastKey != null) {
            sb.appendLine("密钥: ✓ 已捕获 $lastKey")
            if (lastKeyTime > 0) {
                sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastKeyTime))}")
            }
        } else {
            sb.appendLine("密钥: ✗ 未捕获（需要重启微信）")
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
                                stats.forEach { (table, count) ->
                                    sb.appendLine("$table: $count 条")
                                }
                                statusText.text = sb.toString()
                            }
                            db.close()
                        } else {
                            handler.post {
                                sb.appendLine("解密失败")
                                statusText.text = sb.toString()
                            }
                        }
                    } catch (e: Exception) {
                        handler.post {
                            sb.appendLine("解密错误: ${e.message}")
                            statusText.text = sb.toString()
                        }
                    }
                }.start()
            }
        } else {
            sb.appendLine("数据库: ✗ 文件不存在（需要 root）")
        }

        sb.appendLine("\n=== 缓存状态 ===")
        try {
            val cacheDb = File(getDatabasePath("wxhook.db").parent, "wxhook.db")
            if (cacheDb.exists()) {
                sb.appendLine("本地缓存: ✓ (${cacheDb.length()} bytes)")
            } else {
                sb.appendLine("本地缓存: ✗ 未创建")
            }
        } catch (_: Exception) {
            sb.appendLine("本地缓存: ✗ 未创建")
        }

        statusText.text = sb.toString()
    }
}
