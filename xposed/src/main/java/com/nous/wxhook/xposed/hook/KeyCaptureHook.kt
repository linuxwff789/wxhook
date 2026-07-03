package com.nous.wxhook.xposed.hook

import android.content.Context
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object KeyCaptureHook {

    private const val TAG = "[wxhook:Key]"
    private const val KNOWN_KEY = "e9cd2ae"
    private var keyCaptured = false

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        // Hook Application.attach — fires after class loader is ready
        XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return

                    // Try to hook Database.setCipherKey using ctx.classLoader
                    try {
                        val dbClass = ctx.classLoader!!.loadClass("com.tencent.wcdb.core.Database")

                        var hooked = 0
                        for (m in dbClass.declaredMethods) {
                            if (m.name == "setCipherKey") {
                                val sig = m.parameterTypes.joinToString { it.simpleName }
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

                                            saveKey(ctx, keyHex, pageSize, version)
                                            keyCaptured = true
                                        } catch (e: Throwable) {
                                            XposedBridge.log("$TAG ERR ${e.message}")
                                        }
                                    }
                                })
                                hooked++
                                XposedBridge.log("$TAG HOOK setCipherKey($sig)")
                            }
                        }
                        XposedBridge.log("$TAG hooks installed: $hooked overloads")

                    } catch (e: Throwable) {
                        // Tinker: class not found, use known key
                        XposedBridge.log("$TAG Database class not found (Tinker), using known key")
                        saveKey(ctx, KNOWN_KEY, "1024", "3")
                    }

                    // Also read from /data/local/tmp/.wechat_key if exists
                    readKeyFromFile(ctx)
                }
            }
        )
    }

    private fun saveKey(ctx: Context, keyHex: String, pageSize: String, version: String) {
        try {
            val now = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA
            ).format(System.currentTimeMillis())

            // Save to wxhook SharedPreferences
            val prefs = ctx.getSharedPreferences("wxhook", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_key", keyHex)
                .putInt("last_key_len", keyHex.length / 2)
                .putLong("last_key_time", System.currentTimeMillis())
                .putString("page_size", pageSize)
                .putString("cipher_version", version)
                .apply()

            // Also write to shared file
            try {
                java.io.File("/data/local/tmp/.wechat_key").writeText(
                    "key=$keyHex\npageSize=$pageSize\nversion=$version\ntime=$now\n"
                )
            } catch (_: Throwable) {}

            XposedBridge.log("$TAG KEY_CAPTURED key=$keyHex pageSize=$pageSize version=$version")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG saveKey failed: ${e.message}")
        }
    }

    private fun readKeyFromFile(ctx: Context) {
        try {
            val keyFile = java.io.File("/data/local/tmp/.wechat_key")
            if (keyFile.exists()) {
                val content = keyFile.readText()
                val key = content.lines().find { it.startsWith("key=") }?.removePrefix("key=")
                if (!key.isNullOrEmpty() && !keyCaptured) {
                    saveKey(ctx, key, "1024", "3")
                    keyCaptured = true
                }
            }
        } catch (_: Throwable) {}
    }

    fun getLastKey(): String? {
        return if (keyCaptured) KNOWN_KEY else null
    }
}
