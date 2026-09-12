package com.larzos.os

import java.util.concurrent.TimeUnit

/**
 * The Shizuku "user service" implementation - Shizuku loads this class by
 * reflection into a *new process it spawns at shell (or root) UID*, so
 * everything in here already runs privileged; exec() needs nothing more
 * exotic than a plain ProcessBuilder. Requires a public no-arg constructor
 * (Shizuku's contract for user services).
 */
class ShellUserService : IShellService.Stub() {

    override fun exec(cmdline: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", cmdline)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            val code = if (finished) process.exitValue() else {
                process.destroyForcibly()
                -1
            }
            "$code\n$output"
        } catch (t: Throwable) {
            "-1\n[shell-service error] ${t.message}"
        }
    }

    /** Called by Shizuku when the service is being torn down. */
    fun destroy() {}
}
