package com.handsfreegpt

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** Setup checklist, start button and settings. Plain platform views: no extra libraries. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var micStatus: TextView
    private lateinit var micButton: Button
    private lateinit var serviceStatus: TextView
    private lateinit var chatGptStatus: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var downloadButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (handleAction(intent)) return
        setContentView(buildUi())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    /** Launcher shortcuts, routines (Tasker, Modes…) and adb can start/stop without the UI. */
    private fun handleAction(intent: Intent?): Boolean {
        when (intent?.action) {
            // "Hey Google, play HandsFree GPT" (Android Auto / Assistant media search) starts it too.
            ACTION_START, MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> start()
            ACTION_STOP -> HandsFreeService.instance?.controller?.endSession()
            else -> return false
        }
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applyInsets(scroll)

        col += text("HandsFree GPT", 26f, bold = true)
        col += text(
            "Starts ChatGPT's dictation for you and finishes it when you stop talking, say \"send it\", " +
                "or press a steering-wheel button — so you can use the regular ChatGPT models hands-free " +
                "instead of voice mode.",
            15f,
        )

        col += header("Setup")
        micStatus = text("", 15f).also { col += it }
        micButton = button("Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }.also { col += it }
        serviceStatus = text("", 15f).also { col += it }
        col += row(
            button("Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            button("App info") {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            },
        )
        col += hint(
            "Installed the APK yourself? If the switch is greyed out (\"Restricted setting\"), open " +
                "App info → ⋮ menu → \"Allow restricted settings\", then turn the service on.",
        )
        chatGptStatus = text("", 15f).also { col += it }

        col += header("Go")
        col += row(
            button("▶ Start hands-free") { start() },
            button("■ Stop") {
                HandsFreeService.instance?.controller?.endSession()
                refreshStatus()
            },
        )
        col += hint(
            "Tip: add the \"Hands-free GPT\" Quick Settings tile for one-tap start. While it's on, a pill " +
                "floats over ChatGPT: tap it to talk or to stop early, hold it to turn hands-free off, drag to move it.",
        )

        col += header("Behaviour")
        col += switch("Auto-send when you stop talking", prefs.autoSend) { prefs.autoSend = it }
        col += switch(
            "Continuous conversation: send, wait for the reply, then listen again (experimental)",
            prefs.continuous,
        ) { prefs.continuous = it }
        col += switch("Read replies aloud", prefs.readAloud) { prefs.readAloud = it }
        col += hint(
            "Presses ChatGPT's own Read aloud button when a reply finishes, and waits for it before listening " +
                "again. Play/pause or a tap on the pill pauses and resumes it, next ⏭ skips the rest and starts " +
                "talking, and previous ⏮ stops it. If you pause it in ChatGPT, the app waits until you play it on.",
        )
        col += switch("Start listening whenever ChatGPT opens", prefs.autoListenOnOpen) { prefs.autoListenOnOpen = it }
        col += switch("Beep when listening starts and stops", prefs.beeps) { prefs.beeps = it }

        col += header("Voice commands")
        voiceStatus = text("", 15f).also { col += it }
        downloadButton = button("Download offline speech") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                VoiceCommandRecognizer.downloadLanguage(this)
                toast("Follow the system prompt, then come back")
            }
        }.also {
            it.visibility = View.GONE
            col += it
        }
        col += switch("Finish by saying a command", prefs.voiceCommands) { prefs.voiceCommands = it }
        col += words("Say to send", prefs.sendPhrases) { prefs.sendPhrases = it }
        col += words("Say to stop (keeps the text so you can edit it)", prefs.stopPhrases) { prefs.stopPhrases = it }
        col += words("Say to cancel", prefs.cancelPhrases) { prefs.cancelPhrases = it }
        col += switch("Remove the command from my message", prefs.stripCommand) { prefs.stripCommand = it }
        col += row(
            button("Test voice commands") { testVoiceCommands() },
            button("Reset phrases") {
                prefs.resetPhrases()
                recreate()
            },
        )
        col += hint(
            "Say the command as the very last thing, e.g. \"…what's the forecast for tomorrow, send it\". " +
                "In the middle of a sentence it's ignored. Recognised on the phone by Android's offline speech " +
                "recognition (Android 13+), even over road noise or music.",
        )

        col += header("Car & headset buttons")
        col += switch("Steering-wheel and headset buttons", prefs.carButtons) { prefs.carButtons = it }
        col += hint(
            "Play/Pause: talk ↔ finish · Next ⏭: send, or skip a reply being read · Previous ⏮: cancel · " +
                "Stop: hands-free off. " +
                "Your music pauses while hands-free is on, so the buttons reach this app.",
        )
        col += hint(
            "Android Auto: HandsFree GPT is listed among the media apps, with Talk / Send / Cancel on the car " +
                "screen. For an app not installed from Play, enable it once: Android Auto settings → tap Version " +
                "10 times → ⋮ → Developer settings → Unknown sources.",
        )

        col += header("Timing")
        col += switch("Also stop when I go quiet", prefs.stopOnSilence) { prefs.stopOnSilence = it }
        col += slider("Stop after this much silence", 600, 5000, 100, prefs.silenceMs, ::seconds) { prefs.silenceMs = it }
        col += slider("Speech must be louder than background by", 4, 24, 1, prefs.sensitivityDb, { "$it dB" }) {
            prefs.sensitivityDb = it
        }
        col += hint("Raise it if road noise keeps it listening; lower it if it cuts you off or never notices you talking.")
        col += slider("Cancel if nothing is said within", 0, 30000, 1000, prefs.noSpeechTimeoutMs, {
            if (it == 0) "never" else seconds(it)
        }) { prefs.noSpeechTimeoutMs = it }
        col += slider("Longest single dictation", 15000, 300000, 5000, prefs.maxListenMs, ::seconds) {
            prefs.maxListenMs = it
        }
        col += slider("If silence can't be detected, stop after", 5000, 60000, 1000, prefs.fallbackStopMs, ::seconds) {
            prefs.fallbackStopMs = it
        }

        col += header("Button matching (advanced)")
        col += hint(
            "Words looked for in ChatGPT's button labels, comma separated. Only change these if a capture " +
                "(below) shows ChatGPT uses different labels, e.g. in another language.",
        )
        col += words("Mic (start dictation)", prefs.micWords) { prefs.micWords = it }
        col += words("Stop dictation (■)", prefs.stopWords) { prefs.stopWords = it }
        col += words("Send (↑)", prefs.sendWords) { prefs.sendWords = it }
        col += words("Cancel dictation (X)", prefs.cancelWords) { prefs.cancelWords = it }
        col += words("Read aloud (under a reply)", prefs.readAloudWords) { prefs.readAloudWords = it }
        col += words("Pause reading (while ChatGPT speaks)", prefs.stopReadingWords) { prefs.stopReadingWords = it }
        col += words("Play reading (while it's paused)", prefs.resumeReadingWords) { prefs.resumeReadingWords = it }
        col += button("Reset to defaults") {
            prefs.resetButtonWords()
            recreate()
        }

        col += header("Diagnostics")
        col += hint(
            "When a button can't be found, a capture of ChatGPT's screen layout is saved automatically. " +
                "To take one yourself, tap Capture, then within 8 s get ChatGPT into the state you want " +
                "(for example, start a dictation). Long chat text is left out of captures.",
        )
        col += row(
            button("Capture in 8 s") { captureSoon() },
            button("View") { viewCapture() },
            button("Share") { shareCapture() },
        )
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        col += hint("Version $version")
        return scroll
    }

    private fun refreshStatus() {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (micOk) {
            "✅ Microphone allowed (only used to notice when you stop talking)"
        } else {
            "❌ Microphone not allowed — needed to notice when you stop talking"
        }
        micButton.visibility = if (micOk) View.GONE else View.VISIBLE
        serviceStatus.text = if (HandsFreeService.instance != null) {
            "✅ Accessibility service on"
        } else {
            "❌ Accessibility service off — turn on \"HandsFree GPT\" in Accessibility settings " +
                "(often under \"Downloaded apps\" or \"Installed apps\")"
        }
        val chatGpt = runCatching { packageManager.getPackageInfo(ChatGptUi.PKG, 0).versionName }.getOrNull()
        chatGptStatus.text = if (chatGpt != null) "✅ ChatGPT $chatGpt found" else "❌ ChatGPT app not found"
        refreshVoiceStatus()
    }

    private fun refreshVoiceStatus() {
        val language = Locale.getDefault().displayLanguage
        VoiceSupport.unavailableReason(this)?.let {
            voiceStatus.text = "❌ Voice commands unavailable: $it"
            downloadButton.visibility = View.GONE
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        voiceStatus.text = "⏳ Checking offline speech recognition…"
        VoiceCommandRecognizer.checkLanguage(this) { installed ->
            voiceStatus.text = when (installed) {
                true -> "✅ Offline speech recognition ready ($language)"
                false -> "⬇ Offline speech for $language isn't installed yet"
                null -> "✅ On-device speech recognition available. Use Test to check it."
            }
            downloadButton.visibility = if (installed == false) View.VISIBLE else View.GONE
        }
    }

    /** Listens for 15 s and shows what was heard and which command matched: proves it works on this phone. */
    private fun testVoiceCommands() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        VoiceSupport.unavailableReason(this)?.let { return toast("Voice commands unavailable: $it") }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val heard = text("Listening… say something that ends with \"${prefs.sendHint() ?: "send it"}\".", 15f)
        val result = text("", 17f, bold = true)
        val pad = dp(20)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(heard)
            addView(result)
        }
        val recognizer = VoiceCommandRecognizer(
            this, CommandMatcher(prefs.commandPhrases()),
            object : CommandRecognizer.Listener {
                override fun onHeard(text: String) {
                    heard.text = "Heard: \"$text\""
                }

                override fun onCommand(match: CommandMatcher.Match) {
                    val what = when (match.command) {
                        Command.SEND -> "send"
                        Command.STOP -> "stop and keep the text"
                        Command.CANCEL -> "cancel"
                    }
                    result.text = "✅ \"${match.phrase}\" → $what"
                }

                override fun onUnavailable(why: String) {
                    result.text = "❌ $why"
                }
            },
        )
        val detector = SpeechEndDetector(
            silenceMs = 0, sensitivityDb = prefs.sensitivityDb, noSpeechTimeoutMs = 0, maxListenMs = 15_000,
            listener = object : SpeechEndDetector.Listener {
                override fun onSpeaking(speaking: Boolean) = Unit

                override fun onEnd(reason: SpeechTracker.End) {
                    recognizer.stop()
                    if (result.text.isEmpty()) result.text = "No command heard (stopped after 15 s)"
                }

                override fun onUnavailable(why: String) {
                    recognizer.stop()
                    result.text = "❌ Microphone: $why"
                }
            },
            frameSink = recognizer::feed,
        )
        AlertDialog.Builder(this)
            .setTitle("Test voice commands")
            .setView(body)
            .setPositiveButton("Done", null)
            .setOnDismissListener {
                detector.stop()
                recognizer.stop()
            }
            .show()
        detector.start()
        recognizer.start()
    }

    private fun start() {
        val service = HandsFreeService.instance
        if (service == null) {
            toast("Turn on HandsFree GPT in Accessibility settings first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (ChatGptUi.launchIntent(this) == null) return toast("ChatGPT app not found")
        service.controller.startSession(listenNow = true) // also brings ChatGPT to the front
    }

    private fun captureSoon() {
        val service = HandsFreeService.instance ?: return toast("Turn on the accessibility service first")
        service.captureIn(8000)
        toast("Capturing in 8 s…")
        ChatGptUi.launchIntent(this)?.let(::startActivity)
    }

    private fun readCapture(): String? =
        File(filesDir, HandsFreeService.CAPTURE_FILE).takeIf { it.exists() }?.readText()

    private fun viewCapture() {
        val capture = readCapture() ?: return toast("No capture yet")
        val pad = dp(12)
        val body = TextView(this).apply {
            text = capture
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Last capture")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("HandsFree GPT capture", capture))
                toast("Copied")
            }
            .show()
    }

    private fun shareCapture() {
        val capture = readCapture() ?: return toast("No capture yet")
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "HandsFree GPT capture")
            .putExtra(Intent.EXTRA_TEXT, capture.take(150_000))
        startActivity(Intent.createChooser(send, "Share capture"))
    }

    // --- tiny view helpers ---------------------------------------------------------------

    private operator fun LinearLayout.plusAssign(v: View) = addView(v)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun seconds(ms: Int) = if (ms % 1000 == 0) "${ms / 1000} s" else "%.1f s".format(ms / 1000f)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun applyInsets(v: View) {
        val pad = dp(16)
        v.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        v.setPadding(pad, pad, pad, pad)
    }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun hint(s: String) = text(s, 13f).apply { alpha = 0.7f }

    private fun header(s: String) = text(s, 19f, bold = true).apply { setPadding(0, dp(20), 0, dp(4)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    @Suppress("DEPRECATION") // android.widget.Switch is fine for a plain platform UI
    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        isChecked = checked
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun slider(
        title: String, min: Int, max: Int, step: Int, value: Int,
        format: (Int) -> String, onChange: (Int) -> Unit,
    ): View {
        val label = text("", 15f)
        fun render(v: Int) {
            label.text = getString(R.string.label_value, title, format(v))
        }
        render(value)
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (value.coerceIn(min, max) - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = min + progress * step
                    render(v)
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(label)
            addView(bar)
        }
    }

    companion object {
        const val ACTION_START = "com.handsfreegpt.action.START"
        const val ACTION_STOP = "com.handsfreegpt.action.STOP"
    }

    private fun words(title: String, value: String, save: (String) -> Unit): View {
        val edit = EditText(this).apply {
            setText(value)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = save(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 14f))
            addView(edit)
        }
    }
}
