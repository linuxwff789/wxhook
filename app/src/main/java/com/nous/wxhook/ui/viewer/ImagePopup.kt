package com.nous.wxhook.ui.viewer

import android.app.Dialog
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class ImagePopup(context: Context) {

    private val dialog: Dialog
    private val imageView: ImageView
    private val matrix = Matrix()
    private var savedMatrix = Matrix()
    private var start = PointF()
    private var mode = 0
    private val detector: ScaleGestureDetector
    private var prevSpacing = 0f

    init {
        val root = FrameLayout(context).apply { setBackgroundColor(0xFF000000.toInt()) }

        // Close button — only this closes
        root.addView(TextView(context).apply {
            text = "✕"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0x44000000.toInt())
            setOnClickListener { dialog.dismiss() }
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.TOP or Gravity.END; setMargins(0, 96, 32, 0)
            }
        })

        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setOnTouchListener { v, event ->
                detector.onTouchEvent(event)
                val masked = event.action and MotionEvent.ACTION_MASK
                when (masked) {
                    MotionEvent.ACTION_DOWN -> {
                        savedMatrix.set(matrix)
                        start.set(event.x, event.y)
                        mode = 1; v.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> { mode = 2; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { mode = 0; v.parent.requestDisallowInterceptTouchEvent(false); true }
                    MotionEvent.ACTION_MOVE -> {
                        if (mode == 1) {
                            matrix.set(savedMatrix)
                            matrix.postTranslate(event.x - start.x, event.y - start.y)
                        }
                        try { imageMatrix = matrix } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }
        }
        root.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                matrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
                try { imageView.imageMatrix = matrix } catch (_: Exception) {}
                return true
            }
        })

        dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(root)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    fun show(path: String) {
        imageView.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
        imageView.post {
            val vw = imageView.width.toFloat(); val vh = imageView.height.toFloat()
            val dw = imageView.drawable?.intrinsicWidth?.toFloat() ?: vw
            val dh = imageView.drawable?.intrinsicHeight?.toFloat() ?: vh
            val s = minOf(vw / dw, vh / dh, 1f)
            matrix.reset(); matrix.setScale(s, s)
            matrix.postTranslate((vw - dw * s) / 2, (vh - dh * s) / 2)
            imageView.imageMatrix = matrix
        }
        dialog.show()
    }
}