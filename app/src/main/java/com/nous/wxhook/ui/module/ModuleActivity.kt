package com.nous.wxhook.ui.module

import android.content.Intent
import android.content.Context
import org.json.JSONObject
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nous.wxhook.db.BackupManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ModuleActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var backupBtn: Button
    private lateinit var incrBtn: Button
    private lateinit var pathInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var isBackingUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.nous.wxhook.util.SetupManager.setup(this)
        com.nous.wxhook.ui.module.BackupHookLocal.init(this)

        val sv = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xFFF5F5F5.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "wxhook 模块"
            textSize = 22f; setTextColor(0xFF6200EE.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16))
        })

        // Settings button (gear icon)
        root.addView(Button(this).apply {
            text = "⚙️ 设置"
            textSize = 13f; setTextColor(0xFF6200EE.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.END
            setOnClickListener { startActivity(Intent(this@ModuleActivity, com.nous.wxhook.ui.settings.SettingsActivity::class.java)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END }
        })

        // ── Status card ──
        root.addView(makeCardTitle("📊 状态"))
        val statusCard = makeCard()
        val statusText = TextView(this).apply { textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8)); typeface = Typeface.MONOSPACE }
        Thread { val s = getStatusText(); handler.post { statusText.text = s } }.start()

        // 检测按钮
        val checkBtn = Button(this).apply {
            text = "🔍 检测环境"
            setOnClickListener { checkEnvironment(statusText) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        statusCard.addView(checkBtn)
        statusCard.addView(statusText)
        root.addView(statusCard)

        // ── Backup path ──
        root.addView(makeCardTitle("📁 备份路径"))
        val pathCard = makeCard()
        pathInput = EditText(this).apply {
            setText(BackupManager.BACKUP_DIR)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        pathCard.addView(pathInput)
        val savePathBtn = Button(this).apply {
            text = "保存路径"; textSize = 12f
            setOnClickListener {
                val path = pathInput.text.toString().trim()
                if (path.isNotEmpty()) {
                    File(path).mkdirs()
                    log("📁 路径已保存: $path")
                }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        pathCard.addView(savePathBtn)
        root.addView(pathCard)

        // ── Remote config card ──
        root.addView(makeCardTitle("☁️ 云同步"))
        val remoteCard = makeCard()
        val remoteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)); gravity = Gravity.CENTER_VERTICAL }
        remoteRow.addView(TextView(this).apply { text = "启用云同步"; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val remoteSwitch = Switch(this).apply {
            isChecked = runCatching { JSONObject(java.io.File("/sdcard/Download/wxhook_backup/remote_config.json").readText()).optBoolean("enabled", false) }.getOrDefault(false)
            setOnCheckedChangeListener { _, checked -> runCatching { val f = java.io.File("/sdcard/Download/wxhook_backup/remote_config.json"); val o = if (f.exists()) JSONObject(f.readText()) else JSONObject(); o.put("enabled", checked); f.writeText(o.toString()) } }
        }
        remoteRow.addView(remoteSwitch)
        remoteCard.addView(remoteRow)

        // Remote path input
        val remotePathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)); gravity = Gravity.CENTER_VERTICAL }
        remotePathRow.addView(TextView(this).apply { text = "远程路径: "; textSize = 13f })
        val remotePathInput = EditText(this).apply {
            setText(runCatching { JSONObject(java.io.File("/sdcard/Download/wxhook_backup/remote_config.json").readText()).optString("remote", "gdrive:wxhook-backup") }.getOrDefault("gdrive:wxhook-backup"))
            textSize = 13f; setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        remotePathRow.addView(remotePathInput)
        val saveRemoteBtn = Button(this).apply {
            text = "保存"; textSize = 12f
            setOnClickListener {
                runCatching { val f = java.io.File("/sdcard/Download/wxhook_backup/remote_config.json"); val o = if (f.exists()) JSONObject(f.readText()) else JSONObject(); o.put("remote", remotePathInput.text.toString().trim()); f.writeText(o.toString()) }
                log("☁️ 远程路径已保存: ${remotePathInput.text}")
            }
        }
        remotePathRow.addView(saveRemoteBtn)
        remoteCard.addView(remotePathRow)

        // Sync button
        val syncBtn = Button(this).apply {
            text = "立即同步到云盘"; textSize = 12f
            setOnClickListener { doSync() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        remoteCard.addView(syncBtn)

        // Rclone config editor
        val rcloneCfgDir = File(filesDir, ".config/rclone")
        rcloneCfgDir.mkdirs()
        val rcloneCfgFile = File(rcloneCfgDir, "rclone.conf")
        val cfgLabel = TextView(this).apply {
            text = "rclone 配置 (rclone.conf)"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        remoteCard.addView(cfgLabel)
        val rcloneCfgInput = EditText(this).apply {
            setText(if (rcloneCfgFile.exists()) rcloneCfgFile.readText() else "# 在此粘贴 rclone.conf\n# 格式:\n# [remote_name]\n# type = drive\n# scope = drive\n# token = {...}")
            textSize = 10f; typeface = Typeface.MONOSPACE
            minLines = 8; gravity = Gravity.START
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFFF0F0F0.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        remoteCard.addView(rcloneCfgInput)
        val saveCfgBtn = Button(this).apply {
            text = "保存配置"; textSize = 12f
            setOnClickListener {
                runCatching { rcloneCfgFile.writeText(rcloneCfgInput.text.toString()); log("✅ rclone 配置已保存") }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        remoteCard.addView(saveCfgBtn)
        root.addView(remoteCard)

        // ── Backup card ──
        root.addView(makeCardTitle("💾 备份"))
        val backupCard = makeCard()

        // 压缩开关
        val compressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        compressRow.addView(TextView(this).apply { text = "压缩备份 (gzip)"; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val compressSwitch = Switch(this).apply { isChecked = true }
        compressRow.addView(compressSwitch)
        backupCard.addView(compressRow)

        backupBtn = Button(this).apply {
            text = "全量备份 (DB + 附件)"
            setOnClickListener {
                android.util.Log.e("wxhook:CLICK", "full backup clicked")
                doBackup(false, compressSwitch.isChecked)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) }
        }
        backupCard.addView(backupBtn)

        incrBtn = Button(this).apply {
            text = "增量备份 (仅新文件)"
            setOnClickListener {
                android.util.Log.e("wxhook:CLICK", "incremental backup clicked")
                doBackup(true, compressSwitch.isChecked)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        }
        backupCard.addView(incrBtn)
        root.addView(backupCard)

        // ── Records card ──
        root.addView(makeCardTitle("📋 备份记录"))
        val recordsCard = makeCard()
        logView = TextView(this).apply {
            textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(0xFF424242.toInt())
        }
        recordsCard.addView(logView)
        root.addView(recordsCard)

        // Load records
        Thread { try { val records = BackupManager.getRecords(); val sb = StringBuilder(); records.take(10).forEach { r -> val time = BackupManager.formatTime(r.time); val size = BackupManager.formatSize(r.totalSize); val type = if (r.type == "full") "全量" else "增量"; sb.appendLine("[$time] $type | $size | ${r.fileCount}文件"); sb.appendLine("  ${r.message}") }; handler.post { logView.text = sb.toString() } } catch (e: Exception) { handler.post { logView.text = "记录加载失败" } } }.start()

        sv.addView(root)
        setContentView(sv)
    }

    private fun doBackup(incremental: Boolean, compress: Boolean = true) {
        android.util.Log.e("wxhook:CLICK", "doBackup incremental=$incremental compress=$compress")
        val btn = if (incremental) incrBtn else backupBtn
        if (isBackingUp) { log("⏳ 正在备份中..."); return }
        isBackingUp = true
        backupBtn.isEnabled = false; incrBtn.isEnabled = false
        backupBtn.text = "备份中..."; incrBtn.text = "备份中..."

        Thread {
            val dir = pathInput.text.toString().trim()
            val cb = object : BackupHookLocal.ProgressCallback {
                override fun onProgress(current: String, fileCount: Long, totalSize: Long) {
                    handler.post { log("⏳ $current") }
                }
            }
            val result = if (incremental) {
                BackupHookLocal.doIncrementalBackup(cb)
            } else {
                BackupHookLocal.doFullBackup(cb)
            }

            handler.post {
                if (result.success) {
                    val sizeStr = "备份完成"
                    log("✅ ${result.message} ($sizeStr)")
                } else {
                    log("❌ ${result.message}")
                }
                Thread { try { val records = BackupManager.getRecords(); val sb = StringBuilder(); records.take(10).forEach { r -> val time = BackupManager.formatTime(r.time); val size = BackupManager.formatSize(r.totalSize); val type = if (r.type == "full") "全量" else "增量"; sb.appendLine("[$time] $type | $size | ${r.fileCount}文件"); sb.appendLine("  ${r.message}") }; handler.post { logView.text = sb.toString() } } catch (e: Exception) { handler.post { logView.text = "记录加载失败" } } }.start()
                isBackingUp = false
                backupBtn.isEnabled = true; incrBtn.isEnabled = true
                backupBtn.text = "全量备份 (DB + 附件)"; incrBtn.text = "增量备份 (仅新文件)"
            }
        }.start()
    }

    private fun refreshRecords() {
        Thread {
        try {
        val records = BackupManager.getRecords()
        if (records.isEmpty()) {
            handler.post { logView.text = "暂无备份记录" }
            return@Thread
        }
        val sb = StringBuilder()
        records.take(10).forEach { r ->
            val time = BackupManager.formatTime(r.time)
            val size = BackupManager.formatSize(r.totalSize)
            val type = if (r.type == "full") "全量" else "增量"
            sb.appendLine("[$time] $type | $size | ${r.fileCount}文件")
            sb.appendLine("  ${r.message}")
        }
        handler.post { logView.text = sb.toString() }
        } catch (e: Exception) {
            handler.post { logView.text = "记录加载失败: ${e.message}" }
        }
        }.start()
    }

    private fun getStatusText(): String {
        try {
        val sb = StringBuilder()
        try {
            val keyFile = File("/data/local/tmp/.wechat_key")
            if (keyFile.exists()) {
                val key = keyFile.readText().lines().find { it.startsWith("key=") } ?: "未知"
                sb.appendLine("  密钥: $key")
            } else {
                sb.appendLine("  密钥: 未捕获")
            }
        } catch (_: Exception) { sb.appendLine("  密钥: 读取失败") }
        val dbFile = File("/sdcard/Download/EnMicroMsg.db")
        if (dbFile.exists()) {
            sb.appendLine("  数据库: ${BackupManager.formatSize(dbFile.length())}")
        } else {
            sb.appendLine("  数据库: 未复制")
        }
        val info = BackupManager.getBackupInfo()
        sb.appendLine("  备份目录: ${info.optString("backupDir", "无")}")
        sb.appendLine("  备份文件: ${info.optInt("fileCount", 0)}个")
        sb.appendLine("  最后备份: ${BackupManager.formatTime(info.optLong("lastBackupTime", 0))}")
        return sb.toString()
        } catch (e: Exception) { return "状态加载失败: ${e.message}" }
    }

    private fun checkEnvironment(statusText: TextView) {
        Thread {
            val sb = StringBuilder()
            sb.appendLine("=== 环境检测 ===")

            // 1. Root 检测
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = proc.inputStream.bufferedReader().readText().trim()
                val exit = proc.waitFor()
                if (exit == 0 && output.contains("uid=0")) {
                    sb.appendLine("✅ Root: 正常 (${output})")
                } else {
                    sb.appendLine("❌ Root: 失败 (exit=$exit)")
                }
            } catch (e: Exception) {
                sb.appendLine("❌ Root: 异常 (${e.message})")
            }

            // 2. Xposed 模块检测
            try {
                // 检查 Xposed 模块包是否存在
                val xpPkg = "com.nous.wxhook.xposed"
                val xpProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm list packages | grep $xpPkg"))
                val xpOutput = xpProc.inputStream.bufferedReader().readText().trim()
                xpProc.waitFor()
                if (xpOutput.contains(xpPkg)) {
                    sb.appendLine("✅ Xposed 模块: 已安装")
                } else {
                    sb.appendLine("❌ Xposed 模块: 未安装")
                }

                // 检查 LSPosed 是否加载了模块
                val lsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls /data/adb/lspd/modules/"))
                val lsOutput = lsProc.inputStream.bufferedReader().readText().trim()
                lsProc.waitFor()
                if (lsOutput.contains("wxhook")) {
                    sb.appendLine("✅ LSPosed: 模块已注册")
                } else {
                    sb.appendLine("⚠️ LSPosed: 模块未注册")
                }

                // 检查 Xposed 日志
                val logProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -d | grep 'wxhook:Hook' | tail -1"))
                val logOutput = logProc.inputStream.bufferedReader().readText().trim()
                logProc.waitFor()
                if (logOutput.isNotEmpty()) {
                    sb.appendLine("✅ Xposed Hook: 已加载")
                    sb.appendLine("   $logOutput")
                } else {
                    sb.appendLine("⚠️ Xposed Hook: 未检测到日志")
                }
            } catch (e: Exception) {
                sb.appendLine("❌ Xposed: 检测失败")
            }

            // 3. 微信进程检测
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
                val pid = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                if (pid.isNotEmpty()) {
                    sb.appendLine("✅ 微信: 运行中 (pid=$pid)")
                } else {
                    sb.appendLine("❌ 微信: 未运行")
                }
            } catch (e: Exception) {
                sb.appendLine("❌ 微信: 检测失败")
            }

            // 3. 文件访问检测
            try {
                val dbFile = File("/sdcard/Download/EnMicroMsg.db")
                if (dbFile.exists()) {
                    sb.appendLine("✅ 数据库: 存在 (${BackupManager.formatSize(dbFile.length())})")
                } else {
                    sb.appendLine("⚠️ 数据库: 不存在")
                }
            } catch (e: Exception) {
                sb.appendLine("❌ 数据库: 检测失败")
            }

            // 4. 备份目录检测
            val backupDir = File(pathInput.text.toString().trim())
            sb.appendLine("${if (backupDir.exists()) "✅" else "⚠️"} 备份目录: ${backupDir.absolutePath}")

            // 5. 密钥检测
            try {
                val keyFile = File("/data/local/tmp/.wechat_key")
                if (keyFile.exists()) {
                    val key = keyFile.readText().lines().find { it.startsWith("key=") } ?: "未知"
                    sb.appendLine("✅ 密钥: $key")
                } else {
                    sb.appendLine("⚠️ 密钥: 未捕获")
                }
            } catch (e: Exception) {
                sb.appendLine("❌ 密钥: 读取失败")
            }

            handler.post { statusText.text = sb.toString() }
        }.start()
    }

    private fun doSync() {
        Thread {
            val remote = runCatching { JSONObject(java.io.File("/sdcard/Download/wxhook_backup/remote_config.json").readText()).optString("remote", "gdrive:wxhook-backup") }.getOrDefault("gdrive:wxhook-backup") ?: "gdrive:wxhook-backup"
            try {
                log("☁️ 同步到 $remote...")
                val configPath = File(filesDir, ".config/rclone/rclone.conf")
                val rcloneArgs = mutableListOf(com.nous.wxhook.ui.module.BackupHookLocal.binPath + "/rclone", "sync", "/sdcard/Download/wxhook_backup", remote, "--update")
                if (configPath.exists()) { rcloneArgs.add("--config"); rcloneArgs.add(configPath.absolutePath) }
                val proc = Runtime.getRuntime().exec(rcloneArgs.toTypedArray())
                proc.waitFor()
                handler.post { log("☁️ 同步完成") }
            } catch (e: Exception) {
                handler.post { log("☁️ 同步失败: ${e.message}") }
            }
        }.start()
    }

    private fun log(msg: String) {
        android.util.Log.i("wxhook:Backup", msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.text = "[$time] $msg\n${logView.text}"
    }

    private fun makeCardTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(0xFF424242.toInt())
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(6))
        }
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) }
            val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE); setStroke(1, 0xFFE0E0E0.toInt()) }
            background = bg
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
