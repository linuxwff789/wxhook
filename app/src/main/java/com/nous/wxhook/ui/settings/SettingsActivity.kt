package com.nous.wxhook.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nous.wxhook.ui.module.BackupHookLocal
import java.io.File
import org.json.JSONObject

// ── Data ──
sealed class SettingsItem {
    data class Header(val title: String) : SettingsItem()
    data class Toggle(val label: String, val key: String, val def: Boolean = false) : SettingsItem()
    data class Input(val label: String, val key: String, val def: String = "", val hint: String = "") : SettingsItem()
    data class Button(val text: String, val action: String = "") : SettingsItem()
    data class Info(val text: String) : SettingsItem()
}

class SettingsActivity : AppCompatActivity() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private val configFile get() = File(filesDir, "settings_config.json")
    private val remoteCfgFile get() = File("/sdcard/Download/wxhook_backup/remote_config.json")
    private val rcloneCfgFile get() = File(filesDir, ".config/rclone/rclone.conf")

    private fun loadConfig(): JSONObject = runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
    private fun saveConfig(o: JSONObject) { configFile.writeText(o.toString()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            id = View.generateViewId()
        }
        setContentView(recyclerView)
        buildItems()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun buildItems() {
        val cfg = loadConfig()
        val items = mutableListOf<SettingsItem>()

        // ── Cloud sync section ──
        items.add(SettingsItem.Header("☁️ 云同步"))
        items.add(SettingsItem.Toggle("启用云同步", "sync_enabled", false))
        items.add(SettingsItem.Input("远程路径", "remote_path", "gdrive:wxhook-backup", "例: gdrive:wxhook-backup"))
        items.add(SettingsItem.Button("立即同步到云盘"))

        // ── Rclone config section ──
        items.add(SettingsItem.Header("🔑 rclone 配置"))
        items.add(SettingsItem.Info("在下方粘贴 rclone.conf 内容，包含 [remote] 和 token"))

        val rcloneConfText = if (rcloneCfgFile.exists()) rcloneCfgFile.readText() else ""
        items.add(SettingsItem.Input("", "rclone_conf", rcloneConfText, "在此粘贴配置"))
        items.add(SettingsItem.Button("保存 rclone 配置", "save_rclone"))

        // ── Backup section ──
        items.add(SettingsItem.Header("📂 备份设置"))
        items.add(SettingsItem.Input("备份路径", "backup_path", "/sdcard/Download/wxhook_backup"))
        items.add(SettingsItem.Toggle("压缩附件", "compress", false))

        recyclerView.adapter = SettingsAdapter(items) { action, data ->
            handleAction(action, data, cfg)
        }
    }

    private fun handleAction(action: String, data: Any?, cfg: JSONObject) {
        when (action) {
            "save_rclone" -> {
                val text = data as? String ?: return
                rcloneCfgFile.parentFile?.mkdirs()
                rcloneCfgFile.writeText(text)
                runOnUiThread { supportActionBar?.title = "设置 ✅ 配置已保存" }
            }
            "立即同步到云盘" -> doSync()
        }
    }

    private fun doSync() {
        Thread {
            val cfg = loadConfig()
            val enabled = cfg.optBoolean("sync_enabled", false)
            val remote = cfg.optString("remote_path", "")
            if (!enabled || remote.isBlank()) {
                runOnUiThread { supportActionBar?.title = "设置 ⚠️ 未启用或未配置路径" }; return@Thread
            }
            runOnUiThread { supportActionBar?.title = "设置 ☁️ 同步中..." }
            try {
                val args = mutableListOf(BackupHookLocal.binPath + "/rclone", "sync", "/sdcard/Download/wxhook_backup", remote, "--update")
                if (rcloneCfgFile.exists()) { args.add("--config"); args.add(rcloneCfgFile.absolutePath) }
                Runtime.getRuntime().exec(args.toTypedArray()).waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                runOnUiThread { supportActionBar?.title = "设置 ✅ 同步完成" }
            } catch (e: Exception) {
                runOnUiThread { supportActionBar?.title = "设置 ❌ 同步失败: ${e.message}" }
            }
        }.start()
    }
}

// ── Adapter ──
class SettingsAdapter(
    private val items: List<SettingsItem>,
    private val onAction: (String, Any?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0; const val TYPE_TOGGLE = 1
        const val TYPE_INPUT = 2; const val TYPE_BUTTON = 3; const val TYPE_INFO = 4
    }

    override fun getItemViewType(pos: Int) = when (items[pos]) {
        is SettingsItem.Header -> TYPE_HEADER; is SettingsItem.Toggle -> TYPE_TOGGLE
        is SettingsItem.Input -> TYPE_INPUT; is SettingsItem.Button -> TYPE_BUTTON
        is SettingsItem.Info -> TYPE_INFO
    }
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (vt) {
            TYPE_HEADER -> {
                val tv = TextView(ctx).apply {
                    textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF6200EE.toInt())
                    setPadding(20, 24, 20, 8); setBackgroundColor(0xFFF5F5F5.toInt())
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
            TYPE_TOGGLE -> {
                val card = CardView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    radius = 8f; cardElevation = 1f; setContentPadding(16, 8, 16, 8)
                    setCardBackgroundColor(Color.WHITE)
                    val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(16, 4, 16, 4)
                    }
                    layoutParams = lp
                }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    minimumHeight = 48
                }
                val label = TextView(ctx).apply {
                    id = View.generateViewId(); textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(label)
                val sw = Switch(ctx).apply { id = View.generateViewId() }
                row.addView(sw)
                card.addView(row)
                object : RecyclerView.ViewHolder(card) {}
            }
            TYPE_INPUT -> {
                val card = CardView(ctx).apply {
                    radius = 8f; cardElevation = 1f; setContentPadding(16, 8, 16, 8)
                    setCardBackgroundColor(Color.WHITE)
                    val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(16, 4, 16, 4)
                    }
                    layoutParams = lp
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val label = TextView(ctx).apply {
                    id = View.generateViewId(); textSize = 13f; setTextColor(0xFF757575.toInt())
                }
                col.addView(label)
                val et = EditText(ctx).apply {
                    id = View.generateViewId(); textSize = 14f
                    setPadding(0, 4, 0, 4); setBackgroundColor(Color.TRANSPARENT)
                }
                col.addView(et)
                card.addView(col)
                object : RecyclerView.ViewHolder(card) {}
            }
            TYPE_BUTTON -> {
                val btn = Button(ctx).apply {
                    textSize = 14f; setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF6200EE.toInt())
                    val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(16, 8, 16, 8)
                    }
                    layoutParams = lp
                }
                object : RecyclerView.ViewHolder(btn) {}
            }
            TYPE_INFO -> {
                val tv = TextView(ctx).apply {
                    textSize = 12f; setTextColor(0xFF9E9E9E.toInt())
                    setPadding(20, 4, 20, 4)
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
            else -> error("unknown type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val ctx = holder.itemView.context
        when (val item = items[pos]) {
            is SettingsItem.Header -> (holder.itemView as TextView).text = item.title
            is SettingsItem.Toggle -> {
                val card = holder.itemView as CardView
                val label = card.findViewWithTag<TextView>("label") ?: (card.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? TextView
                label?.text = item.label
                // Load actual value from config
                val cfg = runCatching { JSONObject(File(ctx.filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject())
                val sw = card.findViewWithTag<Switch>("switch") ?: (card.getChildAt(0) as? LinearLayout)?.getChildAt(1) as? Switch
                sw?.isChecked = cfg.optBoolean(item.key, item.def)
                sw?.setOnCheckedChangeListener { _, checked ->
                    val o = runCatching { JSONObject(File(ctx.filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject())
                    o.put(item.key, checked)
                    File(ctx.filesDir, "settings_config.json").writeText(o.toString())
                }
            }
            is SettingsItem.Input -> {
                val card = holder.itemView as CardView
                val col = card.getChildAt(0) as? LinearLayout
                val label = col?.getChildAt(0) as? TextView
                label?.text = item.label
                val et = col?.getChildAt(1) as? EditText
                if (et != null) {
                    if (item.key == "rclone_conf") {
                        et.setText(item.def); et.minLines = 10; et.gravity = Gravity.START
                        et.typeface = Typeface.MONOSPACE; et.textSize = 11f
                        et.setBackgroundColor(0xFFF5F5F5.toInt())
                        et.setPadding(8, 8, 8, 8)
                    } else {
                        et.setText(cfg.optString(item.key, item.def))
                        et.hint = item.hint
                    }
                    // Save on focus change
                    et.setOnFocusChangeListener { _, focused ->
                        if (!focused) {
                            val o = runCatching { JSONObject(File(ctx.filesDir, "settings_config.json").readText()) }.getOrDefault(JSONObject())
                            o.put(item.key, et.text.toString())
                            File(ctx.filesDir, "settings_config.json").writeText(o.toString())
                        }
                    }
                }
            }
            is SettingsItem.Button -> {
                val btn = holder.itemView as Button
                btn.text = item.text
                btn.setOnClickListener {
                    // For rclone save, pass the input text
                    if (item.action == "save_rclone") {
                        // Find the Input with key "rclone_conf" and get its text
                        for (i in items.indices) {
                            if (items[i] is SettingsItem.Input && (items[i] as SettingsItem.Input).key == "rclone_conf") {
                                val vh = recyclerView.findViewHolderForAdapterPosition(i)
                                if (vh != null) {
                                    val card = vh.itemView as CardView
                                    val col = card.getChildAt(0) as? LinearLayout
                                    val et = col?.getChildAt(1) as? EditText
                                    if (et != null) {
                                        onAction(item.action, et.text.toString())
                                        return@setOnClickListener
                                    }
                                }
                            }
                        }
                    }
                    onAction(item.text, null)
                }
            }
            is SettingsItem.Info -> (holder.itemView as TextView).text = item.text
        }
    }
}

