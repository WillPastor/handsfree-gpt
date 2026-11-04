package com.handsfreegpt

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Date
import java.util.Locale
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
    /** The composer's text field, if visible. */
    val input: AccessibilityNodeInfo?,
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
        // What you've dictated or typed stays out of captures: only its length shows.
        append("\n  input=").append(
            input?.let {
                val text = it.text?.toString().orEmpty()
                when {
                    it.isShowingHintText -> "\"${text.take(40)}\" (placeholder)"
                    text.isEmpty() -> "\"\""
                    else -> "[${text.length} chars]"
                }
            },
        )
        if (guessed.isNotEmpty()) append("\n  guessed by position: ").append(guessed.joinToString())
    }
}

/**
 * ChatGPT's read-aloud controls: Read aloud under the latest reply, and the audio bar shown at the top of the
 * screen once it's pressed, [pause/play] 00:04 [seek bar] [X]. The bar stays after the reply has ended, showing
 * play, so an ended reply and a paused one differ only in where the seek bar is.
 */
class ReadAloud(
    val read: Btn?,
    /** Pauses the speech ("Pause audio"), or stops it where a stop shows instead. Only there while it plays. */
    val stop: Btn?,
    /** Plays the speech ("Play audio"): there while it's paused, and once it has ended. */
    val resume: Btn?,
    /** The bar's X ("Close"), which stops the speech and closes the bar. Found by position, so any language works. */
    val close: Btn?,
    /** How far along the bar's seek bar is, from 0 to 1, if it says. */
    val position: Float?,
) {
    /** Paused rather than ended: the seek bar is short of its end, where an ended reply leaves it. */
    val pausedPartway get() = stop == null && resume != null && position != null && position < 1 - SEEK_END

    /** ChatGPT's audio bar is showing, rather than a stop or play button somewhere else. */
    val barShown get() = close != null || position != null

    override fun toString() = "read=$read stop=$stop resume=$resume close=$close position=" +
        (position?.let { String.format(Locale.US, "%.3f", it) } ?: "-") + if (pausedPartway) " (paused partway)" else ""

    companion object {
        /** How near the end of the seek bar, as a fraction of it, still counts as the end. */
        const val SEEK_END = 0.02f
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
        val (buttons, input) = collect(root)
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
        return Snapshot(mic, stop, send, cancel, voice, input, guessed)
    }

    /**
     * Finds the Read aloud button of the latest reply, and the audio bar's controls. Replies can sit anywhere
     * on screen, so this looks at the whole window, lowest (latest) first. Any plain "stop" counts as pausing
     * too: when a reply is being read, nothing else ChatGPT shows can be stopped.
     */
    fun readAloud(): ReadAloud? {
        val root = root() ?: return null
        val (buttons, _) = collect(root, minFraction = 0f)
        val readW = Prefs.words(prefs.readAloudWords)
        val stopW = Prefs.words(prefs.stopReadingWords)
        val resumeW = Prefs.words(prefs.resumeReadingWords)
        fun isStop(l: String) = stopW.any { it in l } || l == "stop" || l.startsWith("stop ")
        var read: Btn? = null
        val stops = mutableListOf<Btn>()
        var resume: Btn? = null
        for (b in buttons.sortedByDescending { it.rect.centerY() }) {
            when {
                b.label.isEmpty() -> Unit
                isStop(b.label) -> stops += b
                resumeW.any { it in b.label } -> if (resume == null) resume = b
                readW.any { it in b.label } -> if (read == null) read = b
            }
        }
        // Press the bar's own button rather than its label, which sits too deep inside it to click. Once there's a
        // bar, only its own button counts: another "stop", such as a dictation's ■, can't stop the reply (#18).
        val stopBar = stops.firstNotNullOfOrNull(::audioBar)
        val resumeBar = if (stopBar == null) resume?.let(::audioBar) else null
        val bar = stopBar ?: resumeBar
        val stop = if (bar != null) stopBar?.toggle else stops.firstOrNull()
        return ReadAloud(read, stop, resumeBar?.toggle ?: resume, bar?.close, bar?.position)
    }

    private class AudioBar(val toggle: Btn, val close: Btn?, val position: Float?)

    /**
     * The audio bar around its play/pause control: [▶] 00:04 [seek bar] [X]. Null unless it has a seek bar,
     * so a stop button somewhere else is never taken for a bar, nor its neighbours for an X.
     */
    private fun audioBar(control: Btn): AudioBar? {
        val toggle = generateSequence(control.node) { it.parent }.take(9).firstOrNull { it.isClickable } ?: return null
        val bar = toggle.parent ?: return null
        val parts = (0 until bar.childCount).mapNotNull(bar::getChild).filter { it.isVisibleToUser }
        val seek = parts.firstOrNull { it.rangeInfo != null || it.className?.toString() == SEEK_BAR } ?: return null
        val seekRect = boundsOf(seek)
        val position = seek.rangeInfo?.takeIf { it.max > it.min }
            ?.let { ((it.current - it.min) / (it.max - it.min)).coerceIn(0f, 1f) }
        val close = parts.lastOrNull { it.isClickable && it != toggle && boundsOf(it).left >= seekRect.centerX() }
        return AudioBar(
            Btn(toggle, boundsOf(toggle), control.label),
            close?.let { Btn(it, boundsOf(it), labelOf(it)) },
            position,
        )
    }

    private fun boundsOf(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    /** What's typed in the composer ("" when it only shows its placeholder). */
    fun composerText(input: AccessibilityNodeInfo): String =
        if (input.isShowingHintText) "" else input.text?.toString().orEmpty()

    fun setComposerText(input: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            .also { Log.i(TAG, "set composer text (${text.length} chars): $it") }
    }

    /** Clickable things roughly on the same horizontal line as [anchor], left to right. */
    private fun rowOf(anchor: Btn, all: List<Btn>): List<Btn> {
        val tolerance = max(anchor.rect.height(), 24)
        return all.filter { it.node.isClickable && abs(it.rect.centerY() - anchor.rect.centerY()) < tolerance }
            .sortedBy { it.rect.centerX() }
    }

    /**
     * Button-like nodes below [minFraction] of the window, plus the lowest text field (the composer's
     * input). By default only the lower part, where the composer lives (it rises to mid-screen when
     * the keyboard is up), so words like "stop" or "send" in chat messages aren't buttons.
     */
    private fun collect(
        root: AccessibilityNodeInfo,
        minFraction: Float = 0.4f,
    ): Pair<List<Btn>, AccessibilityNodeInfo?> {
        val win = Rect().also { root.getBoundsInScreen(it) }
        val minY = win.top + win.height() * minFraction
        val out = ArrayList<Btn>()
        var input: AccessibilityNodeInfo? = null
        var inputY = Int.MIN_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            if (!n.isVisibleToUser) continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty || r.centerY() < minY) continue
            if (n.isEditable && r.centerY() > inputY) {
                input = n
                inputY = r.centerY()
            }
            // Chat text is never a button, whatever it says: ChatGPT labels each paragraph with its own text,
            // so a reply mentioning "done" would otherwise pass for a stop button.
            if (!n.isClickable && n.className?.toString() == TEXT_VIEW) continue
            val label = labelOf(n)
            if (n.isClickable || label.isNotEmpty()) out += Btn(n, r, label)
        }
        return out to input
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

    /**
     * Text dump of ChatGPT's accessibility tree, for fixing button matching. Chat text only shows its length,
     * and layout wrappers with nothing of their own are left out, so a capture fits in a message.
     */
    fun dump(reason: String): String = buildString {
        append("HandsFree GPT UI capture — ").append(Date()).append(" — ").append(reason).append('\n')
        append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        val version = runCatching { service.packageManager.getPackageInfo(PKG, 0).versionName }.getOrNull()
        append("ChatGPT ").append(version ?: "not found").append('\n')
        append("Detected: ").append(snapshot() ?: "ChatGPT window not on screen").append('\n')
        append("Read aloud: ").append(readAloud() ?: "-").append("\n\n")
        var lines = 0
        for (w in service.windows) {
            val r = w.root
            append("== window type=").append(w.type).append(" active=").append(w.isActive)
            append(" package=").append(r?.packageName).append('\n')
            if (r == null || r.packageName != PKG) continue
            lines = dumpNode(r, 0, 0, lines)
        }
    }

    /** Prints [n] at [depth] and its children below it. [level] is how deep it really is in the tree. */
    private fun StringBuilder.dumpNode(n: AccessibilityNodeInfo, depth: Int, level: Int, linesSoFar: Int): Int {
        if (linesSoFar > 4000 || level > 150) return linesSoFar
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        if (n.childCount <= 1 && plain(n, cls)) {
            val child = if (n.childCount == 1) n.getChild(0) else null
            return child?.let { dumpNode(it, depth, level + 1, linesSoFar) } ?: linesSoFar
        }
        var lines = linesSoFar + 1
        val r = Rect().also { n.getBoundsInScreen(it) }
        append("  ".repeat(depth)).append(cls)
        n.viewIdResourceName?.let { append(" id=").append(it.substringAfter(":id/")) }
        n.contentDescription?.let { append(" desc=").append(quoted(it, n, cls)) }
        n.text?.let { append(" text=").append(quoted(it, n, cls)) }
        n.hintText?.let { append(" hint=\"").append(it.take(60)).append('"') }
        n.rangeInfo?.let { append(" range=").append(it.min).append("..").append(it.max).append(" at ").append(it.current) }
        if (n.isClickable) append(" CLICK")
        if (n.isLongClickable) append(" LONGCLICK")
        if (n.isEditable) append(" EDIT")
        if (!n.isEnabled) append(" DISABLED")
        if (!n.isVisibleToUser) append(" HIDDEN")
        append(' ').append(r.toShortString()).append('\n')
        for (i in 0 until n.childCount) n.getChild(i)?.let { lines = dumpNode(it, depth + 1, level + 1, lines) }
        return lines
    }

    /**
     * A label or text as it goes in a capture. Chat text and what's in the text box only show their length:
     * ChatGPT repeats each paragraph in its description, so both are checked. Text without letters, such as
     * the audio bar's time, is kept.
     */
    private fun quoted(s: CharSequence, n: AccessibilityNodeInfo, cls: String): String {
        val typed = n.isEditable && !n.isShowingHintText
        val chat = typed || (!n.isClickable && (s.length > 40 || (cls == "TextView" && s.any(Char::isLetter))))
        return if (chat && s.isNotEmpty()) "[${s.length} chars]" else "\"${s.take(60)}\""
    }

    /** A layout wrapper with nothing of its own to show. Most of a Compose tree is these. */
    private fun plain(n: AccessibilityNodeInfo, cls: String) =
        (cls == "View" || cls.endsWith("Layout")) && n.viewIdResourceName == null && n.contentDescription == null &&
            n.text == null && n.hintText == null && n.rangeInfo == null &&
            !n.isClickable && !n.isLongClickable && !n.isEditable

    companion object {
        const val PKG = "com.openai.chatgpt"
        private const val TAG = "HandsFreeGPT"
        private const val TEXT_VIEW = "android.widget.TextView"
        private const val SEEK_BAR = "android.widget.SeekBar"

        fun launchIntent(context: Context): Intent? =
            context.packageManager.getLaunchIntentForPackage(PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private const val MAX_NODES = 2500
        // The full voice-mode ("GPT live") button — never pressed, only used as a landmark.
        private val VOICE_MODE_WORDS = listOf("voice conversation", "voice mode", "voice chat", "advanced voice")
    }
}
