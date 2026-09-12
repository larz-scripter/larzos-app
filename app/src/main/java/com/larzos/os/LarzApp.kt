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

    /** proot's symlink-to-symlink emulation dir (PROOT_L2S_DIR). */
    val l2s: File = File(root, "l2s")

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
        listOf(root, prootTmp, l2s).forEach { it.mkdirs() }
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
}
