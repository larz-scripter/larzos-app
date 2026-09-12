package com.larzos.os

import android.os.Build

/**
 * Builds the proot command line that boots the LarzOS userland, and the
 * environment proot itself needs. The heavy lifting (the pty) is done by the
 * Termux terminal-emulator native layer via [com.termux.terminal.TerminalSession].
 */
object LarzSession {

    /** The command inside the guest we drop the user into. */
    private const val LOGIN = "/usr/bin/larzsh"
    private const val LOGIN_FALLBACK = "/bin/bash"

    /**
     * The proot flags + host bindings every guest invocation needs,
     * regardless of what it then runs - shared by [prootArgv] (the
     * interactive login shell) and [prootArgvForCommand] (one-shot, no
     * PTY - see ClaudeVoiceBridge). Does not include `-w` (workdir) or the
     * trailing guest command; callers append both.
     */
    private fun commonArgs(env: LarzEnv): MutableList<String> {
        val rootfs = env.rootfs.absolutePath
        val args = mutableListOf(
            env.proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-r", rootfs,
            "-0",                       // fake root inside the guest
            // host bindings the guest needs
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${env.prootTmp.absolutePath}:/dev/shm",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/system",
        )
        if (java.io.File("/apex").exists()) args += listOf("-b", "/apex")

        // Android 10+ hides /proc/net/{tcp,udp,...} from sandboxed apps, so
        // netstat / ss error out with "no support for AF INET". Bind a stub
        // (header only) over each so the tools show an empty table instead of
        // failing. Connection listing across the system genuinely can't be
        // granted to an unprivileged app - `ip addr` / `ip route` / `ping`
        // are the ways to inspect networking here.
        val procNetStub = env.ensureProcNetStub().absolutePath
        for (t in listOf("tcp", "tcp6", "udp", "udp6", "raw", "raw6")) {
            args += listOf("-b", "$procNetStub:/proc/net/$t")
        }
        // a persistent /root that survives even if the rootfs is reinstalled
        // could be added here later; for now /root lives in the rootfs.

        // Shared phone storage (Downloads, Pictures, ...) - only once "All
        // files access" is granted (see SystemAccessActivity). Termux calls
        // this same convention ~/storage/shared; the mountpoint must already
        // exist in the rootfs (Installer.postExtractFixups creates it).
        if (env.sharedStorageAvailable) {
            val shared = android.os.Environment.getExternalStorageDirectory().absolutePath
            args += listOf("-b", "$shared:/root/storage/shared")
        }
        // Shell-UID command bridge (see LarzPrivService + the `larz-priv`
        // guest script) - the per-install token that authenticates the
        // guest to that loopback service. ensurePrivToken() also creates
        // the file if this is the first launch.
        env.ensurePrivToken()
        args += listOf("-b", "${env.privTokenFile.absolutePath}:/root/.larz-priv-token")
        return args
    }

    private val guestEnv = listOf(
        "/usr/bin/env", "-i",
        "HOME=/root",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "PROOT_NO_SECCOMP=1",
        "MOZ_FAKE_NO_SANDBOX=1",
    )

    /**
     * argv[0] is proot itself (the executable path); the rest are proot args
     * ending in the guest command. Feed this to TerminalSession as
     * (shellPath = args[0], args = args.drop(1)).
     */
    fun prootArgv(env: LarzEnv): List<String> {
        val args = commonArgs(env)
        args += listOf("-w", "/root")
        args += guestEnv
        val login = if (java.io.File(env.rootfs, LOGIN.removePrefix("/")).exists())
            LOGIN else LOGIN_FALLBACK
        args += listOf(login, "-l")
        return args
    }

    /**
     * Same guest, no PTY: for a one-shot headless command (ClaudeVoiceBridge)
     * run via a plain ProcessBuilder instead of TerminalSession. [workdir] is
     * a guest-absolute path (e.g. "/root/voice"); [guestCmd] is argv, not a
     * shell string - no quoting needed, each element is passed through as-is.
     */
    fun prootArgvForCommand(env: LarzEnv, workdir: String, guestCmd: List<String>): List<String> {
        val args = commonArgs(env)
        args += listOf("-w", workdir)
        args += guestEnv
        args += guestCmd
        return args
    }

    /** Environment for the proot *process* (not the guest). */
    fun prootEnv(env: LarzEnv): Array<String> = buildList {
        add("PROOT_TMP_DIR=${env.prootTmp.absolutePath}")
        add("PROOT_LOADER=${env.prootLoader.absolutePath}")
        if (env.prootLoader32.exists()) add("PROOT_LOADER_32=${env.prootLoader32.absolutePath}")
        add("PROOT_L2S_DIR=${env.l2s.absolutePath}")
        add("HOME=${env.root.absolutePath}")
        add("TMPDIR=${env.prootTmp.absolutePath}")
        add("LD_LIBRARY_PATH=${env.nativeDir}")
        add("TERM=xterm-256color")
        add("ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: "/system"}")
        add("ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: "/data"}")
        System.getenv("ANDROID_ART_ROOT")?.let { add("ANDROID_ART_ROOT=$it") }
        System.getenv("DEX2OATBOOTCLASSPATH")?.let { add("DEX2OATBOOTCLASSPATH=$it") }
        System.getenv("BOOTCLASSPATH")?.let { add("BOOTCLASSPATH=$it") }
    }.toTypedArray()

    val supportedAbi: String? get() = when {
        Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64-v8a"
        Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armeabi-v7a"
        else -> null
    }
}
