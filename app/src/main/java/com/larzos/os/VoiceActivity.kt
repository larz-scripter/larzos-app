package com.larzos.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.ArrayDeque
import java.util.Locale

/**
 * Voice chat with Claude: tap-to-talk or say "Doctor Larz" to wake it,
 * speak your prompt, it runs headlessly via ClaudeVoiceBridge (full tool
 * access - see that class's doc comment for the permission tradeoff) and
 * speaks the reply back.
 *
 * Chat-style transcript (bubbles, not a single scrolling text blob) plus a
 * live "HEARING" panel that shows every phrase the wake-word loop
 * transcribes, matched or not - added after on-device testing found no way
 * to tell whether the mic/recognizer was even working while "listening for
 * Doctor Larz" was on.
 */
class VoiceActivity : AppCompatActivity() {

    private enum class State { IDLE, WAKE_LISTENING, LISTENING, THINKING, SPEAKING }

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var transcriptBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var hearingPanel: View
    private lateinit var hearingLog: TextView
    private lateinit var hearingScroll: ScrollView
    private lateinit var micButton: Button
    private lateinit var wakeSwitch: Switch
    private lateinit var resetButton: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var commandRecognizer: SpeechRecognizer? = null
    private var wakeWord: WakeWordDetector? = null
    private var state = State.IDLE
    private val handler = Handler(Looper.getMainLooper())
    private val heardLines = ArrayDeque<String>()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onMicTapped() else toast("Voice needs microphone access.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice)
        statusDot = findViewById(R.id.voice_status_dot)
        statusText = findViewById(R.id.voice_status)
        transcriptBox = findViewById(R.id.voice_transcript_box)
        scroll = findViewById(R.id.voice_scroll)
        hearingPanel = findViewById(R.id.voice_hearing_panel)
        hearingLog = findViewById(R.id.voice_hearing_log)
        hearingScroll = findViewById(R.id.voice_hearing_scroll)
        micButton = findViewById(R.id.voice_mic)
        wakeSwitch = findViewById(R.id.voice_wake_switch)
        resetButton = findViewById(R.id.voice_reset)

        tts = TextToSpeech(this) { code -> ttsReady = (code == TextToSpeech.SUCCESS) }
        wakeWord = SpeechRecognizerWakeWordDetector(this)

        micButton.setOnClickListener { onMicTapped() }
        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            hearingPanel.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) startWakeListening() else stopWakeListening()
        }
        resetButton.setOnClickListener {
            (application as LarzApp).env.resetVoiceSession()
            transcriptBox.removeAllViews()
            toast("Conversation reset - next turn starts fresh.")
        }

        setState(State.IDLE)
    }

    override fun onDestroy() {
        wakeWord?.stop()
        commandRecognizer?.let { runCatching { it.destroy() } }
        tts?.let { runCatching { it.stop(); it.shutdown() } }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- mic / wake word -----------------------------------------------

    private fun onMicTapped() {
        if (hasMicPermission()) {
            if (state == State.LISTENING) return
            wakeWord?.stop()
            wakeSwitch.isChecked = false
            startCommandListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startWakeListening() {
        if (!hasMicPermission()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            wakeSwitch.isChecked = false
            return
        }
        setState(State.WAKE_LISTENING)
        wakeWord?.start(
            onWake = { handler.post { startCommandListening() } },
            onHeard = { phrase -> handler.post { appendHeard(phrase) } }
        )
    }

    private fun stopWakeListening() {
        wakeWord?.stop()
        if (state == State.WAKE_LISTENING) setState(State.IDLE)
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---- one command turn -----------------------------------------------

    private fun startCommandListening() {
        setState(State.LISTENING)
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        commandRecognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                r.destroy()
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (said.isNullOrBlank()) {
                    backToIdleOrWake()
                } else {
                    appendTranscript("You", said, isUser = true)
                    askClaude(said)
                }
            }

            override fun onError(error: Int) {
                r.destroy()
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    toast("Didn't catch that (error $error).")
                }
                backToIdleOrWake()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        runCatching { r.startListening(intent) }.onFailure {
            toast("Speech recognizer unavailable.")
            backToIdleOrWake()
        }
    }

    private fun askClaude(prompt: String) {
        setState(State.THINKING)
        val env = (application as LarzApp).env
        Thread {
            val result = ClaudeVoiceBridge.ask(env, prompt)
            handler.post {
                appendTranscript("Doctor Larz", result.text, isUser = false)
                speak(result.text)
            }
        }.start()
    }

    private fun speak(text: String) {
        setState(State.SPEAKING)
        val spoken = stripForSpeech(text)
        if (!ttsReady || spoken.isBlank()) {
            backToIdleOrWake()
            return
        }
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { handler.post { backToIdleOrWake() } }
            @Deprecated("required override")
            override fun onError(utteranceId: String?) { handler.post { backToIdleOrWake() } }
        })
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "larz-voice-reply")
    }

    private fun backToIdleOrWake() {
        if (wakeSwitch.isChecked) startWakeListening() else setState(State.IDLE)
    }

    // ---- state / status ---------------------------------------------------

    private fun setState(s: State) {
        state = s
        val (color, label) = when (s) {
            State.IDLE -> "#3a4557" to "Tap the mic, or turn on “Doctor Larz” below"
            State.WAKE_LISTENING -> "#22D3EE" to "Listening for “Doctor Larz”…"
            State.LISTENING -> "#22D3EE" to "Listening…"
            State.THINKING -> "#F5A524" to "Thinking…"
            State.SPEAKING -> "#00C896" to "Speaking…"
        }
        statusText.text = label
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
        statusDot.background = dot
        micButton.isEnabled = s == State.IDLE || s == State.WAKE_LISTENING
        micButton.alpha = if (micButton.isEnabled) 1f else 0.5f
        micButton.text = when (s) {
            State.LISTENING -> "Listening…"
            State.THINKING -> "Thinking…"
            State.SPEAKING -> "Speaking…"
            else -> "🎤  Tap to talk"
        }
    }

    // ---- transcript / hearing log ------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun appendTranscript(who: String, text: String, isUser: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(4), 0, dp(4))
            layoutParams = lp
        }
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(if (isUser) "#04121A" else "#E8EDF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(if (isUser) "#22D3EE" else "#141c2e"))
            }
        }
        row.addView(bubble)
        transcriptBox.addView(row)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendHeard(phrase: String) {
        heardLines.addLast(phrase)
        while (heardLines.size > 12) heardLines.removeFirst()
        hearingLog.text = heardLines.joinToString("\n")
        hearingScroll.post { hearingScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Defense in depth, not the real fix: ClaudeVoiceBridge appends a system
     * prompt telling Claude this is a spoken interface, which is what
     * actually stops it answering in markdown tables/lists in the first
     * place (confirmed on-device: a "check the server" reply came back as a
     * full markdown table before that existed). This just cleans up
     * whatever formatting slips through anyway.
     */
    private fun stripForSpeech(s: String): String {
        val noTableRows = s.lineSequence().filterNot { line ->
            val t = line.trim()
            (t.startsWith("|") && t.endsWith("|")) || Regex("^[|\\-:\\s]+$").matches(t) && t.isNotEmpty()
        }.joinToString("\n")
        return noTableRows
            .replace(Regex("```[\\s\\S]*?```"), " code block omitted ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("\\*\\*([^*]*)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]*)\\*"), "$1")
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
            .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
            .replace(Regex("\\|"), " ")
            .replace(Regex("\\n{2,}"), ". ")
            .trim()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
