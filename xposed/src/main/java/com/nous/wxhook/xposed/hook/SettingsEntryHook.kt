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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Inject wxhook module entry into WeChat settings.
 * Uses WindowManager overlay for reliable visibility.
 * Auto-removes when leaving settings.
 */
object SettingsEntryHook {

    private const val TAG = "wxhook:Hook"
    private const val WXHOOK_PKG = "com.nous.wxhook"
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var lastActivityClass: String = ""

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
                        // Show overlay
                        if (lastActivityClass != name) {
                            lastActivityClass = name
                            handler.post { showOverlay(activity) }
                        }
                    } else {
                        // Left settings, remove overlay
                        if (lastActivityClass.isNotEmpty()) {
                            lastActivityClass = ""
                            handler.post { removeOverlay() }
                        }
                    }
                }
            })
        XposedBridge.log("$TAG framework hook installed")
    }

    private fun showOverlay(activity: Activity) {
        removeOverlay()
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
            row.addView(TextView(activity).apply {
                text = "⚙"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = iconBg
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })

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
                dp(240),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12)
                y = dp(72)
            }

            wm.addView(row, params)
            overlayView = row
            XposedBridge.log("$TAG overlay shown!")
        } catch (e: Exception) {
            XposedBridge.log("$TAG overlay error: $e")
        }
    }

    private fun removeOverlay() {
        overlayView?.let { v ->
            try {
                val wm = v.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(v)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun dp(value: Int): Int {
        return (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }
}
