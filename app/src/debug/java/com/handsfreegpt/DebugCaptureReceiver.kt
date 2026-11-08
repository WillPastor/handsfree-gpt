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
 *
 * Add `--ez labels true` to see the names of things you can tap or long-press, such as menu items, chips and
 * chats' titles, instead of only their length when they're 40 characters or fewer. A message you sent can be
 * tapped too, so a short one would show: use it on menus and lists, not in an open chat.
 */
class DebugCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = HandsFreeService.instance?.controller ?: return
        controller.capture("test capture", labels = intent.getBooleanExtra("labels", false))
        Log.i("HandsFreeGPT", "test capture saved")
    }
}
