package com.handsfreegpt

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** User-tunable settings, backed by SharedPreferences. Read them fresh each time; they're cheap. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * How long you must be quiet (after speaking) before dictation is stopped. A minute, so a pause to think
     * doesn't end it: "send it" or a button is the usual way to finish.
     */
    var silenceMs by intPref("silence_ms", 60_000)

    /** How far (dB) above the background noise a sound must be to count as speech. */
    var sensitivityDb by intPref("sensitivity_db", 10)

    /** Give up (cancel dictation) if nothing is said for this long. 0 = wait forever. */
    var noSpeechTimeoutMs by intPref("no_speech_timeout_ms", 12000)

    /**
     * Hard cap on a single dictation. Just under the 10 minutes ChatGPT records, so the app finishes it as usual
     * (sending it, with auto-send) rather than ChatGPT stopping it and leaving the text in the box.
     */
    var maxListenMs by intPref("max_listen_ms", 585_000)

    /** Used when Android won't share the microphone with us, so silence can't be detected. */
    var fallbackStopMs by intPref("fallback_stop_ms", 15000)

    /** Stop when you go quiet. Can be turned off when voice commands / buttons do the ending. */
    var stopOnSilence by boolPref("stop_on_silence", true)

    var voiceCommands by boolPref("voice_commands", true)
    var sendPhrases by stringPref("send_phrases", DEFAULT_SEND_PHRASES)
    var stopPhrases by stringPref("stop_phrases", DEFAULT_STOP_PHRASES)
    var cancelPhrases by stringPref("cancel_phrases", DEFAULT_CANCEL_PHRASES)
    var stripCommand by boolPref("strip_command", true)

    /** Steering-wheel / headset media buttons (and Android Auto) control hands-free. */
    var carButtons by boolPref("car_buttons", true)

    fun commandPhrases(): Map<Command, List<String>> = mapOf(
        Command.SEND to words(sendPhrases),
        Command.STOP to words(stopPhrases),
        Command.CANCEL to words(cancelPhrases),
    )

    /** The first "send" phrase, for hints like `say "send it"`. */
    fun sendHint(): String? = words(sendPhrases).firstOrNull()

    var autoSend by boolPref("auto_send", false)
    var autoListenOnOpen by boolPref("auto_listen_on_open", false)
    var continuous by boolPref("continuous", false)
    var beeps by boolPref("beeps", true)

    /** Press ChatGPT's own Read aloud button once a reply has finished. */
    var readAloud by boolPref("read_aloud", false)

    /** After sending, wait for the reply: to listen again, to read it aloud, or both. */
    val waitsForReply get() = continuous || readAloud

    /** Vertical position of the floating status pill, in px; -1 = default. */
    var overlayY by intPref("overlay_y", -1)

    var micWords by stringPref("mic_words", DEFAULT_MIC_WORDS)
    var stopWords by stringPref("stop_words", DEFAULT_STOP_WORDS)
    var sendWords by stringPref("send_words", DEFAULT_SEND_WORDS)
    var cancelWords by stringPref("cancel_words", DEFAULT_CANCEL_WORDS)
    var readAloudWords by stringPref("read_aloud_words", DEFAULT_READ_ALOUD_WORDS)
    var stopReadingWords by stringPref("stop_reading_words", DEFAULT_STOP_READING_WORDS)
    var resumeReadingWords by stringPref("resume_reading_words", DEFAULT_RESUME_READING_WORDS)

    fun resetButtonWords() {
        micWords = DEFAULT_MIC_WORDS
        stopWords = DEFAULT_STOP_WORDS
        sendWords = DEFAULT_SEND_WORDS
        cancelWords = DEFAULT_CANCEL_WORDS
        readAloudWords = DEFAULT_READ_ALOUD_WORDS
        stopReadingWords = DEFAULT_STOP_READING_WORDS
        resumeReadingWords = DEFAULT_RESUME_READING_WORDS
    }

    fun resetPhrases() {
        sendPhrases = DEFAULT_SEND_PHRASES
        stopPhrases = DEFAULT_STOP_PHRASES
        cancelPhrases = DEFAULT_CANCEL_PHRASES
    }

    private fun intPref(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            sp.edit().putInt(key, value).apply()
    }

    private fun boolPref(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    private fun stringPref(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            sp.edit().putString(key, value).apply()
    }

    companion object {
        // Matched (case-insensitive, substring) against each button's content description / text.
        const val DEFAULT_MIC_WORDS = "dictat, voice input, speech to text, microphone, transcri"
        const val DEFAULT_STOP_WORDS = "stop, done"
        const val DEFAULT_SEND_WORDS = "send, submit"
        const val DEFAULT_CANCEL_WORDS = "cancel, discard"

        // The speaker button under a reply, and the play/pause button of the audio bar ChatGPT shows while
        // it reads one out. Any stop button counts as pausing too, as nothing else is running then.
        const val DEFAULT_READ_ALOUD_WORDS = "read aloud, read out loud"
        const val DEFAULT_STOP_READING_WORDS = "pause audio, stop reading, stop speaking, stop read aloud"
        const val DEFAULT_RESUME_READING_WORDS = "play audio, resume audio"

        // Spoken at the end of a dictation. Two-word phrases rarely end a sentence by accident.
        const val DEFAULT_SEND_PHRASES = "send it, send message"
        const val DEFAULT_STOP_PHRASES = "stop dictation, stop listening"
        const val DEFAULT_CANCEL_PHRASES = "cancel that, cancel message"

        fun words(csv: String): List<String> =
            csv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
}
