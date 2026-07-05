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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Inject wxhook entry into WeChat settings.
 * Strategy: hook RecyclerView.setAdapter() to wrap the adapter with an extra item.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private const val VIEW_TYPE_WXHOOK = 99999
    private val handler = Handler(Looper.getMainLooper())

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        // Hook RecyclerView.setAdapter to wrap the adapter
        XposedHelpers.findAndHookMethod(
            RecyclerView::class.java, "setAdapter",
            RecyclerView.Adapter::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val rv = param.thisObject as? RecyclerView ?: return
                    val adapter = param.args[0] as? RecyclerView.Adapter<*> ?: return

                    // Check if this is a settings page RecyclerView
                    val activity = rv.context as? Activity ?: return
                    val name = activity.javaClass.name
                    if (!name.contains("Settings") && !name.contains("settings")) return

                    // Skip if already wrapped
                    if (adapter.javaClass.name.contains("Wxhook")) return

                    handler.postDelayed({ injectItem(rv, adapter) }, 300)
                }
            })
        XposedBridge.log("$TAG setAdapter hook installed")
    }

    private fun injectItem(rv: RecyclerView, originalAdapter: RecyclerView.Adapter<*>) {
        try {
            val activity = rv.context as? Activity ?: return
            val item = createItem(activity)

            // Create a wrapper view that contains both original content and our item
            // Actually, just add a footer view to the RecyclerView's parent
            val parent = rv.parent as? ViewGroup ?: return

            // Check if already added
            if (parent.findViewWithTag<View>("wxhook_item") != null) return

            parent.addView(item)
            XposedBridge.log("$tag wxhook item added below RecyclerView")
        } catch (e: Exception) {
            XposedBridge.log("$TAG inject error: $e")
        }
    }

    private fun createItem(activity: Activity): View {
        val dark = isDarkMode(activity)
        val bgColor = if (dark) 0xFF2C2C2C.toInt() else Color.WHITE
        val textColor = if (dark) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()
        val subColor = 0xFF999999.toInt()
        val arrowColor = if (dark) 0xFF666666.toInt() else 0xFFCCCCCC.toInt()

        val row = LinearLayout(activity).apply {
            tag = "wxhook_item"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val iconBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF6200EE.toInt()); setSize(dp(36), dp(36)) }
        row.addView(TextView(activity).apply { text = "⚙"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = iconBg; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) })

        val textArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(14), 0, 0, 0) } }
        textArea.addView(TextView(activity).apply { text = "wxhook 模块"; textSize = 16f; setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD })
        textArea.addView(TextView(activity).apply { text = "备份 · 定时备份 · 模块状态"; textSize = 12f; setTextColor(subColor) })
        row.addView(textArea)

        row.addView(TextView(activity).apply { text = "›"; textSize = 20f; setTextColor(arrowColor); gravity = Gravity.CENTER })

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

    private fun isDarkMode(context: android.content.Context): Boolean {
        val flags = context.resources.configuration.uiMode
        return (flags and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
