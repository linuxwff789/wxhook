package com.nous.wxhook.ui.module

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Local backup for Manager App.
 * Uses su -c cp to access WeChat files (verified working).
 */
object BackupHookLocal {

    private const val RECORDS_FILE = "backup_records.json"
    private const val STATE_FILE = "backup_state.json"

    interface ProgressCallback {
        fun onProgress(current: String, fileCount: Long, totalSize: Long)
    }

    fun doFullBackup(backupDir: String, callback: ProgressCallback? = null): Result {
        return try {
            val dir = File(backupDir); if (!dir.exists()) dir.mkdirs()
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L; var fileCount = 0L; var totalSize = 0L

            // Get WeChat PID
            val pid = getWxPid() ?: return Result(false, "微信未运行")

            // Backup DB (gzip)
            callback?.onProgress("备份数据库...", fileCount, totalSize)
            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists()) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db.gz")
                compressFile(dbSrc, dbDst)
                dbSize = dbDst.length(); fileCount++; totalSize += dbSize
            }

            // Backup attachments via su
            val wxBase = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
            val attDirs = listOf("image2", "voice2", "video", "cdn")

            for (attDir in attDirs) {
                callback?.onProgress("备份 $attDir...", fileCount, totalSize)
                val dst = File(dir, attDir)
                if (!dst.exists()) dst.mkdirs()
                val count = copyDirSu(wxBase, attDir, dst)
                fileCount += count.first; totalSize += count.second
            }

            saveState(tag, fileCount, totalSize)
            addRecord(createRecord(tag, "full", dbSize, fileCount, totalSize, "全量备份完成"))
            Result(true, "全量备份完成: ${fileCount}个文件")
        } catch (e: Exception) { Result(false, "备份失败: ${e.message}") }
    }

    fun doIncrementalBackup(backupDir: String, callback: ProgressCallback? = null): Result {
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
                val dbDst = File(dir, "EnMicroMsg_$tag.db.gz")
                dir.listFiles()?.filter { it.name.startsWith("EnMicroMsg_") && it.name.endsWith(".db.gz") }?.forEach { it.delete() }
                compressFile(dbSrc, dbDst)
                dbSize = dbDst.length(); fileCount++; totalSize += dbSize; newFiles++
            }

            // Incremental attachments
            val wxBase = "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
            val attDirs = listOf("image2", "voice2", "video", "cdn")

            for (attDir in attDirs) {
                callback?.onProgress("增量 $attDir...", fileCount, totalSize)
                val dst = File(dir, attDir)
                if (!dst.exists()) dst.mkdirs()
                val count = copyDirIncrementalSu(wxBase, attDir, dst, lastTime)
                fileCount += count.first; totalSize += count.second; newFiles += count.third
            }

            saveState(tag, fileCount, totalSize)
            addRecord(createRecord(tag, "incremental", dbSize, fileCount, totalSize, if (newFiles > 0) "增量: ${newFiles}个新文件" else "无新文件"))
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个新文件" else "无新文件"
            Result(true, msg)
        } catch (e: Exception) { Result(false, "增量备份失败: ${e.message}") }
    }

    // ── Helpers ──

    private fun getWxPid(): String? {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
            val pid = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pid.isEmpty()) null else pid
        } catch (e: Exception) { null }
    }

    private fun compressFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            GZIPOutputStream(FileOutputStream(dst)).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
    }

    /**
     * Copy directory via su (recursive)
     * @return Pair(fileCount, totalSize)
     */
    private fun copyDirSu(wxBase: String, attDir: String, dstDir: File): Pair<Long, Long> {
        val srcPath = "$wxBase/$attDir"
        // Get file list via su
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "find '$srcPath' -type f 2>/dev/null"))
        val files = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()

        var count = 0L; var size = 0L
        for (filePath in files) {
            if (filePath.isBlank()) continue
            val relPath = filePath.removePrefix("$srcPath/")
            val dstFile = File(dstDir, relPath)
            dstFile.parentFile?.mkdirs()

            // Copy via su
            Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '$filePath' '${dstFile.absolutePath}' && chmod 644 '${dstFile.absolutePath}'")).waitFor()

            if (dstFile.exists()) {
                count++
                size += dstFile.length()
            }
        }
        return Pair(count, size)
    }

    /**
     * Incremental copy via su
     * @return Triple(fileCount, totalSize, newFileCount)
     */
    private fun copyDirIncrementalSu(wxBase: String, attDir: String, dstDir: File, lastTime: Long): Triple<Long, Long, Int> {
        val srcPath = "$wxBase/$attDir"
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "find '$srcPath' -type f -newer /proc/1/cmdline 2>/dev/null"))
        // Simpler: get all files, check modification via ls -l
        val proc2 = Runtime.getRuntime().exec(arrayOf("su", "-c", "find '$srcPath' -type f 2>/dev/null"))
        val files = proc2.inputStream.bufferedReader().readLines()
        proc2.waitFor()

        var count = 0L; var size = 0L; var newCount = 0
        for (filePath in files) {
            if (filePath.isBlank()) continue
            val relPath = filePath.removePrefix("$srcPath/")
            val dstFile = File(dstDir, relPath)

            // Check if file needs copying (newer or missing)
            if (!dstFile.exists() || dstFile.lastModified() < lastTime) {
                dstFile.parentFile?.mkdirs()
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '$filePath' '${dstFile.absolutePath}' && chmod 644 '${dstFile.absolutePath}'")).waitFor()
                if (dstFile.exists()) newCount++
            }
            count++
            if (dstFile.exists()) size += dstFile.length()
        }
        return Triple(count, size, newCount)
    }

    private fun createRecord(tag: String, type: String, dbSize: Long, fileCount: Long, totalSize: Long, message: String): JSONObject {
        return JSONObject().apply {
            put("tag", tag); put("type", type); put("time", System.currentTimeMillis())
            put("dbSize", dbSize); put("fileCount", fileCount); put("totalSize", totalSize); put("message", message)
        }
    }

    private fun addRecord(record: JSONObject) {
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
