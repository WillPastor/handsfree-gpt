package com.handsfreegpt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log

/**
 * Steering-wheel, headset and Android Auto controls, through one process-wide media session:
 * play/pause = talk ↔ finish, next = send, previous = cancel, stop = hands-free off.
 *
 * Android sends media buttons to the app that is playing audio, so while hands-free is on we play
 * silence and hold transient audio focus — which also pauses music, keeping it out of the
 * transcript. The status text is published as the track title, so car displays show it.
 */
object MediaControls {
    private const val TAG = "HandsFreeGPT"
    private const val ACTION_SEND = "send"
    private const val ACTION_CANCEL = "cancel"
    private const val SILENCE_RATE = 8000

    private var session: MediaSession? = null
    private var silence: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var engaged = false
    private var browsing = false
    private var status = ""

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** The shared session, created on first use (by hands-free starting, or Android Auto connecting). */
    fun session(context: Context): MediaSession =
        session ?: MediaSession(context.applicationContext, "HandsFreeGPT").also {
            it.setCallback(callback)
            session = it
            publish()
        }

    /** Hands-free turned on: take over the media buttons. */
    fun engage(context: Context) {
        engaged = true
        session(context).isActive = true
        startSilence(context.applicationContext)
        publish()
    }

    /** Hands-free turned off: give the media buttons (and the music) back. */
    fun release(context: Context) {
        if (!engaged) return
        engaged = false
        stopSilence(context.applicationContext)
        publish()
        session?.isActive = browsing
    }

    /** Android Auto showing us as a media source; keeps the session reachable while hands-free is off. */
    fun setBrowsing(context: Context, connected: Boolean) {
        browsing = connected
        session(context).isActive = engaged || browsing
    }

    fun setStatus(text: String) {
        status = text
        publish()
    }

    private fun publish() {
        val s = session ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (engaged) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .addCustomAction(ACTION_SEND, "Send", R.drawable.ic_send)
                .addCustomAction(ACTION_CANCEL, "Cancel", R.drawable.ic_cancel)
                .build(),
        )
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, if (engaged) status else "Press play to talk to ChatGPT")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "HandsFree GPT")
                .build(),
        )
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = toggle()
        override fun onPause() = toggle()
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = toggle()

        // "Hey Google, play HandsFree GPT": there's only one thing to play.
        override fun onPlayFromSearch(query: String?, extras: Bundle?) = toggle()

        override fun onSkipToNext() {
            Log.i(TAG, "media button: send")
            HandsFreeService.instance?.controller?.sendNow()
        }

        override fun onSkipToPrevious() {
            Log.i(TAG, "media button: cancel")
            HandsFreeService.instance?.controller?.cancelNow()
        }

        override fun onStop() {
            HandsFreeService.instance?.controller?.endSession()
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_SEND -> onSkipToNext()
                ACTION_CANCEL -> onSkipToPrevious()
            }
        }
    }

    private fun toggle() {
        Log.i(TAG, "media button: talk / finish")
        val controller = HandsFreeService.instance?.controller
        if (controller == null) {
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_ERROR, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .setErrorMessage("Turn on HandsFree GPT in Accessibility settings")
                    .build(),
            )
            return
        }
        controller.toggleListening()
    }

    private fun startSilence(context: Context) {
        if (silence != null) return
        val audio = context.getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { } // keep "playing" regardless: it's what routes the buttons to us
            .build()
            .also { audio.requestAudioFocus(it) }
        silence = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SILENCE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(SILENCE_RATE * 2)
                .build()
                .apply {
                    write(ShortArray(SILENCE_RATE), 0, SILENCE_RATE) // one second, looped forever
                    setLoopPoints(0, SILENCE_RATE, -1)
                    play()
                }
        }.onFailure { Log.w(TAG, "couldn't start silent playback", it) }.getOrNull()
    }

    private fun stopSilence(context: Context) {
        silence?.let {
            runCatching { it.stop() }
            it.release()
        }
        silence = null
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }
}
