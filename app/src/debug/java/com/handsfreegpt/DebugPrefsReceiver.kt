package com.handsfreegpt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: lets scripts/e2e.sh switch settings on and off, e.g.
 *
 *   adb shell am broadcast -n com.handsfreegpt/.DebugPrefsReceiver --ez read_aloud true --ez continuous true
 *
 * Every extra is stored as a boolean setting under its own key (see [Prefs]).
 */
class DebugPrefsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val edit = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        for (key in extras.keySet()) edit.putBoolean(key, extras.getBoolean(key))
        edit.apply()
        Log.i("HandsFreeGPT", "test settings: " + extras.keySet().joinToString { "$it=${extras.getBoolean(it)}" })
    }
}
