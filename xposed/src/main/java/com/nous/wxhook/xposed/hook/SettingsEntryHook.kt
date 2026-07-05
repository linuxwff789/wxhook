package com.nous.wxhook.xposed.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
 * Inject wxhook entry into WeChat settings via RecyclerView adapter wrapping.
 * The item scrolls naturally with the list.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private const val VIEW_TYPE_WXHOOK = 99999
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
                        handler.postDelayed({ wrapAdapters(activity) }, 500)
                    }
                }
            })
        XposedBridge.log("$TAG framework hook installed")
    }

    private fun wrapAdapters(activity: Activity) {
        try {
            val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            val rv = findRecyclerView(root) ?: return

            val adapter = rv.adapter ?: run {
                XposedBridge.log("$TAG RecyclerView has no adapter")
                return
            }

            // Check if already wrapped
            if (adapter.javaClass.simpleName.contains("Wxhook")) return

            // Wrap the adapter via Xposed
            val wrappedAdapter = Proxy.newProxyInstance(
                adapter.javaClass.classLoader,
                arrayOf(RecyclerView.Adapter::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "getItemCount" -> {
                        val original = method.invoke(adapter, *(args ?: emptyArray())) as Int
                        original + 1 // +1 for our wxhook item
                    }
                    "getItemViewType" -> {
                        val pos = args?.get(0) as? Int ?: 0
                        val original = method.invoke(adapter, *(args ?: emptyArray())) as Int
                        if (pos == 0) VIEW_TYPE_WXHOOK else original
                    }
                    "onCreateViewHolder" -> {
                        val viewType = args?.get(1) as? Int ?: 0
                        if (viewType == VIEW_TYPE_WXHOOK) {
                            createViewHolder(rv)
                        } else {
                            method.invoke(adapter, *(args ?: emptyArray()))
                        }
                    }
                    "onBindViewHolder" -> {
                        val holder = args?.get(0) as RecyclerView.ViewHolder
                        val pos = args?.get(1) as? Int ?: 0
                        if (holder.itemView.tag == "wxhook_item") {
                            // Already bound
                        } else {
                            method.invoke(adapter, *(args ?: emptyArray()))
                        }
                    }
                    else -> method.invoke(adapter, *(args ?: emptyArray()))
                }
            } as RecyclerView.Adapter<*>

            rv.adapter = wrappedAdapter
            XposedBridge.log("$TAG adapter wrapped!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG wrap error: $e")
        }
    }

    private fun createViewHolder(rv: RecyclerView): RecyclerView.ViewHolder {
        val holder = object : RecyclerView.ViewHolder(createItemView(rv.context)) {}
        return holder
    }

    private fun createItemView(context: android.content.Context): View {
        val dark = isDarkMode(context)
        val bgColor = if (dark) 0xFF2C2C2C.toInt() else Color.WHITE
        val textColor = if (dark) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()
        val subColor = 0xFF999999.toInt()
        val arrowColor = if (dark) 0xFF666666.toInt() else 0xFFCCCCCC.toInt()

        val row = FrameLayout(context).apply {
            tag = "wxhook_item"
            setBackgroundColor(bgColor)
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF6200EE.toInt())
            setSize(dp(36), dp(36))
        }
        inner.addView(TextView(context).apply {
            text = "⚙"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = iconBg; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        })

        val textArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(14), 0, 0, 0) }
        }
        textArea.addView(TextView(context).apply { text = "wxhook 模块"; textSize = 16f; setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD })
        textArea.addView(TextView(context).apply { text = "备份 · 定时备份 · 模块状态"; textSize = 12f; setTextColor(subColor) })
        inner.addView(textArea)

        inner.addView(TextView(context).apply { text = "›"; textSize = 20f; setTextColor(arrowColor); gravity = Gravity.CENTER })

        row.addView(inner)

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

    private fun findRecyclerView(view: ViewGroup): RecyclerView? {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is RecyclerView) return child
            if (child is ViewGroup) {
                val r = findRecyclerView(child)
                if (r != null) return r
            }
        }
        return null
    }

    private fun isDarkMode(context: android.content.Context): Boolean {
        val flags = context.resources.configuration.uiMode
        return (flags and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
