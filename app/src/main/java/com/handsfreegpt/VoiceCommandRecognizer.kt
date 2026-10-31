package com.handsfreegpt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Listens for spoken commands in audio we capture. Construct via [VoiceSupport] checks. */
interface CommandRecognizer {
    interface Listener {
        /** The latest words heard, for live feedback. */
        fun onHeard(text: String)
        fun onCommand(match: CommandMatcher.Match)
        fun onUnavailable(why: String)
    }

    /** Called on the audio thread with every captured 16 kHz frame. */
    fun feed(frame: ShortArray, size: Int)
    fun start()
    fun stop()
}

/** Whether voice commands can run on this phone (safe to call on any Android version). */
object VoiceSupport {
    fun unavailableReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "needs Android 13 or newer"
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return "this phone has no on-device speech recognition"
        return null
    }
}

/**
 * Hears spoken commands ("send it", "cancel that") in the audio we already capture, using the phone's
 * own on-device speech recognizer. Audio is handed over through a pipe, so no second microphone stream
 * is opened, and recognition never leaves the phone. Needs Android 13 (recognition from an audio source).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class VoiceCommandRecognizer(
    private val context: Context,
    private val matcher: CommandMatcher,
    private val listener: CommandRecognizer.Listener,
) : CommandRecognizer {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var pipe: AudioPipe? = null
    private var active = false
    private var restarts = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            matcher.tick(SystemClock.elapsedRealtime())?.let(::fire)
            main.postDelayed(this, 100)
        }
    }

    // Recognizers that ignore segmented sessions stop after every utterance; just keep listening.
    private val restart = Runnable { if (active) listen() }

    override fun feed(frame: ShortArray, size: Int) {
        pipe?.offer(frame, size)
    }

    override fun start() {
        VoiceSupport.unavailableReason(context)?.let {
            listener.onUnavailable(it)
            return
        }
        active = true
        restarts = 0
        matcher.reset()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { it.setRecognitionListener(callbacks) }
        listen()
        main.post(ticker)
    }

    override fun stop() {
        active = false
        main.removeCallbacks(ticker)
        main.removeCallbacks(restart)
        pipe?.close()
        pipe = null
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        pipe?.close()
        val audio = AudioPipe()
        pipe = audio
        val intent = recognizeIntent()
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio.readEnd)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            // One session until the pipe closes, reporting a segment per utterance.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        recognizer?.startListening(intent)
    }

    private fun scheduleRestart() {
        if (!active) return
        if (++restarts > MAX_RESTARTS) {
            unavailable("the speech recognizer keeps stopping")
            return
        }
        main.removeCallbacks(restart)
        main.postDelayed(restart, 150)
    }

    private fun heard(results: Bundle?, final: Boolean) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!active || text.isNullOrBlank()) return
        listener.onHeard(text)
        matcher.update(text, final, SystemClock.elapsedRealtime())?.let(::fire)
    }

    private fun fire(match: CommandMatcher.Match) {
        if (active) listener.onCommand(match)
    }

    private fun unavailable(why: String) {
        if (!active) return
        stop()
        listener.onUnavailable(why)
    }

    private val callbacks = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) = heard(partialResults, final = false)
        override fun onSegmentResults(segmentResults: Bundle) = heard(segmentResults, final = true)
        override fun onEndOfSegmentedSession() = scheduleRestart()

        override fun onResults(results: Bundle?) {
            heard(results, final = true)
            scheduleRestart()
        }

        override fun onError(error: Int) {
            Log.i(TAG, "speech recognizer error $error")
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    unavailable("offline speech for ${Locale.getDefault().displayLanguage} isn't installed")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> unavailable("no microphone permission")
                else -> scheduleRestart() // no match, timeout, busy…: keep listening
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Carries audio from the capture thread into the recognizer without ever blocking capture. */
    private class AudioPipe {
        private val ends = ParcelFileDescriptor.createPipe()
        val readEnd: ParcelFileDescriptor = ends[0]
        private val queue = ArrayBlockingQueue<ByteArray>(64) // ~1.3 s of audio
        @Volatile private var open = true

        init {
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(ends[1]).use { out ->
                        while (open) queue.poll(100, TimeUnit.MILLISECONDS)?.let(out::write)
                    }
                } catch (_: IOException) {
                    // The recognizer closed its end.
                }
            }, "voice-command-audio").start()
        }

        fun offer(frame: ShortArray, size: Int) {
            if (!open) return
            val bytes = ByteArray(size * 2)
            for (i in 0 until size) {
                val s = frame[i].toInt()
                bytes[2 * i] = s.toByte() // 16-bit little-endian
                bytes[2 * i + 1] = (s shr 8).toByte()
            }
            queue.offer(bytes) // when full, drop audio rather than stall the microphone
        }

        fun close() {
            open = false
            runCatching { readEnd.close() }
        }
    }

    companion object {
        private const val TAG = "HandsFreeGPT"
        private const val MAX_RESTARTS = 30

        fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

        /** Whether the phone's language is installed for offline recognition: true, false, or null if unknown. */
        fun checkLanguage(context: Context, result: (Boolean?) -> Unit) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.checkRecognitionSupport(
                recognizeIntent(), context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val language = Locale.getDefault().language
                        result(support.installedOnDeviceLanguages.any { Locale.forLanguageTag(it).language == language })
                        recognizer.destroy()
                    }

                    override fun onError(error: Int) {
                        result(null)
                        recognizer.destroy()
                    }
                },
            )
        }

        /** Asks the system to download the phone's language for offline recognition (it shows its own UI). */
        fun downloadLanguage(context: Context) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.triggerModelDownload(recognizeIntent())
            Handler(Looper.getMainLooper()).postDelayed({ recognizer.destroy() }, 15_000)
        }
    }
}
