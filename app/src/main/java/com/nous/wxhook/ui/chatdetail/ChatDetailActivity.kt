package com.nous.wxhook.ui.chatdetail

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.nous.wxhook.db.MessageParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
        Log.i("wxhook:ChatDtl","onCreate talker=$talker")
        recyclerView = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ChatDetailActivity) }
        setContentView(recyclerView)
        supportActionBar?.title = nickname
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        loadMessages()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun execCmd(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su","-c",cmd))
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }

    private val fileCache = ConcurrentHashMap<String, Boolean>()
    private fun fileExists(wxPath: String): Boolean =
        fileCache.getOrPut(wxPath) { execCmd("test -f '$wxPath' && echo 1").contains("1") }

    private fun resolveWxPath(md5OrPath: String?, fileType: Int): String? {
        if (md5OrPath.isNullOrBlank()) return null
        val md5 = md5OrPath.substringAfter("th_").substringBefore("|").take(32)
        if (md5.length < 32) return null
        val wpid = execCmd("pidof com.tencent.mm")
        if (wpid.isBlank()) return null
        val base = "/proc/${wpid}/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
        return when (fileType) {
            3 -> "$base/image2/${md5.substring(0,2)}/${md5.substring(2,4)}/th_$md5"
            34 -> "$base/voice2/${md5.substring(0,2)}/msg_$md5.amr"
            43 -> "$base/video/$md5"
            else -> "$base/attachment/$md5"
        }
    }

    private fun copyToCache(wxPath: String): String? {
        val name = wxPath.substringAfterLast("/")
        val local = File(cacheDir, name)
        if (local.exists()) return local.absolutePath
        return if (execCmd("cp '$wxPath' '${local.absolutePath}' && echo ok").contains("ok")) local.absolutePath
        else null
    }

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
                val lines = proc.inputStream.bufferedReader().readLines(); proc.waitFor(); sqlFile.delete()
                val cnt = Runtime.getRuntime().exec(arrayOf("su","-c","$sc '$dbPath' < '${cntFile.absolutePath}'"))
                val cntOut = cnt.inputStream.bufferedReader().readText().trim(); cnt.waitFor(); cntFile.delete()
                val total = cntOut.lines().lastOrNull { it.all { c -> c.isDigit() } }?.toLongOrNull() ?: 0L
                val msgs = mutableListOf<ChatMessage>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 6 && !p[0].startsWith("ok"))
                        msgs.add(ChatMessage(p[0].toLongOrNull()?:0L, p[1].toIntOrNull()?:0, p[2], p[3].toLongOrNull()?:0L, p[4]=="1", p.getOrNull(5)))
                }
                Log.i("wxhook:ChatDtl","exit=0 lines=${lines.size} total=$total parsed=${msgs.size}")
                handler.post {
                    if (msgs.isEmpty()) setContentView(TextView(this).apply { text = "没有消息"; textSize = 18f })
                    else { supportActionBar?.subtitle = "共 $total 条"; recyclerView.adapter = MessageAdapter(msgs, ::fileExists, ::resolveWxPath, ::copyToCache, cacheDir) }
                }
            } catch (e: Exception) {
                Log.e("wxhook:ChatDtl","failed",e)
                handler.post { setContentView(TextView(this).apply { text = "查询失败: ${e.message}"; textSize = 14f }) }
            }
        }.start()
    }
}

// ── Adapter ──

class MessageAdapter(
    private val items: List<ChatMessage>,
    private val fileExists: (String) -> Boolean,
    private val resolveWxPath: (String?, Int) -> String?,
    private val copyToCache: (String) -> String?,
    private val cacheDir: File
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val handler = Handler(Looper.getMainLooper())

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
        holder.card.removeAllViews(); holder.card.tag = null

        val vert = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Top row
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dir = if (msg.isSend) "→" else "←"
        val parsed = MessageParser.parse(msg.type, msg.content, 0)
        top.addView(TextView(ctx).apply {
            text = "$dir ${typeTag(msg, parsed)}"; textSize = 12f; setTextColor(0xFF6200EE.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(ctx).apply {
            text = if (msg.createTime > 0L) SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.createTime)) else ""
            textSize = 11f; setTextColor(0x8A000000.toInt()); gravity = Gravity.END
        })
        vert.addView(top)

        // Body
        when (msg.type) {
            3 -> addImage(vert, ctx, msg)       // image → show thumbnail
            34 -> addVoice(vert, ctx, msg)       // voice
            43 -> addVideo(vert, ctx, msg)       // video → playable
            else -> addTextContent(vert, ctx, msg, parsed)
        }

        holder.card.addView(vert)
    }

    // ── Image ──
    private fun addImage(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage) {
        // Placeholder while loading
        val tv = TextView(ctx).apply { text = "  ⏳ 加载图片..."; textSize = 13f; setTextColor(0x8A000000.toInt()); setPadding(0, 8, 0, 0) }
        vert.addView(tv)
        // Also add an ImageView (hidden until loaded)
        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        vert.addView(iv)

        Thread {
            val wxPath = resolveWxPath(msg.imgPath, 3)
            val exists = wxPath != null && fileExists(wxPath)
            val local = if (exists) copyToCache(wxPath!!) else null
            handler.post {
                tv.visibility = View.GONE
                if (local != null) {
                    val bm = BitmapFactory.decodeFile(local)
                    if (bm != null) { iv.setImageBitmap(bm); iv.visibility = View.VISIBLE; iv.setOnClickListener { openFile(ctx, local) } }
                    else { tv.text = "  ⚠️ 图片解码失败"; tv.visibility = View.VISIBLE }
                } else {
                    tv.text = if (wxPath == null) "  ⚠️ 需微信运行" else "  ⚠️ 图片已丢失"
                    tv.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    // ── Video ──
    private fun addVideo(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage) {
        val tv = TextView(ctx).apply {
            text = "  🎬 [视频]  ⏳ 检查文件..."; textSize = 14f
            setTextColor(0xFF6200EE.toInt()); setPadding(0, 8, 0, 0)
        }
        vert.addView(tv)
        Thread {
            val wxPath = resolveWxPath(msg.imgPath, 43)
            val exists = wxPath != null && fileExists(wxPath)
            val local = if (exists) copyToCache(wxPath!!) else null
            handler.post {
                if (local != null) {
                    tv.text = "  ▶️ [视频]  ${wxPath!!.substringAfterLast("/").take(40)}\n  👆 点击播放"
                    tv.setOnClickListener { openFile(ctx, local) }
                } else {
                    tv.text = if (wxPath == null) "  🎬 [视频]\n  ⚠️ 需微信运行" else "  🎬 [视频]\n  ⚠️ 视频文件已丢失"
                }
            }
        }.start()
    }

    // ── Voice ──
    private fun addVoice(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage) {
        val parsed = MessageParser.parse(msg.type, msg.content, 0)
        val tv = TextView(ctx).apply {
            text = "  🎵 [语音] ${parsed.mediaPath?.let { "(${it}ms)" } ?: ""}\n  ⏳ 检查文件..."
            textSize = 14f; setTextColor(0xFF6200EE.toInt()); setPadding(0, 8, 0, 0)
        }
        vert.addView(tv)
        Thread {
            val wxPath = resolveWxPath(msg.imgPath, 34)
            val exists = wxPath != null && fileExists(wxPath)
            val local = if (exists) copyToCache(wxPath!!) else null
            handler.post {
                if (local != null) {
                    tv.text = "  🎵 [语音] ${parsed.mediaPath?.let { "(${it}ms)" } ?: ""}\n  👆 点击播放"
                    tv.setOnClickListener { openFile(ctx, local) }
                } else {
                    tv.text = "  🎵 [语音] \n  ${if(wxPath==null)"⚠️ 需微信运行" else "⚠️ 语音文件已丢失"}"
                }
            }
        }.start()
    }

    // ── File ──
    private fun addFile(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage, fileName: String?) {
        val tv = TextView(ctx).apply {
            text = "  📎 ${fileName?.take(50) ?: "未知文件"}\n  ⏳ 检查文件..."
            textSize = 14f; setTextColor(0xFF6200EE.toInt()); setPadding(0, 8, 0, 0)
        }
        vert.addView(tv)
        Thread {
            val wxPath = resolveWxPath(msg.imgPath, 0)
            val exists = wxPath != null && fileExists(wxPath)
            val local = if (exists) copyToCache(wxPath!!) else null
            handler.post {
                if (local != null) {
                    tv.text = "  📎 ${fileName?.take(50) ?: "文件"}\n  ✅ 点此打开"
                    tv.setOnClickListener { openFile(ctx, local) }
                } else {
                    tv.text = "  📎 ${fileName?.take(50) ?: "文件"}\n  ${if(wxPath==null)"⚠️ 需微信运行" else "⚠️ 文件已丢失"}"
                }
            }
        }.start()
    }

    // ── Text content ──
    private fun addTextContent(vert: LinearLayout, ctx: android.content.Context, msg: ChatMessage, parsed: MessageParser.ParsedMessage) {
        val tv = TextView(ctx).apply { textSize = 14f; setTextColor(0xDE000000.toInt()); setPadding(0, 8, 0, 0) }
        when (msg.type) {
            1 -> tv.text = parsed.content ?: "(空)"
            47 -> tv.text = "[表情] ${parsed.content?.take(100) ?: ""}"
            48 -> { tv.text = "[位置] ${parsed.content?.take(100) ?: ""}"; tv.setTextColor(0xFF6200EE.toInt()) }
            42 -> { tv.text = "[名片] ${parsed.content?.take(100) ?: ""}"; tv.setTextColor(0xFF6200EE.toInt()) }
            49 -> {
                when (parsed.subType) {
                    MessageParser.APP_LINK -> {
                        tv.text = buildString { parsed.title?.let { appendLine(it) }; parsed.url?.take(80)?.let { appendLine(it) } }
                        tv.setTextColor(0xFF6200EE.toInt())
                        parsed.url?.takeUnless { it.isBlank() }?.let { url ->
                            tv.setOnClickListener {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                catch (_: Exception) { Toast.makeText(ctx, "无法打开链接", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                    MessageParser.APP_FILE -> addFile(vert, ctx, msg, parsed.fileName)
                    MessageParser.APP_MINI_PROGRAM -> tv.text = "[小程序] ${parsed.title?.take(50) ?: ""}"
                    MessageParser.APP_MERGE_FORWARD -> tv.text = "[合并转发]"
                    else -> tv.text = parsed.content?.take(500) ?: "(空)"
                }
            }
            10000 -> { tv.text = parsed.content?.take(500) ?: ""; tv.setTextColor(0x8A000000.toInt()); tv.textSize = 12f }
            10002 -> { tv.text = "[对方撤回了一条消息]"; tv.setTextColor(0x8A000000.toInt()) }
            50   -> { tv.text = "[动画表情]"; tv.setTextColor(0xFF6200EE.toInt()) }
            else -> { val r = msg.content?.take(300) ?: ""; tv.text = r.ifEmpty { "(类型${msg.type})" }; tv.setTextColor(0x8A000000.toInt()) }
        }
        if (tv.text.isNotBlank()) vert.addView(tv)
    }

    private fun typeTag(msg: ChatMessage, parsed: MessageParser.ParsedMessage): String = when (msg.type) {
        1 -> "📝 文本"; 3 -> "🖼 图片"; 34 -> "🎵 语音"; 43 -> "🎬 视频"
        47 -> "😊 表情"; 48 -> "📍 位置"; 42 -> "👤 名片"
        49 -> when (parsed.subType) {
            MessageParser.APP_LINK -> "🔗 链接"; MessageParser.APP_FILE -> "📎 文件"
            MessageParser.APP_MINI_PROGRAM -> "🧩 小程序"; MessageParser.APP_MUSIC -> "🎵 音乐"
            MessageParser.APP_MERGE_FORWARD -> "💬 合并转发"; MessageParser.APP_LOCATION -> "📍 实时位置"
            else -> "📦 ${parsed.typeDesc}"
        }
        10000 -> "ℹ️ 系统"; 10002 -> "↩️ 撤回"; else -> "❓ 类型${msg.type}"
    }

    private fun openFile(ctx: android.content.Context, localPath: String) {
        val file = File(localPath)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
            val mime = when {
                localPath.endsWith(".mp4")||localPath.endsWith(".avi") -> "video/*"
                localPath.endsWith(".amr")||localPath.endsWith(".mp3") -> "audio/*"
                localPath.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)")) -> "image/*"
                else -> "*/*"
            }
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) { Toast.makeText(ctx, "无法打开: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun getItemCount() = items.size
}