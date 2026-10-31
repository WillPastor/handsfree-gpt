package com.handsfreegpt

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap opens ChatGPT and starts listening; tap again to turn hands-free off. */
class HandsFreeTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val service = HandsFreeService.instance
        if (service == null) {
            // Not set up yet: show the setup screen.
            launch(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (service.controller.active) {
            service.controller.endSession()
        } else {
            service.controller.startSession(listenNow = true)
            ChatGptUi.launchIntent(this)?.let(::launch)
        }
        refresh()
    }

    /** Opens an activity and collapses the shade so ChatGPT is actually visible. */
    @SuppressLint("StartActivityAndCollapseDeprecated") // the PendingIntent overload only exists on API 34+
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val service = HandsFreeService.instance
        val on = service?.controller?.active == true
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            service == null -> "Set up"
            on -> "On"
            else -> "Off"
        }
        tile.updateTile()
    }
}
