package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook WeChat to show a settings entry for wxhook module.
 * Approach: Hook LauncherUI.onCreate, show a dialog with "wxhook" option
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        val candidates = listOf(
            "com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
        )

        for (clsName in candidates) {
            try {
                val cls = lpparam.classLoader.loadClass(clsName)
                XposedBridge.log("$TAG hooking $clsName")
                XposedHelpers.findAndHookMethod(cls, "onCreate",
                    android.os.Bundle::class.java, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val activity = param.thisObject as Activity
                                XposedBridge.log("$TAG onCreate called: $activity")
                                injectButton(activity)
                            } catch (e: Exception) {
                                XposedBridge.log("$TAG error: $e")
                            }
                        }
                    })
            } catch (e: Exception) {
                XposedBridge.log("$TAG $clsName: ${e.message}")
            }
        }
    }

    private fun injectButton(activity: Activity) {
        try {
            val root = activity.findViewById<android.view.View>(android.R.id.content)
            if (root == null) {
                XposedBridge.log("$TAG no content view found")
                return
            }
            XposedBridge.log("$TAG content view type: ${root.javaClass.simpleName}")

            if (root.findViewWithTag<android.view.View>("wxhook_entry") != null) {
                XposedBridge.log("$TAG button already exists")
                return
            }

            val btn = android.widget.Button(activity).apply {
                text = "⚙️ wxhook"
                tag = "wxhook_entry"
                textSize = 14f
                setPadding(32, 16, 32, 16)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF6200EE.toInt())
                setOnClickListener {
                    try {
                        val intent = Intent().apply {
                            component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        }
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG startActivity error: $e")
                    }
                }
            }

            val container = android.widget.FrameLayout(activity)
            container.addView(btn, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(0, 200, 48, 0)
            })
            container.tag = "wxhook_entry"

            (root as android.view.ViewGroup).addView(container)
            XposedBridge.log("$TAG button injected!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG injectButton error: $e")
        }
    }
}