package com.nous.wxhook.ui.chatlist

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File

data class ChatConversation(
    val username: String,
    val nickname: String,
    val unReadCount: Int,
    val conversationTime: Long
)

class ChatListActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("wxhook:ChatList", "onCreate")

        // Root view
        val root = androidx.constraintlayout.widget.ConstraintLayout(this)
        recyclerView = RecyclerView(this).apply { id = View.generateViewId() }
        recyclerView.layoutManager = LinearLayoutManager(this)

        progressBar = CircularProgressIndicator(this, null,
            com.google.android.material.R.attr.circularProgressIndicatorStyle).apply {
            id = View.generateViewId()
            isIndeterminate = true
        }

        root.addView(recyclerView)
        root.addView(progressBar)

        // Progress centered
        (progressBar.layoutParams as? ViewGroup.LayoutParams)?.also {
            it.width = 96; it.height = 96
        }

        setContentView(root)
        supportActionBar?.title = "聊天列表"
        loadConversations()
    }

    private fun loadConversations() {
        Log.i("wxhook:ChatList", "loadConversations start")
        Thread {
            Log.i("wxhook:ChatList", "thread start")
            try {
                val key = "e9cd2ae"
                val dbPath = "/sdcard/Download/EnMicroMsg.db"
                val dbFile = File(dbPath)
                if (!dbFile.exists()) {
                    Log.e("wxhook:ChatList", "DB not found")
                    handler.post {
                        progressBar.visibility = View.GONE
                        val tv = TextView(this).apply { text = "数据库不存在"; textSize = 18f }
                        setContentView(tv)
                    }
                    return@Thread
                }

                val tag = System.currentTimeMillis().toString()
                val sqlFile = File(cacheDir, "cl_sql_${tag}.sql")

                sqlFile.writeText("PRAGMA key='$key';" +
                    "PRAGMA cipher_compatibility=3;" +
                    "PRAGMA cipher_page_size=1024;" +
                    "PRAGMA kdf_iter=4000;" +
                    "PRAGMA cipher_use_hmac=OFF;" +
                    "SELECT c.username, IFNULL(r.nickname, c.username) AS nickname, " +
                    "c.unReadCount, c.conversationTime " +
                    "FROM rconversation c " +
                    "LEFT JOIN rcontact r ON c.username = r.username " +
                    "ORDER BY c.conversationTime DESC LIMIT 200;")

                val scPath = "/data/local/sqlcipher"
                val cmd = "$scPath '$dbPath' < '${sqlFile.absolutePath}'"
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val lines = proc.inputStream.bufferedReader().readLines()
                val err = proc.errorStream.bufferedReader().readText().trim()
                val exit = proc.waitFor()
                sqlFile.delete()
                Log.i("wxhook:ChatList", "exit=$exit lines=${lines.size} err=|$err|")

                val convs = mutableListOf<ChatConversation>()
                for (line in lines) {
                    val parts = line.split("|")
                    if (parts.size >= 4 && !parts[0].startsWith("ok")) {
                        convs.add(ChatConversation(
                            username = parts[0],
                            nickname = parts[1],
                            unReadCount = parts[2].toIntOrNull() ?: 0,
                            conversationTime = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                        ))
                    }
                }
                Log.i("wxhook:ChatList", "parsed ${convs.size} conversations")

                handler.post {
                    progressBar.visibility = View.GONE
                    if (convs.isEmpty()) {
                        val tv = TextView(this).apply { text = "没有会话数据"; textSize = 18f }
                        setContentView(tv)
                    } else {
                        recyclerView.adapter = ChatAdapter(convs) { conv ->
                            val intent = Intent(this@ChatListActivity,
                                com.nous.wxhook.ui.chatdetail.ChatDetailActivity::class.java)
                            intent.putExtra("talker", conv.username)
                            intent.putExtra("nickname", conv.nickname)
                            startActivity(intent)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("wxhook:ChatList", "query failed", e)
                handler.post {
                    progressBar.visibility = View.GONE
                    val tv = TextView(this).apply { text = "查询失败: ${e.message}\n${e.stackTraceToString().take(500)}"; textSize = 14f }
                    setContentView(tv)
                }
            }
        }.start()
    }
}
// end of ChatListActivity

class ChatAdapter(
    private val items: List<ChatConversation>,
    private val onClick: (ChatConversation) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {

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
        val item = items[pos]
        val ctx = holder.card.context
        holder.card.removeAllViews()

        val layout = androidx.constraintlayout.widget.ConstraintLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        layout.id = View.generateViewId()

        val nameView = TextView(ctx).apply {
            id = View.generateViewId()
            text = item.nickname
            textSize = 16f
            setTextColor(0xDE000000.toInt())
        }
        val badgeView = TextView(ctx).apply {
            id = View.generateViewId()
            text = if (item.unReadCount > 0) "${item.unReadCount}" else ""
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFFF4444.toInt())
            setPadding(12, 4, 12, 4)
        }
        val timeView = TextView(ctx).apply {
            id = View.generateViewId()
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            text = if (item.conversationTime > 0L) sdf.format(java.util.Date(item.conversationTime)) else ""
            textSize = 12f
            setTextColor(0x8A000000.toInt())
        }

        layout.addView(nameView)
        layout.addView(badgeView)
        layout.addView(timeView)
        holder.card.addView(layout)
        holder.card.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
