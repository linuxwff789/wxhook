package com.nous.wxhook.xposed.hook

import android.content.ComponentName
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Injects "wxhook模块" entry into WeChat's main UI.
 * Hooks LauncherUI (always present) and adds a FAB-style button.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        // Hook LauncherUI (WeChat main screen) — always present
        tryHook("com.tencent.mm.ui.LauncherUI", lpparam.classLoader)
        // Also try settings activity
        tryHook("com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI", lpparam.classLoader)
        tryHook("com.tencent.mm.plugin.setting.ui.setting.SettingsUI", lpparam.classLoader)
    }

    private fun tryHook(clsName: String, cl: ClassLoader) {
        try {
            val cls = cl.loadClass(clsName)
            XposedBridge.log("$TAG hooking $clsName")
            XposedHelpers.findAndHookMethod(cls, "onCreate",
                android.os.Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try { addButton(param.thisObject as android.app.Activity) }
                        catch (e: Exception) { XposedBridge.log("$TAG addButton error: $e") }
                    }
                })
        } catch (e: Exception) {
            XposedBridge.log("$TAG $clsName not found: ${e.message}")
        }
    }

    private fun addButton(activity: android.app.Activity) {
        val root = activity.findViewById<android.view.View>(android.R.id.content) as? ViewGroup ?: return
        if (root.findViewWithTag<android.view.View>("wxhook_entry") != null) return

        val btn = Button(activity).apply {
            text = "⚙️ wxhook"
            tag = "wxhook_entry"
            textSize = 14f
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
        root.addView(btn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 32, 32)
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        })
        XposedBridge.log("$TAG button added to $activity")
    }
}