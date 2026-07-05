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
 * Hook WeChat settings to add wxhook module entry.
 * Strategy: hook Activity.onResume globally, filter by class name.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private var injected = false

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        // Hook Activity.onResume at framework level - guaranteed to fire
        XposedHelpers.findAndHookMethod(
            android.app.Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val name = activity.javaClass.name
                    // Only target WeChat settings activities
                    if (name.contains("Settings") || name.contains("settings")) {
                        XposedBridge.log("$TAG settings activity: $name")
                        injectButton(activity)
                    }
                }
            })
        XposedBridge.log("$TAG framework onResume hook installed")
    }

    private fun injectButton(activity: Activity) {
        if (injected) return
        try {
            val root = activity.findViewById<android.view.View>(android.R.id.content) as? ViewGroup
                ?: return
            if (root.findViewWithTag<android.view.View>("wxhook_entry") != null) return

            val btn = TextView(activity).apply {
                text = "⚙️ wxhook 模块"
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
            XposedBridge.log("$TAG BUTTON INJECTED into $activity")
        } catch (e: Exception) {
            XposedBridge.log("$TAG inject error: $e")
        }
    }
}
