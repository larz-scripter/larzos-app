package com.larzos.os

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Loopback-only bridge the guest's `larz-priv` script (see
 * Installer.LARZ_PRIV_SCRIPT) talks to for shell-UID commands via
 * [ShizukuBridge]. Started/stopped alongside the terminal session
 * (TerminalActivity) - it only needs to exist while a LarzOS session is
 * actually open.
 *
 * Binds 127.0.0.1 only, so nothing off-device can reach it - but loopback
 * itself is shared across every app on the phone, so every request is
 * still gated on a per-install random token (LarzEnv.ensurePrivToken)
 * that's only readable by this app's own proot guest (bind-mounted at
 * /root/.larz-priv-token, see LarzSession.prootArgv).
 *
 * Wire protocol (line-based, UTF-8):
 *   client -> token\n
 *   client -> command line\n
 *   server -> zero or more output lines
 *   server -> "__LARZ_ERR__:<message>" (optional, on failure)
 *   server -> "__LARZ_EXIT__:<code>"   (always last)
 */
class LarzPrivService : Service() {

    companion object {
        const val PORT = 8199
        private const val TAG = "LarzPrivService"
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private lateinit var token: String

    override fun onCreate() {
        super.onCreate()
        token = (application as LarzApp).env.ensurePrivToken()
        Thread(::runServer, "larz-priv-accept").start()
    }

    private fun runServer() {
        try {
            val s = ServerSocket(PORT, 8, InetAddress.getLoopbackAddress())
            server = s
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(client) }
            }
        } catch (t: Throwable) {
            // Port already bound by a previous instance, or service is
            // stopping - the guest's larz-priv just reports "not reachable".
            Log.w(TAG, "server loop ended: ${t.message}")
        }
    }

    private fun handle(sock: Socket) {
        sock.use {
            sock.soTimeout = 35_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val writer = PrintWriter(sock.getOutputStream(), true)
            val gotToken = reader.readLine() ?: return
            if (gotToken != token) {
                writer.println("__LARZ_ERR__:bad token")
                writer.println("__LARZ_EXIT__:127")
                return
            }
            val cmdline = reader.readLine() ?: return
            if (cmdline.isBlank()) {
                writer.println("__LARZ_EXIT__:0")
                return
            }
            if (!ShizukuBridge.hasPermission) {
                writer.println("__LARZ_ERR__:Shizuku not connected - open LarzOS > System access")
                writer.println("__LARZ_EXIT__:127")
                return
            }
            val (code, output) = ShizukuBridge.run(cmdline)
            writer.print(output)   // already newline-terminated per line, see ShizukuBridge.run
            writer.println("__LARZ_EXIT__:$code")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        runCatching { server?.close() }
        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
