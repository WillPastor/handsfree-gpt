package com.handsfreegpt

import kotlin.math.min

/**
 * Decides, one 20 ms frame at a time, whether you're talking and when you've finished.
 * Pure logic (no Android APIs) so it can be unit-tested with synthetic audio.
 *
 * Speech = a frame louder than the background noise floor by [sensitivityDb]. The floor adapts:
 * it falls quickly in pauses and rises slowly, so steady road noise is learned as background
 * and even speech that starts before the floor is known is picked up at the first pause.
 */
class SpeechTracker(
    /** Silence after speech that ends listening; 0 = never (e.g. when voice commands do the ending). */
    private val silenceMs: Int,
    private val sensitivityDb: Int,
    private val noSpeechTimeoutMs: Int,
    private val maxListenMs: Int,
) {
    enum class End { SPEECH_ENDED, NO_SPEECH, MAX_DURATION }

    /** True while you're talking (once speech is confirmed). */
    var speaking = false
        private set

    var noiseDb = Float.NaN
        private set

    private var warmupMin = Float.MAX_VALUE
    private var burstMs = 0
    private var speechStarted = false
    private var lastVoiceAt = -1_000_000L

    /** [db] is the frame loudness in dBFS, [elapsedMs] the time since listening started. */
    fun feed(db: Float, frameMs: Int, elapsedMs: Long): End? {
        if (elapsedMs < WARMUP_MS) {
            warmupMin = min(warmupMin, db)
            return null
        }
        if (noiseDb.isNaN()) noiseDb = if (warmupMin == Float.MAX_VALUE) -70f else warmupMin
        noiseDb = noiseDb.coerceIn(-80f, -20f)

        val recentlyVoiced = elapsedMs - lastVoiceAt < HANGOVER_MS
        val threshold = noiseDb + sensitivityDb - (if (recentlyVoiced) 3 else 0)
        if (db > threshold && db > ABS_MIN_DB) {
            if (!recentlyVoiced) burstMs = 0
            lastVoiceAt = elapsedMs
            burstMs += frameMs
            if (burstMs >= MIN_SPEECH_MS) speechStarted = true
            noiseDb += (db - noiseDb) * 0.002f // creep up very slowly in case the noise itself got louder
        } else {
            noiseDb += (db - noiseDb) * (if (db < noiseDb) 0.15f else 0.03f) // falls fast, rises slowly
        }
        speaking = speechStarted && elapsedMs - lastVoiceAt < HANGOVER_MS

        return when {
            silenceMs > 0 && speechStarted && elapsedMs - lastVoiceAt >= silenceMs -> End.SPEECH_ENDED
            !speechStarted && noSpeechTimeoutMs > 0 && elapsedMs >= noSpeechTimeoutMs -> End.NO_SPEECH
            elapsedMs >= maxListenMs -> End.MAX_DURATION
            else -> null
        }
    }

    private companion object {
        const val WARMUP_MS = 300
        const val MIN_SPEECH_MS = 200
        const val HANGOVER_MS = 300
        const val ABS_MIN_DB = -58f
    }
}
