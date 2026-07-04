package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook WeChat to inject wxhook module entry button.
 * Hooks LauncherUI.onStart + onResume for reliability.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private var injected = false
    private val callback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val activity = param.thisObject as Activity
                XposedBridge.log("$TAG callback: ${activity.javaClass.simpleName}")
                injectButton(activity)
            } catch (e: Exception) {
                XposedBridge.log("$TAG callback error: $e")
            }
        }
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        val candidates = listOf(
            "com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
        )

        for (clsName in candidates) {
            try {
                val cls = lpparam.classLoader.loadClass(clsName)
                XposedBridge.log("$TAG hooking $clsName")
                // Hook onCreate (with Bundle) and onResume (no args)
                try {
                    XposedHelpers.findAndHookMethod(cls, "onCreate",
                        android.os.Bundle::class.java, callback)
                } catch (e: Exception) {
                    XposedBridge.log("$TAG $clsName.onCreate: ${e.message}")
                }
                try {
                    XposedHelpers.findAndHookMethod(cls, "onResume", callback)
                } catch (e: Exception) {
                    XposedBridge.log("$TAG $clsName.onResume: ${e.message}")
                }
                return // success on first class
            } catch (e: Exception) {
                XposedBridge.log("$TAG $clsName load failed: ${e.message}")
            }
        }
        XposedBridge.log("$TAG no class loaded")
    }

    private fun injectButton(activity: Activity) {
        if (injected) return
        try {
            val root = activity.findViewById<android.view.View>(android.R.id.content) as? ViewGroup
            if (root == null) {
                XposedBridge.log("$TAG root is null")
                return
            }
            XposedBridge.log("$TAG root: ${root.javaClass.simpleName}")

            // Check if already injected
            if (root.findViewWithTag<android.view.View>("wxhook_entry") != null) {
                XposedBridge.log("$TAG already injected")
                return
            }

            val btn = TextView(activity).apply {
                text = "⚙️ wxhook 模块"
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
                        XposedBridge.log("$TAG startActivity: $e")
                    }
                }
            }

            val container = FrameLayout(activity).apply {
                tag = "wxhook_entry"
                addView(btn, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, 200, 48, 0)
                })
            }

            root.addView(container)
            injected = true
            XposedBridge.log("$TAG BUTTON INJECTED!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG inject error: $e")
        }
    }
}