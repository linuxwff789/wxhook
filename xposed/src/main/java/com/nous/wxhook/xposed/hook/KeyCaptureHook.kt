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
    private const val PROVIDER_URI = "content://com.nous.wxhook.provider/key"
    private var keyCaptured = false

    // JNI native methods (from libwcdbhook.so)
    private external fun nativeHello(): String
    private external fun nativeInstallHooks(): String

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        // Hook Application.attach — fires after class loader is ready
        XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return

                    // 1. Load native hook library (early hook)
                    try {
                        val pkgMgr = ctx.packageManager
                        val moduleInfo = pkgMgr.getApplicationInfo("com.nous.wxhook.xposed", 0)
                        val loadPath = moduleInfo.nativeLibraryDir + "/libwcdbhook.so"
                        if (java.io.File(loadPath).exists()) {
                            System.load(loadPath)
                            val hello = nativeHello()
                            val status = nativeInstallHooks()
                            XposedBridge.log("$TAG NATIVE loaded: hello=$hello status=$status")
                        } else {
                            XposedBridge.log("$TAG NATIVE not found: $loadPath")
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG NATIVE load failed: ${e.message}")
                    }

                    // 2. Java hook: Database.setCipherKey
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
                        XposedBridge.log("$TAG Java hooks installed: $hooked overloads")

                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG Database class not found: ${e.message}")
                    }
                }
            }
        )
    }

    private fun pushKey(ctx: Context, keyHex: String, pageSize: String, version: String) {
        val now = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA
        ).format(System.currentTimeMillis())

        // Save to WeChat's shared_prefs (XP module can write here)
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
            XposedBridge.log("$TAG save prefs failed: ${e.message}")
        }

        // Try ContentProvider
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

    fun getLastKey(): String? {
        return if (keyCaptured) "captured" else null
    }
}
