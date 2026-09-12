package com.larzos.os

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the Shizuku API: once the user has Shizuku's own
 * privileged service running (started via a one-time wireless-debugging
 * pairing, outside this app - see SystemAccessActivity for the exact
 * steps), this runs commands at Android's "shell" UID - far more than any
 * app's own sandbox, still short of root.
 *
 * Uses Shizuku.newProcess(), which is deprecated upstream in favour of a
 * custom AIDL "user service" - kept here because it maps directly onto
 * "run this shell command and get its output", which is all LarzPrivService
 * needs, without a second AIDL interface + implementation to maintain.
 */
object ShizukuBridge {
    const val REQUEST_CODE = 7412

    val isAvailable: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val hasPermission: Boolean
        get() = isAvailable && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** Fire-and-forget; the result arrives via Shizuku.addRequestPermissionResultListener. */
    fun requestPermission() {
        if (isAvailable && !hasPermission) Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * Runs `sh -c cmdline` at shell UID. Buffers output (no live streaming,
     * no stdin) and enforces [timeoutSec] / [maxBytes] - fine for the
     * package-management / settings / diagnostics commands this bridge is
     * for, not for long-running or interactive ones (use the terminal's own
     * proot shell for those).
     */
    fun run(cmdline: String, timeoutSec: Long = 30, maxBytes: Int = 512 * 1024): Pair<Int, String> {
        if (!hasPermission) return -1 to "shizuku permission not granted"
        val process = try {
            Shizuku.newProcess(arrayOf("sh", "-c", cmdline), null, null)
        } catch (t: Throwable) {
            return -1 to "shizuku newProcess failed: ${t.message}"
        }
        val out = StringBuilder()
        val lock = Any()
        fun pump(stream: java.io.InputStream) = Thread {
            runCatching {
                BufferedReader(InputStreamReader(stream)).forEachLine { line ->
                    synchronized(lock) { if (out.length < maxBytes) out.append(line).append('\n') }
                }
            }
        }.apply { start() }
        val readers = listOf(pump(process.inputStream), pump(process.errorStream))

        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            readers.forEach { runCatching { it.join(500) } }
            return -1 to (out.toString() + "[larz-priv: timed out after ${timeoutSec}s]\n")
        }
        readers.forEach { runCatching { it.join(1000) } }
        return process.exitValue() to out.toString()
    }
}
