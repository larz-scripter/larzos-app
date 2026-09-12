package com.larzos.os

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Detects the "Doctor Larz" wake phrase and calls back so VoiceActivity can
 * start an actual command turn. Swappable: a real low-power engine
 * (Picovoice Porcupine, once a custom "Doctor Larz" keyword is trained
 * through their console) can implement this same interface later with no
 * changes anywhere else - VoiceActivity only ever talks to this interface.
 */
interface WakeWordDetector {
    /**
     * [onHeard] fires for every transcript this hears - matched or not,
     * partial or final - so the caller can show a live "what is it hearing"
     * debug log (on-device feedback asked for exactly this: no visibility
     * into whether the wake word was even being picked up). [onWake] fires
     * only when the wake phrase itself is detected.
     */
    fun start(onWake: () -> Unit, onHeard: (String) -> Unit = {})
    fun stop()
    val isListening: Boolean
}

/**
 * v1: no custom wake-word engine, no external account needed. Loops
 * Android's built-in SpeechRecognizer continuously and checks every
 * transcript (partial results included, for lower latency than waiting on
 * onResults) for the wake phrase. This is meaningfully worse than a real
 * wake-word engine on two axes - battery (full speech recognition running
 * continuously, not a lightweight keyword spotter) and privacy (it is
 * genuinely transcribing everything while active, not just listening for
 * one phrase) - both inherent to using SpeechRecognizer this way, not
 * fixable by better code here. What CAN improve over time without
 * switching engines:
 *   - gate listening behind cheap on-device voice-activity detection
 *     (e.g. AudioRecord + an RMS/energy threshold) so SpeechRecognizer
 *     only spins up when something is actually being said, instead of
 *     running continuously through silence
 *   - react on onPartialResults rather than only the final onResults
 *     (already done here) to cut wake latency
 *   - tune the restart backoff below based on real-world ERROR_* frequency
 *     on the target device instead of the fixed values used here
 *
 * Matching is fuzzy, not a fixed phrase list: on-device testing showed
 * generic STT regularly mangles "Larz" (not a dictionary word) into
 * things like "large", "lars", "larsh". matchesWakeWord() normalizes the
 * transcript and accepts anything starting with "doctor"/"dr" followed
 * within two words by something within edit distance 2 of "larz".
 */
class SpeechRecognizerWakeWordDetector(private val context: Context) : WakeWordDetector {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val RESTART_DELAY_MS = 300L
        private const val ERROR_BACKOFF_MS = 1500L

        private fun normalize(s: String): String =
            s.lowercase(Locale.US).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()

        private fun levenshtein(a: String, b: String): Int {
            val dp = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) dp[i][0] = i
            for (j in 0..b.length) dp[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
            return dp[a.length][b.length]
        }

        fun matchesWakeWord(heard: String): Boolean {
            val norm = normalize(heard)
            if (norm.contains("doctorlarz") || norm.contains("drlarz")) return true
            val words = norm.split(" ")
            for (i in words.indices) {
                if (words[i] != "doctor" && words[i] != "dr") continue
                for (j in (i + 1)..minOf(i + 2, words.size - 1)) {
                    val cand = words.getOrNull(j) ?: continue
                    if (cand.isEmpty()) continue
                    if (levenshtein(cand, "larz") <= 2) return true
                }
            }
            return false
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wanted = false
    override var isListening: Boolean = false
        private set

    override fun start(onWake: () -> Unit, onHeard: (String) -> Unit) {
        if (wanted) return
        wanted = true
        runCycle(onWake, onHeard)
    }

    override fun stop() {
        wanted = false
        isListening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.stopListening(); it.destroy() } }
        recognizer = null
    }

    private fun runCycle(onWake: () -> Unit, onHeard: (String) -> Unit) {
        if (!wanted) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "no speech recognizer available on this device")
            onHeard("(no speech recognizer available on this device)")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) = checkAndMaybeWake(partialResults, onWake, onHeard)
            override fun onResults(results: Bundle?) = checkAndMaybeWake(results, onWake, onHeard)

            override fun onError(error: Int) {
                isListening = false
                r.destroy()
                if (!wanted) return
                // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT are the normal "heard
                // silence, nothing recognized" case in a continuous loop -
                // restart immediately. Anything else (busy, network,
                // client...) backs off a bit so a persistent failure doesn't
                // spin the CPU.
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RESTART_DELAY_MS
                    else -> ERROR_BACKOFF_MS
                }
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    onHeard("(recognizer error $error, retrying)")
                }
                handler.postDelayed({ runCycle(onWake, onHeard) }, delay)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { r.startListening(intent) }.onFailure {
            onHeard("(couldn't start listening: ${it.message})")
            handler.postDelayed({ runCycle(onWake, onHeard) }, ERROR_BACKOFF_MS)
        }
    }

    private fun checkAndMaybeWake(bundle: Bundle?, onWake: () -> Unit, onHeard: (String) -> Unit) {
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val top = heard.firstOrNull()
        if (!top.isNullOrBlank()) onHeard(top)
        if (heard.any { matchesWakeWord(it) }) {
            stop()
            onWake()
        }
    }
}
