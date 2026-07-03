package com.nous.wxhook.xposed.hook

import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object KeyCaptureHook {

    private const val TAG = "[wxhook:Key]"
    private var capturedKey: String? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        try {
            // Hook Database.setCipherKey(byte[], int, CipherVersion)
            val dbClass = XposedHelpers.findClass(
                "com.tencent.wcdb.core.Database", loader
            )

            // Hook setCipherKey overloads
            for (method in dbClass.declaredMethods) {
                if (method.name == "setCipherKey" && method.parameterTypes.size >= 2) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val keyBytes = param.args[0] as? ByteArray ?: return
                                val keyHex = keyBytes.joinToString("") {
                                    String.format("%02x", if (it < 0) it + 256 else it.toInt())
                                }
                                val keyStr = String(keyBytes)

                                if (keyHex != capturedKey) {
                                    capturedKey = keyHex
                                    XposedBridge.log("$TAG key captured: $keyHex (len=${keyBytes.size})")

                                    // Broadcast to wxhook app
                                    broadcastKey(keyHex, keyBytes.size)
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG key extraction error: ${e.message}")
                            }
                        }
                    })
                    XposedBridge.log("$TAG hooked ${method.name}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG hook failed: ${e.message}")
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
            ) as android.content.Context

            val intent = Intent("com.nous.wxhook.KEY_CAPTURED").apply {
                putExtra("key", keyHex)
                putExtra("keyLen", keyLen)
                putExtra("time", System.currentTimeMillis().toString())
            }
            context.sendBroadcast(intent)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG broadcast failed: ${e.message}")
        }
    }

    fun getLastKey(): String? = capturedKey
}
