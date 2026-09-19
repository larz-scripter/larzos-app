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
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var hearingHeader: View
    private lateinit var hearingChevron: TextView
    private lateinit var hearingLog: TextView
    private lateinit var hearingScroll: ScrollView
    private lateinit var micButton: Button
    private lateinit var wakeButton: Button
    private lateinit var resetButton: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var commandRecognizer: SpeechRecognizer? = null
    private var wakeWord: WakeWordDetector? = null
    private var interruptListener: WakeWordDetector? = null
    private var currentTurn: ClaudeVoiceBridge.VoiceTurn? = null
    private var state = State.IDLE
    private var wakeEnabled = false
    private var hearingExpanded = false
    private val handler = Handler(Looper.getMainLooper())
    private val heardLines = ArrayDeque<String>()
    private var lastLoggedHeard: String? = null
    private var utteranceCounter = 0
    private var finalUtteranceId: String? = null
    private var pendingSpeechCount = 0
    private var lastNarrationText: String? = null
    // One-off acknowledgments ("Yes boss?") register a completion callback
    // here instead of going through the narration queue/finalUtteranceId -
    // see speakThen().
    private val utteranceCallbacks = mutableMapOf<String, () -> Unit>()

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
        hearingHeader = findViewById(R.id.voice_hearing_header)
        hearingChevron = findViewById(R.id.voice_hearing_chevron)
        hearingLog = findViewById(R.id.voice_hearing_log)
        hearingScroll = findViewById(R.id.voice_hearing_scroll)
        micButton = findViewById(R.id.voice_mic)
        wakeButton = findViewById(R.id.voice_wake_button)
        resetButton = findViewById(R.id.voice_reset)

        tts = TextToSpeech(this) { code ->
            ttsReady = (code == TextToSpeech.SUCCESS)
            if (ttsReady) {
                // Set once, not per utterance: narration snippets and the
                // final answer are queued in order (QUEUE_ADD, see
                // queueSpeech) so they play one after another without
                // cutting each other off - only the LAST queued one should
                // trigger the return to idle/wake-listening.
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post { onUtteranceFinished(utteranceId) } }
                    @Deprecated("required override")
                    override fun onError(utteranceId: String?) { handler.post { onUtteranceFinished(utteranceId) } }
                })
            }
        }
        wakeWord = SpeechRecognizerWakeWordDetector(this)

        micButton.setOnClickListener { onMicTapped() }
        wakeButton.setOnClickListener { toggleWakeWord() }
        hearingHeader.setOnClickListener { toggleHearingPanel() }
        resetButton.setOnClickListener {
            (application as LarzApp).env.resetVoiceSession()
            transcriptBox.removeAllViews()
            toast("Conversation reset - next turn starts fresh.")
        }

        setState(State.IDLE)
    }

    override fun onDestroy() {
        // ClaudeVoiceBridge runs the actual claude process on its own
        // background thread, independent of this Activity - closing this
        // screen mid-task doesn't (and shouldn't) kill a big task partway
        // through. Its callbacks still fire afterwards though (guarded by
        // Activity.isDestroyed - see askClaude) so they don't touch views
        // that no longer exist.
        wakeWord?.stop()
        commandRecognizer?.let { runCatching { it.destroy() } }
        tts?.let { runCatching { it.stop(); it.shutdown() } }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- mic / wake word -----------------------------------------------

    private fun onMicTapped() {
        if (!hasMicPermission()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        when (state) {
            State.LISTENING -> return  // already listening for a command
            State.THINKING, State.SPEAKING -> {
                // Manual equivalent of saying "stop" - tapping mid-task or
                // mid-answer used to just start a second recognizer session
                // on top of the still-running one (a real latent bug: two
                // things fighting for the mic/TTS at once). Interrupt first,
                // then go straight into listening rather than routing
                // through backToIdleOrWake() (which could start the
                // wake-word recognizer only to immediately replace it here).
                resetActiveTurn()
                startCommandListening()
            }
            else -> {
                if (wakeEnabled) { wakeEnabled = false; wakeWord?.stop(); updateWakeButton() }
                startCommandListening()
            }
        }
    }

    private fun toggleWakeWord() {
        wakeEnabled = !wakeEnabled
        updateWakeButton()
        // The hearing panel only exists to debug the wake-word loop - it has
        // nothing to show and no reason to take space once wake mode is off.
        hearingPanel.visibility = if (wakeEnabled) View.VISIBLE else View.GONE
        if (wakeEnabled) startWakeListening() else stopWakeListening()
    }

    private fun updateWakeButton() {
        wakeButton.text = if (wakeEnabled)
            "Listening for “Doctor Larz” — tap to stop"
        else
            "Enable wake word: “Doctor Larz”"
    }

    private fun toggleHearingPanel() {
        hearingExpanded = !hearingExpanded
        hearingScroll.visibility = if (hearingExpanded) View.VISIBLE else View.GONE
        hearingChevron.text = if (hearingExpanded) "▾" else "▸"
    }

    private fun startWakeListening() {
        if (!hasMicPermission()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            wakeEnabled = false
            updateWakeButton()
            hearingPanel.visibility = View.GONE
            return
        }
        setState(State.WAKE_LISTENING)
        val env = (application as LarzApp).env
        wakeWord?.start(
            onWake = {
                env.appendWakeLog("MATCHED -> starting command turn")
                // A spoken acknowledgment that it actually heard the wake
                // word - requested explicitly so it's clear when it's your
                // turn to talk, instead of guessing whether it caught it.
                // Waits for "Yes boss?" to actually finish before opening
                // the mic, so the recognizer doesn't pick up its own voice.
                handler.post { speakThen("Yes boss?") { startCommandListening() } }
            },
            onHeard = { phrase ->
                // Partial results repeat the same growing prefix many times
                // a second while actively speaking - only log on an actual
                // change, or every partial phrase would flood wake.log.
                if (phrase != lastLoggedHeard) {
                    lastLoggedHeard = phrase
                    env.appendWakeLog(phrase)
                }
                handler.post { appendHeard(phrase) }
            }
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
        finalUtteranceId = null
        pendingSpeechCount = 0
        lastNarrationText = null
        startInterruptListening()
        val env = (application as LarzApp).env
        // ClaudeVoiceBridge.ask() streams: onNarration fires zero or more
        // times while the turn is still running (a tool starting, the
        // model's own interim commentary, a "still working" heartbeat if it
        // goes quiet) so a long/multi-step task doesn't read as broken from
        // total silence - each gets spoken and shown in the status line as
        // it arrives, in order, without interrupting each other. onDone
        // fires exactly once with the final answer, which also gets its own
        // permanent transcript bubble. Both callbacks fire on a background
        // thread; hop to the main thread before touching any views.
        currentTurn = ClaudeVoiceBridge.ask(env, prompt,
            onNarration = { text ->
                handler.post {
                    if (isDestroyed) return@post
                    // Visual feedback is never throttled - only the spoken
                    // queue is, so the status line always shows the latest
                    // even if TTS is a step or two behind.
                    if (state == State.THINKING) statusText.text = text
                    if (text != lastNarrationText) {
                        lastNarrationText = text
                        queueSpeech(text, isFinal = false)
                    }
                }
            },
            onDone = { result ->
                handler.post {
                    if (isDestroyed) return@post
                    currentTurn = null
                    appendTranscript("Doctor Larz", result.text, isUser = false)
                    if (result.needsSetup) appendSignInButton()
                    setState(State.SPEAKING)
                    if (result.alreadySpoken) {
                        // The last streamed narration chunk WAS the final
                        // answer, word for word - it already got spoken once
                        // as it streamed in (see ClaudeVoiceBridge). Saying
                        // it again back to back was a real bug found
                        // on-device.
                        backToIdleOrWake()
                    } else {
                        queueSpeech(result.text, isFinal = true)
                    }
                }
            }
        )
    }

    /** Queues one utterance after whatever's already speaking (QUEUE_ADD) -
     *  narration and the final answer play in the order they were produced,
     *  never cutting each other off. Only the utterance marked [isFinal]
     *  triggers the return to idle/wake-listening once it finishes.
     *
     *  A task with several quick tool calls in a row could otherwise queue
     *  up "Running a command." many times over and end up narrating minutes
     *  behind actual progress by the time TTS works through the backlog -
     *  non-final narration is dropped (never the final answer) once two are
     *  already waiting to be spoken, so the spoken queue stays roughly
     *  caught up with what's actually happening instead of trailing it. */
    private fun queueSpeech(text: String, isFinal: Boolean) {
        val spoken = stripForSpeech(text)
        if (!ttsReady || spoken.isBlank()) {
            if (isFinal) backToIdleOrWake()
            return
        }
        if (!isFinal && pendingSpeechCount >= 2) return
        val id = "larz-voice-" + (utteranceCounter++)
        if (isFinal) finalUtteranceId = id
        pendingSpeechCount++
        tts?.speak(spoken, TextToSpeech.QUEUE_ADD, null, id)
    }

    /** One TTS utterance finished (successfully or not) - either it was a
     *  one-off acknowledgment with its own registered callback (speakThen),
     *  or it's part of the normal narration/answer queue, in which case
     *  only the one marked final should trigger returning to idle/wake. */
    private fun onUtteranceFinished(utteranceId: String?) {
        if (pendingSpeechCount > 0) pendingSpeechCount--
        val callback = utteranceId?.let { utteranceCallbacks.remove(it) }
        if (callback != null) {
            callback()
        } else if (utteranceId != null && utteranceId == finalUtteranceId) {
            backToIdleOrWake()
        }
    }

    /** Speaks one short acknowledgment immediately (flushing anything else
     *  queued - nothing meaningful should be mid-speech at the point this
     *  is used) and runs [onComplete] once it's actually finished, so the
     *  mic doesn't start listening while the ack is still being spoken. */
    private fun speakThen(text: String, onComplete: () -> Unit) {
        if (!ttsReady) { onComplete(); return }
        val id = "larz-voice-ack-" + (utteranceCounter++)
        utteranceCallbacks[id] = onComplete
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun backToIdleOrWake() {
        stopInterruptListening()
        if (wakeEnabled) startWakeListening() else setState(State.IDLE)
    }

    // ---- interrupting a running/speaking turn ("stop talking") -----------

    /** Active only during THINKING/SPEAKING - listens for "stop"/"cancel"/
     *  "never mind" so a big task or a long answer can be interrupted by
     *  voice instead of needing to wait it out or tap the screen. */
    private fun startInterruptListening() {
        if (!hasMicPermission()) return
        interruptListener?.stop()
        interruptListener = SpeechRecognizerWakeWordDetector(this) { SpeechRecognizerWakeWordDetector.matchesStopPhrase(it) }
        interruptListener?.start(onWake = { handler.post { onInterrupted() } })
    }

    private fun stopInterruptListening() {
        interruptListener?.stop()
        interruptListener = null
    }

    /** Stops any speech in progress and cancels the running turn, if any -
     *  shared by the voice interrupt ("stop") and tapping the mic mid-task,
     *  which need the same cleanup but different next steps. */
    private fun resetActiveTurn() {
        tts?.stop()
        currentTurn?.cancel()
        currentTurn = null
        pendingSpeechCount = 0
        finalUtteranceId = null
        utteranceCallbacks.clear()
        stopInterruptListening()
    }

    /** Cuts off whatever's running/speaking and goes straight back to
     *  idle/wake-listening, ready for a new command - no "cancelled"
     *  announcement, since the user just said "stop" and already knows. */
    private fun onInterrupted() {
        if (state != State.THINKING && state != State.SPEAKING) return
        resetActiveTurn()
        backToIdleOrWake()
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

    /** One-tap Claude sign-in, shown under the reply when Claude isn't signed in. */
    private fun appendSignInButton() {
        val btn = Button(this).apply {
            text = "Sign in with Claude"
            isAllCaps = false
            setTextColor(Color.parseColor("#08131c"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#22D3EE"))
            }
            setOnClickListener { startActivity(TerminalActivity.signInIntent(this@VoiceActivity)) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(4), 0, dp(4))
            layoutParams = lp
        }
        row.addView(btn)
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
