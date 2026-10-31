package com.handsfreegpt

import com.handsfreegpt.SpeechTracker.End
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Feeds synthetic loudness traces (20 ms frames, ±1.5 dB jitter) through the tracker. */
class SpeechTrackerTest {

    private val random = Random(42)

    private fun tracker(maxMs: Int = 90_000) =
        SpeechTracker(silenceMs = 1600, sensitivityDb = 10, noSpeechTimeoutMs = 12_000, maxListenMs = maxMs)

    private fun simulate(tracker: SpeechTracker, level: (Long) -> Float): Pair<End?, Long> {
        var t = 0L
        while (t <= 400_000) {
            val end = tracker.feed(level(t) + (random.nextGaussian() * 1.5).toFloat(), 20, t)
            if (end != null) return end to t
            t += 20
        }
        return null to t
    }

    /** Words: 450 ms of speech then a 150 ms gap, between [from] and [to]. */
    private fun talking(t: Long, from: Long, to: Long, speechDb: Float, noiseDb: Float) =
        if (t in from until to && (t - from) % 600 < 450) speechDb else noiseDb

    @Test
    fun quietRoom_stopsAfterSilence() {
        // Last word ends at ~3.85 s, so it should stop ~1.6 s later.
        val (end, at) = simulate(tracker()) { t -> talking(t, 1000, 4000, -30f, -62f) }
        assertEquals(End.SPEECH_ENDED, end)
        assertTrue("ended at $at", at in 5300..5600)
    }

    @Test
    fun roadNoise_isBackgroundNotSpeech() {
        // Loud steady cabin noise; speech only 14 dB above it. Last word ends at ~3.65 s.
        val (end, at) = simulate(tracker()) { t -> talking(t, 800, 3800, -24f, -38f) }
        assertEquals(End.SPEECH_ENDED, end)
        assertTrue("ended at $at", at in 5100..5500)
    }

    @Test
    fun talkingBeforeTheNoiseFloorIsKnown_isStillHeard() {
        // Already talking when listening starts; the floor is learned in the first gap.
        val (end, at) = simulate(tracker()) { t -> talking(t, 0, 3000, -30f, -62f) }
        assertEquals(End.SPEECH_ENDED, end)
        assertTrue("ended at $at", at in 4300..4650)
    }

    @Test
    fun pauseShorterThanSilenceSetting_doesNotStop() {
        // 1.2 s pause mid-thought, then more talking until 5 s.
        val (end, at) = simulate(tracker()) { t -> if (t in 1000 until 2500 || t in 3700 until 5000) -30f else -62f }
        assertEquals(End.SPEECH_ENDED, end)
        assertTrue("ended at $at", at in 6500..6700)
    }

    @Test
    fun backgroundGetsLouderAfterYouStop_stillStops() {
        // Noise jumps 8 dB right after you finish (e.g. window opened).
        val (end, at) = simulate(tracker()) { t -> if (t < 4000) talking(t, 1000, 4000, -30f, -62f) else -54f }
        assertEquals(End.SPEECH_ENDED, end)
        assertTrue("ended at $at", at < 8000)
    }

    @Test
    fun silence_givesUpAtNoSpeechTimeout() {
        val (end, at) = simulate(tracker()) { -45f }
        assertEquals(End.NO_SPEECH, end)
        assertEquals(12_000L, at)
    }

    @Test
    fun nonStopTalking_hitsMaxDuration() {
        val (end, at) = simulate(tracker(maxMs = 20_000)) { t -> talking(t, 500, Long.MAX_VALUE, -28f, -60f) }
        assertEquals(End.MAX_DURATION, end)
        assertEquals(20_000L, at)
    }
}
