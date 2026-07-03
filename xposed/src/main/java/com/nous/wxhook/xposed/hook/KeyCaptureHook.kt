package com.nous.wxhook.xposed.hook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.sqlcipher.database.SQLiteDatabase

object KeyCaptureHook {

    private const val TAG = "[wxhook:Key]"
    private const val PROVIDER_URI = "content://com.nous.wxhook.provider/key"
    private const val KNOWN_KEY = "e9cd2ae"

    private external fun nativeHello(): String
    private external fun nativeInstallHooks(): String

    fun hook(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
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

                    // Hook setCipherKey (for future key changes)
                    try {
                        val dbClass = ctx.classLoader!!.loadClass("com.tencent.wcdb.core.Database")
                        var hooked = 0
                        for (m in dbClass.declaredMethods) {
                            if (m.name == "setCipherKey") {
                                de.robv.android.xposed.XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                                    override fun beforeHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                                        try {
                                            val args = param.args
                                            val keyHex = if (args.size >= 1 && args[0] is ByteArray) {
                                                (args[0] as ByteArray).joinToString("") { "%02x".format(it) }
                                            } else return

                                            XposedBridge.log("$TAG KEY_CAPTURED key=$keyHex")
                                            pushKey(ctx, keyHex)
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

                    // Decrypt DB directly with known key
                    Thread { decryptAndPush(ctx, KNOWN_KEY) }.start()
                }
            }
        )
    }

    private fun decryptAndPush(ctx: Context, keyHex: String) {
        try {
            // Load SQLCipher library
            SQLiteDatabase.loadLibs(ctx)

            val dbPath = "/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
            val dbFile = File(dbPath)

            if (!dbFile.exists()) {
                XposedBridge.log("$TAG DB not found at $dbPath")
                return
            }

            XposedBridge.log("$TAG DB found: ${dbFile.length() / 1024 / 1024} MB, decrypting...")

            // Open with SQLCipher
            val password = keyHex.toByteArray()
            val db = SQLiteDatabase.openDatabase(dbPath, password, null,
                SQLiteDatabase.OPEN_READONLY,
                null,
                object : net.sqlcipher.database.SQLiteDatabaseHook {
                    override fun preKey(database: net.sqlcipher.database.SQLiteDatabase?) {}
                    override fun postKey(database: net.sqlcipher.database.SQLiteDatabase?) {
                        database?.rawExecSQL("PRAGMA cipher_compatibility = 3")
                        database?.rawExecSQL("PRAGMA cipher_page_size = 1024")
                        database?.rawExecSQL("PRAGMA kdf_iter = 4000")
                        database?.rawExecSQL("PRAGMA cipher_use_hmac = OFF")
                    }
                })

            // Get stats
            val stats = mutableMapOf<String, Long>()
            for (table in listOf("message", "rconversation", "chatroom")) {
                try {
                    val cursor = db.rawQuery("SELECT count(*) FROM $table", null)
                    if (cursor.moveToFirst()) stats[table] = cursor.getLong(0)
                    cursor.close()
                } catch (_: Exception) {}
            }
            db.close()

            XposedBridge.log("$TAG Decrypted! Stats: $stats")

            // Push stats via ContentProvider
            pushStats(ctx, keyHex, stats)

        } catch (e: Throwable) {
            XposedBridge.log("$TAG decryptAndPush failed: ${e.message}")
            e.printStackTrace()
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
            XposedBridge.log("$TAG Stats pushed: msg=${stats["message"]}, conv=${stats["rconversation"]}, room=${stats["chatroom"]}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG pushStats failed: ${e.message}")
        }
    }

    private fun pushKey(ctx: Context, keyHex: String) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        try {
            File("/data/local/tmp/.wechat_key").writeText("key=$keyHex\ntime=$now\n")
        } catch (_: Throwable) {}

        try {
            val values = ContentValues().apply {
                put("key", keyHex)
                put("time", now)
            }
            ctx.contentResolver.insert(Uri.parse(PROVIDER_URI), values)
        } catch (_: Throwable) {}
    }

    fun getLastKey(): String? = KNOWN_KEY
}
