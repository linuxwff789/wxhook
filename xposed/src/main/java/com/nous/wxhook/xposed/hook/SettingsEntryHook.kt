package com.nous.wxhook.xposed.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Injects "wxhook模块" entry into WeChat's settings page.
 * Targets com.tencent.mm.plugin.setting.ui.setting.SettingsUI
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:SettHook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private const val TARGET_PKG = "com.tencent.mm"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return

        // Try known WeChat settings class names (8.0.74+ uses setting_new)
        val candidates = listOf(
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting.SettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting.SettingsUI\$MoreTabUI"
        )

        for (clsName in candidates) {
            try {
                val cls = lpparam.classLoader.loadClass(clsName)
                XposedBridge.log("$TAG found $clsName, hooking...")
                XposedHelpers.findAndHookMethod(cls, "onCreate",
                    android.os.Bundle::class.java, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try { injectEntry(param.thisObject as android.app.Activity) }
                            catch (e: Exception) { XposedBridge.log("$TAG inject error: $e") }
                        }
                    })
                return // success
            } catch (_: Exception) {
                XposedBridge.log("$TAG $clsName not found, trying next")
            }
        }
        XposedBridge.log("$TAG no settings class found")
    }

    private fun injectEntry(activity: android.app.Activity) {
        try {
            // Find the ListView or RecyclerView in settings and add our entry
            // Approach: add a button below the existing settings list
            val root = activity.findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup ?: return
            if (root.findViewWithTag<android.view.View>("wxhook_entry") != null) return // already injected

            val btn = android.widget.Button(activity).apply {
                text = "⚙️ wxhook 模块"
                tag = "wxhook_entry"
                setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_MAIN).apply {
                            component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        }
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG startActivity failed: $e")
                    }
                }
            }
            root.addView(btn, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            XposedBridge.log("$TAG injected wxhook module entry")
        } catch (e: Exception) {
            XposedBridge.log("$TAG injectEntry error: $e")
        }
    }
}