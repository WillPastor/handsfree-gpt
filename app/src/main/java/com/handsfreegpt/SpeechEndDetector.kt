package com.handsfreegpt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.Random
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens to the microphone alongside ChatGPT's dictation and reports when you've finished talking.
 *
 * Android 10+ lets an enabled accessibility service capture audio at the same time as the app on
 * screen, so ChatGPT keeps transcribing while we only measure loudness (see [SpeechTracker]).
 * Each 20 ms frame can also be handed to [frameSink] (the voice-command recognizer), so nothing
 * opens a second capture. Audio is analysed in memory frame by frame and immediately discarded.
 */
class SpeechEndDetector(
    private val silenceMs: Int,
    private val sensitivityDb: Int,
    private val noSpeechTimeoutMs: Int,
    private val maxListenMs: Int,
    private val listener: Listener,
    /** Called on the audio thread with every captured frame (16 kHz mono PCM). */
    private val frameSink: ((ShortArray, Int) -> Unit)? = null,
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
        TestAudio.take()?.let { recording ->
            val end = listen(gen, replay(recording)) { false }
            if (end != null) post(gen) { listener.onEnd(end) }
            return
        }
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
            val end = listen(gen, { buf -> rec.read(buf, 0, buf.size) }) {
                rec.activeRecordingConfiguration?.isClientSilenced == true
            }
            if (end != null) post(gen) { listener.onEnd(end) }
        } catch (e: Exception) {
            post(gen) { listener.onUnavailable("mic error: ${e.message}") }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Returns why listening ended, or null if we were stopped / the mic became unavailable. */
    private fun listen(gen: Int, read: (ShortArray) -> Int, silenced: () -> Boolean): SpeechTracker.End? {
        val tracker = SpeechTracker(silenceMs, sensitivityDb, noSpeechTimeoutMs, maxListenMs)
        val buf = ShortArray(FRAME)
        val started = SystemClock.elapsedRealtime()
        var speakingShown = false
        var zeroMs = 0
        var frames = 0
        var silencedChecks = 0

        while (activeGen == gen) {
            val n = read(buf)
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
            if (++frames % 25 == 0) silencedChecks = if (silenced()) silencedChecks + 1 else 0
            if (zeroMs >= 1500 || silencedChecks >= 3) {
                post(gen) { listener.onUnavailable("Android isn't sharing the microphone") }
                return null
            }
            if (allZero) continue

            frameSink?.invoke(buf, n)
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

    /** Plays a recording at real-time pace, then faint noise, in place of the microphone. */
    private fun replay(pcm: ShortArray): (ShortArray) -> Int {
        var pos = 0
        val noise = Random(1)
        return { buf ->
            Thread.sleep(FRAME * 1000L / RATE)
            for (i in buf.indices) {
                // A real microphone is never digitally silent: add a little noise throughout.
                val sample = (if (pos < pcm.size) pcm[pos++].toInt() else 0) + (noise.nextGaussian() * 20).toInt()
                buf[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            buf.size
        }
    }

    private companion object {
        const val RATE = 16000
        const val FRAME = 320 // 20 ms
    }
}

/**
 * Test seam: debug builds can queue a recording (16 kHz mono PCM) that the next listening session
 * plays instead of the microphone, so end-to-end tests can "speak" (see src/debug). Empty otherwise.
 */
object TestAudio {
    @Volatile private var queued: ShortArray? = null

    fun queue(pcm: ShortArray) {
        queued = pcm
    }

    fun take(): ShortArray? = queued.also { queued = null }
}
