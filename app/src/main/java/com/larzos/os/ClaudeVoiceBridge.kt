package com.larzos.os

import java.io.File
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
 * turn by turn like the interactive terminal. Needs on-device
 * verification: exact CLI flags matched against `claude --help` at the
 * time this was written, not tested against a live voice turn.
 */
object ClaudeVoiceBridge {

    private const val TIMEOUT_SEC = 120L
    private const val WORKDIR = "/root/voice"

    data class Result(val ok: Boolean, val text: String)

    fun ask(env: LarzEnv, prompt: String): Result {
        val guestClaude = resolveClaudeGuestPath(env)
            ?: return Result(false, "Claude isn't installed in LarzOS yet - open the terminal and run `claude` once first.")

        val firstTurn = !env.voiceSessionMarker.exists()
        val sessionFlags = if (firstTurn)
            listOf("--session-id", env.voiceSessionId) else listOf("--resume", env.voiceSessionId)

        val guestCmd = mutableListOf(guestClaude, "-p", "--dangerously-skip-permissions")
        guestCmd += sessionFlags
        guestCmd += prompt   // one argv element - proot/ProcessBuilder need no shell quoting

        val argv = LarzSession.prootArgvForCommand(env, WORKDIR, guestCmd)
        return try {
            val pb = ProcessBuilder(argv)
            pb.environment().clear()
            for (kv in LarzSession.prootEnv(env)) {
                val i = kv.indexOf('=')
                if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
            }
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result(false, "Claude took too long to answer.")
            }
            if (process.exitValue() != 0 && output.isBlank()) {
                return Result(false, "Claude couldn't answer that (exit ${process.exitValue()}).")
            }
            if (firstTurn) env.voiceSessionMarker.writeText("1")
            Result(true, output.trim())
        } catch (t: Throwable) {
            Result(false, "Voice bridge failed: ${t.message}")
        }
    }

    private fun resolveClaudeGuestPath(env: LarzEnv): String? {
        for (p in listOf("root/.local/bin/claude", "usr/local/bin/claude", "usr/bin/claude")) {
            if (File(env.rootfs, p).exists()) return "/$p"
        }
        return null
    }
}
