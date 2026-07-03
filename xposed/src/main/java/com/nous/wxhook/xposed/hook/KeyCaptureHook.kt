package com.nous.wxhook.xposed.hook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object KeyCaptureHook {

    private const val TAG = "[wxhook:Key]"
    private const val KNOWN_KEY = "e9cd2ae"
    private const val PROVIDER_URI = "content://com.nous.wxhook.provider/key"
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

                                            pushKey(ctx, keyHex, pageSize, version)
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
                        pushKey(ctx, KNOWN_KEY, "1024", "3")
                    }
                }
            }
        )
    }

    private fun pushKey(ctx: Context, keyHex: String, pageSize: String, version: String) {
        val now = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA
        ).format(System.currentTimeMillis())

        // Method 1: ContentProvider (most reliable across processes)
        try {
            val values = ContentValues().apply {
                put("key", keyHex)
                put("page_size", pageSize)
                put("version", version)
                put("time", now)
            }
            ctx.contentResolver.insert(Uri.parse(PROVIDER_URI), values)
            XposedBridge.log("$TAG key pushed via ContentProvider: $keyHex")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG ContentProvider failed: ${e.message}")

            // Method 2: Broadcast fallback (may be blocked by MIUI)
            try {
                val intent = android.content.Intent("com.nous.wxhook.KEY_CAPTURED").apply {
                    putExtra("key", keyHex)
                    putExtra("keyLen", keyHex.length / 2)
                    setPackage("com.nous.wxhook")
                }
                ctx.sendBroadcast(intent)
                XposedBridge.log("$TAG broadcast sent as fallback")
            } catch (e2: Throwable) {
                XposedBridge.log("$TAG broadcast also failed: ${e2.message}")
            }
        }

        XposedBridge.log("$TAG KEY_CAPTURED key=$keyHex pageSize=$pageSize version=$version")
    }

    fun getLastKey(): String? {
        return if (keyCaptured) KNOWN_KEY else null
    }
}
