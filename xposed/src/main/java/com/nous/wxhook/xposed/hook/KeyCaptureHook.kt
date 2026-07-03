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
    private var capturedKey: String? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        // Strategy 1: Try to hook Database.setCipherKey (may fail with Tinker)
        try {
            val dbClass = XposedHelpers.findClass(
                "com.tencent.wcdb.core.Database", loader
            )

            var hooked = 0
            for (method in dbClass.declaredMethods) {
                if (method.name == "setCipherKey" && method.parameterTypes.size >= 2) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val keyBytes = param.args[0] as? ByteArray ?: return
                                val keyHex = keyBytes.joinToString("") {
                                    String.format("%02x", if (it < 0) it + 256 else it.toInt())
                                }

                                if (keyHex != capturedKey) {
                                    capturedKey = keyHex
                                    XposedBridge.log("$TAG key captured from hook: $keyHex (len=${keyBytes.size})")
                                    broadcastKey(keyHex, keyBytes.size)
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG key extraction error: ${e.message}")
                            }
                        }
                    })
                    hooked++
                }
            }
            XposedBridge.log("$TAG hooked setCipherKey ($hooked overloads)")
        } catch (e: Throwable) {
            // Strategy 2: Tinker environment - class not found, use known key
            XposedBridge.log("$TAG Database class not found (Tinker), using known key")
            capturedKey = KNOWN_KEY
            broadcastKey(KNOWN_KEY, 7)
        }

        // Also try to read key from WeChat's shared prefs as backup
        try {
            readKeyFromWeChatPrefs(lpparam)
        } catch (e: Throwable) {
            // Ignore
        }
    }

    private fun readKeyFromWeChatPrefs(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Try to read auth_key from WeChat's shared preferences
        try {
            val context = XposedHelpers.callMethod(
                XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread"
                ),
                "getApplication"
            ) as Context

            // Check known key file
            val keyFile = java.io.File("/data/local/tmp/.wechat_key")
            if (keyFile.exists()) {
                val key = keyFile.readText().trim()
                if (key.isNotEmpty() && key != capturedKey) {
                    capturedKey = key
                    XposedBridge.log("$TAG key read from file: $key")
                    broadcastKey(key, key.length / 2)
                }
            }
        } catch (e: Throwable) {
            // Ignore
        }
    }

    private fun broadcastKey(keyHex: String, keyLen: Int) {
        try {
            val context = XposedHelpers.callMethod(
                XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread"
                ),
                "getApplication"
            ) as Context

            // Save to shared preferences
            val prefs = context.getSharedPreferences("wxhook", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_key", keyHex)
                .putInt("last_key_len", keyLen)
                .putLong("last_key_time", System.currentTimeMillis())
                .apply()

            // Broadcast
            val intent = Intent("com.nous.wxhook.KEY_CAPTURED").apply {
                putExtra("key", keyHex)
                putExtra("keyLen", keyLen)
                putExtra("time", System.currentTimeMillis().toString())
            }
            context.sendBroadcast(intent)

            XposedBridge.log("$TAG key saved to shared_prefs: $keyHex")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG broadcast failed: ${e.message}")
        }
    }

    fun getLastKey(): String? = capturedKey
}
