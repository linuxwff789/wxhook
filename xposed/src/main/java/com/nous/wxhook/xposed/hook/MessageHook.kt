package com.nous.wxhook.xposed.hook

import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object MessageHook {

    private const val TAG = "[wxhook:Msg]"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        // Phase 1: Scan messenger.foundation package for message-related classes
        val candidates = listOf(
            "com.tencent.mm.plugin.messenger.foundation.a.a",
            "com.tencent.mm.plugin.messenger.foundation.a.s",
            "com.tencent.mm.plugin.messenger.foundation.a.y",
            "com.tencent.mm.plugin.messenger.foundation.b.a",
            "com.tencent.mm.storage.MsgInfo",
            "com.tencent.mm.storage.aj",
        )

        candidates.forEach { className ->
            try {
                val clazz = XposedHelpers.findClass(className, loader)
                XposedBridge.log("$TAG found candidate: $className")

                // Hook all methods to find message-related ones
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size in 1..5) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val args = param.args
                                // Log method calls for analysis
                                val argTypes = args.map { it?.javaClass?.simpleName ?: "null" }
                                XposedBridge.log("$TAG ${clazz.simpleName}.${method.name}(${argTypes.joinToString(", ")})")
                            }
                        })
                    }
                }
            } catch (e: Throwable) {
                // Class not found, skip
            }
        }

        XposedBridge.log("$TAG message scan candidates hooked")
    }
}
