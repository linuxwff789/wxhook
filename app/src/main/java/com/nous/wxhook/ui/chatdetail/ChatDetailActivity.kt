package com.nous.wxhook.ui.chatdetail

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val msgSvrId: Long,
    val type: Int,
    val content: String?,
    val createTime: Long,
    val isSend: Boolean
)

class ChatDetailActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private var talker = ""
    private var nickname = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        talker = intent.getStringExtra("talker") ?: ""
        nickname = intent.getStringExtra("nickname") ?: talker

        setContentView(RecyclerView(this).also { recyclerView = it })
        recyclerView.layoutManager = LinearLayoutManager(this)
        supportActionBar?.title = nickname
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadMessages()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun loadMessages() {
        Thread {
            val prefs = getSharedPreferences("wxhook", MODE_PRIVATE)
            val key = prefs.getString("last_key", null) ?: "e9cd2ae"
            val dbPath = "/sdcard/Download/EnMicroMsg.db"

            if (!File(dbPath).exists()) {
                handler.post { Toast.makeText(this, "数据库不存在", Toast.LENGTH_SHORT).show() }
                return@Thread
            }

            try {
                val sql = """PRAGMA key='$key';
PRAGMA cipher_compatibility=3;
PRAGMA cipher_page_size=1024;
PRAGMA kdf_iter=4000;
PRAGMA cipher_use_hmac=OFF;
SELECT msgSvrId, type, content, createTime, isSend
FROM message WHERE talker='$talker'
ORDER BY createTime DESC LIMIT 100;"""

                val sqlFile = File(cacheDir, "chatdetail_${System.currentTimeMillis()}.sql")
                sqlFile.writeText(sql)
                val proc = Runtime.getRuntime().exec(arrayOf(
                    "su", "-c",
                    "/data/local/sqlcipher '$dbPath' < '${sqlFile.absolutePath}' 2>/dev/null"
                ))
                val lines = proc.inputStream.bufferedReader().readLines()
                proc.waitFor()
                sqlFile.delete()

                val msgs = mutableListOf<ChatMessage>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 5) {
                        msgs.add(ChatMessage(
                            msgSvrId = p[0].toLongOrNull() ?: 0L,
                            type = p[1].toIntOrNull() ?: 0,
                            content = p[2],
                            createTime = p[3].toLongOrNull() ?: 0L,
                            isSend = p[4] == "1"
                        ))
                    }
                }

                handler.post {
                    recyclerView.adapter = MessageAdapter(msgs)
                }
            } catch (e: Exception) {
                handler.post { Toast.makeText(this, "查询失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}

class MessageAdapter(private val items: List<ChatMessage>) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = MaterialCardView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            radius = 0f
            cardElevation = 0f
            setContentPadding(48, 24, 48, 24)
        }
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = items[pos]
        val ctx = holder.card.context
        holder.card.removeAllViews()

        val layout = androidx.constraintlayout.widget.ConstraintLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val dir = if (msg.isSend) "→" else "←"
        val typeMap = mapOf(1 to "文本", 3 to "图片", 34 to "语音", 43 to "视频", 49 to "链接", 10000 to "系统")
        val typeStr = typeMap[msg.type] ?: "类型${msg.type}"
        val timeStr = if (msg.createTime > 0)
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.createTime)) else ""

        val content = (msg.content ?: "").take(200)
        val display = "$dir [$typeStr] $timeStr\n$content"

        val tv = TextView(ctx).apply {
            text = display
            textSize = 14f
            setTextColor(0xDE000000.toInt())
        }
        layout.addView(tv)
        holder.card.addView(layout)
    }

    override fun getItemCount() = items.size
}
