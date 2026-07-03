package com.nous.wxhook.ui.chatdetail

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val msgSvrId: Long, val type: Int,
    val content: String?, val createTime: Long,
    val isSend: Boolean
)

class ChatDetailActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private var talker = ""
    private var nickname = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        talker = intent.getStringExtra("talker") ?: ""; nickname = intent.getStringExtra("nickname") ?: talker
        Log.i("wxhook:ChatDetail","onCreate talker=$talker nickname=$nickname")
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatDetailActivity)
        }
        setContentView(recyclerView)
        supportActionBar?.title = nickname
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        loadMessages()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun loadMessages() {
        Thread {
            try {
                val key = "e9cd2ae"; val dbPath = "/sdcard/Download/EnMicroMsg.db"
                if (!File(dbPath).exists()) {
                    handler.post { setContentView(TextView(this).apply { text = "数据库不存在"; textSize = 18f }) }
                    return@Thread
                }
                if (talker.isEmpty()) {
                    handler.post { setContentView(TextView(this).apply { text = "未指定会话"; textSize = 18f }) }
                    return@Thread
                }
                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cd_${tag}.sql")
                // Use hex(talker) to avoid SQL injection from special chars
                sqlFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT msgSvrId,type,content,createTime,isSend FROM message WHERE talker='$talker' ORDER BY createTime DESC LIMIT 100;")
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val proc = Runtime.getRuntime().exec(arrayOf("su","-c","$sc '$dbPath' < '${sqlFile.absolutePath}'"))
                val lines = proc.inputStream.bufferedReader().readLines()
                val err = proc.errorStream.bufferedReader().readText().trim()
                val exit = proc.waitFor()
                sqlFile.delete()
                Log.i("wxhook:ChatDetail","exit=$exit lines=${lines.size} err=|$err|")

                val msgs = mutableListOf<ChatMessage>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 5 && !p[0].startsWith("ok")) {
                        msgs.add(ChatMessage(p[0].toLongOrNull()?:0L, p[1].toIntOrNull()?:0, p[2], p[3].toLongOrNull()?:0L, p[4]=="1"))
                    }
                }
                Log.i("wxhook:ChatDetail","parsed ${msgs.size} messages")
                handler.post {
                    if (msgs.isEmpty()) setContentView(TextView(this).apply { text = "没有消息"; textSize = 18f })
                    else recyclerView.adapter = MessageAdapter(msgs)
                }
            } catch (e: Exception) {
                Log.e("wxhook:ChatDetail","query failed",e)
                handler.post { setContentView(TextView(this).apply { text = "查询失败: ${e.message}"; textSize = 14f }) }
            }
        }.start()
    }
}

class MessageAdapter(private val items: List<ChatMessage>) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = MaterialCardView(parent.context).apply {
            val lp = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(12, 6, 12, 6)
            layoutParams = lp
            radius = 8f; cardElevation = 1f
            setContentPadding(36, 16, 36, 16)
        }
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = items[pos]; val ctx = holder.card.context
        holder.card.removeAllViews()

        val vert = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Top row: direction + type badge + time
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dir = if (msg.isSend) "→" else "←"
        val typeMap = mapOf(1 to "文本", 3 to "图片", 34 to "语音", 43 to "视频", 49 to "链接", 10000 to "系统")
        val typeStr = typeMap[msg.type] ?: "类型${msg.type}"
        val timeStr = if (msg.createTime > 0L) SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.createTime)) else ""

        topRow.addView(TextView(ctx).apply {
            text = "$dir [$typeStr]"; textSize = 12f
            setTextColor(0xFF6200EE.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(ctx).apply {
            text = timeStr; textSize = 11f; setTextColor(0x8A000000.toInt())
            gravity = Gravity.END
        })
        vert.addView(topRow)

        // Message content
        val content = (msg.content ?: "(空)").take(500)
        vert.addView(TextView(ctx).apply {
            text = content; textSize = 14f
            setTextColor(0xDE000000.toInt())
            setPadding(0, 8, 0, 0)
        })

        holder.card.addView(vert)
    }

    override fun getItemCount() = items.size
}