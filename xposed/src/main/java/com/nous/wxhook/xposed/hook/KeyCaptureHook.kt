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

    private fun pushKey(ctx: Context, keyHex: String, pageSize: String, version: String) {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(System.currentTimeMillis())

        // 1. Save to wxhook app's data dir via shell (survives cache clear)
        try {
            val prefsDir = "/data/data/com.nous.wxhook/shared_prefs"
            val prefsFile = "$prefsDir/wxhook.xml"
            val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="last_key">$keyHex</string>
    <int name="last_key_len" value="${keyHex.length / 2}" />
    <long name="last_key_time" value="${System.currentTimeMillis()}" />
</map>"""
            // Use echo to write file
            val cmd = "echo '${xml}' > $prefsFile"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
            XposedBridge.log("$TAG wrote $prefsFile")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG write file failed: ${e.message}")
        }

        // 2. Save to /data/local/tmp/.wechat_key (world readable)
        try {
            File("/data/local/tmp/.wechat_key").writeText(
                "key=$keyHex\npageSize=$pageSize\nversion=$version\ntime=$now\n"
            )
            XposedBridge.log("$TAG wrote /data/local/tmp/.wechat_key")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG write .wechat_key failed: ${e.message}")
        }

        // 3. ContentProvider (for in-memory reads)
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
