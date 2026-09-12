package com.larzos.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Voice chat with Claude: tap-to-talk or say "Doctor Larz" to wake it,
 * speak your prompt, it runs headlessly via ClaudeVoiceBridge (full tool
 * access - see that class's doc comment for the permission tradeoff) and
 * speaks the reply back. Everything here is best-effort scaffolding that
 * needs real on-device testing: SpeechRecognizer/TextToSpeech behaviour
 * varies a lot by device and installed voice-input app.
 */
class VoiceActivity : AppCompatActivity() {

    private enum class State { IDLE, WAKE_LISTENING, LISTENING, THINKING, SPEAKING }

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var micButton: Button
    private lateinit var wakeSwitch: Switch
    private lateinit var resetButton: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var commandRecognizer: SpeechRecognizer? = null
    private var wakeWord: WakeWordDetector? = null
    private var state = State.IDLE
    private val handler = Handler(Looper.getMainLooper())

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onMicTapped() else toast("Voice needs microphone access.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice)
        status = findViewById(R.id.voice_status)
        transcript = findViewById(R.id.voice_transcript)
        scroll = findViewById(R.id.voice_scroll)
        micButton = findViewById(R.id.voice_mic)
        wakeSwitch = findViewById(R.id.voice_wake_switch)
        resetButton = findViewById(R.id.voice_reset)

        tts = TextToSpeech(this) { code -> ttsReady = (code == TextToSpeech.SUCCESS) }
        wakeWord = SpeechRecognizerWakeWordDetector(this)

        micButton.setOnClickListener { onMicTapped() }
        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) startWakeListening() else stopWakeListening()
        }
        resetButton.setOnClickListener {
            (application as LarzApp).env.resetVoiceSession()
            transcript.text = ""
            toast("Conversation reset - next turn starts fresh.")
        }

        setState(State.IDLE)
    }

    override fun onPause() {
        super.onPause()
        // Wake-word listening is meant to keep running while this screen is
        // open but backgrounded briefly (e.g. you switch apps to check
        // something); stopping only on onDestroy avoids surprising drops.
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
        wakeWord?.start {
            handler.post { startCommandListening() }
        }
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
                    appendTranscript("You", said)
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
                appendTranscript("Claude", result.text)
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

    // ---- small helpers ----------------------------------------------------

    private fun setState(s: State) {
        state = s
        status.text = when (s) {
            State.IDLE -> "Tap the mic, or turn on \"Doctor Larz\" below."
            State.WAKE_LISTENING -> "Listening for “Doctor Larz”…"
            State.LISTENING -> "Listening…"
            State.THINKING -> "Thinking…"
            State.SPEAKING -> "Speaking…"
        }
        micButton.isEnabled = s == State.IDLE || s == State.WAKE_LISTENING
    }

    private fun appendTranscript(who: String, text: String) {
        transcript.append("$who: $text\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Best-effort cleanup so TTS doesn't read out raw markdown syntax. */
    private fun stripForSpeech(s: String): String = s
        .replace(Regex("```[\\s\\S]*?```"), " code block omitted ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("\\*\\*([^*]*)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]*)\\*"), "$1")
        .replace(Regex("(?m)^[-*]\\s+"), "")
        .replace(Regex("\\n{2,}"), ". ")
        .trim()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
