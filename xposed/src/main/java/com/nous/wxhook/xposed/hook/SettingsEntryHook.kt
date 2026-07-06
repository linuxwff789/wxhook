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

/**
 * Inject wxhook entry into WeChat settings.
 * Strategy: onResume → find RecyclerView → get adapter via getter → wrap with Proxy → inject item.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private val handler = Handler(Looper.getMainLooper())
    private var injected = false

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
                        injected = false
                        handler.postDelayed({ inject(activity) }, 600)
                    }
                }
            })
        XposedBridge.log("$TAG framework hook installed")
    }

    private fun inject(activity: Activity) {
        if (injected) return
        try {
            val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return

            // Find RecyclerView
            val rv = findByClassName(root, "RecyclerView") ?: run {
                XposedBridge.log("$TAG no RecyclerView")
                return
            }

            // Try to get adapter via reflection (multiple field names)
            var adapter: Any? = null
            val rvClass = rv.javaClass

            // Try common field names
            for (fieldName in listOf("mAdapter", "adapter", "mWrapAdapter", "mOuterAdapter")) {
                try {
                    val f = findFieldRecursive(rvClass, fieldName)
                    if (f != null) {
                        f.isAccessible = true
                        adapter = f.get(rv)
                        if (adapter != null) {
                            XposedBridge.log("$TAG found adapter via field: $fieldName")
                            break
                        }
                    }
                } catch (_: Exception) {}
            }

            // Try getAdapter() method
            if (adapter == null) {
                try {
                    val m = rvClass.getMethod("getAdapter")
                    adapter = m.invoke(rv)
                    XposedBridge.log("$TAG found adapter via getAdapter()")
                } catch (_: Exception) {}
            }

            if (adapter == null) {
                XposedBridge.log("$TAG no adapter found")
                // Fallback: add item below RecyclerView
                val parent = rv.parent as? ViewGroup
                if (parent != null) {
                    parent.addView(createItem(activity), parent.indexOfChild(rv) + 1)
                    injected = true
                    XposedBridge.log("$TAG fallback: added below RecyclerView")
                }
                return
            }

            // Check if already wrapped
            if (adapter.javaClass.name.contains("Wxhook")) return

            // Wrap adapter with Proxy
            val original = adapter
            val wrapped = Proxy.newProxyInstance(
                original.javaClass.classLoader,
                original.javaClass.interfaces
            ) { _, method, args ->
                when (method.name) {
                    "getItemCount" -> {
                        val orig = method.invoke(original, *(args ?: emptyArray())) as Int
                        orig + 1
                    }
                    "getItemViewType" -> {
                        val pos = args?.get(0) as? Int ?: 0
                        if (pos == 0) 99999 else method.invoke(original, *(args ?: emptyArray()))
                    }
                    "onCreateViewHolder" -> {
                        val vt = args?.get(1) as? Int ?: 0
                        if (vt == 99999) {
                            // Create ViewHolder for our item
                            val holderCls = Class.forName("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
                            val holder = createViewHolder(activity)
                            holder
                        } else {
                            method.invoke(original, *(args ?: emptyArray()))
                        }
                    }
                    "onBindViewHolder" -> {
                        val holder = args?.get(0)
                        val pos = args?.get(1) as? Int ?: 0
                        if (pos == 0 && holder != null) {
                            val iv = holderCls_getItemView(holder)
                            if (iv != null && iv.tag != "wxhook_item") {
                                // Bind our item
                            }
                            null
                        } else {
                            method.invoke(original, *(args ?: emptyArray()))
                        }
                    }
                    else -> method.invoke(original, *(args ?: emptyArray()))
                }
            }

            // Set wrapped adapter
            val setAdapterMethod = rvClass.getMethod("setAdapter", Class.forName("androidx.recyclerview.widget.RecyclerView\$Adapter"))
            setAdapterMethod.invoke(rv, wrapped)

            injected = true
            XposedBridge.log("$TAG adapter wrapped!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG error: $e")
            // Fallback
            tryAddBelowRecyclerView(activity)
        }
    }

    private fun tryAddBelowRecyclerView(activity: Activity) {
        try {
            val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            if (root.findViewWithTag<View>("wxhook_item") != null) return
            val rv = findByClassName(root, "RecyclerView") ?: return
            val parent = rv.parent as? ViewGroup ?: return
            parent.addView(createItem(activity), parent.indexOfChild(rv) + 1)
            injected = true
            XposedBridge.log("$TAG fallback: added below RecyclerView")
        } catch (e: Exception) {
            XposedBridge.log("$TAG fallback error: $e")
        }
    }

    private fun createViewHolder(activity: Activity): Any {
        // Use reflection to create a proper ViewHolder
        val item = createItem(activity)
        val holderCls = Class.forName("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        return holderCls.getConstructor(View::class.java).newInstance(item)
    }

    private fun findFieldRecursive(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
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
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            } catch (e: Exception) { XposedBridge.log("$tag startActivity: $e") }
        }
        return row
    }

    private fun isDarkMode(context: android.content.Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
