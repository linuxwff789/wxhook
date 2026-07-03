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
    private var hasRoot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply { text = "wxhook"; textSize = 20f })
        statusText = TextView(this).apply { text = "检测 root 权限..."; textSize = 14f; setPadding(0, 32, 0, 0) }
        layout.addView(statusText)
        setContentView(layout)

        // Check root
        Thread {
            hasRoot = checkRoot()
            handler.post {
                if (hasRoot) {
                    statusText.text = "root ✓"
                    check()
                } else {
                    statusText.text = "需要 root 权限\n请在 Magisk 中授权 wxhook"
                }
            }
        }.start()
    }

    private fun checkRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) { false }
    }

    private fun su(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }

    private fun check() {
        val sb = StringBuilder()
        sb.appendLine("root ✓")

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

        // Decrypt
        Thread {
            try {
                val pid = su("pidof com.tencent.mm")
                if (pid.isEmpty()) { sb.appendLine("微信未运行"); post(sb); return@Thread }

                val src = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
                val dst = "/sdcard/Download/EnMicroMsg.db"

                val srcSize = su("stat -c %s $src 2>/dev/null")
                if (srcSize.isEmpty()) { sb.appendLine("数据库: ✗ 不存在"); post(sb); return@Thread }
                sb.appendLine("数据库: ${srcSize.toLong() / 1024 / 1024} MB")

                val dstSize = su("stat -c %s $dst 2>/dev/null")
                if (dstSize != srcSize) {
                    sb.appendLine("复制中 (dd)...")
                    post(sb)
                    su("dd if=$src of=$dst bs=1M 2>&1")
                    sb.appendLine("复制完成")
                } else {
                    sb.appendLine("使用本地副本")
                }

                sb.appendLine("解密中...")
                post(sb)

                val sql = "PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT '消息: '||count(*) FROM message;SELECT '会话: '||count(*) FROM rconversation;SELECT '群聊: '||count(*) FROM chatroom;"
                val sqlFile = "/sdcard/Download/q.sql"
                File(sqlFile).writeText(sql)
                su("chmod 666 $sqlFile")

                val out = su("/data/data/com.termux/files/usr/bin/sqlcipher $dst < $sqlFile 2>&1")
                su("rm -f $sqlFile")

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
