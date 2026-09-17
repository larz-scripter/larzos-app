package com.larzos.os

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One-tap path to the LarzOS graphical desktop (the `larz-gui` package
 * shipped by larzos-linux v0.1.13+): installs and starts the VNC-backed
 * Openbox desktop inside the guest, then hands off actual pixel rendering
 * to whatever vnc:// viewer is installed (e.g. AVNC, which already
 * declares an ACTION_VIEW intent-filter for that scheme) - reached from
 * the 🖥 button in TerminalActivity's extra-keys row.
 *
 * True in-process rendering (no second app at all) would mean vendoring
 * AVNC's renderer/input code plus a new native NDK/CMake/vcpkg build for
 * libvncserver - out of scope here, tracked as a follow-up in BUILD.md.
 */
class DesktopActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var env: LarzEnv

    private val vncPort = 5901
    private val workdir = "/root"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = (application as LarzApp).env

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#CFE8F3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "Preparing the desktop…"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(Color.parseColor("#0B1020"))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        if (!env.isInstalled) {
            status.text = "LarzOS isn't set up yet - finish setup first."
            return
        }
        startSetupFlow()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(line: String) {
        runOnUiThread { status.append("\n" + line) }
    }

    private fun startSetupFlow() {
        Thread({
            log("Installing larz-gui…")
            val installed = runGuest(
                listOf("bash", "-lc", "command -v larz-gui >/dev/null 2>&1 || apt-get install -y larz-gui"),
                fakeRoot = true, timeoutMin = 10
            )
            if (installed.first != 0) {
                log("Couldn't install larz-gui (exit ${installed.first}):")
                log(installed.second.trim().takeLast(600))
                return@Thread
            }
            log("larz-gui installed.")

            val hasPassword = File(env.rootfs, "root/.vnc/passwd").exists()
            if (!hasPassword) {
                runOnUiThread { promptForPassword { pwd -> continueAfterPassword(pwd) } }
            } else {
                continueAfterPassword(null)
            }
        }, "desktop-setup").start()
    }

    private fun continueAfterPassword(password: String?) {
        Thread({
            if (password != null) {
                log("Setting the desktop password…")
                val r = runGuest(
                    listOf("bash", "-lc", "mkdir -p ~/.vnc && vncpasswd -f > ~/.vnc/passwd && chmod 600 ~/.vnc/passwd"),
                    fakeRoot = false, stdin = "$password\n"
                )
                if (r.first != 0) {
                    log("Couldn't set the desktop password (exit ${r.first}):")
                    log(r.second.trim().takeLast(600))
                    return@Thread
                }
            }

            log("Starting the desktop…")
            val started = runGuest(listOf("larz-gui", "start"), fakeRoot = false)
            log(
                started.second.trim().ifEmpty {
                    if (started.first == 0) "Desktop is running." else "larz-gui start exited ${started.first}."
                }
            )

            runOnUiThread { handoffToViewer() }
        }, "desktop-start").start()
    }

    private fun promptForPassword(onSet: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Desktop password (6-8 characters)"
        }
        AlertDialog.Builder(this)
            .setTitle("Set a desktop password")
            .setMessage(
                "This protects your LarzOS desktop over VNC. Enter it once here, " +
                "then again the first time your viewer app connects."
            )
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Set") { _, _ ->
                val pwd = input.text.toString()
                if (pwd.length < 6) {
                    Toast.makeText(
                        this, "Needs to be at least 6 characters - tap the desktop button again.", Toast.LENGTH_LONG
                    ).show()
                    finish()
                } else {
                    onSet(pwd)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun handoffToViewer() {
        val uri = Uri.parse("vnc://127.0.0.1:$vncPort")
        val viewIntent = Intent(Intent.ACTION_VIEW, uri)
        val handlers = packageManager.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.isNotEmpty()) {
            log("Opening the desktop viewer…")
            startActivity(viewIntent)
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Desktop is running")
                .setMessage(
                    "LarzOS's graphical desktop is up, but no VNC viewer app is " +
                    "installed to show it. AVNC (free, open source) works well - " +
                    "install it, then tap the desktop button again."
                )
                .setPositiveButton("Get AVNC") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.gaurav.avnc/")))
                    finish()
                }
                .setNegativeButton("Later") { _, _ -> finish() }
                .show()
        }
    }

    /** One-shot headless guest command via a plain ProcessBuilder - same
     *  pattern ClaudeVoiceBridge.runTurn uses, minus the streaming/
     *  heartbeat machinery this doesn't need. Returns (exitCode, combined
     *  stdout+stderr). A watchdog thread bounds the wait since reading the
     *  process's output otherwise blocks until it closes stdout. */
    private fun runGuest(
        guestCmd: List<String>, fakeRoot: Boolean, stdin: String? = null, timeoutMin: Long = 5
    ): Pair<Int, String> {
        val argv = LarzSession.prootArgvForCommand(env, workdir, guestCmd, fakeRoot)
        return try {
            val pb = ProcessBuilder(argv)
            pb.environment().clear()
            for (kv in LarzSession.prootEnv(env)) {
                val i = kv.indexOf('=')
                if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
            }
            pb.redirectErrorStream(true)
            val p = pb.start()
            if (stdin != null) runCatching { p.outputStream.write(stdin.toByteArray()); p.outputStream.flush() }
            runCatching { p.outputStream.close() }
            val watchdog = Thread({
                if (!p.waitFor(timeoutMin, TimeUnit.MINUTES)) runCatching { p.destroyForcibly() }
            }, "guest-cmd-watchdog").apply { isDaemon = true; start() }
            val output = p.inputStream.bufferedReader().readText()
            watchdog.join()
            runCatching { p.exitValue() }.getOrDefault(-1) to output
        } catch (t: Throwable) {
            -1 to "guest command failed: ${t.message}"
        }
    }
}
