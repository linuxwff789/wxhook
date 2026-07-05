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
                        // Remove old one first, then re-inject
                        handler.postDelayed({
                            removeOld(activity)
                            inject(activity)
                        }, 600)
                    }
                }
            })
        XposedBridge.log("$TAG framework onResume hook installed")
    }

    private fun removeOld(activity: Activity) {
        val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        val old = root.findViewWithTag<View>("wxhook_entry") ?: return
        (old.parent as? ViewGroup)?.removeView(old)
    }

    private fun inject(activity: Activity) {
        try {
            val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            val item = createItem(activity)

            val rv = findByClassName(root, "RecyclerView")
            if (rv != null) {
                val rvGroup = rv as ViewGroup
                if (rvGroup.childCount > 0) {
                    val child0 = rvGroup.getChildAt(0) as? ViewGroup
                    if (child0 != null) {
                        child0.addView(item) // append at BOTTOM
                        XposedBridge.log("$TAG appended to bottom (${child0.childCount} items)")
                        return
                    }
                }
                val parent = rv.parent as? ViewGroup
                if (parent != null) {
                    parent.addView(item)
                    XposedBridge.log("$TAG added below RecyclerView")
                    return
                }
            }
            XposedBridge.log("$TAG no RecyclerView found")
        } catch (e: Exception) {
            XposedBridge.log("$TAG error: $e")
        }
    }

    private fun findByClassName(view: ViewGroup, name: String): View? {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.javaClass.simpleName.contains(name)) return child
            if (child is ViewGroup) {
                val r = findByClassName(child, name)
                if (r != null) return r
            }
        }
        return null
    }

    private fun createItem(activity: android.content.Context): View {
        val dark = isDarkMode(activity)
        val bgColor = if (dark) 0xFF2C2C2C.toInt() else Color.WHITE
        val textColor = if (dark) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()
        val subColor = if (dark) 0xFF999999.toInt() else 0xFF999999.toInt()
        val arrowColor = if (dark) 0xFF666666.toInt() else 0xFFCCCCCC.toInt()
        val dividerColor = if (dark) 0xFF3A3A3A.toInt() else 0xFFE5E5E5.toInt()

        val row = LinearLayout(activity).apply {
            tag = "wxhook_entry"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(bgColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        })
        textArea.addView(TextView(activity).apply {
            text = "备份 · 定时备份 · 模块状态"
            textSize = 12f
            setTextColor(subColor)
        })
        row.addView(textArea)

        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 20f
            setTextColor(arrowColor)
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

    private fun isDarkMode(activity: Activity): Boolean {
        val flags = activity.resources.configuration.uiMode
        return (flags and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
