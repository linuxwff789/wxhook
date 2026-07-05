package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook WeChat settings to add wxhook module entry.
 * Injects a preference-style item matching WeChat's native settings look.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private var lastActivity: String = ""

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        XposedHelpers.findAndHookMethod(
            android.app.Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val name = activity.javaClass.name
                    if (name.contains("Settings") || name.contains("settings")) {
                        if (lastActivity != name) {
                            lastActivity = name
                            injectSettingsItem(activity)
                        }
                    }
                }
            })
        XposedBridge.log("$TAG framework hook installed")
    }

    private fun injectSettingsItem(activity: Activity) {
        try {
            val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            if (root.findViewWithTag<View>("wxhook_entry") != null) return

            // Build a preference-style row: [icon] [title + subtitle] [>]
            val row = LinearLayout(activity).apply {
                tag = "wxhook_entry"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundColor(Color.WHITE)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // Icon circle (purple)
            val iconBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF6200EE.toInt())
                setSize(dp(36), dp(36))
            }
            val icon = TextView(activity).apply {
                text = "⚙"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = iconBg
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            }
            row.addView(icon)

            // Text area
            val textArea = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(14), 0, 0, 0)
                }
            }
            textArea.addView(TextView(activity).apply {
                text = "wxhook 模块"
                textSize = 16f
                setTextColor(0xFF1A1A1A.toInt())
                typeface = Typeface.DEFAULT
            })
            textArea.addView(TextView(activity).apply {
                text = "备份、定时备份、模块状态"
                textSize = 12f
                setTextColor(0xFF999999.toInt())
            })
            row.addView(textArea)

            // Right arrow ">"
            row.addView(TextView(activity).apply {
                text = "›"
                textSize = 20f
                setTextColor(0xFFCCCCCC.toInt())
                gravity = Gravity.CENTER
            })

            row.setOnClickListener {
                try {
                    activity.startActivity(Intent().apply {
                        component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    XposedBridge.log("$TAG startActivity: $e")
                }
            }

            // Divider line at top
            val divider = View(activity).apply {
                setBackgroundColor(0xFFE5E5E5.toInt())
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            }

            // Insert at the top of the content (below any existing views)
            root.addView(divider, 0)
            root.addView(row, 1)

            XposedBridge.log("$TAG settings item injected!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG inject error: $e")
        }
    }

    private fun dp(value: Int): Int {
        return (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }
}
