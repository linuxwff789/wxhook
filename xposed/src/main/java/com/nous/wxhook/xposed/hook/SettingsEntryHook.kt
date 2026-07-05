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
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook WeChat settings to inject wxhook entry into the actual settings list.
 * Finds the settings list view and appends our item.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private val handler = Handler(Looper.getMainLooper())

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        // Hook Activity.onResume at framework level
        XposedHelpers.findAndHookMethod(
            android.app.Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val name = activity.javaClass.name
                    if (name.contains("Settings") || name.contains("settings")) {
                        handler.post { injectIntoList(activity) }
                    }
                }
            })
        XposedBridge.log("$TAG framework hook installed")
    }

    private fun injectIntoList(activity: Activity) {
        try {
            val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            if (root.findViewWithTag<View>("wxhook_entry") != null) return

            // Find the actual list container (RecyclerView, ListView, ScrollView, etc.)
            val listContainer = findListContainer(root)

            // Create the settings-style item
            val item = createSettingsItem(activity)

            if (listContainer != null) {
                // Inject into the list
                listContainer.addView(item)
                XposedBridge.log("$TAG injected into ${listContainer.javaClass.simpleName}")
            } else {
                // Fallback: add to content view at the end
                root.addView(item)
                XposedBridge.log("$TAG injected into content (no list found)")
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG inject error: $e")
        }
    }

    private fun findListContainer(root: ViewGroup): ViewGroup? {
        // Try to find RecyclerView or ListView by traversing the view tree
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val className = child.javaClass.name
            if (className.contains("RecyclerView") || className.contains("ListView") ||
                className.contains("ScrollView") || className.contains("NestedScrollView")) {
                return child as? ViewGroup
            }
            if (child is ViewGroup) {
                val found = findListContainer(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun createSettingsItem(activity: android.content.Context): View {
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

        // Purple circle icon
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

        // Right arrow
        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 20f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        })

        // Click handler
        row.setOnClickListener {
            try {
                val ctx = it.context
                ctx.startActivity(Intent().apply {
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
