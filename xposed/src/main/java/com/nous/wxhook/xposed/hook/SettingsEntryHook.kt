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
                XposedHelpers.findAndHookMethod(cls, "onCreateOptionsMenu",
                    Menu::class.java, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val menu = param.args[0] as Menu
                                // Add our item at the end
                                menu.add(0, MENU_ID, 999, "⚙️ wxhook 模块").apply {
                                    setOnMenuItemClickListener { _ ->
                                        try {
                                            val activity = param.thisObject as Activity
                                            activity.startActivity(Intent().apply {
                                                component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity")
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            })
                                        } catch (e: Exception) {
                                            XposedBridge.log("$TAG startActivity: $e")
                                        }
                                        true
                                    }
                                    setIcon(android.R.drawable.ic_menu_manage)
                                }
                                XposedBridge.log("$TAG menu item added!")
                            } catch (e: Exception) {
                                XposedBridge.log("$TAG menu error: $e")
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
