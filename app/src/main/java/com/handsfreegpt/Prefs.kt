package com.handsfreegpt

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** User-tunable settings, backed by SharedPreferences. Read them fresh each time; they're cheap. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How long you must be quiet (after speaking) before dictation is stopped. */
    var silenceMs by intPref("silence_ms", 1600)

    /** How far (dB) above the background noise a sound must be to count as speech. */
    var sensitivityDb by intPref("sensitivity_db", 10)

    /** Give up (cancel dictation) if nothing is said for this long. 0 = wait forever. */
    var noSpeechTimeoutMs by intPref("no_speech_timeout_ms", 12000)

    /** Hard cap on a single dictation. */
    var maxListenMs by intPref("max_listen_ms", 90000)

    /** Used when Android won't share the microphone with us, so silence can't be detected. */
    var fallbackStopMs by intPref("fallback_stop_ms", 15000)

    var autoSend by boolPref("auto_send", false)
    var autoListenOnOpen by boolPref("auto_listen_on_open", false)
    var continuous by boolPref("continuous", false)
    var beeps by boolPref("beeps", true)

    /** Vertical position of the floating status pill, in px; -1 = default. */
    var overlayY by intPref("overlay_y", -1)

    var micWords by stringPref("mic_words", DEFAULT_MIC_WORDS)
    var stopWords by stringPref("stop_words", DEFAULT_STOP_WORDS)
    var sendWords by stringPref("send_words", DEFAULT_SEND_WORDS)
    var cancelWords by stringPref("cancel_words", DEFAULT_CANCEL_WORDS)

    fun resetButtonWords() {
        micWords = DEFAULT_MIC_WORDS
        stopWords = DEFAULT_STOP_WORDS
        sendWords = DEFAULT_SEND_WORDS
        cancelWords = DEFAULT_CANCEL_WORDS
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

        fun words(csv: String): List<String> =
            csv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
}
