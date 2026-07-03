package com.nous.wxhook.db

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupManager {

    private const val TAG = "wxhook:Backup"
    private const val BACKUP_DIR = "wxhook_backups"
    private const val SOURCE_DB = "/sdcard/Download/EnMicroMsg.db"

    data class BackupInfo(
        val id: Long,
        val path: String,
        val timestamp: Long,
        val fileSize: Long,
        val key: String?,
        val notes: String?
    )

    fun getBackupDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createBackup(context: Context, key: String, notes: String? = null): BackupInfo? {
        return try {
            val srcFile = File(SOURCE_DB)
            if (!srcFile.exists()) {
                Log.e(TAG, "Source DB not found: $SOURCE_DB")
                return null
            }

            val timestamp = System.currentTimeMillis()
            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
            val backupDir = File(getBackupDir(context), timeStr)
            backupDir.mkdirs()

            // Copy from /sdcard/Download
            val dstFile = File(backupDir, "EnMicroMsg.db")
            srcFile.copyTo(dstFile, overwrite = true)

            // Save metadata
            File(backupDir, "key.txt").writeText(key)
            File(backupDir, "notes.txt").writeText(notes ?: "")

            Log.i(TAG, "Backup created: ${backupDir.absolutePath} (${dstFile.length()} bytes)")

            BackupInfo(
                id = timestamp,
                path = backupDir.absolutePath,
                timestamp = timestamp,
                fileSize = dstFile.length(),
                key = key,
                notes = notes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}")
            null
        }
    }

    fun listBackups(context: Context): List<BackupInfo> {
        val backupDir = getBackupDir(context)
        val backups = mutableListOf<BackupInfo>()

        backupDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            try {
                val timestamp = dir.name.toLongOrNull() ?: return@forEach
                val keyFile = File(dir, "key.txt")
                val notesFile = File(dir, "notes.txt")
                val dbFile = File(dir, "EnMicroMsg.db")

                if (dbFile.exists()) {
                    backups.add(BackupInfo(
                        id = timestamp,
                        path = dir.absolutePath,
                        timestamp = timestamp,
                        fileSize = dbFile.length(),
                        key = if (keyFile.exists()) keyFile.readText() else null,
                        notes = if (notesFile.exists()) notesFile.readText() else null
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse backup: ${dir.name}")
            }
        }

        return backups.sortedByDescending { it.timestamp }
    }

    fun deleteBackup(context: Context, backupId: Long): Boolean {
        val backups = listBackups(context)
        val backup = backups.find { it.id == backupId } ?: return false
        return try {
            File(backup.path).deleteRecursively()
            true
        } catch (_: Exception) { false }
    }
}
