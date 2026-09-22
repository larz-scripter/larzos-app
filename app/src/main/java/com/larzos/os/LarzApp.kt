package com.larzos.os

import android.app.Application
import java.io.File

class LarzApp : Application() {
    lateinit var env: LarzEnv
        private set

    override fun onCreate() {
        super.onCreate()
        env = LarzEnv(this)
    }
}

/** All the on-device paths for the LarzOS userland. */
class LarzEnv(app: Application) {
    /** filesDir/larzos — everything LarzOS lives here. */
    val root: File = File(app.filesDir, "larzos")

    /** The extracted Debian/LarzOS root filesystem. */
    val rootfs: File = File(root, "rootfs")

    /** Writable tmp for proot itself (PROOT_TMP_DIR). */
    val prootTmp: File = File(root, "tmp")

    /** proot's hard-link emulation dir (PROOT_L2S_DIR). It MUST be inside the
     *  rootfs: proot stores the real file here and leaves a symlink holding the
     *  absolute host path, which it can only translate back into the guest for
     *  paths under the rootfs or a bind. Outside it every emulated hard link
     *  dangled inside the guest - which is what broke shadow's lock files
     *  (`groupadd: /etc/group.N file stat error`, so no adduser/useradd). */
    val l2s: File = File(rootfs, ".l2s")

    /** Marker written once the rootfs is fully unpacked and set up.
     *  Line 1 is the rootfs asset name it was built from. */
    val installedMarker: File = File(root, ".installed")

    /** Which rootfs asset the installed system was unpacked from, or null. */
    val installedRootfs: String?
        get() = runCatching {
            installedMarker.readText().lineSequence().firstOrNull()?.trim()?.ifEmpty { null }
        }.getOrNull()

    /** True when a system is installed but from an older rootfs than this build ships. */
    val needsRootfsUpdate: Boolean
        get() = isInstalled && installedRootfs != BuildConfig.ROOTFS_ARM64

    /** nativeLibraryDir — the one place an unprivileged app may exec from.
     *  proot + its loader ship here as lib*.so (see tools/fetch-proot.sh). */
    val nativeDir: String = app.applicationInfo.nativeLibraryDir
    val proot: File = File(nativeDir, "libproot.so")
    val prootLoader: File = File(nativeDir, "libproot-loader.so")
    val prootLoader32: File = File(nativeDir, "libproot-loader32.so")

    val isInstalled: Boolean get() = installedMarker.exists() && rootfs.isDirectory

    fun ensureDirs() {
        listOf(root, prootTmp).forEach { it.mkdirs() }   // l2s lives in the rootfs: see ensureGuestPaths
    }

    // Header-only stand-in for the /proc/net connection tables Android hides
    // from sandboxed apps (see LarzSession.prootArgv). Idempotent.
    private val procNetStub: File = File(prootTmp, "proc-net-stub")
    fun ensureProcNetStub(): File {
        if (!procNetStub.exists()) {
            prootTmp.mkdirs()
            procNetStub.writeText(
                "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
            )
        }
        return procNetStub
    }

    /** True once the user has granted "All files access" (or on pre-API-30
     *  devices, where the legacy READ/WRITE permissions cover it instead). */
    val sharedStorageAvailable: Boolean
        get() = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()

    // Per-install random secret so the loopback-only LarzPrivService (see
    // that class) can tell "the LarzOS guest" apart from any other app on
    // the phone that also happens to connect to 127.0.0.1 on its port -
    // loopback is shared across every app on the device, this bind-mounted
    // file is not (only this app's own proot guest can read it).
    val privTokenFile: File = File(root, "priv-token")
    fun ensurePrivToken(): String {
        if (!privTokenFile.exists()) {
            val bytes = ByteArray(24)
            java.security.SecureRandom().nextBytes(bytes)
            privTokenFile.writeText(
                android.util.Base64.encodeToString(
                    bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
                )
            )
        }
        return privTokenFile.readText().trim()
    }

    // Voice mode (see ClaudeVoiceBridge, VoiceActivity) - a fixed
    // per-install Claude session id, resumed every turn, kept entirely
    // separate from whatever's happening in the interactive terminal.
    private val voiceSessionIdFile: File = File(root, "voice-session-id")
    val voiceSessionId: String
        get() {
            if (!voiceSessionIdFile.exists()) voiceSessionIdFile.writeText(java.util.UUID.randomUUID().toString())
            return voiceSessionIdFile.readText().trim()
        }
    /** Present once the voice session has been created on Claude's side (first turn). */
    val voiceSessionMarker: File = File(root, "voice-session-started")
    /** Deletes voice state so the next turn starts a brand new conversation. */
    fun resetVoiceSession() {
        voiceSessionIdFile.delete()
        voiceSessionMarker.delete()
    }

    /** Every voice turn's raw invocation + output, for on-device debugging -
     *  see ClaudeVoiceBridge. Also bind-mounted into the guest (LarzSession)
     *  at /root/voice/voice.log so `tail -f ~/voice/voice.log` works too. */
    val voiceLogFile: File = File(root, "voice.log")

    /** Every phrase the wake-word loop hears, matched or not, plus every
     *  match - see VoiceActivity/WakeWordDetector. Same bind-mount pattern
     *  as voiceLogFile: also readable from the terminal at
     *  ~/voice/wake.log, not just the app's own rolling 12-line UI panel,
     *  so it can actually be monitored/tuned over a longer session. */
    val wakeLogFile: File = File(root, "wake.log")
    private val wakeLogTs = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    fun appendWakeLog(line: String) {
        runCatching { wakeLogFile.appendText("[${wakeLogTs.format(java.util.Date())}] $line\n") }
    }

    /**
     * Creates guest-side paths that other bind mounts/tools need to already
     * exist (proot's -b requires the target to be there first) - Installer.
     * postExtractFixups() creates these too, but only runs at (re)install
     * time. An already-installed rootfs from before a given feature shipped
     * would never get them otherwise, so this runs on every proot launch
     * instead (LarzSession.commonArgs) - cheap, idempotent.
     */
    fun ensureGuestPaths() {
        if (!rootfs.isDirectory) return
        l2s.mkdirs()
        File(rootfs, "root/voice").mkdirs()
        File(rootfs, "root/storage/shared").mkdirs()
        File(rootfs, "home/larz/storage/shared").mkdirs()   // ~/storage/shared for the larz user
        File(rootfs, "root/.larz-priv-token").apply {
            if (!exists()) runCatching { parentFile?.mkdirs(); writeText("") }
        }
        if (!voiceLogFile.exists()) runCatching { voiceLogFile.writeText("") }
        if (!wakeLogFile.exists()) runCatching { wakeLogFile.writeText("") }
    }
}
