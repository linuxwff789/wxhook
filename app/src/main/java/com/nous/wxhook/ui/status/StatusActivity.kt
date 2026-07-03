package com.nous.wxhook.ui.status

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.nous.wxhook.service.DecryptService
import java.io.File

class StatusActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView

    private val decryptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val results = intent.getStringExtra("results") ?: "无结果"
            handler.post {
                statusText.append("\n\n=== 解密统计 ===\n$results")
            }
        }
    }

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

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.nous.wxhook.DECRYPT_DONE")
        registerReceiver(decryptReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(decryptReceiver)
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

        // Check if local DB exists
        val localDb = "/sdcard/Download/EnMicroMsg.db"
        val localSize = runSu("stat -c %s $localDb 2>/dev/null")

        if (localSize.isEmpty()) {
            sb.appendLine("本地数据库: ✗ 不存在")
            sb.appendLine("启动解密服务（会自动复制数据库）...")
            statusText.text = sb.toString()

            // Start decrypt service
            startService(Intent(this, DecryptService::class.java).apply {
                action = "com.nous.wxhook.DECRYPT_START"
            })
            return
        }

        sb.appendLine("本地数据库: ✓ ${localSize.toLong() / 1024 / 1024} MB")
        sb.appendLine("启动解密服务...")
        statusText.text = sb.toString()

        // Start decrypt service
        startService(Intent(this, DecryptService::class.java).apply {
            action = "com.nous.wxhook.DECRYPT_START"
        })
    }
}
