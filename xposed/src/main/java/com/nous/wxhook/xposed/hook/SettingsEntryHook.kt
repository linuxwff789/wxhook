package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
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
 * Only hooks specific lifecycle methods to avoid breaking orientation etc.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private var injected = false

    private val callback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val activity = param.thisObject as? Activity ?: return
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
                // Only hook specific lifecycle methods, not ALL methods
                try { XposedHelpers.findAndHookMethod(cls, "onResume", callback) }
                catch (_: Exception) {}
                try { XposedHelpers.findAndHookMethod(cls, "onStart", callback) }
                catch (_: Exception) {}
                XposedBridge.log("$TAG hooked $clsName lifecycle methods")
                return
            } catch (_: Exception) {}
        }
    }

    private fun injectButton(activity: Activity) {
        if (injected) return
        try {
            val root = activity.findViewById<android.view.View>(android.R.id.content) as? ViewGroup
                ?: return
            if (root.findViewWithTag<android.view.View>("wxhook_entry") != null) return

            val btn = TextView(activity).apply {
                text = "⚙️ wxhook"
                tag = "wxhook_entry"
                textSize = 13f
                setPadding(24, 12, 24, 12)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF6200EE.toInt())
                setOnClickListener {
                    try {
                        activity.startActivity(Intent().apply {
                            component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
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
