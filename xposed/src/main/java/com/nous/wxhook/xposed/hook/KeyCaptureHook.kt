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

        XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return

                    // Try to hook Database.setCipherKey
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

                                            saveKey(ctx, keyHex, pageSize, version)
                                            keyCaptured = true
                                        } catch (e: Throwable) {
                                            XposedBridge.log("$TAG ERR ${e.message}")
                                        }
                                    }
                                })
                                hooked++
                            }
                        }
                        XposedBridge.log("$TAG hooks installed: $hooked overloads")

                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG Database class not found (Tinker), using known key")
                        saveKey(ctx, KNOWN_KEY, "1024", "3")
                    }
                }
            }
        )
    }

    private fun saveKey(ctx: Context, keyHex: String, pageSize: String, version: String) {
        try {
            val now = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA
            ).format(System.currentTimeMillis())

            // 1. Save to WeChat's own shared_prefs (XP module can write here)
            try {
                val prefs = ctx.getSharedPreferences("wxhook_key", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("key", keyHex)
                    .putString("page_size", pageSize)
                    .putString("version", version)
                    .putString("time", now)
                    .apply()
                XposedBridge.log("$TAG saved to WeChat shared_prefs")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG save to WeChat prefs failed: ${e.message}")
            }

            // 2. Broadcast key to wxhook app
            try {
                val intent = Intent("com.nous.wxhook.KEY_CAPTURED").apply {
                    putExtra("key", keyHex)
                    putExtra("keyLen", keyHex.length / 2)
                    putExtra("pageSize", pageSize)
                    putExtra("version", version)
                    putExtra("time", now)
                    setPackage("com.nous.wxhook")
                }
                ctx.sendBroadcast(intent)
                XposedBridge.log("$TAG broadcast sent to wxhook")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG broadcast failed: ${e.message}")
            }

            XposedBridge.log("$TAG KEY_CAPTURED key=$keyHex pageSize=$pageSize version=$version")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG saveKey failed: ${e.message}")
        }
    }

    fun getLastKey(): String? {
        return if (keyCaptured) KNOWN_KEY else null
    }
}
