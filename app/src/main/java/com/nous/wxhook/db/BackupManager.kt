package com.nous.wxhook.db

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WeChat database backup manager.
 * Handles snapshot creation, listing, restore, and export.
 */
object BackupManager {

    private const val TAG = "wxhook:Backup"
    private const val BACKUP_DIR = "wxhook_backups"
    private const val WECHAT_DB_DIR = "/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"

    data class BackupInfo(
        val id: Long,
        val path: String,
        val timestamp: Long,
        val messageCount: Long,
        val fileSize: Long,
        val key: String?,
        val notes: String?
    )

    /**
     * Get backup directory.
     */
    fun getBackupDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Create a database snapshot backup.
     * Requires root access to copy WeChat DB files.
     */
    fun createBackup(context: Context, key: String, notes: String? = null): BackupInfo? {
        return try {
            val timestamp = System.currentTimeMillis()
            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
            val backupDir = File(getBackupDir(context), timeStr)
            backupDir.mkdirs()

            val dbFiles = listOf("EnMicroMsg.db", "EnMicroMsg.db-wal", "EnMicroMsg.db-shm")
            var totalSize = 0L

            for (fileName in dbFiles) {
                val src = File(WECHAT_DB_DIR, fileName)
                val dst = File(backupDir, fileName)
                if (src.exists()) {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '${src.absolutePath}' '${dst.absolutePath}'"))
                    process.waitFor()
                    if (dst.exists()) {
                        totalSize += dst.length()
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 644 '${dst.absolutePath}'")).waitFor()
                    }
                }
            }

            // Save metadata
            val metaFile = File(backupDir, "metadata.json")
            metaFile.writeText("""
                {
                    "timestamp": $timestamp,
                    "key": "$key",
                    "notes": "${notes ?: ""}",
                    "files": ${dbFiles.filter { File(backupDir, it).exists() }.size},
                    "totalSize": $totalSize
                }
            """.trimIndent())

            // Save key separately
            val keyFile = File(backupDir, "key.txt")
            keyFile.writeText(key)

            Log.i(TAG, "Backup created: ${backupDir.absolutePath} ($totalSize bytes)")

            BackupInfo(
                id = timestamp,
                path = backupDir.absolutePath,
                timestamp = timestamp,
                messageCount = 0, // Will be filled after decrypt
                fileSize = totalSize,
                key = key,
                notes = notes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}")
            null
        }
    }

    /**
     * List all backups.
     */
    fun listBackups(context: Context): List<BackupInfo> {
        val backupDir = getBackupDir(context)
        val backups = mutableListOf<BackupInfo>()

        backupDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val metaFile = File(dir, "metadata.json")
            if (metaFile.exists()) {
                try {
                    val meta = metaFile.readText()
                    val timestamp = extractJsonLong(meta, "timestamp") ?: dir.name.toLongOrNull() ?: 0
                    val key = extractJsonString(meta, "key")
                    val notes = extractJsonString(meta, "notes")
                    val totalSize = dir.listFiles()?.sumOf { it.length() } ?: 0

                    backups.add(BackupInfo(
                        id = timestamp,
                        path = dir.absolutePath,
                        timestamp = timestamp,
                        messageCount = 0,
                        fileSize = totalSize,
                        key = key,
                        notes = notes
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse backup: ${dir.name}")
                }
            }
        }

        return backups.sortedByDescending { it.timestamp }
    }

    /**
     * Delete a backup.
     */
    fun deleteBackup(context: Context, backupId: Long): Boolean {
        val backups = listBackups(context)
        val backup = backups.find { it.id == backupId } ?: return false

        return try {
            val dir = File(backup.path)
            dir.deleteRecursively()
            Log.i(TAG, "Backup deleted: ${backup.path}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed: ${e.message}")
            false
        }
    }

    /**
     * Restore a backup to WeChat.
     * Requires root access.
     */
    fun restoreBackup(context: Context, backupId: Long): Boolean {
        val backups = listBackups(context)
        val backup = backups.find { it.id == backupId } ?: return false

        return try {
            val dbFiles = listOf("EnMicroMsg.db", "EnMicroMsg.db-wal", "EnMicroMsg.db-shm")

            // Stop WeChat first
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.tencent.mm")).waitFor()
            Thread.sleep(2000)

            for (fileName in dbFiles) {
                val src = File(backup.path, fileName)
                val dst = File(WECHAT_DB_DIR, fileName)
                if (src.exists()) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '${src.absolutePath}' '${dst.absolutePath}'")).waitFor()
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 600 '${dst.absolutePath}'")).waitFor()
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "chown com.tencent.mm:com.tencent.mm '${dst.absolutePath}'")).waitFor()
                }
            }

            Log.i(TAG, "Backup restored from: ${backup.path}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed: ${e.message}")
            false
        }
    }

    /**
     * Export backup to user-accessible directory.
     */
    fun exportBackup(context: Context, backupId: Long, exportDir: File): File? {
        val backups = listBackups(context)
        val backup = backups.find { it.id == backupId } ?: return null

        return try {
            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(backup.timestamp))
            val exportFile = File(exportDir, "wxhook_backup_$timeStr.zip")

            // Create zip archive
            val process = Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "cd '${backup.path}' && zip -r '${exportFile.absolutePath}' ."
            ))
            process.waitFor()

            if (exportFile.exists()) {
                Log.i(TAG, "Backup exported to: ${exportFile.absolutePath}")
                exportFile
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            null
        }
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val regex = Regex("\"$key\"\\s*:\\s*(\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.get(1)
    }
}
