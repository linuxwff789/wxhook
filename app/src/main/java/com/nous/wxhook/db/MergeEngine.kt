package com.nous.wxhook.db

import android.util.Log
import java.security.MessageDigest

/**
 * WeChat database merge engine.
 * Merges multiple EnMicroMsg.db snapshots with deduplication.
 */
object MergeEngine {

    private const val TAG = "wxhook:Merge"

    enum class MergeStrategy {
        UNION,          // Keep all messages, dedup by msgSvrId
        NEWEST_WINS,    // Same msgSvrId: keep newest
        BASE_WINS,      // Same msgSvrId: keep existing
        INTERSECTION    // Only keep messages present in both
    }

    data class MergeConfig(
        val strategy: MergeStrategy = MergeStrategy.UNION,
        val dedupKey: String = "msgSvrID"  // Primary dedup field
    )

    data class MergeResult(
        val totalMessages: Long,
        val mergedMessages: Long,
        val duplicatesRemoved: Long,
        val conflicts: List<String>,
        val outputPath: String
    )

    /**
     * Merge two decrypted databases.
     * Both databases must be opened with WeChatDbDecryptor.openDecryptedDb().
     */
    fun mergeDatabases(
        baseDbPath: String,
        overlayDbPath: String,
        outputPath: String,
        config: MergeConfig = MergeConfig()
    ): MergeResult {
        Log.i(TAG, "Merging: $baseDbPath + $overlayDbPath -> $outputPath")
        Log.i(TAG, "Strategy: ${config.strategy}")

        // Open both databases
        val baseDb = WeChatDbDecryptor.openDecryptedDb(baseDbPath)
        val overlayDb = WeChatDbDecryptor.openDecryptedDb(overlayDbPath)

        if (baseDb == null || overlayDb == null) {
            return MergeResult(0, 0, 0, listOf("Failed to open databases"), outputPath)
        }

        try {
            // Get all messages from overlay
            val overlayMessages = mutableListOf<Map<String, Any?>>()
            val cursor = overlayDb.rawQuery("SELECT * FROM message", null)
            val columns = cursor.columnNames

            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, Any?>()
                for (i in columns.indices) {
                    row[columns[i]] = when (cursor.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                        android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                        else -> null
                    }
                }
                overlayMessages.add(row)
            }
            cursor.close()

            // Get existing msgSvrIds from base
            val existingIds = mutableSetOf<Long>()
            val baseCursor = baseDb.rawQuery("SELECT msgSvrID FROM message", null)
            while (baseCursor.moveToNext()) {
                existingIds.add(baseCursor.getLong(0))
            }
            baseCursor.close()

            Log.i(TAG, "Overlay: ${overlayMessages.size} messages")
            Log.i(TAG, "Base: ${existingIds.size} existing msgSvrIDs")

            // Apply merge strategy
            val toInsert = mutableListOf<Map<String, Any?>>()
            var duplicatesRemoved = 0L
            val conflicts = mutableListOf<String>()

            for (msg in overlayMessages) {
                val msgSvrId = msg["msgSvrID"] as? Long ?: 0L

                if (msgSvrId in existingIds) {
                    when (config.strategy) {
                        MergeStrategy.UNION -> {
                            // Skip duplicate
                            duplicatesRemoved++
                        }
                        MergeStrategy.NEWEST_WINS -> {
                            // Update existing with newer data
                            duplicatesRemoved++
                            toInsert.add(msg) // Will be used for update
                            conflicts.add("Updated msgSvrId=$msgSvrId")
                        }
                        MergeStrategy.BASE_WINS -> {
                            // Skip, keep base version
                            duplicatesRemoved++
                        }
                        MergeStrategy.INTERSECTION -> {
                            // Keep (it's in both)
                            toInsert.add(msg)
                        }
                    }
                } else {
                    // New message, always insert
                    toInsert.add(msg)
                    existingIds.add(msgSvrId)
                }
            }

            Log.i(TAG, "To insert: ${toInsert.size}, Duplicates: $duplicatesRemoved")

            // Insert into base (or create new DB)
            val outputPath2 = if (baseDbPath == outputPath) {
                // Update in place
                baseDbPath
            } else {
                // Copy base to output first
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '$baseDbPath' '$outputPath'"))
                process.waitFor()
                outputPath
            }

            // Insert new messages
            var inserted = 0L
            for (msg in toInsert) {
                try {
                    val columns2 = msg.keys.joinToString(", ") { "\"$it\"" }
                    val placeholders = msg.keys.joinToString(", ") { "?" }
                    val values = msg.values.toTypedArray()

                    baseDb.execSQL(
                        "INSERT OR REPLACE INTO message ($columns2) VALUES ($placeholders)",
                        values
                    )
                    inserted++
                } catch (e: Exception) {
                    Log.e(TAG, "Insert failed: ${e.message}")
                }
            }

            baseDb.close()
            overlayDb.close()

            val result = MergeResult(
                totalMessages = overlayMessages.size.toLong(),
                mergedMessages = inserted,
                duplicatesRemoved = duplicatesRemoved,
                conflicts = conflicts.take(100), // Limit conflict list
                outputPath = outputPath2
            )

            Log.i(TAG, "Merge complete: ${result.mergedMessages} inserted, ${result.duplicatesRemoved} duplicates removed")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Merge failed: ${e.message}")
            baseDb?.close()
            overlayDb?.close()
            return MergeResult(0, 0, 0, listOf("Error: ${e.message}"), outputPath)
        }
    }

    /**
     * Generate content hash for deduplication.
     */
    fun contentHash(talker: String, content: String?, createTime: Long): String {
        val input = "$talker|$content|$createTime"
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { String.format("%02x", it) }
    }
}
