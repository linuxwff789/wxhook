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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private var floatingView: View? = null
    private var currentActivity: Activity? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        val handler = Handler(Looper.getMainLooper())

        XposedHelpers.findAndHookMethod(
            android.app.Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val name = activity.javaClass.name
                    if (name.contains("Settings") || name.contains("settings")) {
                        currentActivity = activity
                        handler.post { showFloatingEntry(activity) }
                    } else {
                        handler.post { removeFloatingEntry() }
                    }
                }
            })
        XposedBridge.log("$TAG framework hook installed")
    }

    private fun showFloatingEntry(activity: Activity) {
        removeFloatingEntry()
        try {
            val wm = activity.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundColor(Color.WHITE)
            }

            val iconBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF6200EE.toInt())
                setSize(dp(28), dp(28))
            }
            val icon = TextView(activity).apply {
                text = "⚙"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = iconBg
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            }
            row.addView(icon)

            val textArea = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(10), 0, 0, 0)
                }
            }
            textArea.addView(TextView(activity).apply {
                text = "wxhook 模块"
                textSize = 15f
                setTextColor(0xFF1A1A1A.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            textArea.addView(TextView(activity).apply {
                text = "备份 · 定时备份 · 模块状态"
                textSize = 11f
                setTextColor(0xFF999999.toInt())
            })
            row.addView(textArea)

            row.addView(TextView(activity).apply {
                text = "›"
                textSize = 18f
                setTextColor(0xFFCCCCCC.toInt())
                gravity = Gravity.CENTER
            })

            // Rounded card background
            val cardBg = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(1, 0xFFE0E0E0.toInt())
            }
            row.background = cardBg

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

            val params = WindowManager.LayoutParams(
                dp(260),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(16)
                y = dp(80)
            }

            wm.addView(row, params)
            floatingView = row
            XposedBridge.log("$TAG floating entry shown!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG show error: $e")
        }
    }

    private fun removeFloatingEntry() {
        floatingView?.let { v ->
            try {
                val ctx = v.context
                val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(v)
            } catch (_: Exception) {}
            floatingView = null
        }
    }

    private fun dp(value: Int): Int {
        return (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }
}
