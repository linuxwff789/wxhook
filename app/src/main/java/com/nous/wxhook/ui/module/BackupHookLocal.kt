package com.nous.wxhook.ui.module

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Local backup for Manager App.
 * Uses /proc/PID/root/ to read WeChat files (no su needed).
 */
object BackupHookLocal {

    private const val RECORDS_FILE = "backup_records.json"
    private const val STATE_FILE = "backup_state.json"
    private const val WX_HASH = "6d1f34a5edc49e8b6d238141b2d004f3"

    interface ProgressCallback {
        fun onProgress(current: String, fileCount: Long, totalSize: Long)
    }

    fun doFullBackup(backupDir: String, callback: ProgressCallback? = null, compress: Boolean = true): Result {
        android.util.Log.i("wxhook:Backup", "doFullBackup called: dir=$backupDir, compress=$compress")
        return try {
            val dir = File(backupDir); if (!dir.exists()) dir.mkdirs()
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L; var fileCount = 0L; var totalSize = 0L

            val pid = getWxPid() ?: return Result(false, "微信未运行")

            // Backup DB
            callback?.onProgress("备份数据库...", fileCount, totalSize)
            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists()) {
                val ext = if (compress) ".db.gz" else ".db"
                val dbDst = File(dir, "EnMicroMsg_$tag$ext")
                if (compress) compressFile(dbSrc, dbDst) else copyFileJava(dbSrc, dbDst)
                dbSize = dbDst.length(); fileCount++; totalSize += dbSize
            }

            // Backup attachments via su shell script
                        callback?.onProgress("备份附件...", fileCount, totalSize)
            val wxBasePath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/$WX_HASH"
            val backupBasePath = dir.absolutePath
            for (attDir in listOf("image2", "voice2", "video", "cdn")) {
                val src = "\$wxBasePath/\$attDir"
                val dst = "\$backupBasePath/\$attDir"
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p \$dst")).waitFor()
                    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp -r \$src/* \$dst/ 2>/dev/null && chmod -R 644 \$dst/ 2>/dev/null"))
                    proc.waitFor()
                    val dstDir = File(dst)
                    if (dstDir.exists()) {
                        val count = dstDir.walkTopDown().filter { it.isFile }.count().toLong()
                        val size = dstDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        fileCount += count; totalSize += size
                    }
                    android.util.Log.i("wxhook:Backup", "Copied \$attDir")
                } catch (e: Exception) {
                    android.util.Log.e("wxhook:Backup", "Copy \$attDir failed: \$e")
                }
            }

            android.util.Log.i("wxhook:Backup", "Backup done: fileCount=$fileCount, totalSize=$totalSize")
            saveState(tag, fileCount, totalSize)
            addRecord(createRecord(tag, "full", dbSize, fileCount, totalSize, "全量备份完成"))
            Result(true, "全量备份完成: ${fileCount}个文件, ${formatSize(totalSize)}")
        } catch (e: Exception) { Result(false, "备份失败: ${e.message}") }
    }

    fun doIncrementalBackup(backupDir: String, callback: ProgressCallback? = null, compress: Boolean = true): Result {
        return try {
            val dir = File(backupDir); if (!dir.exists()) dir.mkdirs()
            val state = loadState(backupDir)
            val lastTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L; var fileCount = 0L; var totalSize = 0L; var newFiles = 0L

            val pid = getWxPid() ?: return Result(false, "微信未运行")

            // Check DB
            callback?.onProgress("检查数据库...", fileCount, totalSize)
            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists() && dbSrc.lastModified() > lastTime) {
                val ext = if (compress) ".db.gz" else ".db"
                val dbDst = File(dir, "EnMicroMsg_$tag$ext")
                dir.listFiles()?.filter { it.name.startsWith("EnMicroMsg_") && (it.name.endsWith(".db.gz") || it.name.endsWith(".db")) }?.forEach { it.delete() }
                if (compress) compressFile(dbSrc, dbDst) else copyFileJava(dbSrc, dbDst)
                dbSize = dbDst.length(); fileCount++; totalSize += dbSize; newFiles++
            }

            // Incremental attachments via su -c cp
            callback?.onProgress("增量备份附件...", fileCount, totalSize)
            val wxBasePath = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/$WX_HASH"
            val backupBasePath = dir.absolutePath
            for (attDir in listOf("image2", "voice2", "video", "cdn")) {
                val src = "\$wxBasePath/\$attDir"
                val dst = "\$backupBasePath/\$attDir"
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p \$dst")).waitFor()
                    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp -r \$src/* \$dst/ 2>/dev/null && chmod -R 644 \$dst/ 2>/dev/null"))
                    proc.waitFor()
                    val dstDir = File(dst)
                    if (dstDir.exists()) {
                        val count = dstDir.walkTopDown().filter { it.isFile }.count().toLong()
                        val size = dstDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        fileCount += count; totalSize += size; newFiles += count
                    }
                    android.util.Log.i("wxhook:Backup", "Copied \$attDir")
                } catch (e: Exception) {
                    android.util.Log.e("wxhook:Backup", "Copy \$attDir failed: \$e")
                }
            }

            android.util.Log.i("wxhook:Backup", "Backup done: fileCount=$fileCount, totalSize=$totalSize")
            saveState(tag, fileCount, totalSize)
            addRecord(createRecord(tag, "incremental", dbSize, fileCount, totalSize, if (newFiles > 0) "增量: ${newFiles}个新文件" else "无新文件"))
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个新文件, ${formatSize(totalSize)}" else "无新文件"
            Result(true, msg)
        } catch (e: Exception) { Result(false, "增量备份失败: ${e.message}") }
    }

    // ── Helpers ──

    private fun getWxPid(): String? {
        android.util.Log.i("wxhook:Backup", "getWxPid called")
        // Try /proc approach first
        try {
            val procDir = File("/proc")
            procDir.listFiles()?.forEach { dir ->
                if (dir.name.toIntOrNull() != null) {
                    val cmdline = File(dir, "cmdline")
                    if (cmdline.exists()) {
                        val content = cmdline.readText()
                        if (content.contains("com.tencent.mm")) { android.util.Log.i("wxhook:Backup", "Found wx pid: ${dir.name}"); return dir.name }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: try su
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
            val pid = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pid.isNotEmpty()) return pid
        } catch (_: Exception) {}

        android.util.Log.i("wxhook:Backup", "getWxPid: no pid found")
        return null
    }

    private fun copyFileJava(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
        dst.setReadable(true, false)
    }

    private fun compressFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            GZIPOutputStream(FileOutputStream(dst)).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
    }

    private fun copyDirJava(wxBase: String, attDir: String, dstDir: File): Pair<Long, Long> {
        android.util.Log.i("wxhook:Backup", "copyDirJava start: src=$wxBase/$attDir")
        android.util.Log.i("wxhook:Backup", "copyDirJava: src=$wxBase/$attDir, dst=${dstDir.absolutePath}")
        val srcDir = File("$wxBase/$attDir")
        if (!srcDir.exists()) { android.util.Log.w("wxhook:Backup", "srcDir not exists: ${srcDir.absolutePath}"); return Pair(0, 0) }

        val files = srcDir.listFiles()
        android.util.Log.i("wxhook:Backup", "srcDir files: ${files?.size ?: 0}")
        var count = 0L; var size = 0L
        files?.forEach { file ->
            if (file.isDirectory) {
                val sub = File(dstDir, file.name); sub.mkdirs()
                val r = copyDirJava("$wxBase/$attDir/${file.name}", "", sub)
                count += r.first; size += r.second
            } else {
                val dstFile = File(dstDir, file.name)
                copyFileJava(file, dstFile)
                count++; size += file.length()
            }
        }
        return Pair(count, size)
    }

    private fun copyDirIncrementalJava(wxBase: String, attDir: String, dstDir: File, lastTime: Long): Triple<Long, Long, Int> {
        val srcDir = File("$wxBase/$attDir")
        if (!srcDir.exists()) return Triple(0, 0, 0)

        var count = 0L; var size = 0L; var newCount = 0
        srcDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val sub = File(dstDir, file.name); sub.mkdirs()
                val r = copyDirIncrementalJava("$wxBase/$attDir/${file.name}", "", sub, lastTime)
                count += r.first; size += r.second; newCount += r.third
            } else {
                val dstFile = File(dstDir, file.name)
                if (!dstFile.exists() || file.lastModified() > lastTime) {
                    copyFileJava(file, dstFile)
                    newCount++
                }
                count++; size += file.length()
            }
        }
        return Triple(count, size, newCount)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes > 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / 1024 / 1024)
            bytes > 1024 -> "%.1f KB".format(bytes.toFloat() / 1024)
            else -> "$bytes B"
        }
    }

    private fun createRecord(tag: String, type: String, dbSize: Long, fileCount: Long, totalSize: Long, message: String): JSONObject {
        return JSONObject().apply {
            put("tag", tag); put("type", type); put("time", System.currentTimeMillis())
            put("dbSize", dbSize); put("fileCount", fileCount); put("totalSize", totalSize); put("message", message)
        }
    }

    private fun addRecord(record: JSONObject) {
        android.util.Log.i("wxhook:Backup", "addRecord called")
        val dir = File(com.nous.wxhook.db.BackupManager.BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, RECORDS_FILE)
        val arr = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
        arr.put(record); while (arr.length() > 50) arr.remove(0)
        f.writeText(arr.toString())
    }

    private fun saveState(tag: String, count: Long, size: Long) {
        val state = JSONObject().apply { put("lastBackupTime", System.currentTimeMillis()); put("lastBackupTag", tag); put("fileCount", count); put("totalSize", size) }
        File(com.nous.wxhook.db.BackupManager.BACKUP_DIR, STATE_FILE).writeText(state.toString())
    }

    private fun loadState(dir: String): JSONObject {
        val f = File(dir, STATE_FILE)
        return if (f.exists()) try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() } else JSONObject()
    }

    data class Result(val success: Boolean, val message: String)
}
