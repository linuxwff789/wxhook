package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceScreen
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook WeChat settings to add wxhook entry via Preference API.
 * Uses WeChat's own preference rendering system.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private val handler = Handler(Looper.getMainLooper())

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        // Try 1: Hook PreferenceActivity.addPreferencesFromResource
        hookPreferenceActivity(lpparam)
        // Try 2: Hook PreferenceActivity.addPreference
        hookAddPreference(lpparam)
        // Try 3: Hook onCreate as fallback
        hookOnCreate(lpparam)
    }

    private fun hookOnCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        val candidates = listOf(
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting_new.CommonSettingsUI"
        )
        for (clsName in candidates) {
            try {
                val cls = lpparam.classLoader.loadClass(clsName)
                XposedBridge.log("$TAG hooking $clsName.onCreate")
                XposedHelpers.findAndHookMethod(cls, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        handler.postDelayed({ tryAddPreference(activity) }, 500)
                    }
                })
                return
            } catch (_: Exception) {}
        }
    }

    private fun hookPreferenceActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                PreferenceActivity::class.java, "addPreferencesFromResource",
                Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val name = activity.javaClass.name
                        if (name.contains("Settings") || name.contains("settings")) {
                            handler.post { tryAddPreference(activity) }
                        }
                    }
                })
            XposedBridge.log("$TAG hooked PreferenceActivity.addPreferencesFromResource")
        } catch (e: Exception) {
            XposedBridge.log("$TAG PreferenceActivity hook failed: ${e.message}")
        }
    }

    private fun hookAddPreference(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                PreferenceActivity::class.java, "addPreference",
                Preference::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val name = activity.javaClass.name
                        if (name.contains("Settings") || name.contains("settings")) {
                            handler.post { tryAddPreference(activity) }
                        }
                    }
                })
            XposedBridge.log("$TAG hooked PreferenceActivity.addPreference")
        } catch (e: Exception) {
            XposedBridge.log("$TAG addPreference hook failed: ${e.message}")
        }
    }

    private fun tryAddPreference(activity: Activity) {
        try {
            if (activity !is PreferenceActivity) {
                XposedBridge.log("$TAG not a PreferenceActivity: ${activity.javaClass.simpleName}")
                return
            }
            // Check if already added
            val ps = activity.preferenceScreen ?: return
            if (findPreferenceByKey(ps, "wxhook_module") != null) return

            // Create category
            val cat = PreferenceCategory(activity).apply {
                key = "wxhook_category"
                title = "扩展插件"
                order = 9999
            }
            ps.addPreference(cat)

            // Create preference
            val pref = Preference(activity).apply {
                key = "wxhook_module"
                title = "wxhook 模块"
                summary = "备份 · 定时备份 · 模块状态"
                order = 10000
                setOnPreferenceClickListener {
                    try {
                        activity.startActivity(Intent().apply {
                            component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG startActivity: $e")
                    }
                    true
                }
            }
            ps.addPreference(pref)

            XposedBridge.log("$TAG preference added!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG tryAddPreference error: $e")
        }
    }

    private fun findPreferenceByKey(screen: PreferenceScreen, key: String): Preference? {
        for (i in 0 until screen.preferenceCount) {
            val p = screen.getPreference(i)
            if (p.key == key) return p
        }
        return null
    }
}
