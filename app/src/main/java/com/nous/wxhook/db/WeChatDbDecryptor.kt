package com.nous.wxhook.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * WeChat EnMicroMsg.db decryptor.
 * SQLCipher params: cipher_compatibility=3, page_size=1024, kdf_iter=4000, hmac=OFF
 * Key: e9cd2ae (7 bytes, from setCipherKey hook)
 */
object WeChatDbDecryptor {

    private const val TAG = "wxhook:Decryptor"

    // WeChat DB paths (relative to /data/data/com.tencent.mm/MicroMsg/{hash}/)
    private const val DB_NAME = "EnMicroMsg.db"
    private const val DB_HASH_DIR = "6d1f34a5edc49e8b6d238141b2d004f3"

    data class DbConfig(
        val key: String = "e9cd2ae",
        val cipherCompatibility: Int = 3,
        val pageSize: Int = 1024,
        val kdfIter: Int = 4000,
        val hmacEnabled: Boolean = false
    )

    data class DbSnapshot(
        val dbFile: File,
        val walFile: File,
        val shmFile: File,
        val key: String,
        val tableCount: Int = 0,
        val messageCount: Long = 0
    )

    /**
     * Open decrypted WeChat database.
     * Returns null if decryption fails.
     */
    fun openDecryptedDb(
        dbPath: String,
        config: DbConfig = DbConfig()
    ): SQLiteDatabase? {
        return try {
            val sql = buildString {
                append("PRAGMA key = '${config.key}'; ")
                append("PRAGMA cipher_compatibility = ${config.cipherCompatibility}; ")
                append("PRAGMA cipher_page_size = ${config.pageSize}; ")
                append("PRAGMA kdf_iter = ${config.kdfIter}; ")
                if (!config.hmacEnabled) {
                    append("PRAGMA cipher_use_hmac = OFF; ")
                }
            }

            val db = SQLiteDatabase.openDatabase(
                dbPath, null,
                SQLiteDatabase.OPEN_READONLY,
                null
            )

            // Execute PRAGMA settings
            val cursor = db.rawQuery(sql + "SELECT count(*) FROM sqlite_master", null)
            if (cursor.moveToFirst()) {
                val tableCount = cursor.getInt(0)
                Log.i(TAG, "Decrypted DB: $tableCount tables")
                cursor.close()
                db
            } else {
                cursor.close()
                db.close()
                Log.e(TAG, "Failed to decrypt DB")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt failed: ${e.message}")
            null
        }
    }

    /**
     * Query messages from decrypted database.
     */
    fun queryMessages(
        db: SQLiteDatabase,
        talker: String,
        limit: Int = 100,
        offset: Int = 0
    ): List<Map<String, Any?>> {
        val messages = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            """SELECT localId, TalkerId, msgSvrID, type, subType, 
                      content, isSend, createTime, talker, imgPath
               FROM message 
               WHERE talker = ? 
               ORDER BY createTime DESC 
               LIMIT ? OFFSET ?""",
            arrayOf(talker, limit.toString(), offset.toString())
        )

        while (cursor.moveToNext()) {
            messages.add(mapOf(
                "msgId" to cursor.getLong(0),
                "talkerId" to cursor.getLong(1),
                "msgSvrId" to cursor.getLong(2),
                "type" to cursor.getInt(3),
                "subType" to cursor.getInt(4),
                "content" to cursor.getString(5),
                "isSend" to cursor.getInt(6) == 1,
                "createTime" to cursor.getLong(7),
                "talker" to cursor.getString(8),
                "imgPath" to cursor.getString(9)
            ))
        }
        cursor.close()
        return messages
    }

    /**
     * Query all conversations.
     */
    fun queryConversations(db: SQLiteDatabase): List<Map<String, Any?>> {
        val conversations = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            """SELECT cusername, md_msg_seq, unReadCount 
               FROM rconversation 
               ORDER BY md_msg_seq DESC""",
            null
        )

        while (cursor.moveToNext()) {
            conversations.add(mapOf(
                "username" to cursor.getString(0),
                "lastMsgSeq" to cursor.getLong(1),
                "unreadCount" to cursor.getInt(2)
            ))
        }
        cursor.close()
        return conversations
    }

    /**
     * Query contacts.
     */
    fun queryContacts(db: SQLiteDatabase): List<Map<String, Any?>> {
        val contacts = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            """SELECT username, nickname, alias, type 
               FROM rcontact 
               WHERE type IN (1, 2, 3, 33)
               ORDER BY nickname ASC""",
            null
        )

        while (cursor.moveToNext()) {
            contacts.add(mapOf(
                "username" to cursor.getString(0),
                "nickname" to cursor.getString(1),
                "alias" to cursor.getString(2),
                "type" to cursor.getInt(3)
            ))
        }
        cursor.close()
        return contacts
    }

    /**
     * Get database statistics.
     */
    fun getStats(db: SQLiteDatabase): Map<String, Long> {
        val stats = mutableMapOf<String, Long>()
        val tables = listOf("message", "rcontact", "rconversation", "ImgInfo2", "VoiceInfo")
        for (table in tables) {
            try {
                val cursor = db.rawQuery("SELECT count(*) FROM $table", null)
                if (cursor.moveToFirst()) {
                    stats[table] = cursor.getLong(0)
                }
                cursor.close()
            } catch (e: Exception) {
                stats[table] = -1
            }
        }
        return stats
    }
}
