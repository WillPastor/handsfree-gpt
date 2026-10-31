package com.handsfreegpt

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * Lists HandsFree GPT as a media source in Android Auto, so its now-playing screen and the
 * steering-wheel buttons drive hands-free through [MediaControls]. One item: "Talk to ChatGPT".
 */
class HandsFreeMediaBrowser : MediaBrowserService() {

    override fun onCreate() {
        super.onCreate()
        sessionToken = MediaControls.session(this).sessionToken
    }

    override fun onDestroy() {
        MediaControls.setBrowsing(this, false)
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // System UI connects to offer "resume playback" controls; there's nothing to resume here.
        if (clientPackageName == "com.android.systemui") return null
        MediaControls.setBrowsing(this, true)
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != ROOT) {
            result.sendResult(mutableListOf())
            return
        }
        val talk = MediaDescription.Builder()
            .setMediaId(TALK)
            .setTitle(getString(R.string.media_talk_title))
            .setSubtitle(getString(R.string.media_talk_subtitle))
            .build()
        result.sendResult(mutableListOf(MediaBrowser.MediaItem(talk, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
    }

    private companion object {
        const val ROOT = "root"
        const val TALK = "talk"
    }
}
