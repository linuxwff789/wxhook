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
import com.nous.wxhook.db.MessageParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val msgSvrId: Long, val type: Int,
    val content: String?, val createTime: Long,
    val isSend: Boolean, val imgPath: String?
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
        Log.i("wxhook:ChatDtl","onCreate talker=$talker nickname=$nickname")
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
                if (!File(dbPath).exists()) { handler.post { setContentView(TextView(this).apply { text = "数据库不存在"; textSize = 18f }) }; return@Thread }
                if (talker.isEmpty()) { handler.post { setContentView(TextView(this).apply { text = "未指定会话"; textSize = 18f }) }; return@Thread }
                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cd_${tag}.sql")
                sqlFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT msgSvrId,type,replace(replace(content,char(10),' '),'|','/'),createTime,isSend,imgPath FROM message WHERE talker='$talker' ORDER BY createTime DESC;")
                val cntFile = File(cacheDir, "cd_cnt_${tag}.sql")
                cntFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT count(*) FROM message WHERE talker='$talker';")
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val proc = Runtime.getRuntime().exec(arrayOf("su","-c","$sc '$dbPath' < '${sqlFile.absolutePath}'"))
                val lines = proc.inputStream.bufferedReader().readLines()
                val err = proc.errorStream.bufferedReader().readText().trim()
                val exit = proc.waitFor(); sqlFile.delete()
                val cntProc = Runtime.getRuntime().exec(arrayOf("su","-c","$sc '$dbPath' < '${cntFile.absolutePath}'"))
                val cntOut = cntProc.inputStream.bufferedReader().readText().trim(); cntProc.waitFor(); cntFile.delete()
                val totalMsg = cntOut.lines().lastOrNull { it.all { c -> c.isDigit() } }?.toLongOrNull() ?: 0L
                Log.i("wxhook:ChatDtl","exit=$exit lines=${lines.size} total=$totalMsg err=|$err|")
                val msgs = mutableListOf<ChatMessage>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 6 && !p[0].startsWith("ok")) {
                        msgs.add(ChatMessage(p[0].toLongOrNull()?:0L, p[1].toIntOrNull()?:0, p[2], p[3].toLongOrNull()?:0L, p[4]=="1", p.getOrNull(5)))
                    }
                }
                Log.i("wxhook:ChatDtl","parsed ${msgs.size} messages")
                handler.post {
                    if (msgs.isEmpty()) setContentView(TextView(this).apply { text = "没有消息"; textSize = 18f })
                    else {
                        supportActionBar?.subtitle = "共 $totalMsg 条"
                        recyclerView.adapter = MessageAdapter(msgs)
                    }
                }
            } catch (e: Exception) {
                Log.e("wxhook:ChatDtl","query failed",e)
                handler.post { setContentView(TextView(this).apply { text = "查询失败: ${e.message}"; textSize = 14f }) }
            }
        }.start()
    }
}

// ── Adapter with type-aware display ──

class MessageAdapter(private val items: List<ChatMessage>) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = MaterialCardView(parent.context).apply {
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(12, 6, 12, 6); layoutParams = lp
            radius = 8f; cardElevation = 1f; setContentPadding(36, 16, 36, 16)
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
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val dir = if (msg.isSend) "→" else "←"
        val parsed = MessageParser.parse(msg.type, msg.content, 0)
        val typeTag = when (msg.type) {
            1    -> "📝 文本"
            3    -> "🖼 图片"
            34   -> "🎵 语音"
            43   -> "🎬 视频"
            47   -> "😊 表情"
            48   -> "📍 位置"
            42   -> "👤 名片"
            49   -> when (parsed.subType) {
                MessageParser.APP_LINK        -> "🔗 链接"
                MessageParser.APP_FILE        -> "📎 文件"
                MessageParser.APP_MINI_PROGRAM -> "🧩 小程序"
                MessageParser.APP_MUSIC       -> "🎵 音乐"
                MessageParser.APP_MERGE_FORWARD -> "💬 合并转发"
                MessageParser.APP_LOCATION    -> "📍 实时位置"
                else -> "📦 ${parsed.typeDesc}"
            }
            10000 -> "ℹ️ 系统"
            10002 -> "↩️ 撤回"
            else  -> "❓ 类型${msg.type}"
        }

        top.addView(TextView(ctx).apply {
            text = "$dir $typeTag"; textSize = 12f
            setTextColor(0xFF6200EE.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val timeStr = if (msg.createTime > 0L) SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.createTime)) else ""
        top.addView(TextView(ctx).apply {
            text = timeStr; textSize = 11f; setTextColor(0x8A000000.toInt()); gravity = Gravity.END
        })
        vert.addView(top)

        // Content based on type
        val contentText = buildContent(ctx, msg, parsed)
        if (contentText != null) vert.addView(contentText)

        holder.card.addView(vert)
    }

    private fun buildContent(ctx: android.content.Context, msg: ChatMessage, parsed: com.nous.wxhook.db.MessageParser.ParsedMessage): TextView? {
        val tv = TextView(ctx).apply {
            textSize = 14f; setTextColor(0xDE000000.toInt())
            setPadding(0, 8, 0, 0)
        }
        when (msg.type) {
            1 -> { // text
                tv.text = parsed.content ?: "(空)"
            }
            3 -> { // image
                val path = msg.imgPath?.take(60) ?: ""
                tv.text = if (path.isNotEmpty()) "[图片] $path" else "[图片]"
                tv.setTextColor(0xFF6200EE.toInt())
            }
            34 -> { // voice
                val extra = parsed.mediaPath?.let { "(${it}ms)" } ?: ""
                tv.text = "[语音]$extra"
                tv.setTextColor(0xFF6200EE.toInt())
            }
            43 -> { // video
                tv.text = "[视频]"
                tv.setTextColor(0xFF6200EE.toInt())
            }
            47 -> { // emoji
                tv.text = "[表情] ${parsed.content?.take(100) ?: ""}"
            }
            48 -> { // location
                tv.text = "[位置] ${parsed.content?.take(100) ?: ""}"
                tv.setTextColor(0xFF6200EE.toInt())
            }
            42 -> { // business card
                tv.text = "[名片] ${parsed.content?.take(100) ?: ""}"
                tv.setTextColor(0xFF6200EE.toInt())
            }
            49 -> { // app message
                when (parsed.subType) {
                    MessageParser.APP_LINK -> {
                        val title = parsed.title ?: ""
                        val url = parsed.url?.take(80) ?: ""
                        tv.text = buildString {
                            if (title.isNotEmpty()) appendLine(title)
                            if (url.isNotEmpty()) appendLine(url)
                            append(parsed.content?.take(200) ?: "")
                        }
                        tv.setTextColor(0xFF6200EE.toInt())
                    }
                    MessageParser.APP_FILE -> {
                        val name = parsed.fileName?.take(50) ?: ""
                        tv.text = "[文件] $name"
                        tv.setTextColor(0xFF6200EE.toInt())
                    }
                    MessageParser.APP_MINI_PROGRAM -> {
                        tv.text = "[小程序] ${parsed.title?.take(50) ?: ""}"
                        tv.setTextColor(0xFF6200EE.toInt())
                    }
                    MessageParser.APP_MERGE_FORWARD -> {
                        tv.text = "[合并转发]"
                        tv.setTextColor(0xFF6200EE.toInt())
                    }
                    else -> {
                        tv.text = parsed.content?.take(500) ?: "(空)"
                    }
                }
            }
            10000 -> { // system
                tv.text = parsed.content?.take(500) ?: ""
                tv.setTextColor(0x8A000000.toInt())
                tv.textSize = 12f
            }
            10002 -> { // revoke
                tv.text = "[对方撤回了一条消息]"
                tv.setTextColor(0x8A000000.toInt())
            }
            50   -> { // sticker/unknown
                tv.text = "[动画表情]"
                tv.setTextColor(0xFF6200EE.toInt())
            }
            else -> {
                val raw = msg.content?.take(300) ?: ""
                tv.text = if (raw.isNotEmpty()) raw else "(类型 ${msg.type} 暂未解析)"
                tv.setTextColor(0x8A000000.toInt())
            }
        }
        // Remove empty lines at start
        return if (tv.text.isNullOrBlank()) null else tv
    }

    override fun getItemCount() = items.size
}