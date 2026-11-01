package com.openai.chatgpt

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Test double for ChatGPT's composer (NOT the real app), driven by scripts/e2e.sh:
 * a composer row [+] [input] [mic] [voice/send/stop] and a dictation bar [X] [waveform] [■] [↑].
 * It holds the microphone while "dictating", like the real app, and logs every tap under the
 * "MockGPT" tag so the script can assert on what HandsFree GPT pressed.
 * A dictation takes 2 s to transcribe. Like the real app appears to (issue #1), the text box comes back,
 * its send arrow showing but disabled, before the transcript is in it.
 * Launch flags, remembered across launches:
 *   --ez odd true          labels the automation doesn't know (tests the position fallback)
 *   --ez ignore_send true  the send arrow does nothing, as if the message couldn't be sent
 *   --es transcript '…'    what the next dictations transcribe to (default "Hello from dictation. Send it.")
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var composer: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var trailing: ImageButton
    private var generating = false
    private var transcribing = false
    private var ignoreSend = false
    private var dictated = DEFAULT_TRANSCRIPT
    @Volatile private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("mock", MODE_PRIVATE)
        for (flag in listOf("odd", "ignore_send")) {
            if (intent.hasExtra(flag)) sp.edit().putBoolean(flag, intent.getBooleanExtra(flag, false)).apply()
        }
        intent.getStringExtra("transcript")?.let { sp.edit().putString("transcript", it).apply() }
        val odd = sp.getBoolean("odd", false)
        ignoreSend = sp.getBoolean("ignore_send", false)
        dictated = sp.getString("transcript", null) ?: DEFAULT_TRANSCRIPT
        transcript = TextView(this).apply { textSize = 14f; text = "Mock ChatGPT (odd labels=$odd)\n" }
        input = EditText(this).apply {
            hint = "Ask ChatGPT"
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = updateTrailing()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        trailing = button(android.R.drawable.ic_btn_speak_now, "Start a voice conversation") { onTrailing() }
        composer = row(
            button(android.R.drawable.ic_input_add, "Add photos and files") { Log.i(TAG, "TAP plus") },
            input,
            button(android.R.drawable.ic_btn_speak_now, if (odd) "Speak" else "Dictate") { startDictation() },
            trailing,
        )
        val waveform = View(this).apply { setBackgroundColor(0xFF555555.toInt()) }
        bar = row(
            button(android.R.drawable.ic_menu_close_clear_cancel, "Cancel") { endDictation("cancel") },
            waveform,
            button(android.R.drawable.ic_media_pause, if (odd) null else "Stop dictation") { endDictation("stop") },
            button(android.R.drawable.ic_menu_send, "Send") { endDictation("send") },
        ).apply { visibility = View.GONE }

        val bottom = FrameLayout(this).apply {
            addView(composer)
            addView(bar)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(ScrollView(context).apply { addView(transcript) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(-1, -2))
        })
        updateTrailing()
    }

    private fun button(icon: Int, desc: String?, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val grow = v is EditText || (v !is ImageButton)
            addView(v, LinearLayout.LayoutParams(if (grow) 0 else -2, if (grow) -1 else -2, if (grow) 1f else 0f))
        }
    }

    private fun updateTrailing() {
        trailing.contentDescription = when {
            generating -> "Stop generating"
            transcribing || input.text.isNotEmpty() -> "Send prompt"
            else -> "Start a voice conversation"
        }
        trailing.isEnabled = !transcribing
    }

    private fun onTrailing() {
        when {
            generating -> { Log.i(TAG, "TAP stop generating"); generating = false; updateTrailing() }
            input.text.isNotEmpty() && ignoreSend -> Log.i(TAG, "TAP send (ignored)")
            input.text.isNotEmpty() -> send(input.text.toString())
            else -> Log.e(TAG, "TAP VOICE MODE (the automation must never do this)")
        }
    }

    private fun startDictation() {
        Log.i(TAG, "TAP mic -> dictation started")
        composer.visibility = View.GONE
        bar.visibility = View.VISIBLE
        recording = true
        Thread(::record, "mock-mic").start()
    }

    private fun endDictation(how: String) {
        Log.i(TAG, "TAP $how -> dictation ended")
        recording = false
        bar.visibility = View.GONE
        composer.visibility = View.VISIBLE
        if (how == "cancel") return
        transcribing = true
        updateTrailing()
        handler.postDelayed({
            transcribing = false
            when (how) {
                // Like the real app, the transcript includes everything said, spoken commands too.
                "stop" -> {
                    input.append(dictated)
                    Log.i(TAG, "TRANSCRIPT READY")
                }
                "send" -> send("hello from dictation")
            }
            updateTrailing()
        }, TRANSCRIBE_MS)
    }

    private fun send(text: String) {
        Log.i(TAG, "SENT: $text")
        transcript.append("\nYou: $text\nGPT:")
        input.setText("")
        generating = true
        updateTrailing()
        var words = 0
        handler.post(object : Runnable {
            override fun run() {
                if (!generating) return
                transcript.append(" word${++words}")
                if (words < 30) handler.postDelayed(this, 100) else {
                    generating = false
                    updateTrailing()
                    Log.i(TAG, "REPLY DONE")
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, 16000)
        rec.startRecording()
        val buf = ShortArray(1600)
        var reads = 0
        while (recording) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0 && ++reads % 10 == 0) {
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val db = 20 * log10(maxOf(sqrt(sum / n) / 32768.0, 1e-7))
                val silenced = rec.activeRecordingConfiguration?.isClientSilenced
                Log.i(TAG, "mock mic: silenced=$silenced level=${"%.0f".format(db)} dB")
            }
        }
        rec.stop()
        rec.release()
    }

    private companion object {
        const val TAG = "MockGPT"
        const val TRANSCRIBE_MS = 2000L
        const val DEFAULT_TRANSCRIPT = "Hello from dictation. Send it."
    }
}
