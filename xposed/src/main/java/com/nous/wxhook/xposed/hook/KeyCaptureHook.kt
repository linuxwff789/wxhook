package com.nous.wxhook.xposed.hook

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KeyCaptureHook {

    private const val TAG = "[wxhook:Key]"
    private const val PROVIDER_URI = "content://com.nous.wxhook.provider/key"
    private var keyCaptured = false

    private external fun nativeHello(): String
    private external fun nativeInstallHooks(): String

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        de.robv.android.xposed.XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return

                    // Load native library
                    try {
                        val moduleInfo = ctx.packageManager.getApplicationInfo("com.nous.wxhook.xposed", 0)
                        val loadPath = moduleInfo.nativeLibraryDir + "/libwcdbhook.so"
                        if (File(loadPath).exists()) {
                            System.load(loadPath)
                            XposedBridge.log("$TAG NATIVE loaded: ${nativeHello()}")
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG NATIVE load failed: ${e.message}")
                    }

                    // Hook setCipherKey
                    try {
                        val dbClass = ctx.classLoader!!.loadClass("com.tencent.wcdb.core.Database")
                        var hooked = 0
                        for (m in dbClass.declaredMethods) {
                            if (m.name == "setCipherKey") {
                                de.robv.android.xposed.XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                                    override fun beforeHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                                        if (keyCaptured) return
                                        try {
                                            val args = param.args
                                            val keyHex = if (args.size >= 1 && args[0] is ByteArray) {
                                                (args[0] as ByteArray).joinToString("") { "%02x".format(it) }
                                            } else "null"
                                            val pageSize = args.getOrNull(1)?.toString() ?: "?"
                                            val version = args.getOrNull(2)?.toString() ?: "?"

                                            pushKey(ctx, keyHex, pageSize, version)
                                            keyCaptured = true

                                            // Decrypt DB directly in WeChat's process
                                            Thread { decryptAndPush(ctx, keyHex) }.start()
                                        } catch (e: Throwable) {
                                            XposedBridge.log("$TAG ERR ${e.message}")
                                        }
                                    }
                                })
                                hooked++
                            }
                        }
                        XposedBridge.log("$TAG Java hooks: $hooked overloads")
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG Database class not found: ${e.message}")
                    }
                }
            }
        )
    }

    private fun decryptAndPush(ctx: Context, keyHex: String) {
        try {
            // We are in WeChat's process, can access its files directly
            val dbPath = "/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
            val dbFile = File(dbPath)

            if (!dbFile.exists()) {
                XposedBridge.log("$TAG DB not found at $dbPath")
                return
            }

            XposedBridge.log("$TAG DB found: ${dbFile.length() / 1024 / 1024} MB, decrypting...")

            // Open database with SQLCipher (we're in WeChat's process)
            val sql = "PRAGMA key='$keyHex';" +
                "PRAGMA cipher_compatibility=3;" +
                "PRAGMA cipher_page_size=1024;" +
                "PRAGMA kdf_iter=4000;" +
                "PRAGMA cipher_use_hmac=OFF;"

            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY, null)

            // Execute PRAGMA settings
            db.rawQuery(sql + "SELECT count(*) FROM sqlite_master", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val tableCount = cursor.getInt(0)
                    XposedBridge.log("$TAG Decrypted: $tableCount tables")
                }
            }

            // Get stats
            val stats = mutableMapOf<String, Long>()
            for (table in listOf("message", "rconversation", "chatroom", "Contact")) {
                try {
                    db.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            stats[table] = cursor.getLong(0)
                        }
                    }
                } catch (_: Exception) {}
            }
            db.close()

            XposedBridge.log("$TAG Stats: $stats")

            // Push stats via ContentProvider
            pushStats(ctx, keyHex, stats)

        } catch (e: Throwable) {
            XposedBridge.log("$TAG decryptAndPush failed: ${e.message}")
        }
    }

    private fun pushStats(ctx: Context, keyHex: String, stats: Map<String, Long>) {
        try {
            val values = ContentValues().apply {
                put("key", keyHex)
                put("message_count", stats["message"] ?: 0)
                put("conversation_count", stats["rconversation"] ?: 0)
                put("chatroom_count", stats["chatroom"] ?: 0)
                put("time", System.currentTimeMillis().toString())
            }
            ctx.contentResolver.insert(Uri.parse("$PROVIDER_URI/stats"), values)
            XposedBridge.log("$TAG Stats pushed via ContentProvider")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG pushStats failed: ${e.message}")
        }
    }

    private fun pushKey(ctx: Context, keyHex: String, pageSize: String, version: String) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        // Write to .wechat_key
        try {
            File("/data/local/tmp/.wechat_key").writeText(
                "key=$keyHex\npageSize=$pageSize\nversion=$version\ntime=$now\n"
            )
            XposedBridge.log("$TAG wrote .wechat_key")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG write .wechat_key failed: ${e.message}")
        }

        // ContentProvider
        try {
            val values = ContentValues().apply {
                put("key", keyHex)
                put("page_size", pageSize)
                put("version", version)
                put("time", now)
            }
            ctx.contentResolver.insert(Uri.parse(PROVIDER_URI), values)
            XposedBridge.log("$TAG key pushed via ContentProvider")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG ContentProvider failed: ${e.message}")
        }

        XposedBridge.log("$TAG KEY_CAPTURED key=$keyHex pageSize=$pageSize version=$version")
    }

    fun getLastKey(): String? = if (keyCaptured) "captured" else null
}
