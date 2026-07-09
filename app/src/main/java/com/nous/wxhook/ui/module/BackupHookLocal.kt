package com.nous.wxhook.ui.module

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup structure:
 *   backupDir/
 *     db_config.json          (passwords, params)
 *     20260710_030000/        (each backup = one timestamp folder)
 *       <user_hash>/          (per user)
 *         EnMicroMsg_xxx.db.gz
 *         image2/
 *         voice2/
 *         ...
 *     backup_records.json
 *     backup_state.json
 */
object BackupHookLocal {

    private const val RECORDS_FILE = "backup_records.json"
    private const val STATE_FILE = "backup_state.json"
    private const val DB_CONFIG_FILE = "db_config.json"

    interface ProgressCallback {
        fun onProgress(current: String, fileCount: Long, totalSize: Long)
    }

    private val ATT_DIRS = listOf("image2", "voice2", "video", "emoji", "avatar", "cdn", "record", "favorite")

    fun doFullBackup(backupDir: String, callback: ProgressCallback? = null, compress: Boolean = true): Result {
        return try {
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val timeDir = File(backupDir, tag)
            var totalFiles = 0L; var totalSize = 0L

            val wxPaths = findWxPaths()
            if (wxPaths.isEmpty()) return Result(false, "微信未运行或未找到数据")

            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(timeDir, userHash)
                userDir.mkdirs()

                // DB
                callback?.onProgress("[$userHash] 数据库...", totalFiles, totalSize)
                val dbSrc = "$wxBasePath/EnMicroMsg.db"
                val dbDst = File(userDir, "EnMicroMsg_$tag.db.gz")
                compressFileSu(dbSrc, dbDst.absolutePath)
                if (dbDst.exists()) { totalFiles++; totalSize += dbDst.length() }

                // Attachments
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

            saveDbConfig(backupDir)
            saveState(tag, totalFiles, totalSize)
            addRecord(createRecord(tag, "full", totalFiles, totalSize, "全量备份完成"))
            Result(true, "全量备份完成: ${totalFiles}个文件, ${formatSize(totalSize)}")
        } catch (e: Exception) { Result(false, "备份失败: ${e.message}") }
    }

    fun doIncrementalBackup(backupDir: String, callback: ProgressCallback? = null, compress: Boolean = true): Result {
        return try {
            val state = loadState(backupDir)
            val lastTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val timeDir = File(backupDir, tag)
            var totalFiles = 0L; var totalSize = 0L; var newFiles = 0L

            val wxPaths = findWxPaths()
            if (wxPaths.isEmpty()) return Result(false, "微信未运行或未找到数据")

            for (wxBasePath in wxPaths) {
                val userHash = wxBasePath.substringAfterLast("/")
                val userDir = File(timeDir, userHash)
                userDir.mkdirs()

                // DB
                val dbSrc = File("$wxBasePath/EnMicroMsg.db")
                if (dbSrc.exists() && dbSrc.lastModified() > lastTime) {
                    callback?.onProgress("[$userHash] 数据库更新...", totalFiles, totalSize)
                    val dbDst = File(userDir, "EnMicroMsg_$tag.db.gz")
                    compressFileSu(dbSrc.absolutePath, dbDst.absolutePath)
                    if (dbDst.exists()) { totalFiles++; totalSize += dbDst.length(); newFiles++ }
                }

                // Attachments
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

            saveDbConfig(backupDir)
            saveState(tag, totalFiles, totalSize)
            addRecord(createRecord(tag, "incremental", totalFiles, totalSize, if (newFiles > 0) "增量: ${newFiles}个新文件" else "无新文件"))
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个新文件, ${formatSize(totalSize)}" else "无新文件"
            Result(true, msg)
        } catch (e: Exception) { Result(false, "增量备份失败: ${e.message}") }
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

    private fun compressFileSu(srcPath: String, dstPath: String) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "gzip -c '$srcPath' > '$dstPath' && chmod 644 '$dstPath'"))
            proc.waitFor()
        } catch (_: Exception) {}
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
        val dir = File(com.nous.wxhook.db.BackupManager.BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, RECORDS_FILE)
        val arr = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
        arr.put(record); while (arr.length() > 50) arr.remove(0)
        f.writeText(arr.toString())
    }

    private fun saveState(tag: String, count: Long, size: Long) {
        val state = JSONObject().apply { put("lastBackupTime", System.currentTimeMillis()); put("lastBackupTag", tag); put("fileCount", count); put("totalSize", size) }
        File(com.nous.wxhook.db.BackupManager.BACKUP_DIR, STATE_FILE).writeText(state.toString())
    }

    private fun loadState(dir: String): JSONObject {
        val f = File(dir, STATE_FILE)
        return if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
    }

    private fun saveDbConfig(backupDir: String) {
        val config = JSONObject().apply { put("password", "e9cd2ae"); put("savedAt", System.currentTimeMillis()) }
        File(backupDir, DB_CONFIG_FILE).writeText(config.toString())
    }

    data class Result(val success: Boolean, val message: String)
}
