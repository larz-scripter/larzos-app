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
}
