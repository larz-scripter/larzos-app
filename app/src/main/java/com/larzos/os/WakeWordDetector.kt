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

/**
 * Detects the "Doctor Larz" wake phrase and calls back so VoiceActivity can
 * start an actual command turn. Swappable: a real low-power engine
 * (Picovoice Porcupine, once a custom "Doctor Larz" keyword is trained
 * through their console) can implement this same interface later with no
 * changes anywhere else - VoiceActivity only ever talks to this interface.
 */
interface WakeWordDetector {
    fun start(onWake: () -> Unit)
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
 */
class SpeechRecognizerWakeWordDetector(private val context: Context) : WakeWordDetector {

    companion object {
        private const val TAG = "WakeWordDetector"
        private val WAKE_PHRASES = listOf("doctor larz", "dr larz", "dr. larz", "doctor lars")
        private const val RESTART_DELAY_MS = 300L
        private const val ERROR_BACKOFF_MS = 1500L
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wanted = false
    override var isListening: Boolean = false
        private set

    override fun start(onWake: () -> Unit) {
        if (wanted) return
        wanted = true
        runCycle(onWake)
    }

    override fun stop() {
        wanted = false
        isListening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.stopListening(); it.destroy() } }
        recognizer = null
    }

    private fun runCycle(onWake: () -> Unit) {
        if (!wanted) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "no speech recognizer available on this device")
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

            override fun onPartialResults(partialResults: Bundle?) = checkAndMaybeWake(partialResults, onWake)
            override fun onResults(results: Bundle?) = checkAndMaybeWake(results, onWake)

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
                handler.postDelayed({ runCycle(onWake) }, delay)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { r.startListening(intent) }.onFailure {
            handler.postDelayed({ runCycle(onWake) }, ERROR_BACKOFF_MS)
        }
    }

    private fun checkAndMaybeWake(bundle: Bundle?, onWake: () -> Unit) {
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val matched = heard.any { phrase -> WAKE_PHRASES.any { phrase.contains(it, ignoreCase = true) } }
        if (matched) {
            stop()
            onWake()
        }
    }
}
