package com.larzos.os

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs `claude -p` headlessly inside the proot guest for one voice turn -
 * no PTY, just a plain process with stdout captured. Print mode's default
 * text output is already just the final reply (no TUI boxes/spinners),
 * which is what makes this workable for text-to-speech at all - see
 * VoiceActivity for the wake-word/STT/TTS side.
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
 * exactly this reason - confirmed via an on-device run that hit the
 * refusal before that fix landed.
 *
 * Every turn is appended to LarzEnv.voiceLogFile (also bind-mounted into
 * the guest at ~/voice/voice.log) - the raw command, exit code, and full
 * output, regardless of success, for on-device debugging.
 */
object ClaudeVoiceBridge {

    private const val TIMEOUT_SEC = 120L
    private const val WORKDIR = "/root/voice"
    private val LOG_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class Result(val ok: Boolean, val text: String)

    fun ask(env: LarzEnv, prompt: String): Result {
        val guestClaude = resolveClaudeGuestPath(env)
        if (guestClaude == null) {
            val msg = "Claude isn't installed in LarzOS yet - open the terminal and run `claude` once first."
            log(env, prompt, null, -1, msg)
            return Result(false, msg)
        }

        val firstTurn = !env.voiceSessionMarker.exists()
        val sessionFlags = if (firstTurn)
            listOf("--session-id", env.voiceSessionId) else listOf("--resume", env.voiceSessionId)

        val guestCmd = mutableListOf(guestClaude, "-p", "--dangerously-skip-permissions")
        guestCmd += sessionFlags
        guestCmd += prompt   // one argv element - proot/ProcessBuilder need no shell quoting

        val argv = LarzSession.prootArgvForCommand(env, WORKDIR, guestCmd)
        try {
            val pb = ProcessBuilder(argv)
            pb.environment().clear()
            for (kv in LarzSession.prootEnv(env)) {
                val i = kv.indexOf('=')
                if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
            }
            pb.redirectErrorStream(true)
            val process = pb.start()
            // claude waits ~3s to see if anything is coming on stdin before
            // giving up and printing a warning - we never have anything to
            // send, so close it immediately instead of leaving it open
            // (inherited from this process, which never writes to it either).
            runCatching { process.outputStream.close() }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                log(env, prompt, guestCmd, -1, output + "\n[timed out after ${TIMEOUT_SEC}s]")
                return Result(false, "Claude took too long to answer.")
            }
            val code = process.exitValue()
            log(env, prompt, guestCmd, code, output)
            if (code != 0 && output.isBlank()) {
                return Result(false, "Claude couldn't answer that (exit $code).")
            }
            if (firstTurn) env.voiceSessionMarker.writeText("1")
            return Result(true, output.trim())
        } catch (t: Throwable) {
            log(env, prompt, guestCmd, -1, t.stackTraceToString())
            return Result(false, "Voice bridge failed: ${t.message}")
        }
    }

    private fun log(env: LarzEnv, prompt: String, guestCmd: List<String>?, exitCode: Int, output: String) {
        runCatching {
            env.voiceLogFile.appendText(
                buildString {
                    append("=== ").append(LOG_TS.format(Date())).append(" exit=").append(exitCode).append(" ===\n")
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
