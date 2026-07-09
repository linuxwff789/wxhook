package com.nous.wxhook.ui.module

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup system:
 * - Files (images/voice/video etc.) → git version control (auto dedup + incremental)
 * - Database → custom incremental (baseline SQL + incremental SQL dumps)
 * - Each backup = git commit + DB incremental dump
 */
object BackupHookLocal {

    private const val RECORDS_FILE = "backup_records.json"
    private const val STATE_FILE = "backup_state.json"
    private const val DB_CONFIG_FILE = "db_config.json"
    private const val DB_STATE_FILE = "db_state.json"
    private const val RCLONE_REMOTE = "gdrive:wxhook-backup"
    private const val BACKUP_DIR = "/sdcard/Download/wxhook_backup"

    interface ProgressCallback {
        fun onProgress(current: String, fileCount: Long, totalSize: Long)
    }

    private val ATT_DIRS = listOf("image2", "voice2", "video", "emoji", "avatar", "cdn", "record", "favorite")

    fun doFullBackup(callback: ProgressCallback? = null): Result {
        return try {
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(BACKUP_DIR); if (!dir.exists()) dir.mkdirs()
            var totalFiles = 0L; var totalSize = 0L

            // 1. Find WeChat users
            val wxPaths = findWxPaths()
            if (wxPaths.isEmpty()) return Result(false, "微信未运行或未找到数据")

            // 2. Backup DB (baseline)
            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(dir, userDir(userHash))
                userDir.mkdirs()

                callback?.onProgress("[$userHash] 数据库基线...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val dbDst = File(userDir, "EnMicroMsg_baseline.sql.gz")
                // Decrypt and dump full SQL
                val sqlDump = decryptAndDump(dbSrc)
                if (sqlDump.isNotEmpty()) {
                    File(userDir, "EnMicroMsg_baseline.sql.gz").writeBytes(compressGzip(sqlDump.toByteArray()))
                    totalFiles++; totalSize += dbDst.length()
                }

                // Save DB state
                saveDbState(userDir, tag)
            }

            // 3. Backup attachments (git will handle dedup)
            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(dir, userDir(userHash))
                userDir.mkdirs()

                for (attDir in ATT_DIRS) {
                    callback?.onProgress("[$userHash] $attDir...", totalFiles, totalSize)
                    val src = "$wxBasePath/$attDir"
                    val dst = "${userDir.absolutePath}/$attDir"
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p $dst")).waitFor()
                        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp -r $src $dst/ 2>/dev/null && chmod -R 644 $dst/ 2>/dev/null"))
                        proc.waitFor()
                        val d = File(dst)
                        if (d.exists()) {
                            totalFiles += d.walkTopDown().filter { it.isFile }.count()
                            totalSize += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("wxhook:Backup", "Copy $userHash/$attDir failed: $e")
                    }
                }
            }

            // 4. Git commit
            gitAddAndCommit(tag)
            rcloneSync(callback)

            // 5. Save config and records
            saveDbConfig()
            saveState(tag, totalFiles, totalSize)
            addRecord(createRecord(tag, "full", totalFiles, totalSize, "全量备份完成"))
            Result(true, "全量备份完成: ${totalFiles}个文件, ${formatSize(totalSize)}")
        } catch (e: Exception) { Result(false, "备份失败: ${e.message}") }
    }

    fun doIncrementalBackup(callback: ProgressCallback? = null): Result {
        return try {
            val state = loadState()
            val lastTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(BACKUP_DIR)
            var totalFiles = 0L; var totalSize = 0L; var newFiles = 0L

            val wxPaths = findWxPaths()
            if (wxPaths.isEmpty()) return Result(false, "微信未运行或未找到数据")

            // 1. DB incremental
            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(dir, userDir(userHash))
                userDir.mkdirs()

                val dbState = loadDbState(userDir)
                val lastRowId = dbState.optLong("lastMessageRowId", 0)

                callback?.onProgress("[$userHash] DB增量...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val incrSql = decryptIncremental(dbSrc, lastRowId)
                if (incrSql.isNotEmpty()) {
                    val incrFile = File(userDir, "incr_$tag.sql.gz")
                    incrFile.writeBytes(compressGzip(incrSql.toByteArray()))
                    totalFiles++; totalSize += incrFile.length(); newFiles++
                    // Update DB state
                    updateDbState(userDir, tag, incrSql)
                }
            }

            // 2. Attachments incremental (only newer files)
            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(dir, userDir(userHash))
                userDir.mkdirs()

                for (attDir in ATT_DIRS) {
                    val src = "$wxBasePath/$attDir"
                    val dst = "${userDir.absolutePath}/$attDir"
                    try {
                        val findProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "find $src -type f -newermt @$lastTime 2>/dev/null"))
                        val list = findProc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
                        findProc.waitFor()
                        if (list.isNotEmpty()) {
                            callback?.onProgress("[$userHash] 增量 $attDir: ${list.size}个", totalFiles, totalSize)
                            for (fp in list) {
                                val rel = fp.removePrefix("$src/")
                                val dstFile = File(dst, rel)
                                dstFile.parentFile?.mkdirs()
                                Runtime.getRuntime().exec(arrayOf("su", "-c", "cp \"$fp\" \"${dstFile.absolutePath}\" && chmod 644 \"${dstFile.absolutePath}\"")).waitFor()
                                if (dstFile.exists()) { totalFiles++; totalSize += dstFile.length(); newFiles++ }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("wxhook:Backup", "Incr $userHash/$attDir failed: $e")
                    }
                }
            }

            // 3. Git commit
            gitAddAndCommit(tag)
            rcloneSync(callback)

            saveState(tag, totalFiles, totalSize)
            addRecord(createRecord(tag, "incremental", totalFiles, totalSize, if (newFiles > 0) "增量: ${newFiles}个新文件" else "无新文件"))
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个新文件, ${formatSize(totalSize)}" else "无新文件"
            Result(true, msg)
        } catch (e: Exception) { Result(false, "增量备份失败: ${e.message}") }
    }

    // ── Git operations ──

    private fun gitAddAndCommit(tag: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "cd $BACKUP_DIR && git add -A && git commit -m 'backup: $tag' --allow-empty")).waitFor()
        } catch (_: Exception) {}
    }

    private fun rcloneSync(callback: ProgressCallback?) {
        try {
            val configFile = File(BACKUP_DIR, "remote_config.json")
            if (!configFile.exists()) return
            val config = JSONObject(configFile.readText())
            val enabled = config.optBoolean("enabled", false)
            if (!enabled) return
            val remote = config.optString("remote", "")
            if (remote.isBlank()) return
            callback?.onProgress("同步到 $remote...", 0, 0)
            val proc = Runtime.getRuntime().exec(arrayOf("rclone", "sync", BACKUP_DIR, remote, "--update"))
            proc.waitFor()
        } catch (_: Exception) {}
    }

    private fun userDir(hash: String) = hash

    // ── DB incremental backup ──

    private fun decryptAndDump(dbPath: String): String {
        // Decrypt DB and dump full SQL
        // Uses sqlcipher CLI with LD_PRELOAD
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "LD_PRELOAD='/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6' " +
                "/data/local/sqlcipher '$dbPath' << 'SQL'\n" +
                "PRAGMA key = 'e9cd2ae';\n" +
                "PRAGMA cipher_compatibility = 3;\n" +
                "PRAGMA cipher_page_size = 1024;\n" +
                "PRAGMA kdf_iter = 4000;\n" +
                "PRAGMA cipher_use_hmac = OFF;\n" +
                ".mode insert\n" +
                "SELECT * FROM message;\n" +
                "SELECT * FROM rconversation;\n" +
                "SQL"
            ))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output
        } catch (_: Exception) { "" }
    }

    private fun decryptIncremental(dbPath: String, lastRowId: Long): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "LD_PRELOAD='/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6' " +
                "/data/local/sqlcipher '$dbPath' << 'SQL'\n" +
                "PRAGMA key = 'e9cd2ae';
                PRAGMA cipher_compatibility = 3;\n" +
                "PRAGMA cipher_page_size = 1024;\n" +
                "PRAGMA kdf_iter = 4000;\n" +
                "PRAGMA cipher_use_hmac = OFF;\n" +
                ".mode insert\n" +
                "SELECT * FROM message WHERE rowid > $lastRowId;\n" +
                "SQL"
            ))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output
        } catch (_: Exception) { "" }
    }

    private fun compressGzip(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun saveDbState(userDir: File, tag: String) {
        val state = JSONObject().apply { put("lastBackupTag", tag); put("lastBackupTime", System.currentTimeMillis()) }
        File(userDir, DB_STATE_FILE).writeText(state.toString())
    }

    private fun loadDbState(userDir: File): JSONObject {
        val f = File(userDir, DB_STATE_FILE)
        return if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
    }

    private fun updateDbState(userDir: File, tag: String, incrSql: String) {
        val state = loadDbState(userDir)
        state.put("lastBackupTag", tag)
        state.put("lastBackupTime", System.currentTimeMillis())
        state.put("incrCount", state.optInt("incrCount", 0) + 1)
        // Try to extract last rowid from INSERT statements
        val lastInsert = incrSql.lines().lastOrNull { it.contains("INSERT INTO message") }
        // Save state
        File(userDir, DB_STATE_FILE).writeText(state.toString())
    }

    // ── Helpers ──

    private fun findWxPaths(): List<String> {
        val paths = mutableListOf<String>()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
            val pid = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pid.isNotEmpty()) {
                val basePath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg"
                val lsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls $basePath 2>/dev/null"))
                val dirs = lsProc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
                lsProc.waitFor()
                for (d in dirs) {
                    val check = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls $basePath/$d/EnMicroMsg.db 2>/dev/null"))
                    val out = check.inputStream.bufferedReader().readText().trim()
                    check.waitFor()
                    if (out.isNotEmpty()) paths.add("$basePath/$d")
                }
            }
        } catch (_: Exception) {}
        return paths
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes > 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / 1024 / 1024 / 1024)
            bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / 1024 / 1024)
            bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
            else -> "$bytes B"
        }
    }

    private fun createRecord(tag: String, type: String, fileCount: Long, totalSize: Long, message: String): JSONObject {
        return JSONObject().apply {
            put("tag", tag); put("type", type); put("time", System.currentTimeMillis())
            put("fileCount", fileCount); put("totalSize", totalSize); put("message", message)
        }
    }

    private fun addRecord(record: JSONObject) {
        val dir = File(BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, RECORDS_FILE)
        val arr = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
        arr.put(record); while (arr.length() > 50) arr.remove(0)
        f.writeText(arr.toString())
    }

    private fun saveState(tag: String, count: Long, size: Long) {
        val state = JSONObject().apply { put("lastBackupTime", System.currentTimeMillis()); put("lastBackupTag", tag); put("fileCount", count); put("totalSize", size) }
        File(BACKUP_DIR, STATE_FILE).writeText(state.toString())
    }

    private fun loadState(): JSONObject {
        val f = File(BACKUP_DIR, STATE_FILE)
        return if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
    }

    private fun saveDbConfig() {
        val config = JSONObject().apply { put("password", "e9cd2ae"); put("savedAt", System.currentTimeMillis()) }
        File(BACKUP_DIR, DB_CONFIG_FILE).writeText(config.toString())
    }

    data class Result(val success: Boolean, val message: String)
}
