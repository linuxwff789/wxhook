package com.nous.wxhook.ui.search

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nous.wxhook.db.WeChatDbDecryptor
import java.io.File

class SearchActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var searchText: EditText
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "搜索消息"
            textSize = 20f
        }
        layout.addView(title)

        searchText = EditText(this).apply {
            hint = "输入关键词..."
            setPadding(0, 32, 0, 0)
        }
        layout.addView(searchText)

        val searchBtn = android.widget.Button(this).apply {
            text = "搜索"
            setOnClickListener { performSearch() }
        }
        layout.addView(searchBtn)

        resultText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(resultText)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun performSearch() {
        val keyword = searchText.text.toString().trim()
        if (keyword.isEmpty()) {
            resultText.text = "请输入关键词"
            return
        }

        resultText.text = "搜索中..."

        Thread {
            val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
            val key = prefs.getString("last_key", null) ?: run {
                handler.post { resultText.text = "未捕获密钥" }
                return@Thread
            }

            val dbPath = "/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
            if (!File(dbPath).exists()) {
                handler.post { resultText.text = "数据库文件不存在" }
                return@Thread
            }

            try {
                val db = WeChatDbDecryptor.openDecryptedDb(dbPath) ?: run {
                    handler.post { resultText.text = "解密失败" }
                    return@Thread
                }

                val cursor = db.rawQuery(
                    """SELECT talker, type, content, createTime, isSend 
                       FROM message 
                       WHERE content LIKE ? 
                       ORDER BY createTime DESC 
                       LIMIT 100""",
                    arrayOf("%$keyword%")
                )

                val sb = StringBuilder()
                var count = 0
                while (cursor.moveToNext()) {
                    count++
                    val talker = cursor.getString(0)
                    val type = cursor.getInt(1)
                    val content = cursor.getString(2)
                    val time = cursor.getLong(3)
                    val isSend = cursor.getInt(4) == 1

                    val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(time))
                    val dir = if (isSend) "→" else "←"

                    sb.appendLine("$timeStr $dir [$talker]")
                    sb.appendLine("  ${content?.take(150) ?: "null"}")
                    sb.appendLine()
                }
                cursor.close()
                db.close()

                if (count == 0) {
                    sb.appendLine("未找到包含 \"$keyword\" 的消息")
                } else {
                    sb.insert(0, "找到 $count 条结果\n\n")
                }

                handler.post { resultText.text = sb.toString() }
            } catch (e: Exception) {
                handler.post { resultText.text = "搜索错误: ${e.message}" }
            }
        }.start()
    }
}
