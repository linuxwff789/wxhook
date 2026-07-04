package com.nous.wxhook.ui.chatlist

import android.content.Intent
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
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File

data class ChatConversation(
    val username: String, val nickname: String,
    val unReadCount: Int, val conversationTime: Long, val typeTag: String
)

data class SectionItem(val isHeader: Boolean, val title: String = "", val conv: ChatConversation? = null)

class ChatListActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("wxhook:ChatList","onCreate")
        val root = androidx.constraintlayout.widget.ConstraintLayout(this)
        recyclerView = RecyclerView(this).apply { id = View.generateViewId() }
        recyclerView.layoutManager = LinearLayoutManager(this)
        progressBar = CircularProgressIndicator(this, null,
            com.google.android.material.R.attr.circularProgressIndicatorStyle).apply {
            id = View.generateViewId(); isIndeterminate = true
            layoutParams = ViewGroup.LayoutParams(96, 96)
        }
        root.addView(recyclerView); root.addView(progressBar); setContentView(root)
        supportActionBar?.title = "聊天列表"
        loadConversations()
    }

    private fun typeLabel(t: String) = when (t) {
        "group" -> "👥 群聊"; "official" -> "📢 公众号"; else -> "👤 联系人"
    }

    private fun loadConversations() {
        Thread {
            try {
                val key = "e9cd2ae"; val dbPath = "/sdcard/Download/EnMicroMsg.db"
                if (!File(dbPath).exists()) {
                    handler.post { progressBar.visibility = View.GONE; setContentView(TextView(this).apply { text = "数据库不存在"; textSize = 18f }) }; return@Thread }
                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cl_${tag}.sql")
                sqlFile.writeText("PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT c.username,IFNULL(NULLIF(r.conRemark,''),IFNULL(r.nickname,c.username)),c.unReadCount,c.conversationTime,CASE WHEN c.username LIKE '%@chatroom' THEN 'group' WHEN c.username LIKE '%@app' OR c.username LIKE 'gh_%' THEN 'official' ELSE 'contact' END FROM rconversation c LEFT JOIN rcontact r ON c.username=r.username ORDER BY c.conversationTime DESC LIMIT 200;")
                val sc = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"
                val proc = Runtime.getRuntime().exec(arrayOf("su","-c","$sc '$dbPath' < '${sqlFile.absolutePath}'"))
                val lines = proc.inputStream.bufferedReader().readLines()
                val exit = proc.waitFor(); sqlFile.delete()
                Log.i("wxhook:ChatList","exit=$exit lines=${lines.size}")
                val convs = mutableListOf<ChatConversation>()
                for (line in lines) {
                    val p = line.split("|")
                    if (p.size >= 5 && !p[0].startsWith("ok"))
                        convs.add(ChatConversation(p[0], p[1], p[2].toIntOrNull()?:0, p.getOrNull(3)?.toLongOrNull()?:0L, p[4]))
                }
                Log.i("wxhook:ChatList","parsed ${convs.size} conversations")

                // Sort into sections
                val groups = mutableListOf<SectionItem>()
                for (type in listOf("contact", "group", "official")) {
                    val items = convs.filter { it.typeTag == type }
                    if (items.isNotEmpty()) {
                        groups.add(SectionItem(isHeader = true, title = typeLabel(type) + " (${items.size})"))
                        items.forEach { groups.add(SectionItem(isHeader = false, conv = it)) }
                    }
                }
                Log.i("wxhook:ChatList","sections: ${groups.size} items")

                handler.post {
                    progressBar.visibility = View.GONE
                    if (convs.isEmpty()) setContentView(TextView(this).apply { text = "没有会话数据"; textSize = 18f })
                    else recyclerView.adapter = SectionAdapter(groups) { conv ->
                        startActivity(Intent(this@ChatListActivity, com.nous.wxhook.ui.chatdetail.ChatDetailActivity::class.java).apply {
                            putExtra("talker", conv.username); putExtra("nickname", conv.nickname)
                        })
                    }
                }
            } catch (e: Exception) {
                Log.e("wxhook:ChatList","failed",e)
                handler.post { progressBar.visibility = View.GONE; setContentView(TextView(this).apply { text = "查询失败: ${e.message}"; textSize = 14f }) }
            }
        }.start()
    }
}

// ── Sectioned Adapter ──

class SectionAdapter(
    private val items: List<SectionItem>,
    private val onClick: (ChatConversation) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object { const val TYPE_HEADER = 0; const val TYPE_ITEM = 1 }

    override fun getItemViewType(position: Int) = if (items[position].isHeader) TYPE_HEADER else TYPE_ITEM
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val tv = TextView(parent.context).apply {
                textSize = 14f; setTextColor(0xFF6200EE.toInt())
                setPadding(48, 24, 48, 12)
                setBackgroundColor(0xFFF5F5F5.toInt())
            }
            object : RecyclerView.ViewHolder(tv) {}
        } else {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                radius = 0f; cardElevation = 0.5f; setContentPadding(48, 20, 48, 20)
            }
            object : RecyclerView.ViewHolder(card) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val item = items[pos]
        if (item.isHeader) {
            (holder.itemView as TextView).text = item.title
            return
        }
        val conv = item.conv ?: return
        val card = holder.itemView as MaterialCardView
        card.removeAllViews()

        val vert = LinearLayout(card.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val topRow = LinearLayout(card.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        topRow.addView(TextView(card.context).apply {
            text = conv.nickname; textSize = 16f; setTextColor(0xDE000000.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(card.context).apply {
            text = if (conv.conversationTime > 0L) {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(conv.conversationTime))
            } else ""
            textSize = 12f; setTextColor(0x8A000000.toInt()); gravity = Gravity.END
        })
        vert.addView(topRow)
        if (conv.unReadCount > 0) {
            vert.addView(TextView(card.context).apply {
                text = "${conv.unReadCount} 条未读"; textSize = 12f
                setTextColor(0xFF6200EE.toInt()); setPadding(0, 8, 0, 0)
            })
        }
        card.addView(vert)
        card.setOnClickListener { onClick(conv) }
    }
}