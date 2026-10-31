package com.handsfreegpt

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Small pill floating over ChatGPT while hands-free is on. Tap = talk / stop now,
 * press and hold = turn hands-free off, drag = move it. It also keeps the screen on, because
 * ChatGPT has to stay on screen for its buttons to be pressed.
 */
class StatusOverlay(
    private val service: AccessibilityService,
    private val prefs: Prefs,
    private val onTap: () -> Unit,
    private val onHold: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private val background = GradientDrawable().apply { cornerRadius = 24 * density }
    private var view: TextView? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = (8 * density).toInt()
    }

    @SuppressLint("ClickableViewAccessibility") // onTouch calls performClick() for taps
    fun show() {
        if (view != null) return
        val saved = prefs.overlayY
        params.y = if (saved >= 0) saved else (service.resources.displayMetrics.heightPixels * 0.3f).toInt()
        val tv = TextView(service).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            background = this@StatusOverlay.background
            val h = (14 * density).toInt()
            val v = (8 * density).toInt()
            setPadding(h, v, h, v)
            keepScreenOn = true
            // Click listeners also make the pill usable with TalkBack / Switch Access.
            setOnClickListener { onTap() }
            setOnLongClickListener {
                onHold()
                true
            }
            setOnTouchListener(::onTouch)
        }
        view = tv
        runCatching { wm.addView(tv, params) }.onFailure { view = null }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    fun update(text: String, color: Int) {
        val v = view ?: return
        v.text = text
        background.setColor(color)
        v.invalidate()
    }

    private var downY = 0f
    private var startY = 0
    private var dragging = false
    private var held = false
    private val holdRunnable = Runnable {
        held = true
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHold()
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY
                startY = params.y
                dragging = false
                held = false
                handler.postDelayed(holdRunnable, 700)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (!dragging && abs(dy) > slop) {
                    dragging = true
                    handler.removeCallbacks(holdRunnable)
                }
                if (dragging) {
                    params.y = (startY + dy).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(v, params) }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                if (dragging) {
                    prefs.overlayY = params.y
                } else if (!held) {
                    v.performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(holdRunnable)
        }
        return true
    }
}
