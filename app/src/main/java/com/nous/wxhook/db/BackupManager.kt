package com.nous.wxhook.ui.module

import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupManager {

    private const val TAG = "wxhook:Backup"
    private const val WXHOOK_DIR = "/sdcard/Download/wxhook_backup"
    private const val STATE_FILE = "backup_state.json"

    data class BackupResult(
        val success: Boolean,
        val dbSize: Long,
        val fileCount: Int,
        val totalSize: Long,
        val newFiles: Int,
        val message: String
    )

    /**
     * Full backup: DB + all attachments
     */
    fun doFullBackup(): BackupResult {
        return try {
            val dir = ensureBackupDir()
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L
            var fileCount = 0
            var totalSize = 0L

            // Backup DB
            val dbSrc = "/sdcard/Download/EnMicroMsg.db"
            if (File(dbSrc).exists()) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db")
                copyFile(dbSrc, dbDst.absolutePath)
                dbSize = dbDst.length()
                fileCount++
                totalSize += dbSize
                Log.i(TAG, "DB backed up: ${dbDst.name} ($dbSize bytes)")
            }

            // Backup attachments
            val attachmentDirs = listOf("image2", "voice2", "video", "cdn")
            val wxBase = getWxAttachBase() ?: return BackupResult(false, dbSize, fileCount, totalSize, 0, "微信未运行或路径不存在")

            for (attDir in attachmentDirs) {
                val src = File(wxBase, attDir)
                if (src.exists()) {
                    val count = copyDirRecursive(src, File(dir, attDir))
                    fileCount += count.first
                    totalSize += count.second
                    Log.i(TAG, "Attached $attDir: ${count.first} files, ${count.second} bytes")
                }
            }

            // Save state
            saveState(tag, fileCount, totalSize)

            BackupResult(true, dbSize, fileCount, totalSize, fileCount, "全量备份完成: ${fileCount}个文件")
        } catch (e: Exception) {
            Log.e(TAG, "Full backup failed", e)
            BackupResult(false, 0, 0, 0, 0, "备份失败: ${e.message}")
        }
    }

    /**
     * Incremental backup: only new/modified files since last backup
     */
    fun doIncrementalBackup(): BackupResult {
        return try {
            val dir = ensureBackupDir()
            val state = loadState()
            val lastBackupTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L
            var fileCount = 0
            var totalSize = 0L
            var newFiles = 0

            // Check if DB changed
            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists() && dbSrc.lastModified() > lastBackupTime) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db")
                // Remove old DB backup
                dir.listFiles()?.filter { it.name.startsWith("EnMicroMsg_") && it.name.endsWith(".db") }?.forEach { it.delete() }
                copyFile(dbSrc.absolutePath, dbDst.absolutePath)
                dbSize = dbDst.length()
                fileCount++
                totalSize += dbSize
                newFiles++
                Log.i(TAG, "DB changed, backed up: ${dbDst.name}")
            }

            // Check attachments
            val wxBase = getWxAttachBase() ?: return BackupResult(false, dbSize, fileCount, totalSize, 0, "微信未运行或路径不存在")
            val attachmentDirs = listOf("image2", "voice2", "video", "cdn")

            for (attDir in attachmentDirs) {
                val src = File(wxBase, attDir)
                if (src.exists()) {
                    val dst = File(dir, attDir)
                    val count = copyDirIncremental(src, dst, lastBackupTime)
                    fileCount += count.first
                    totalSize += count.second
                    newFiles += count.third
                    if (count.third > 0) Log.i(TAG, "Incremental $attDir: ${count.third} new files")
                }
            }

            saveState(tag, fileCount, totalSize)

            val msg = if (newFiles > 0) "增量备份完成: ${newFiles}个新文件" else "无新文件需要备份"
            BackupResult(true, dbSize, fileCount, totalSize, newFiles, msg)
        } catch (e: Exception) {
            Log.e(TAG, "Incremental backup failed", e)
            BackupResult(false, 0, 0, 0, 0, "增量备份失败: ${e.message}")
        }
    }

    /**
     * Get backup directory info
     */
    fun getBackupInfo(): JSONObject {
        val dir = File(WXHOOK_DIR)
        val state = loadState()
        val info = JSONObject()
        info.put("exists", dir.exists())
        info.put("totalSize", dir.totalSpace)
        info.put("usedSize", dir.listFiles()?.sumOf { it.length() } ?: 0)
        info.put("lastBackupTime", state.optLong("lastBackupTime", 0))
        info.put("lastFileCount", state.optInt("fileCount", 0))
        info.put("lastTotalSize", state.optLong("totalSize", 0))
        return info
    }

    // ── Private helpers ──

    private fun ensureBackupDir(): File {
        val dir = File(WXHOOK_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getWxAttachBase(): String? {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
            val pid = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pid.isEmpty()) return null
            return "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
        } catch (e: Exception) {
            return null
        }
    }

    private fun copyFile(src: String, dst: String) {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '$src' '$dst' && chmod 644 '$dst'")).waitFor()
    }

    private fun copyDirRecursive(src: File, dst: File): Pair<Int, Long> {
        var count = 0
        var size = 0L
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val sub = File(dst, file.name)
                sub.mkdirs()
                val result = copyDirRecursive(file, sub)
                count += result.first
                size += result.second
            } else {
                val dstFile = File(dst, file.name)
                copyFile(file.absolutePath, dstFile.absolutePath)
                count++
                size += file.length()
            }
        }
        return Pair(count, size)
    }

    /**
     * Incremental copy: only copy files newer than lastBackupTime
     * @return Triple(fileCount, totalSize, newFileCount)
     */
    private fun copyDirIncremental(src: File, dst: File, lastBackupTime: Long): Triple<Int, Long, Int> {
        var count = 0
        var size = 0L
        var newCount = 0
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val sub = File(dst, file.name)
                sub.mkdirs()
                val result = copyDirIncremental(file, sub, lastBackupTime)
                count += result.first
                size += result.second
                newCount += result.third
            } else {
                val dstFile = File(dst, file.name)
                // Copy if file is newer than last backup or destination doesn't exist
                if (file.lastModified() > lastBackupTime || !dstFile.exists()) {
                    copyFile(file.absolutePath, dstFile.absolutePath)
                    newCount++
                }
                count++
                size += file.length()
            }
        }
        return Triple(count, size, newCount)
    }

    private fun saveState(tag: String, fileCount: Int, totalSize: Long) {
        val state = JSONObject()
        state.put("lastBackupTime", System.currentTimeMillis())
        state.put("lastBackupTag", tag)
        state.put("fileCount", fileCount)
        state.put("totalSize", totalSize)
        File(WXHOOK_DIR, STATE_FILE).writeText(state.toString())
    }

    private fun loadState(): JSONObject {
        val f = File(WXHOOK_DIR, STATE_FILE)
        return if (f.exists()) {
            try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
        } else JSONObject()
    }
}
