package com.handsfreegpt

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log

/**
 * The hands-free loop:
 *   WAIT_IDLE  → press ChatGPT's mic (dictation, not voice mode)
 *   STARTING   → wait for the dictation bar to appear
 *   LISTENING  → measure the mic until you stop talking
 *   STOPPING   → press ■ (or ↑ when auto-send is on) and wait for the bar to go away
 *   WAIT_REPLY → (auto-send / continuous) press send if needed, wait for the reply, listen again
 *   READY      → paused; tap the floating pill to talk again
 *
 * Everything runs on the main thread, driven by a 250 ms tick while a session is active.
 * startSession / endSession / toggleListening are the entry points for every remote control
 * (app button, Quick Settings tile, the floating pill — and later Android Auto).
 */
class DictationController(
    private val service: HandsFreeService,
    private val prefs: Prefs,
) : SpeechEndDetector.Listener {

    enum class State { OFF, READY, WAIT_IDLE, STARTING, LISTENING, STOPPING, WAIT_REPLY }

    private val ui = ChatGptUi(service, prefs)
    private val handler = Handler(Looper.getMainLooper())
    private val overlay = StatusOverlay(service, prefs, onTap = ::toggleListening, onHold = ::endSession)
    private val tick = Runnable { tick() }
    private var detector: SpeechEndDetector? = null
    private var tones: ToneGenerator? = null

    var state = State.OFF
        private set
    var statusText = ""
        private set
    private var stateSince = 0L
    private var attempts = 0
    private var beeped = false
    private var stopRetried = false
    private var sending = false
    private var pendingSend = false
    private var sawGenerating = false
    private var lastBusyAt = 0L
    private var fallbackStopAt = 0L // non-zero when silence detection is unavailable
    private var missingTicks = 0
    private var notDictatingTicks = 0
    private var lastMicRect: Rect? = null
    private var chatGptInFront = false
    private var suppressAutoStart = false

    /** Updated by the service on every ChatGPT content change; streaming replies change constantly. */
    var lastContentChangeAt = 0L

    val active get() = state != State.OFF

    fun startSession(listenNow: Boolean = true) {
        if (state == State.OFF) {
            overlay.show()
            service.setWatchContent(true)
        }
        attempts = 0
        if (listenNow) showChatGpt()
        go(if (listenNow) State.WAIT_IDLE else State.READY)
        refreshTile()
    }

    fun endSession() {
        if (state == State.OFF) return
        // Don't leave ChatGPT recording: stop (keeps the text in the box, never sends).
        if (state == State.LISTENING) ui.snapshot()?.takeIf { it.dictating }?.stop?.let(ui::click)
        stopDetector()
        handler.removeCallbacks(tick)
        overlay.hide()
        service.setWatchContent(false)
        state = State.OFF
        statusText = ""
        if (chatGptInFront) suppressAutoStart = true
        refreshTile()
    }

    /** One button that does the obvious thing: talk, finish talking, or stop waiting. */
    fun toggleListening() {
        when (state) {
            State.OFF -> startSession(listenNow = true)
            State.READY -> {
                attempts = 0
                showChatGpt()
                go(State.WAIT_IDLE)
            }
            State.LISTENING -> finishDictation()
            State.WAIT_IDLE, State.STARTING, State.WAIT_REPLY -> {
                pendingSend = false
                go(State.READY)
            }
            State.STOPPING -> Unit
        }
    }

    fun onChatGptInFront(inFront: Boolean) {
        chatGptInFront = inFront
        if (!inFront) {
            suppressAutoStart = false
        } else if (state == State.OFF && prefs.autoListenOnOpen && !suppressAutoStart) {
            startSession(listenNow = true)
        }
    }

    /** Brings ChatGPT to the front if it isn't on screen (accessibility services may start activities). */
    private fun showChatGpt() {
        if (ui.root() != null) return
        ChatGptUi.launchIntent(service)?.let { runCatching { service.startActivity(it) } }
    }

    /** Dumps ChatGPT's current UI (for tuning the button matching). */
    fun capture(reason: String): String = ui.dump(reason).also { service.saveCapture(it) }

    fun shutdown() {
        endSession()
        tones?.release()
        tones = null
    }

    private fun go(next: State) {
        state = next
        stateSince = SystemClock.elapsedRealtime()
        beeped = false
        stopRetried = false
        missingTicks = 0
        notDictatingTicks = 0
        lastMicRect = null
        when (next) {
            State.READY -> show("🎙 Tap to talk · hold to exit", GREY)
            State.WAIT_IDLE -> show("⏳ Getting ready…", GREY)
            State.STARTING -> show("⏳ Starting dictation…", GREY)
            State.LISTENING -> show("🎙 Listening…", RED)
            State.STOPPING -> show(if (sending) "📨 Sending…" else "⏹ Stopping…", GREY)
            State.WAIT_REPLY -> {
                sawGenerating = false
                lastBusyAt = stateSince
                show(if (pendingSend) "📨 Sending…" else "💬 Waiting for reply…", GREY)
            }
            State.OFF -> Unit
        }
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        if (state != State.OFF && state != State.READY) handler.postDelayed(tick, TICK_MS)
    }

    private fun tick() {
        step()
        schedule()
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val inState = now - stateSince
        val s = ui.snapshot()
        if (s == null) {
            when (state) {
                State.WAIT_IDLE, State.WAIT_REPLY -> {
                    stateSince = now
                    show("📱 Open ChatGPT to continue", GREY)
                }
                else -> if (++missingTicks >= 4) {
                    stopDetector()
                    go(State.READY)
                    show("📱 ChatGPT left the screen · tap to talk", GREY)
                }
            }
            return
        }
        missingTicks = 0

        when (state) {
            State.WAIT_IDLE -> when {
                s.dictating -> beginListening() // already dictating (e.g. you pressed the mic)
                s.idle -> {
                    // Wait until the mic stops moving (ChatGPT may still be animating in).
                    val mic = s.mic!!
                    val settled = mic.rect == lastMicRect
                    lastMicRect = mic.rect
                    if (!settled) return
                    if (prefs.beeps && !beeped) {
                        beeped = true // beep first; the next tick presses the mic so the beep isn't transcribed
                        tone(ToneGenerator.TONE_PROP_BEEP)
                    } else {
                        ui.click(mic)
                        go(State.STARTING)
                    }
                }
                s.generating -> {
                    stateSince = now
                    show("💬 Waiting for reply…", GREY)
                }
                inState > 6000 -> {
                    capture("mic button not found")
                    fail("Can't find ChatGPT's mic button")
                }
            }

            State.STARTING -> when {
                s.dictating -> beginListening()
                inState > 3500 -> if (++attempts < 3) {
                    go(State.WAIT_IDLE)
                } else {
                    capture("dictation didn't start")
                    fail("Dictation didn't start")
                }
            }

            State.LISTENING -> when {
                // ChatGPT (or you) ended the dictation; wait ~0.5 s to be sure it isn't a flicker.
                !s.dictating -> if (++notDictatingTicks >= 2) afterDictation()
                fallbackStopAt > 0 && now >= fallbackStopAt -> finishDictation()
                else -> notDictatingTicks = 0
            }

            State.STOPPING -> when {
                !s.dictating -> afterDictation()
                inState > 8000 && !stopRetried -> {
                    // Our press may have been missed. A disabled button means ChatGPT is still
                    // transcribing, so leave it alone.
                    stopRetried = true
                    (if (sending) s.send ?: s.stop else s.stop)?.takeIf { it.node.isEnabled }?.let(ui::click)
                }
                inState > 12000 -> {
                    capture("dictation didn't stop")
                    fail("Couldn't stop dictation")
                }
            }

            State.WAIT_REPLY -> {
                if (pendingSend) {
                    // We pressed ■; once the transcript lands in the box, press its send button.
                    val send = s.send
                    if (send != null && !s.dictating && !s.generating) {
                        ui.click(send)
                        pendingSend = false
                        if (prefs.continuous) {
                            go(State.WAIT_REPLY)
                        } else {
                            go(State.READY)
                            show("✅ Sent · tap to talk", GREY)
                        }
                    } else if (inState > 10000) {
                        pendingSend = false
                        capture("send button not found")
                        fail("Couldn't find the send button")
                    }
                    return
                }
                if (s.generating) {
                    sawGenerating = true
                    lastBusyAt = now
                }
                val quietMs = now - maxOf(lastBusyAt, lastContentChangeAt)
                val replyDone = s.idle && (
                    (quietMs > REPLY_QUIET_MS && (sawGenerating || inState > 8000)) ||
                        (now - lastBusyAt > 6000 && inState > 20000)
                    )
                if (replyDone) {
                    attempts = 0
                    go(State.WAIT_IDLE)
                }
            }

            State.OFF, State.READY -> Unit
        }
    }

    private fun beginListening() {
        go(State.LISTENING)
        fallbackStopAt = 0
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onUnavailable("no microphone permission")
            return
        }
        detector = SpeechEndDetector(
            silenceMs = prefs.silenceMs,
            sensitivityDb = prefs.sensitivityDb,
            noSpeechTimeoutMs = prefs.noSpeechTimeoutMs,
            maxListenMs = prefs.maxListenMs,
            listener = this,
        ).also { it.start() }
    }

    /** Press ■ (or ↑ when auto-sending) on the dictation bar. */
    private fun finishDictation() {
        stopDetector()
        val s = ui.snapshot()
        if (s == null || !s.dictating) {
            afterDictation()
            return
        }
        val wantSend = prefs.autoSend || prefs.continuous
        val target = if (wantSend) s.send ?: s.stop else s.stop
        if (target == null) {
            capture("stop button not found")
            fail("Can't find the ■ stop button")
            return
        }
        sending = wantSend
        pendingSend = wantSend && target !== s.send
        tone(ToneGenerator.TONE_PROP_ACK)
        ui.click(target)
        go(State.STOPPING)
    }

    /** Nothing was said: throw the dictation away (X) and pause. */
    private fun cancelDictation() {
        stopDetector()
        val s = ui.snapshot()
        if (s != null && s.dictating) (s.cancel ?: s.stop)?.let(ui::click)
        tone(ToneGenerator.TONE_PROP_NACK)
        go(State.READY)
        show("🤐 Didn't hear anything · tap to talk", GREY)
    }

    private fun afterDictation() {
        stopDetector()
        if (pendingSend || prefs.continuous) {
            go(State.WAIT_REPLY)
        } else {
            go(State.READY)
            if (sending) show("✅ Sent · tap to talk", GREY)
        }
        sending = false
    }

    private fun fail(message: String) {
        stopDetector()
        go(State.READY)
        show("⚠ $message · tap to retry", AMBER)
    }

    private fun stopDetector() {
        detector?.stop()
        detector = null
    }

    override fun onSpeaking(speaking: Boolean) {
        if (state != State.LISTENING || fallbackStopAt > 0) return
        if (speaking) show("🗣 Hearing you…", GREEN) else show("🎙 Listening…", RED)
    }

    override fun onEnd(reason: SpeechTracker.End) {
        if (state != State.LISTENING) return
        if (reason == SpeechTracker.End.NO_SPEECH) cancelDictation() else finishDictation()
    }

    override fun onUnavailable(why: String) {
        if (state != State.LISTENING) return
        stopDetector()
        fallbackStopAt = SystemClock.elapsedRealtime() + prefs.fallbackStopMs
        show("🎙 Listening · tap to stop ($why)", AMBER)
    }

    private fun tone(type: Int) {
        if (!prefs.beeps) return
        val t = tones ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()?.also { tones = it }
        t?.startTone(type, 150)
    }

    private fun show(text: String, color: Int) {
        if (text != statusText) Log.i(TAG, "$state: $text")
        statusText = text
        overlay.update(text, color)
    }

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(service, ComponentName(service, HandsFreeTileService::class.java))
        }
    }

    private companion object {
        const val TAG = "HandsFreeGPT"
        const val TICK_MS = 250L
        const val REPLY_QUIET_MS = 1500L
        const val GREY = 0xE0303134.toInt()
        const val RED = 0xE0B3261E.toInt()
        const val GREEN = 0xE01E7A34.toInt()
        const val AMBER = 0xE0A15C00.toInt()
    }
}
