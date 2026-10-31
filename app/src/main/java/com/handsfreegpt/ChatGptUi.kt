package com.handsfreegpt

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

/** A visible node in the lower part of ChatGPT's window that looks like a button. */
class Btn(val node: AccessibilityNodeInfo, val rect: Rect, val label: String) {
    override fun toString() = "\"$label\" ${rect.toShortString()}" + if (node.isClickable) "" else " (not clickable)"
}

/**
 * What ChatGPT's composer looks like right now. Buttons are recognised by their accessibility
 * label; when a label is unknown we fall back to position (e.g. the mic sits just left of the
 * voice-mode button, the dictation stop button sits just left of its send arrow).
 */
class Snapshot(
    val mic: Btn?,
    val stop: Btn?,
    val send: Btn?,
    val cancel: Btn?,
    val voice: Btn?,
    val guessed: Set<String>,
) {
    /** The dictation bar (X · waveform · ■ · ↑) is showing. */
    val dictating get() = (stop != null && mic == null) || (cancel != null && (stop != null || mic == null))

    /** A reply is streaming: the normal composer is back, plus a stop button. */
    val generating get() = stop != null && !dictating

    /** Normal composer, ready for a new dictation. */
    val idle get() = mic != null && stop == null && !dictating

    override fun toString() = buildString {
        append(if (dictating) "DICTATING" else if (generating) "GENERATING" else if (idle) "IDLE" else "UNKNOWN")
        append("\n  mic=").append(mic).append("\n  stop=").append(stop).append("\n  send=").append(send)
        append("\n  cancel=").append(cancel).append("\n  voiceMode=").append(voice)
        if (guessed.isNotEmpty()) append("\n  guessed by position: ").append(guessed.joinToString())
    }
}

class ChatGptUi(private val service: AccessibilityService, private val prefs: Prefs) {

    /** Root of ChatGPT's window if it is on screen (also works while the keyboard is up). */
    fun root(): AccessibilityNodeInfo? {
        for (w in service.windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            if (r.packageName == PKG) return r
        }
        return null
    }

    fun snapshot(): Snapshot? {
        val root = root() ?: return null
        val buttons = collectButtons(root)
        val micW = Prefs.words(prefs.micWords)
        val stopW = Prefs.words(prefs.stopWords)
        val sendW = Prefs.words(prefs.sendWords)
        val cancelW = Prefs.words(prefs.cancelWords)

        // Each label gets exactly one role, checked in this order, so "Stop dictation" is a stop
        // button and not a mic.
        fun roleOf(l: String): String? = when {
            l.isEmpty() -> null
            cancelW.any { it in l } -> "cancel"
            stopW.any { it in l } -> "stop"
            sendW.any { it in l } -> "send"
            VOICE_MODE_WORDS.any { it in l } -> "voice"
            micW.any { it in l } -> "mic"
            else -> null
        }

        var mic: Btn? = null
        var stop: Btn? = null
        var send: Btn? = null
        var cancel: Btn? = null
        var voice: Btn? = null
        // Bottom-most first: the composer is the lowest row of controls.
        for (b in buttons.sortedByDescending { it.rect.centerY() }) {
            when (roleOf(b.label)) {
                "cancel" -> if (cancel == null) cancel = b
                "stop" -> if (stop == null) stop = b
                "send" -> if (send == null) send = b
                "voice" -> if (voice == null) voice = b
                "mic" -> if (mic == null) mic = b
            }
        }

        val guessed = mutableSetOf<String>()
        fun unknown(b: Btn) = roleOf(b.label) == null

        // Normal composer: [+] … [mic] [voice mode]. The mic is the button just left of voice mode.
        if (mic == null && stop == null && cancel == null && voice != null) {
            mic = rowOf(voice, buttons).lastOrNull { it.rect.centerX() < voice.rect.left && unknown(it) }
                ?.also { guessed += "mic" }
        }
        // Dictation bar: [X] ~~waveform~~ [■] [↑]. Fill in whichever of stop/send has no label — but
        // only once its X was recognised: a composer holding text ([+] … [mic] [send]) looks the same.
        if (cancel != null && mic == null && (stop == null || send == null)) {
            val row = rowOf(cancel, buttons)
            if (row.size >= 3) {
                if (send == null && unknown(row.last())) send = row.last().also { guessed += "send" }
                val beforeLast = row[row.size - 2]
                if (stop == null && beforeLast != cancel && unknown(beforeLast)) stop = beforeLast.also { guessed += "stop" }
            }
        }
        return Snapshot(mic, stop, send, cancel, voice, guessed)
    }

    /** Clickable things roughly on the same horizontal line as [anchor], left to right. */
    private fun rowOf(anchor: Btn, all: List<Btn>): List<Btn> {
        val tolerance = max(anchor.rect.height(), 24)
        return all.filter { it.node.isClickable && abs(it.rect.centerY() - anchor.rect.centerY()) < tolerance }
            .sortedBy { it.rect.centerX() }
    }

    private fun collectButtons(root: AccessibilityNodeInfo): List<Btn> {
        val win = Rect().also { root.getBoundsInScreen(it) }
        // Only look at the lower part of the window, where the composer lives (it rises to mid-screen
        // when the keyboard is up), so words like "stop" or "send" in chat messages aren't buttons.
        val minY = win.top + win.height() * 0.4f
        val out = ArrayList<Btn>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            if (!n.isVisibleToUser) continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty || r.centerY() < minY) continue
            val label = labelOf(n)
            if (n.isClickable || label.isNotEmpty()) out += Btn(n, r, label)
        }
        return out
    }

    private fun labelOf(n: AccessibilityNodeInfo): String {
        val desc = n.contentDescription?.toString().orEmpty()
        val cls = n.className?.toString().orEmpty()
        val text = n.text?.toString().orEmpty()
        // Text only counts for short, button-ish nodes (message bubbles have long text).
        val useText = text.length <= 30 && (n.isClickable || cls.endsWith("Button")) && !n.isEditable
        return (if (useText) "$desc $text" else desc).trim().lowercase()
    }

    /** Clicks like a finger would: accessibility click first, real tap as a fallback. */
    fun click(b: Btn): Boolean {
        var target: AccessibilityNodeInfo? = b.node
        var hops = 0
        val area = max(1, b.rect.width() * b.rect.height())
        while (target != null && !target.isClickable && hops < 3) {
            val parent = target.parent ?: break
            val pr = Rect().also { parent.getBoundsInScreen(it) }
            if (pr.width() * pr.height() > 4 * area) break // don't click a whole container
            target = parent
            hops++
        }
        if (target != null && target.isClickable && target.isEnabled &&
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            Log.i(TAG, "clicked $b")
            return true
        }
        Log.i(TAG, "tapping $b")
        return tap(b.rect.exactCenterX(), b.rect.exactCenterY())
    }

    private fun tap(x: Float, y: Float): Boolean {
        if (x < 0 || y < 0) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /** Text dump of ChatGPT's accessibility tree, for fixing button matching. Long chat text is redacted. */
    fun dump(reason: String): String = buildString {
        append("HandsFree GPT UI capture — ").append(Date()).append(" — ").append(reason).append('\n')
        append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        val version = runCatching { service.packageManager.getPackageInfo(PKG, 0).versionName }.getOrNull()
        append("ChatGPT ").append(version ?: "not found").append('\n')
        append("Detected: ").append(snapshot() ?: "ChatGPT window not on screen").append("\n\n")
        var lines = 0
        for (w in service.windows) {
            val r = w.root
            append("== window type=").append(w.type).append(" active=").append(w.isActive)
            append(" package=").append(r?.packageName).append('\n')
            if (r == null || r.packageName != PKG) continue
            lines = dumpNode(r, 0, lines)
        }
    }

    private fun StringBuilder.dumpNode(n: AccessibilityNodeInfo, depth: Int, linesSoFar: Int): Int {
        if (linesSoFar > 4000 || depth > 80) return linesSoFar
        var lines = linesSoFar + 1
        val r = Rect().also { n.getBoundsInScreen(it) }
        append("  ".repeat(depth)).append(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.viewIdResourceName?.let { append(" id=").append(it.substringAfter(":id/")) }
        n.contentDescription?.let { append(" desc=\"").append(it.take(100)).append('"') }
        n.text?.let {
            if (it.length > 40 && !n.isClickable) append(" text=[").append(it.length).append(" chars]")
            else append(" text=\"").append(it.take(60)).append('"')
        }
        n.hintText?.let { append(" hint=\"").append(it.take(60)).append('"') }
        if (n.isClickable) append(" CLICK")
        if (n.isLongClickable) append(" LONGCLICK")
        if (n.isEditable) append(" EDIT")
        if (!n.isEnabled) append(" DISABLED")
        if (!n.isVisibleToUser) append(" HIDDEN")
        append(' ').append(r.toShortString()).append('\n')
        for (i in 0 until n.childCount) n.getChild(i)?.let { lines = dumpNode(it, depth + 1, lines) }
        return lines
    }

    companion object {
        const val PKG = "com.openai.chatgpt"
        private const val TAG = "HandsFreeGPT"

        fun launchIntent(context: Context): Intent? =
            context.packageManager.getLaunchIntentForPackage(PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private const val MAX_NODES = 2500
        // The full voice-mode ("GPT live") button — never pressed, only used as a landmark.
        private val VOICE_MODE_WORDS = listOf("voice conversation", "voice mode", "voice chat", "advanced voice")
    }
}
