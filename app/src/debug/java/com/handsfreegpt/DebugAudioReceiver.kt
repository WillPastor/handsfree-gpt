package com.handsfreegpt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: queues a recording from assets (16 kHz mono PCM) that the next dictation
 * "hears" instead of the microphone, so scripts/e2e.sh can test voice commands on an emulator:
 *
 *   adb shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm
 */
class DebugAudioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val asset = intent.getStringExtra("asset") ?: "send-it.pcm"
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val pcm = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        TestAudio.queue(pcm)
        Log.i("HandsFreeGPT", "test audio queued: $asset (${pcm.size / 16} ms)")
    }
}
