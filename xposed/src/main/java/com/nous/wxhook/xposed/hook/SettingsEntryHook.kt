package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private val handler = Handler(Looper.getMainLooper())

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
                        handler.post { inject(activity) }
                    }
                }
            })
        XposedBridge.log("$TAG framework hook installed")
    }

    private fun inject(activity: Activity) {
        try {
            val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            if (root.findViewWithTag<View>("wxhook_entry") != null) return

            val item = createItem(activity)

            // Strategy: find ScrollView → its child LinearLayout → add item at end
            val target = findScrollViewChild(root)
            if (target != null) {
                target.addView(item)
                XposedBridge.log("$TAG added to ${target.javaClass.simpleName} (child count: ${target.childCount})")
            } else {
                // Fallback: add to content view (might be hidden)
                root.addView(item)
                XposedBridge.log("$TAG added to content root (fallback)")
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG error: $e")
        }
    }

    /**
     * Find the LinearLayout/ViewGroup inside a ScrollView that holds settings items.
     */
    private fun findScrollViewChild(view: ViewGroup): ViewGroup? {
        if (view is ScrollView) {
            if (view.childCount > 0) {
                val child = view.getChildAt(0)
                if (child is ViewGroup) return child
            }
            return null
        }
        // Recurse
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is ViewGroup) {
                val result = findScrollViewChild(child)
                if (result != null) return result
            }
        }
        return null
    }

    private fun createItem(activity: android.content.Context): View {
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

        val iconBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF6200EE.toInt())
            setSize(dp(36), dp(36))
        }
        row.addView(TextView(activity).apply {
            text = "⚙"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = iconBg
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        })

        val textArea = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(14), 0, 0, 0)
            }
        }
        textArea.addView(TextView(activity).apply {
            text = "wxhook 模块"
            textSize = 16f
            setTextColor(0xFF1A1A1A.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        textArea.addView(TextView(activity).apply {
            text = "备份 · 定时备份 · 模块状态"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
        })
        row.addView(textArea)

        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 20f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        })

        row.setOnClickListener {
            try {
                it.context.startActivity(Intent().apply {
                    component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                XposedBridge.log("$TAG startActivity: $e")
            }
        }

        return row
    }

    private fun dp(value: Int): Int {
        return (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }
}
