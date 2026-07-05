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
import java.lang.reflect.Proxy

object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private const val VIEW_TYPE = 99999
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
                        handler.postDelayed({ inject(activity) }, 500)
                    }
                }
            })
        XposedBridge.log("$TAG framework hook installed")
    }

    private fun inject(activity: Activity) {
        try {
            val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            if (root.findViewWithTag<View>("wxhook_item") != null) return

            // Find RecyclerView
            val rv = findByClassName(root, "RecyclerView") ?: run {
                XposedBridge.log("$TAG no RecyclerView")
                return
            }

            // Get the adapter
            val adapterField = rv.javaClass.getDeclaredField("mAdapter").apply { isAccessible = true }
            val adapter = adapterField.get(rv) ?: run {
                XposedBridge.log("$TAG no adapter")
                return
            }

            // Check if already wrapped
            if (adapter.javaClass.name.contains("WxhookProxy")) return

            // Get the original adapter class's interfaces
            val adapterInterfaces = adapter.javaClass.interfaces

            // Create a dynamic proxy
            val proxy = Proxy.newProxyInstance(
                adapter.javaClass.classLoader,
                adapterInterfaces
            ) { _, method, args ->
                when (method.name) {
                    "getItemCount" -> {
                        val orig = method.invoke(adapter, *(args ?: emptyArray())) as Int
                        orig + 1
                    }
                    "getItemViewType" -> {
                        val pos = args?.get(0) as? Int ?: return@newProxyInstance method.invoke(adapter, *(args ?: emptyArray()))
                        if (pos == 0) VIEW_TYPE else method.invoke(adapter, *(args ?: emptyArray()))
                    }
                    "getItemCount" -> method.invoke(adapter, *(args ?: emptyArray()))
                    else -> method.invoke(adapter, *(args ?: emptyArray()))
                }
            }

            // Set the proxy as adapter
            adapterField.set(rv, proxy)

            XposedBridge.log("$TAG adapter wrapped!")
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
        row.setOnClickListener { try { it.context.startActivity(Intent().apply { component = ComponentName(WXHOOK_PKG, "$WXHOOK_PKG.ui.module.ModuleActivity"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) { XposedBridge.log("$TAG startActivity: $e") } }
        return row
    }

    private fun isDarkMode(context: android.content.Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
