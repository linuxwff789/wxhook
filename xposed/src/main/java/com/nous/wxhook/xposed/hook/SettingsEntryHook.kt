package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook WeChat settings to add wxhook menu entry.
 * Uses onCreateOptionsMenu approach (方案一).
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private const val MENU_ID = 0x48574B // "HWK" in hex

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        val candidates = listOf(
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
        )

        for (clsName in candidates) {
            try {
                val cls = lpparam.classLoader.loadClass(clsName)
                XposedBridge.log("$TAG found $clsName, hooking menu...")

                // Hook onCreateOptionsMenu to add our menu item
                // Hook onResume to inject our entry via dialog
                XposedHelpers.findAndHookMethod(cls, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as Activity
                            XposedBridge.log("$TAG onResume: ${activity.javaClass.simpleName}")
                            android.widget.Toast.makeText(activity, "⚙️ wxhook: 进入模块", android.widget.Toast.LENGTH_SHORT).show()
                            // Add preference to settings
                            val ps = (activity as? android.preference.PreferenceActivity)?.preferenceScreen
                            if (ps != null) {
                                XposedBridge.log("$TAG preferenceScreen found")
                            } else {
                                XposedBridge.log("$TAG no preferenceScreen, trying addContentView")
                                val btn = android.widget.Button(activity).apply {
                                    text = "⚙️ wxhook 模块"
                                    setOnClickListener {
                                        activity.startActivity(android.content.Intent().apply {
                                            component = android.content.ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity")
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    }
                                }
                                activity.addContentView(btn, android.widget.FrameLayout.LayoutParams(
                                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                                    setMargins(0, 0, 32, 32)
                                })
                                XposedBridge.log("$TAG button added via addContentView!")
                            }
                        } catch (e: Exception) {
                            XposedBridge.log("$TAG onResume error: $e")
                        }
                    }
                })
                return // success
            } catch (e: Exception) {
                XposedBridge.log("$TAG $clsName: ${e.message}")
            }
        }
        XposedBridge.log("$TAG no settings class found")
    }
}
