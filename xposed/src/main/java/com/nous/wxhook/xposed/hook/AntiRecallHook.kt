package com.nous.wxhook.xposed.hook

import android.content.Context
import android.database.Cursor
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object AntiRecallHook {

    private const val TAG = "[wxhook:AntiRecall]"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        try {
            // Hook the revoke message method
            // Target: com.tencent.mm.plugin.messenger.foundation.a.a (MsgRevoke)
            val clazz = XposedHelpers.findClass(
                "com.tencent.mm.plugin.messenger.foundation.a.a", loader
            )

            for (method in clazz.declaredMethods) {
                if (method.name.contains("revoke", ignoreCase = true) ||
                    method.name.contains("recall", ignoreCase = true)) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            XposedBridge.log("$TAG blocked recall: ${method.name}")
                            // Prevent the recall by not calling the original method
                            param.result = null
                        }
                    })
                    XposedBridge.log("$TAG hooked recall method: ${method.name}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG hook failed: ${e.message}")
        }
    }
}
