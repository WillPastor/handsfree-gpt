package com.handsfreegpt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: lets scripts/e2e.sh capture ChatGPT's screen, as the Capture button does, and read it back:
 *
 *   adb shell am broadcast -n com.handsfreegpt/.DebugCaptureReceiver
 *   adb shell run-as com.handsfreegpt cat files/last_capture.txt
 */
class DebugCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = HandsFreeService.instance?.controller ?: return
        controller.capture("test capture")
        Log.i("HandsFreeGPT", "test capture saved")
    }
}
