package com.handsfreegpt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens to the microphone alongside ChatGPT's dictation and reports when you've finished talking.
 *
 * Android 10+ lets an enabled accessibility service capture audio at the same time as the app on
 * screen, so ChatGPT keeps transcribing while we only measure loudness (see [SpeechTracker]).
 * Audio is analysed in memory frame by frame and immediately discarded.
 */
class SpeechEndDetector(
    private val silenceMs: Int,
    private val sensitivityDb: Int,
    private val noSpeechTimeoutMs: Int,
    private val maxListenMs: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onSpeaking(speaking: Boolean)
        fun onEnd(reason: SpeechTracker.End)
        /** The mic can't be shared (permission missing, mic busy, or Android feeds us silence). */
        fun onUnavailable(why: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var counter = 0
    @Volatile private var activeGen = 0

    fun start() {
        val gen = ++counter
        activeGen = gen
        Thread({ run(gen) }, "speech-end-detector").start()
    }

    fun stop() {
        activeGen = 0
    }

    private fun post(gen: Int, block: () -> Unit) {
        main.post { if (activeGen == gen) block() }
    }

    @SuppressLint("MissingPermission") // the controller checks RECORD_AUDIO before starting us
    private fun run(gen: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            // VOICE_RECOGNITION is the source Android lets accessibility services share most freely.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 2 * 8),
            )
        } catch (e: Exception) {
            post(gen) { listener.onUnavailable("mic error: ${e.message}") }
            return
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                post(gen) { listener.onUnavailable("mic unavailable") }
                return
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                post(gen) { listener.onUnavailable("mic busy") }
                return
            }
            val end = listen(rec, gen)
            if (end != null) post(gen) { listener.onEnd(end) }
        } catch (e: Exception) {
            post(gen) { listener.onUnavailable("mic error: ${e.message}") }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Returns why listening ended, or null if we were stopped / the mic became unavailable. */
    private fun listen(rec: AudioRecord, gen: Int): SpeechTracker.End? {
        val tracker = SpeechTracker(silenceMs, sensitivityDb, noSpeechTimeoutMs, maxListenMs)
        val buf = ShortArray(FRAME)
        val started = SystemClock.elapsedRealtime()
        var speakingShown = false
        var zeroMs = 0
        var frames = 0
        var silencedChecks = 0

        while (activeGen == gen) {
            val n = rec.read(buf, 0, FRAME)
            if (n < 0) {
                post(gen) { listener.onUnavailable("mic read error $n") }
                return null
            }
            if (n == 0) continue
            val frameMs = n * 1000 / RATE

            var sum = 0.0
            var allZero = true
            for (i in 0 until n) {
                val s = buf[i].toInt()
                if (s != 0) allZero = false
                sum += (s * s).toDouble()
            }

            // A client Android won't share the mic with gets pure digital silence.
            zeroMs = if (allZero) zeroMs + frameMs else 0
            if (++frames % 25 == 0) {
                val silenced = rec.activeRecordingConfiguration?.isClientSilenced == true
                silencedChecks = if (silenced) silencedChecks + 1 else 0
            }
            if (zeroMs >= 1500 || silencedChecks >= 3) {
                post(gen) { listener.onUnavailable("Android isn't sharing the microphone") }
                return null
            }
            if (allZero) continue

            val db = (20 * log10(max(sqrt(sum / n) / 32768.0, 1e-7))).toFloat()
            val end = tracker.feed(db, frameMs, SystemClock.elapsedRealtime() - started)
            if (tracker.speaking != speakingShown) {
                speakingShown = tracker.speaking
                val speaking = speakingShown
                post(gen) { listener.onSpeaking(speaking) }
            }
            if (end != null) return end
        }
        return null
    }

    private companion object {
        const val RATE = 16000
        const val FRAME = 320 // 20 ms
    }
}
