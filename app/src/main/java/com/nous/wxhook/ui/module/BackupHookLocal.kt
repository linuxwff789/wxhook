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
                val dbGzFile = File(userDir, "EnMicroMsg_baseline.sql.gz")
                val decResult = decryptAndDump(dbSrc)
                if (decResult.startsWith("OK:")) {
                    val gzPath = decResult.substring(3)
                    val gzFile = java.io.File(gzPath)
                    if (gzFile.exists()) {
                        gzFile.renameTo(dbGzFile)
                        totalFiles++; totalSize += dbGzFile.length()
                    }
                }
                if (!dbGzFile.exists()) {
                    compressFileSu(dbSrc, dbGzFile.absolutePath)
                    if (dbGzFile.exists()) { totalFiles++; totalSize += dbGzFile.length() }
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
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "cp -r " + src + " " + dst + " 2>/dev/null && chmod -R 644 " + dst + " 2>/dev/null")).waitFor()
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
                if (incrSql.isNotEmpty() && incrSql.contains("INSERT INTO message")) {
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

    private var cachedPassword: String? = null

    private fun getDbPassword(): String {
        if (cachedPassword != null) return cachedPassword!!
        cachedPassword = try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /data/local/tmp/.wechat_key 2>/dev/null"))
            val raw = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            // Parse key=hex format: key=65396364326165 → e9cd2ae
            val keyLine = raw.lines().firstOrNull { it.startsWith("key=") }
            if (keyLine != null) {
                val hex = keyLine.removePrefix("key=").trim()
                // Convert hex to ASCII bytes
                var pwd = ""
                for (i in hex.indices step 2) {
                    if (i + 1 < hex.length) {
                        val byte = hex.substring(i, i + 2).toIntOrNull(16) ?: continue
                        if (byte > 0) pwd += byte.toChar()
                    }
                }
                if (pwd.isNotEmpty()) pwd
                else hex // fallback: use raw hex
            } else {
                // Try db_config.json
                val cfg = java.io.File(BACKUP_DIR, "db_config.json")
                if (cfg.exists()) JSONObject(cfg.readText()).optString("password", "") else ""
            }
        } catch (_: Exception) { "" }
        return cachedPassword ?: ""
    }

    private fun decryptAndDump(dbPath: String): String {
        // Direct command via sh -c & (no script file, no chmod issues)
        val pwd = getDbPassword()
        val tmpDir = "/sdcard/Download/wxhook_backup/tmp"
        val doneFile = "$tmpDir/decrypt_full_done.txt"
        val gzFile = "$tmpDir/EnMicroMsg_baseline.sql.gz"
        val cmd = "mkdir -p $tmpDir && " +
            "cp \"" + dbPath + "\" $tmpDir/wxhook_dec.db && " +
            "LD_PRELOAD='/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6' " +
            "/data/local/sqlcipher $tmpDir/wxhook_dec.db " +
            "-cmd 'PRAGMA key = \"" + pwd + "\";' " +
            "-cmd 'PRAGMA cipher_compatibility = 3;' " +
            "-cmd 'PRAGMA cipher_page_size = 1024;' " +
            "-cmd 'PRAGMA kdf_iter = 4000;' " +
            "-cmd 'PRAGMA cipher_use_hmac = OFF;' " +
            "-cmd '.mode insert' " +
            "2>/dev/null | gzip -c > $gzFile && " +
            "rm -f $tmpDir/wxhook_dec.db && " +
            "echo done > $doneFile"
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "sh -c '" + cmd.replace("'", "'\\''") + "' &")).waitFor()
            var waited = 0; val maxWait = 300
            while (waited < maxWait) {
                Thread.sleep(1000); waited++
                if (java.io.File(doneFile).exists()) {
                    java.io.File(doneFile).delete()
                    if (java.io.File(gzFile).exists()) return "OK:$gzFile"
                    break
                }
            }
            ""
        } catch (e: Exception) { android.util.Log.e("wxhook:Backup", "decryptAndDump: $e"); "" }
    }
