package com.nous.wxhook.ui.module

import de.robv.android.xposed.XposedBridge
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
 * Local backup for Manager App (no Xposed dependency).
 */
object BackupHookLocal {

    private const val RECORDS_FILE = "backup_records.json"
    private const val STATE_FILE = "backup_state.json"

    fun doFullBackup(backupDir: String): Result {
        return try {
            val dir = File(backupDir); if (!dir.exists()) dir.mkdirs()
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L; var fileCount = 0L; var totalSize = 0L

            // Backup DB
            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists()) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db.gz")
                compressFile(dbSrc, dbDst)
                dbSize = dbDst.length(); fileCount++; totalSize += dbSize
            }

            // Backup attachments
            val wxBase = findWxDataPath()
            if (wxBase != null) {
                for (attDir in listOf("image2", "voice2", "video", "cdn")) {
                    val src = File(wxBase, attDir)
                    if (src.exists()) {
                        val r = copyDirRecursive(src, File(dir, attDir))
                        fileCount += r.first; totalSize += r.second
                    }
                }
            }

            saveState(tag, fileCount, totalSize)
            addRecord(createRecord(tag, "full", dbSize, fileCount, totalSize, "全量备份完成"))
            Result(true, "全量备份完成: ${fileCount}个文件")
        } catch (e: Exception) { Result(false, "备份失败: ${e.message}") }
    }

    fun doIncrementalBackup(backupDir: String): Result {
        return try {
            val dir = File(backupDir); if (!dir.exists()) dir.mkdirs()
            val state = loadState(backupDir)
            val lastTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L; var fileCount = 0L; var totalSize = 0L; var newFiles = 0L

            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists() && dbSrc.lastModified() > lastTime) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db.gz")
                dir.listFiles()?.filter { it.name.startsWith("EnMicroMsg_") && it.name.endsWith(".db.gz") }?.forEach { it.delete() }
                compressFile(dbSrc, dbDst)
                dbSize = dbDst.length(); fileCount++; totalSize += dbSize; newFiles++
            }

            val wxBase = findWxDataPath()
            if (wxBase != null) {
                for (attDir in listOf("image2", "voice2", "video", "cdn")) {
                    val src = File(wxBase, attDir)
                    if (src.exists()) {
                        val r = copyDirIncremental(src, File(dir, attDir), lastTime)
                        fileCount += r.first; totalSize += r.second; newFiles += r.third
                    }
                }
            }

            saveState(tag, fileCount, totalSize)
            addRecord(createRecord(tag, "incremental", dbSize, fileCount, totalSize, if (newFiles > 0) "增量: ${newFiles}个新文件" else "无新文件"))
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个新文件" else "无新文件"
            Result(true, msg)
        } catch (e: Exception) { Result(false, "增量备份失败: ${e.message}") }
    }

    private fun findWxDataPath(): String? {
        val basePath = "/data/data/com.tencent.mm/MicroMsg"
        val baseDir = File(basePath)
        if (!baseDir.exists()) return null
        baseDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && File(dir, "EnMicroMsg.db").exists()) return dir.absolutePath
        }
        return null
    }

    private fun compressFile(src: File, dst: File) {
        FileInputStream(src).use { input -> GZIPOutputStream(FileOutputStream(dst)).use { output -> input.copyTo(output, bufferSize = 8192) } }
    }

    private fun copyDirRecursive(src: File, dst: File): Pair<Long, Long> {
        var count = 0L; var size = 0L
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) { val sub = File(dst, file.name); sub.mkdirs(); val r = copyDirRecursive(file, sub); count += r.first; size += r.second }
            else { val dstFile = File(dst, file.name); FileInputStream(file).use { i -> FileOutputStream(dstFile).use { o -> i.copyTo(o, bufferSize = 8192) } }; dstFile.setReadable(true, false); count++; size += file.length() }
        }
        return Pair(count, size)
    }

    private fun copyDirIncremental(src: File, dst: File, lastTime: Long): Triple<Long, Long, Int> {
        var count = 0L; var size = 0L; var newCount = 0
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) { val sub = File(dst, file.name); sub.mkdirs(); val r = copyDirIncremental(file, sub, lastTime); count += r.first; size += r.second; newCount += r.third }
            else { val dstFile = File(dst, file.name); if (file.lastModified() > lastTime || !dstFile.exists()) { FileInputStream(file).use { i -> FileOutputStream(dstFile).use { o -> i.copyTo(o, bufferSize = 8192) } }; dstFile.setReadable(true, false); newCount++ }; count++; size += file.length() }
        }
        return Triple(count, size, newCount)
    }

    private fun createRecord(tag: String, type: String, dbSize: Long, fileCount: Long, totalSize: Long, message: String): JSONObject {
        return JSONObject().apply { put("tag", tag); put("type", type); put("time", System.currentTimeMillis()); put("dbSize", dbSize); put("fileCount", fileCount); put("totalSize", totalSize); put("message", message) }
    }

    private fun addRecord(record: JSONObject) {
        val f = File(com.nous.wxhook.db.BackupManager.BACKUP_DIR, RECORDS_FILE)
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
