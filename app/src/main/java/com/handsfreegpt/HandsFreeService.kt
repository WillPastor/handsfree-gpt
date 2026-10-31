package com.handsfreegpt

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.io.File

/**
 * The accessibility service: it can read ChatGPT's screen, press its buttons and draw the
 * floating status pill. All the decisions live in [DictationController].
 */
class HandsFreeService : AccessibilityService() {

    lateinit var controller: DictationController
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var keyboardPackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller = DictationController(this, Prefs(this))
        keyboardPackages = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (instance !== this) return
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (pkg == ChatGptUi.PKG) controller.lastContentChangeAt = SystemClock.elapsedRealtime()

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Keyboards, the notification shade and our own pill don't count as "leaving ChatGPT".
                if (pkg == packageName || pkg == "com.android.systemui" || pkg in keyboardPackages) return
                controller.onChatGptInFront(pkg == ChatGptUi.PKG)
            }

            else -> Unit
        }
    }

    /** Content-change events are only needed (to tell when a reply finished) during a session. */
    fun setWatchContent(on: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            (if (on) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0)
        serviceInfo = info
    }

    fun saveCapture(text: String) {
        runCatching { File(filesDir, CAPTURE_FILE).writeText(text) }
    }

    fun captureIn(delayMs: Long) {
        handler.postDelayed({
            controller.capture("manual capture")
            Toast.makeText(this, "HandsFree GPT: ChatGPT screen captured", Toast.LENGTH_SHORT).show()
        }, delayMs)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        if (::controller.isInitialized) controller.shutdown()
    }

    companion object {
        const val CAPTURE_FILE = "last_capture.txt"

        /** The running service, or null when it's switched off in Accessibility settings. */
        @Volatile
        var instance: HandsFreeService? = null
            private set
    }
}
