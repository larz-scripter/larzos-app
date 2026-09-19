package com.larzos.os

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs `claude -p` inside the proot guest for one voice turn, streaming -
 * not one long silent blocking call. A "big task" (multi-step, several tool
 * calls) used to mean total silence from "Thinking..." until a single final
 * answer, which reads as broken even when it's working fine. This instead
 * parses Claude Code's own --output-format stream-json event stream
 * (schema confirmed by actually running `claude -p --output-format
 * stream-json --include-partial-messages --verbose` and inspecting the
 * real output - not guessed) and narrates as it goes: a short line when a
 * tool starts, brief interim commentary the model itself produces between
 * steps, and a periodic "still working" heartbeat if it goes quiet for a
 * while - see VoiceActivity for how those get queued as speech alongside
 * the final answer, in order, without cutting each other off.
 *
 * Conversation continuity: a fixed per-install session id (LarzEnv.
 * voiceSessionId) is created on the first turn (--session-id) and resumed
 * on every one after (--resume), independent of whatever's happening in
 * the interactive terminal.
 *
 * Tool access: runs with --dangerously-skip-permissions so Claude can
 * actually use the shell / edit files by voice, since there's no UI here
 * to approve each action. That's a real safety tradeoff specific to voice
 * mode - bounded by the proot sandbox (and by whatever shared-storage /
 * LarzPrivService access has separately been granted), but NOT reviewed
 * turn by turn like the interactive terminal.
 *
 * `claude` itself hard-refuses --dangerously-skip-permissions when it sees
 * UID 0 (a real safety guard on its end) - the interactive terminal runs
 * proot with fake root (-0) for apt/package-manager compatibility, which
 * would otherwise make every voice turn fail with exactly that refusal.
 * LarzSession.prootArgvForCommand() defaults fakeRoot to false for
 * exactly this reason.
 *
 * Every turn is appended to LarzEnv.voiceLogFile (also bind-mounted into
 * the guest at ~/voice/voice.log) - the raw command and the tail of the
 * raw event stream, regardless of success, for on-device debugging.
 */
object ClaudeVoiceBridge {

    private const val WORKDIR = "/root/voice"
    // A "big task" can legitimately run long (several tool calls, a slow
    // command) - this is a safety net against a genuinely hung process, not
    // the primary pacing mechanism; the heartbeat below is what actually
    // keeps a long wait from feeling broken.
    private const val OVERALL_TIMEOUT_MS = 20 * 60 * 1000L
    private const val HEARTBEAT_START_MS = 15_000L
    private const val HEARTBEAT_MAX_MS = 90_000L
    private val LOG_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private const val VOICE_SYSTEM_PROMPT =
        "You are being used through a voice interface right now: the user is " +
        "speaking to you, and your reply will be read aloud by text-to-speech, " +
        "not displayed as text. Answer in short, natural spoken sentences only - " +
        "no markdown, no tables, no bullet or numbered lists, no headers, no code " +
        "blocks, no asterisks or other formatting symbols. If something is " +
        "naturally tabular or code, describe the key point in plain words instead " +
        "of rendering it. Keep answers brief and conversational unless the user " +
        "clearly asks for more detail. On a task with several steps, brief " +
        "one-sentence updates between steps are welcome - the user is listening, " +
        "not reading, and wants to know it's actually progressing. " +
        "In this voice interface specifically, you are 'Doctor Larz', the voice " +
        "assistant for LarzOS - introduce yourself that way if asked who you are, " +
        "and do not mention Claude, Claude Code, or Anthropic by name here."

    /** [needsSetup]: Claude isn't signed in - VoiceActivity offers a one-tap
     *  sign-in instead of just reading the error out. */
    data class Result(
        val ok: Boolean, val text: String,
        val alreadySpoken: Boolean = false, val needsSetup: Boolean = false
    )

    // What `claude -p` says when there's no usable login (both "Not logged in"
    // and an expired/invalid token end with this), verified against the real
    // CLI. Spoken instead of that raw text, which reads as if it were an answer.
    private const val SIGN_IN_MESSAGE =
        "I'm not signed in yet. Tap the sign in button on screen to connect your Claude subscription."
    private fun isSignInProblem(text: String) = text.contains("/login")

    /** Handle to an in-flight turn - VoiceActivity calls [cancel] when the
     *  user interrupts by voice ("stop talking") or by tapping the mic
     *  while a turn is running. Cancelling kills the underlying process and
     *  suppresses [onDone] entirely - the caller has already moved on by
     *  the time it would fire, so there's nothing useful to tell it. */
    class VoiceTurn internal constructor() {
        @Volatile internal var process: Process? = null
        @Volatile var cancelled = false
            private set
        fun cancel() {
            cancelled = true
            runCatching { process?.destroyForcibly() }
        }
    }

    /**
     * [onNarration] fires zero or more times with short spoken-worthy
     * updates while the turn is running - a tool starting, the model's own
     * interim commentary, or a "still working" heartbeat. [onDone] fires
     * exactly once with the final answer (unless cancelled). Both fire on a
     * background thread - callers hop back to the main thread themselves.
     */
    fun ask(env: LarzEnv, prompt: String, onNarration: (String) -> Unit, onDone: (Result) -> Unit): VoiceTurn {
        val turn = VoiceTurn()
        Thread({ runTurn(env, prompt, turn, onNarration, onDone) }, "claude-voice-turn").start()
        return turn
    }

    private fun runTurn(
        env: LarzEnv, prompt: String, turn: VoiceTurn,
        onNarration: (String) -> Unit, onDone: (Result) -> Unit,
        retried: Boolean = false
    ) {
        val guestClaude = resolveClaudeGuestPath(env)
        if (guestClaude == null) {
            val msg = "Claude Code isn't installed in LarzOS yet - open the terminal and run larz-code to install it."
            log(env, prompt, null, msg)
            onDone(Result(false, msg))
            return
        }

        val firstTurn = !env.voiceSessionMarker.exists()
        val sessionFlags = if (firstTurn)
            listOf("--session-id", env.voiceSessionId) else listOf("--resume", env.voiceSessionId)

        val guestCmd = mutableListOf(
            guestClaude, "-p", "--dangerously-skip-permissions",
            "--output-format", "stream-json", "--include-partial-messages", "--verbose"
        )
        guestCmd += listOf("--append-system-prompt", VOICE_SYSTEM_PROMPT)
        guestCmd += sessionFlags
        guestCmd += prompt   // one argv element - proot/ProcessBuilder need no shell quoting

        val argv = LarzSession.prootArgvForCommand(env, WORKDIR, guestCmd)
        val lastEventAt = AtomicLong(System.currentTimeMillis())
        val done = AtomicBoolean(false)
        val rawLog = StringBuilder()
        var lastAssistantText: String? = null
        var lastNarrated: String? = null   // what onNarration() actually spoke
        var finalResultText: String? = null
        var finalOk = false
        // The marker and the session's transcript can disagree: the marker
        // lives outside the rootfs, the transcript inside it. Either way round
        // claude says so on stderr (merged into the stream below) and we retry
        // once with the other flag.
        var staleSession = false      // --resume, but no such conversation
        var sessionInUse = false      // --session-id, but it already exists

        val heartbeat = Thread({
            var threshold = HEARTBEAT_START_MS
            try {
                while (!done.get()) {
                    Thread.sleep(1000)
                    if (done.get() || turn.cancelled) break
                    val idleMs = System.currentTimeMillis() - lastEventAt.get()
                    if (idleMs >= threshold) {
                        onNarration("Still working on it.")
                        lastEventAt.set(System.currentTimeMillis())
                        threshold = minOf(threshold * 2, HEARTBEAT_MAX_MS)
                    }
                }
            } catch (ignored: InterruptedException) {}
        }, "claude-voice-heartbeat").apply { isDaemon = true }

        try {
            val pb = ProcessBuilder(argv)
            pb.environment().clear()
            for (kv in LarzSession.prootEnv(env)) {
                val i = kv.indexOf('=')
                if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
            }
            pb.redirectErrorStream(true)
            val p = pb.start()
            turn.process = p
            if (turn.cancelled) { runCatching { p.destroyForcibly() }; return } // interrupted before it even started
            // claude waits ~3s to see if anything is coming on stdin before
            // giving up and printing a warning - we never send anything, so
            // close it immediately instead of leaving it open/inherited.
            runCatching { p.outputStream.close() }
            heartbeat.start()

            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val startAt = System.currentTimeMillis()
            while (true) {
                if (turn.cancelled) { runCatching { p.destroyForcibly() }; break }
                if (System.currentTimeMillis() - startAt > OVERALL_TIMEOUT_MS) {
                    runCatching { p.destroyForcibly() }
                    rawLog.append("[killed after exceeding ${OVERALL_TIMEOUT_MS / 60000} minute safety timeout]\n")
                    break
                }
                val line = try { reader.readLine() } catch (t: Throwable) { null } ?: break
                lastEventAt.set(System.currentTimeMillis())
                if (line.isBlank()) continue
                rawLog.append(line).append('\n')
                if (!firstTurn && line.contains("No conversation found with session ID")) staleSession = true
                if (firstTurn && line.contains("is already in use")) sessionInUse = true
                try {
                    val evt = JSONObject(line)
                    when (evt.optString("type")) {
                        "assistant" -> {
                            val content = evt.optJSONObject("message")?.optJSONArray("content") ?: JSONArray()
                            for (i in 0 until content.length()) {
                                val block = content.getJSONObject(i)
                                when (block.optString("type")) {
                                    "text" -> {
                                        val t = block.optString("text", "").trim()
                                        if (t.isNotEmpty()) {
                                            lastAssistantText = t
                                            // A sign-in error is replaced by SIGN_IN_MESSAGE
                                            // below - don't also read the raw text aloud.
                                            if (!turn.cancelled && !isSignInProblem(t)) {
                                                lastNarrated = t
                                                onNarration(t)
                                            }
                                        }
                                    }
                                    "tool_use" -> if (!turn.cancelled) onNarration(narrationForTool(block.optString("name", "")))
                                }
                            }
                        }
                        "result" -> {
                            finalOk = !evt.optBoolean("is_error", false)
                            finalResultText = evt.optString("result", "").trim()
                        }
                    }
                } catch (parseErr: Throwable) {
                    // Not every line is JSON we recognise (a stray CLI
                    // warning printed to stdout, a future event type, etc.)
                    // - skip it, never let one bad line kill the turn.
                }
            }
            done.set(true)
            runCatching { heartbeat.interrupt() }
            if (!p.waitFor(5, TimeUnit.SECONDS)) runCatching { p.destroyForcibly() }

            if (!retried && !turn.cancelled && (staleSession || sessionInUse)) {
                log(env, prompt, guestCmd, "voice session out of sync (stale=$staleSession inUse=$sessionInUse) - retrying\n" +
                    rawLog.toString().takeLast(1000))
                if (staleSession) env.voiceSessionMarker.delete() else env.voiceSessionMarker.writeText("1")
                runTurn(env, prompt, turn, onNarration, onDone, retried = true)
                return
            }

            log(env, prompt, guestCmd, rawLog.toString().takeLast(4000))
            if (turn.cancelled) return  // user already moved on - nothing to report

            val text = finalResultText?.takeIf { it.isNotEmpty() } ?: lastAssistantText
            if (text != null) {
                // A signed-out first turn still creates the session on disk
                // (verified), so the marker below is right even then.
                if (firstTurn) env.voiceSessionMarker.writeText("1")
                if (!finalOk && isSignInProblem(text)) {
                    onDone(Result(false, SIGN_IN_MESSAGE, needsSetup = true))
                    return
                }
                // The final "result" event almost always repeats the exact
                // text of the last streamed assistant turn verbatim (that's
                // literally what it is) - onNarration() already spoke it
                // once as it streamed in, so mark it alreadySpoken instead
                // of queuing the same sentence again (on-device testing
                // caught this: replies were read twice back to back).
                val alreadySpoken = finalResultText != null && finalResultText == lastNarrated
                onDone(Result(finalOk || finalResultText == null, text, alreadySpoken))
            } else {
                onDone(Result(false, "Claude couldn't answer that."))
            }
        } catch (t: Throwable) {
            done.set(true)
            runCatching { heartbeat.interrupt() }
            runCatching { turn.process?.destroyForcibly() }
            if (turn.cancelled) return
            log(env, prompt, guestCmd, t.stackTraceToString())
            onDone(Result(false, "Voice bridge failed: ${t.message}"))
        }
    }

    private fun narrationForTool(name: String): String = when (name) {
        "Bash" -> "Running a command."
        "Edit", "Write", "NotebookEdit" -> "Editing a file."
        "Read" -> "Reading a file."
        "WebFetch", "WebSearch" -> "Searching."
        "Task" -> "Delegating part of this to a subagent."
        "Skill" -> "Using a skill."
        "Agent" -> "Starting a subagent."
        else -> "Working on it."
    }

    private fun log(env: LarzEnv, prompt: String, guestCmd: List<String>?, output: String) {
        runCatching {
            env.voiceLogFile.appendText(
                buildString {
                    append("=== ").append(LOG_TS.format(Date())).append(" ===\n")
                    append("prompt: ").append(prompt).append('\n')
                    if (guestCmd != null) append("cmd: ").append(guestCmd.joinToString(" ")).append('\n')
                    append(output.trim()).append("\n\n")
                }
            )
        }
    }

    private fun resolveClaudeGuestPath(env: LarzEnv): String? {
        for (p in listOf("root/.local/bin/claude", "usr/local/bin/claude", "usr/bin/claude")) {
            if (File(env.rootfs, p).exists()) return "/$p"
        }
        return null
    }
}
