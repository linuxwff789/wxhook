package com.nous.wxhook.xposed.hook

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup functionality for Xposed module.
 * Runs inside WeChat process, can directly access WeChat files.
 */
object BackupHook {

    private const val TAG = "wxhook:Backup"
    private val handler = Handler(Looper.getMainLooper())
    private var wechatPid: Int = 0

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        wechatPid = android.os.Process.myPid()
        XposedBridge.log("$TAG hook installed (pid=$wechatPid)")
    }

    /**
     * Get WeChat data base path
     */
    fun getWxDataPath(): String? {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.tencent.mm"))
            val pid = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pid.isEmpty()) return null
            "/proc/$pid/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
        } catch (e: Exception) {
            XposedBridge.log("$TAG getWxDataPath error: $e")
            null
        }
    }

    /**
     * Full backup: DB + all attachments
     */
    fun doFullBackup(backupDir: String): BackupResult {
        return try {
            val dir = File(backupDir)
            if (!dir.exists()) dir.mkdirs()

            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L
            var fileCount = 0L
            var totalSize = 0L

            // Backup DB
            val dbSrc = "/sdcard/Download/EnMicroMsg.db"
            if (File(dbSrc).exists()) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db")
                execCmd("su -c \"cp '$dbSrc' '${dbDst.absolutePath}' && chmod 644 '${dbDst.absolutePath}'\"")
                dbSize = dbDst.length()
                fileCount++
                totalSize += dbSize
                XposedBridge.log("$TAG DB: ${dbDst.name} ($dbSize bytes)")
            }

            // Backup attachments
            val wxBase = getWxDataPath() ?: return BackupResult(false, dbSize, fileCount, totalSize, 0, "微信未运行")
            val attDirs = listOf("image2", "voice2", "video", "cdn")

            for (attDir in attDirs) {
                val src = File(wxBase, attDir)
                if (src.exists()) {
                    val count = copyDirRecursive(src, File(dir, attDir))
                    fileCount += count.first
                    totalSize += count.second
                    XposedBridge.log("$TAG $attDir: ${count.first} files")
                }
            }

            saveState(backupDir, tag, fileCount, totalSize)
            BackupResult(true, dbSize, fileCount, totalSize, fileCount, "全量备份完成: ${fileCount}个文件")
        } catch (e: Exception) {
            XposedBridge.log("$TAG fullBackup error: $e")
            BackupResult(false, 0, 0, 0, 0, "备份失败: ${e.message}")
        }
    }

    /**
     * Incremental backup: only new/modified files
     */
    fun doIncrementalBackup(backupDir: String): BackupResult {
        return try {
            val dir = File(backupDir)
            if (!dir.exists()) dir.mkdirs()

            val state = loadState(backupDir)
            val lastBackupTime = state.optLong("lastBackupTime", 0L)
            val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            var dbSize = 0L
            var fileCount = 0L
            var totalSize = 0L
            var newFiles = 0

            // Check DB
            val dbSrc = File("/sdcard/Download/EnMicroMsg.db")
            if (dbSrc.exists() && dbSrc.lastModified() > lastBackupTime) {
                val dbDst = File(dir, "EnMicroMsg_$tag.db")
                dir.listFiles()?.filter { it.name.startsWith("EnMicroMsg_") && it.name.endsWith(".db") }?.forEach { it.delete() }
                execCmd("su -c \"cp '${dbSrc.absolutePath}' '${dbDst.absolutePath}' && chmod 644 '${dbDst.absolutePath}'\"")
                dbSize = dbDst.length()
                fileCount++
                totalSize += dbSize
                newFiles++
            }

            // Check attachments
            val wxBase = getWxDataPath() ?: return BackupResult(false, dbSize, fileCount, totalSize, 0, "微信未运行")
            val attDirs = listOf("image2", "voice2", "video", "cdn")

            for (attDir in attDirs) {
                val src = File(wxBase, attDir)
                if (src.exists()) {
                    val dst = File(dir, attDir)
                    val count = copyDirIncremental(src, dst, lastBackupTime)
                    fileCount += count.first
                    totalSize += count.second
                    newFiles += count.third
                }
            }

            saveState(backupDir, tag, fileCount, totalSize)
            val msg = if (newFiles > 0) "增量备份: ${newFiles}个新文件" else "无新文件"
            BackupResult(true, dbSize, fileCount, totalSize, newFiles, msg)
        } catch (e: Exception) {
            XposedBridge.log("$TAG incrementalBackup error: $e")
            BackupResult(false, 0, 0, 0, 0, "增量备份失败: ${e.message}")
        }
    }

    // ── Helpers ──

    private fun copyDirRecursive(src: File, dst: File): Pair<Int, Long> {
        var count = 0; var size = 0L
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val sub = File(dst, file.name); sub.mkdirs()
                val r = copyDirRecursive(file, sub); count += r.first; size += r.second
            } else {
                val dstFile = File(dst, file.name)
                execCmd("su -c \"cp '${file.absolutePath}' '${dstFile.absolutePath}' && chmod 644 '${dstFile.absolutePath}'\"")
                count++; size += file.length()
            }
        }
        return Pair(count, size)
    }

    private fun copyDirIncremental(src: File, dst: File, lastTime: Long): Triple<Int, Long, Int> {
        var count = 0; var size = 0L; var newCount = 0
        src.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val sub = File(dst, file.name); sub.mkdirs()
                val r = copyDirIncremental(file, sub, lastTime); count += r.first; size += r.second; newCount += r.third
            } else {
                val dstFile = File(dst, file.name)
                if (file.lastModified() > lastTime || !dstFile.exists()) {
                    execCmd("su -c \"cp '${file.absolutePath}' '${dstFile.absolutePath}' && chmod 644 '${dstFile.absolutePath}'\"")
                    newCount++
                }
                count++; size += file.length()
            }
        }
        return Triple(count, size, newCount)
    }

    private fun execCmd(cmd: String) {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
    }

    private fun saveState(dir: String, tag: String, count: Int, size: Long) {
        val state = org.json.JSONObject()
        state.put("lastBackupTime", System.currentTimeMillis())
        state.put("lastBackupTag", tag)
        state.put("fileCount", count)
        state.put("totalSize", size)
        File(dir, "backup_state.json").writeText(state.toString())
    }

    private fun loadState(dir: String): org.json.JSONObject {
        val f = File(dir, "backup_state.json")
        return if (f.exists()) try { org.json.JSONObject(f.readText()) } catch (e: Exception) { org.json.JSONObject() } else org.json.JSONObject()
    }

    data class BackupResult(val success: Boolean, val dbSize: Long, val fileCount: Long, val totalSize: Long, val newFiles: Int, val message: String)
}
