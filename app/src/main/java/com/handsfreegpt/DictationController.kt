package com.handsfreegpt

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log

/**
 * The hands-free loop:
 *   WAIT_IDLE  → press ChatGPT's mic (dictation, not voice mode)
 *   STARTING   → wait for the dictation bar to appear
 *   LISTENING  → until you stop talking, say a command ("send it"), or press a car/headset button
 *   STOPPING   → press ■ (or ↑) and wait for the bar to go away
 *   FINISHING  → wait for the transcript to land in the text box, remove a spoken command, send, check it went
 *   WAIT_REPLY → (continuous) wait for the reply, then listen again
 *   READY      → paused; tap the floating pill or press play to talk again
 *
 * Everything runs on the main thread, driven by a 250 ms tick while a session is active.
 * startSession / endSession / toggleListening / sendNow / cancelNow are the entry points for every
 * control: the app, the Quick Settings tile, the floating pill, and media buttons / Android Auto.
 */
class DictationController(
    private val service: HandsFreeService,
    private val prefs: Prefs,
) : SpeechEndDetector.Listener {

    enum class State { OFF, READY, WAIT_IDLE, STARTING, LISTENING, STOPPING, FINISHING, WAIT_REPLY }

    private val ui = ChatGptUi(service, prefs)
    private val handler = Handler(Looper.getMainLooper())
    private val overlay = StatusOverlay(service, prefs, onTap = ::toggleListening, onHold = ::endSession)
    private val tick = Runnable { tick() }
    private var detector: SpeechEndDetector? = null
    private var voice: CommandRecognizer? = null
    private var tones: ToneGenerator? = null

    var state = State.OFF
        private set
    var statusText = ""
        private set
    private var stateSince = 0L
    private var attempts = 0
    private var beeped = false
    private var stopRetried = false
    private var sending = false // this dictation is meant to be sent
    private var pressedSend = false // we pressed the dictation bar's ↑ rather than ■
    private var pendingSend = false // press the composer's send once the transcript lands
    private var pendingStrip: String? = null // spoken command to remove from the transcript first
    private var textBefore: String? = null // the text box when we pressed the mic; null once the transcript is in
    private var lastText: String? = null // the text box on the previous tick, to see the transcript settle
    private var stripTarget: String? = null // the transcript without the spoken command, worked out once
    private var stripTries = 0
    private var sendTries = 0
    private var sentAt = 0L // when we last pressed send; it only counts once ChatGPT has taken the message
    private var sawGenerating = false
    private var lastBusyAt = 0L
    private var fallbackStopAt = 0L // non-zero when silence detection is unavailable
    private var graceUntil = 0L // silence ended speech; a spoken command may still be on its way
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
            if (prefs.carButtons) MediaControls.engage(service)
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
        stopListening()
        handler.removeCallbacks(tick)
        overlay.hide()
        service.setWatchContent(false)
        MediaControls.release(service)
        state = State.OFF
        statusText = ""
        pendingSend = false
        pendingStrip = null
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
            State.WAIT_IDLE, State.STARTING, State.FINISHING, State.WAIT_REPLY -> {
                pendingSend = false
                pendingStrip = null
                go(State.READY)
            }
            State.STOPPING -> Unit
        }
    }

    /** Finish and send (e.g. the ⏭ button). When paused with text in the box, sends that. */
    fun sendNow() {
        when (state) {
            State.LISTENING -> finishDictation(send = true)
            State.READY -> ui.snapshot()?.takeIf { it.idle }?.send?.let {
                ui.click(it)
                if (prefs.continuous) go(State.WAIT_REPLY) else show("✅ Sent · tap to talk", GREY)
            }
            else -> Unit
        }
    }

    /** Throw away the current dictation (e.g. the ⏮ button), or stop waiting. */
    fun cancelNow() {
        when (state) {
            State.LISTENING -> cancelDictation()
            State.WAIT_IDLE, State.STARTING, State.FINISHING, State.WAIT_REPLY -> {
                pendingSend = false
                pendingStrip = null
                go(State.READY)
            }
            else -> Unit
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
        lastText = null
        stripTarget = null
        stripTries = 0
        sendTries = 0
        sentAt = 0
        when (next) {
            State.READY -> show("🎙 Tap to talk · hold to exit", GREY)
            State.WAIT_IDLE -> {
                textBefore = null
                show("⏳ Getting ready…", GREY)
            }
            State.STARTING -> show("⏳ Starting dictation…", GREY)
            State.LISTENING -> show("🎙 Listening…", RED)
            State.STOPPING, State.FINISHING -> show(if (sending) "📨 Sending…" else "⏹ Stopping…", GREY)
            State.WAIT_REPLY -> {
                sawGenerating = false
                lastBusyAt = stateSince
                show("💬 Waiting for reply…", GREY)
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
                    stopListening()
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
                        textBefore = s.input?.let(ui::composerText)
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
                graceUntil > 0 && now >= graceUntil -> finishDictation()
                else -> notDictatingTicks = 0
            }

            State.STOPPING -> when {
                !s.dictating -> afterDictation()
                inState > 8000 && !stopRetried -> {
                    // Our press may have been missed. A disabled button means ChatGPT is still
                    // transcribing, so leave it alone.
                    stopRetried = true
                    (if (pressedSend) s.send else s.stop)?.takeIf { it.node.isEnabled }?.let(ui::click)
                }
                inState > 12000 -> {
                    capture("dictation didn't stop")
                    fail("Couldn't stop dictation")
                }
            }

            State.FINISHING -> finishTranscript(s, inState, now)

            State.WAIT_REPLY -> {
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

    /**
     * After ■: wait for the transcript to land in the text box, strip a spoken command, send, then check
     * that ChatGPT took the message. ChatGPT can bring back its text box, send arrow included, before the
     * transcript is in it (issue #1), so we wait for the text itself.
     */
    private fun finishTranscript(s: Snapshot, inState: Long, now: Long) {
        val input = s.input
        val text = input?.let(ui::composerText).orEmpty()
        val send = s.send?.takeIf { it.node.isEnabled }
        if (sentAt > 0) {
            confirmSent(s, input != null && text.isBlank(), send, now)
            return
        }
        // The transcript is in once there's new text, it has stopped changing and ChatGPT will send it.
        val landed = !s.dictating && !s.generating && text.isNotBlank() && text != textBefore &&
            s.send?.node?.isEnabled != false
        val settled = landed && text == lastText
        lastText = text
        if (inState > FINISH_TIMEOUT_MS) {
            val hasText = text.isNotBlank() && text != textBefore
            val wasSending = pendingSend
            pendingSend = false
            pendingStrip = null
            when {
                hasText && wasSending -> couldNotSend("send button never became ready")
                hasText -> {
                    go(State.READY)
                    show("📝 Text is in the box · tap to talk", GREY)
                }
                else -> {
                    capture("transcript never arrived")
                    go(State.READY)
                    show("🤐 Nothing to send · tap to talk", GREY)
                }
            }
            return
        }
        if (!settled) return
        textBefore = null // the transcript is in; from here on, changes to the text are ours

        pendingStrip?.let { phrase ->
            // Work out the text without the command once, then only check that the edit took (retrying
            // that same edit). A message that itself ends in "send it" keeps it (Codex review of #2).
            val target = stripTarget ?: CommandMatcher.stripTrailingCommand(text, phrase).also { stripTarget = it }
            if (text != target && stripTries++ < 3) {
                ui.setComposerText(input!!, target)
                if (target.isBlank()) {
                    pendingSend = false
                    pendingStrip = null
                    go(State.READY)
                    show("🤐 Nothing to send · tap to talk", GREY)
                } else {
                    lastText = target // carry on next tick, once the edit shows up
                }
                return
            }
            if (text != target) Log.w(TAG, "couldn't remove \"$phrase\" from the text; leaving it in")
            pendingStrip = null
        }
        if (!pendingSend) {
            go(State.READY)
            show("📝 Text is in the box · tap to talk", GREY)
            return
        }
        if (send == null) return // not ready to send yet
        pendingSend = false
        sendTries = 1
        sentAt = now
        ui.click(send)
    }

    /** ChatGPT has taken the message once the text box empties or a reply starts. If not, press send once more. */
    private fun confirmSent(s: Snapshot, boxEmpty: Boolean, send: Btn?, now: Long) {
        when {
            s.generating || boxEmpty -> if (prefs.continuous) {
                go(State.WAIT_REPLY)
            } else {
                go(State.READY)
                show("✅ Sent · tap to talk", GREY)
            }
            now - sentAt < SEND_CONFIRM_MS -> Unit
            sendTries < 2 && send != null -> {
                sendTries++
                sentAt = now
                ui.click(send)
            }
            else -> couldNotSend("message wasn't sent")
        }
    }

    private fun couldNotSend(reason: String) {
        capture(reason)
        pendingSend = false
        pendingStrip = null
        go(State.READY)
        show("⚠ Couldn't send · the text is still in the box", AMBER)
    }

    private fun beginListening() {
        go(State.LISTENING)
        fallbackStopAt = 0
        graceUntil = 0
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onUnavailable("no microphone permission")
            return
        }
        val unavailable = VoiceSupport.unavailableReason(service)
        if (prefs.voiceCommands && unavailable != null) Log.w(TAG, "voice commands unavailable: $unavailable")
        val recognizer: CommandRecognizer? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && prefs.voiceCommands && unavailable == null) {
            VoiceCommandRecognizer(service, CommandMatcher(prefs.commandPhrases()), voiceListener)
        } else {
            null
        }
        voice = recognizer
        detector = SpeechEndDetector(
            silenceMs = if (prefs.stopOnSilence) prefs.silenceMs else 0,
            sensitivityDb = prefs.sensitivityDb,
            noSpeechTimeoutMs = prefs.noSpeechTimeoutMs,
            maxListenMs = prefs.maxListenMs,
            listener = this,
            frameSink = recognizer?.let { it::feed },
        ).also { it.start() }
        recognizer?.start()
        val phrase = prefs.sendHint()
        if (voice != null && phrase != null) show("🎙 Listening · say “$phrase”", RED)
    }

    /** Press ■ (or ↑) on the dictation bar. A spoken command is cleaned out of the text first. */
    private fun finishDictation(send: Boolean = prefs.autoSend || prefs.continuous, stripPhrase: String? = null) {
        stopListening()
        val s = ui.snapshot()
        if (s == null || !s.dictating) {
            afterDictation()
            return
        }
        val strip = stripPhrase?.takeIf { prefs.stripCommand }
        // To remove the command, the transcript must land in the text box, so press ■ rather than ↑.
        val target = when {
            strip != null && s.stop != null -> s.stop
            send -> s.send ?: s.stop
            else -> s.stop
        }
        if (target == null) {
            capture("stop button not found")
            fail("Can't find the ■ stop button")
            return
        }
        pressedSend = target === s.send
        sending = send
        pendingStrip = if (pressedSend) null else strip
        pendingSend = send && !pressedSend
        tone(ToneGenerator.TONE_PROP_ACK)
        ui.click(target)
        go(State.STOPPING)
    }

    /** Nothing was said, or you asked to cancel: throw the dictation away (X) and pause. */
    private fun cancelDictation(message: String = "🗑 Dictation cancelled · tap to talk") {
        stopListening()
        val s = ui.snapshot()
        if (s != null && s.dictating) (s.cancel ?: s.stop)?.let(ui::click)
        tone(ToneGenerator.TONE_PROP_NACK)
        pendingSend = false
        pendingStrip = null
        go(State.READY)
        show(message, GREY)
    }

    private fun afterDictation() {
        stopListening()
        when {
            pendingStrip != null || pendingSend -> go(State.FINISHING)
            prefs.continuous -> go(State.WAIT_REPLY)
            sending -> {
                go(State.READY)
                show("✅ Sent · tap to talk", GREY)
            }
            else -> go(State.READY)
        }
        sending = false
    }

    private fun fail(message: String) {
        stopListening()
        go(State.READY)
        show("⚠ $message · tap to retry", AMBER)
    }

    private fun stopListening() {
        detector?.stop()
        detector = null
        voice?.stop()
        voice = null
        graceUntil = 0
    }

    private val voiceListener = object : CommandRecognizer.Listener {
        override fun onHeard(text: String) {
            if (state != State.LISTENING || fallbackStopAt > 0) return
            show("🗣 …" + text.trim().split(WHITESPACE).takeLast(5).joinToString(" "), GREEN)
        }

        override fun onCommand(match: CommandMatcher.Match) {
            if (state != State.LISTENING) return
            Log.i(TAG, "voice command ${match.command}: \"${match.phrase}\"")
            when (match.command) {
                Command.SEND -> finishDictation(send = true, stripPhrase = match.phrase)
                Command.STOP -> finishDictation(send = false, stripPhrase = match.phrase)
                Command.CANCEL -> cancelDictation()
            }
        }

        override fun onUnavailable(why: String) {
            Log.w(TAG, "voice commands unavailable: $why")
            voice = null
            if (state == State.LISTENING && fallbackStopAt == 0L) show("🎙 Listening · voice commands off ($why)", AMBER)
        }
    }

    override fun onSpeaking(speaking: Boolean) {
        if (state != State.LISTENING || fallbackStopAt > 0 || voice != null) return // live words shown instead
        if (speaking) show("🗣 Hearing you…", GREEN) else show("🎙 Listening…", RED)
    }

    override fun onEnd(reason: SpeechTracker.End) {
        if (state != State.LISTENING) return
        when {
            reason == SpeechTracker.End.NO_SPEECH -> cancelDictation("🤐 Didn't hear anything · tap to talk")
            // You went quiet, but "…send it" may still be being recognised: give it a moment.
            reason == SpeechTracker.End.SPEECH_ENDED && voice != null ->
                graceUntil = SystemClock.elapsedRealtime() + COMMAND_GRACE_MS
            else -> finishDictation()
        }
    }

    override fun onUnavailable(why: String) {
        if (state != State.LISTENING) return
        stopListening()
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
        if (state != State.OFF) MediaControls.setStatus(text)
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
        const val FINISH_TIMEOUT_MS = 20_000L // a long dictation can take ChatGPT a while to transcribe
        const val SEND_CONFIRM_MS = 3000L
        const val COMMAND_GRACE_MS = 1200L
        val WHITESPACE = Regex("\\s+")
        const val GREY = 0xE0303134.toInt()
        const val RED = 0xE0B3261E.toInt()
        const val GREEN = 0xE01E7A34.toInt()
        const val AMBER = 0xE0A15C00.toInt()
    }
}
