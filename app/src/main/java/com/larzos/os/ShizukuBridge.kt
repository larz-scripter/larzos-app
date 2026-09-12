package com.larzos.os

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around the Shizuku API: once the user has Shizuku's own
 * privileged service running (started via a one-time wireless-debugging
 * pairing, outside this app - see SystemAccessActivity for the exact
 * steps) and has granted this app permission, binds Shizuku's "user
 * service" model - [ShellUserService] running in a process Shizuku spawns
 * at shell (or root) UID - to run arbitrary shell commands.
 */
object ShizukuBridge {
    const val REQUEST_CODE = 7412

    private val serviceRef = AtomicReference<IShellService?>(null)
    private var connectLatch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceRef.set(if (binder?.isBinderAlive == true) IShellService.Stub.asInterface(binder) else null)
            connectLatch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceRef.set(null)
        }
    }

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

    /** Call once permission is confirmed granted (e.g. from SystemAccessActivity). Idempotent. */
    fun connect() {
        if (!hasPermission || serviceRef.get() != null) return
        val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        runCatching { Shizuku.bindUserService(args, connection) }
    }

    /** Blocks briefly for a first-time connect if [connect] hasn't finished yet. */
    private fun awaitService(timeoutSec: Long = 5): IShellService? {
        serviceRef.get()?.let { return it }
        if (!hasPermission) return null
        val latch = CountDownLatch(1)
        connectLatch = latch
        connect()
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return serviceRef.get()
    }

    /**
     * Runs `sh -c cmdline` at shell UID via [ShellUserService]. Buffers
     * output (no live streaming, no stdin) - fine for the package-
     * management / settings / diagnostics commands this bridge is for,
     * not for long-running or interactive ones (use the terminal's own
     * proot shell for those).
     */
    fun run(cmdline: String): Pair<Int, String> {
        val service = awaitService() ?: return -1 to
            "shizuku service not connected - open LarzOS > System access and tap Connect"
        val result = try {
            service.exec(cmdline)
        } catch (t: Throwable) {
            serviceRef.set(null) // stale/dead binder - force a reconnect next call
            return -1 to "shell service call failed: ${t.message}"
        }
        val nl = result.indexOf('\n')
        if (nl < 0) return -1 to result
        val code = result.substring(0, nl).toIntOrNull() ?: -1
        return code to result.substring(nl + 1)
    }
}
