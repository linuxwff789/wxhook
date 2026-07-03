package com.nous.wxhook.xposed.hook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object KeyCaptureHook {

    private const val TAG = "[wxhook:Key]"
    private const val PROVIDER_URI = "content://com.nous.wxhook.provider/key"
    private var keyCaptured = false

    private external fun nativeHello(): String
    private external fun nativeInstallHooks(): String

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return

                    // Load native library
                    try {
                        val pkgMgr = ctx.packageManager
                        val moduleInfo = pkgMgr.getApplicationInfo("com.nous.wxhook.xposed", 0)
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
                                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
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

                                            // Copy DB to accessible location
                                            Thread { copyDatabase(ctx, keyHex) }.start()
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

    private fun copyDatabase(ctx: Context, keyHex: String) {
        try {
            // We are in WeChat's process, can access its files directly
            val dbPath = "/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db"
            val dbFile = File(dbPath)

            if (!dbFile.exists()) {
                XposedBridge.log("$TAG DB not found at $dbPath")
                return
            }

            XposedBridge.log("$TAG DB found: ${dbFile.length() / 1024 / 1024} MB")

            // Copy to wxhook's cache (accessible by wxhook app)
            val dstPath = "${ctx.cacheDir.absolutePath}/../wxhook/EnMicroMsg.db"
            val dstFile = File(dstPath)
            dstFile.parentFile?.mkdirs()

            // Use Java stream copy (we have WeChat's permissions)
            FileInputStream(dbFile).use { input ->
                FileOutputStream(dstFile).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024) // 1MB buffer
                }
            }

            if (dstFile.exists()) {
                XposedBridge.log("$TAG DB copied to $dstPath (${dstFile.length() / 1024 / 1024} MB)")

                // Push via ContentProvider
                pushDbInfo(ctx, keyHex, dstPath, dbFile.length())
            } else {
                XposedBridge.log("$TAG DB copy failed")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG copyDatabase failed: ${e.message}")
        }
    }

    private fun pushDbInfo(ctx: Context, keyHex: String, dbPath: String, dbSize: Long) {
        try {
            val values = ContentValues().apply {
                put("key", keyHex)
                put("db_path", dbPath)
                put("db_size", dbSize)
                put("time", System.currentTimeMillis().toString())
            }
            ctx.contentResolver.insert(Uri.parse("$PROVIDER_URI/db"), values)
            XposedBridge.log("$TAG DB info pushed via ContentProvider")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG pushDbInfo failed: ${e.message}")
        }
    }

    private fun pushKey(ctx: Context, keyHex: String, pageSize: String, version: String) {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(System.currentTimeMillis())

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
