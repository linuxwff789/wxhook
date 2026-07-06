package com.nous.wxhook.db

import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup file manager for the Manager App.
 * Lists, reads, deletes backup files. Does NOT execute backups.
 * Backups are executed by Xposed BackupHook in WeChat process.
 */
object BackupManager {

    private const val BACKUP_DIR = "/sdcard/Download/wxhook_backup"

    data class BackupInfo(val name: String, val path: String, val size: Long, val time: Long)

    /**
     * List all backup files
     */
    fun listBackups(): List<BackupInfo> {
        val dir = File(BACKUP_DIR)
        if (!dir.exists()) return emptyList()

        val backups = mutableListOf<BackupInfo>()

        // DB backups
        dir.listFiles()?.filter { it.name.startsWith("EnMicroMsg_") && it.name.endsWith(".db") }?.forEach { f ->
            backups.add(BackupInfo(f.name, f.absolutePath, f.length(), f.lastModified()))
        }

        return backups.sortedByDescending { it.time }
    }

    /**
     * Get backup directory info
     */
    fun getBackupInfo(): JSONObject {
        val dir = File(BACKUP_DIR)
        val info = JSONObject()
        info.put("backupDir", dir.absolutePath)
        info.put("exists", dir.exists())

        if (dir.exists()) {
            var totalSize = 0L
            var fileCount = 0
            dir.listFiles()?.forEach { f ->
                if (f.isFile) { totalSize += f.length(); fileCount++ }
                if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile }.forEach { totalSize += it.length(); fileCount++ }
                }
            }
            info.put("totalSize", totalSize)
            info.put("fileCount", fileCount)

            // Attachment dirs
            val attDirs = listOf("image2", "voice2", "video", "cdn")
            attDirs.forEach { d ->
                val ad = File(dir, d)
                if (ad.exists()) {
                    val count = ad.walkTopDown().filter { it.isFile }.count()
                    val size = ad.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    info.put("${d}_count", count)
                    info.put("${d}_size", size)
                }
            }
        }

        // Load state
        val stateFile = File(BACKUP_DIR, "backup_state.json")
        if (stateFile.exists()) {
            try {
                val state = JSONObject(stateFile.readText())
                info.put("lastBackupTime", state.optLong("lastBackupTime", 0))
                info.put("lastFileCount", state.optInt("fileCount", 0))
                info.put("lastTotalSize", state.optLong("totalSize", 0))
            } catch (_: Exception) {}
        }

        return info
    }

    /**
     * Delete a backup file
     */
    fun deleteBackup(path: String): Boolean {
        val f = File(path)
        return if (f.exists() && f.absolutePath.startsWith(BACKUP_DIR)) {
            f.delete()
        } else false
    }

    /**
     * Get last backup time as formatted string
     */
    fun getLastBackupTime(): String {
        val stateFile = File(BACKUP_DIR, "backup_state.json")
        if (!stateFile.exists()) return "无"
        return try {
            val state = JSONObject(stateFile.readText())
            val time = state.optLong("lastBackupTime", 0)
            if (time == 0L) "无"
            else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
        } catch (e: Exception) { "无" }
    }
}
