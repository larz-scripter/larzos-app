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
    private fun commonArgs(env: LarzEnv, fakeRoot: Boolean = true): MutableList<String> {
        val rootfs = env.rootfs.absolutePath
        val args = mutableListOf(
            env.proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-r", rootfs,
        )
        // Fake root (-0) is what lets apt/package-manager-style tools in the
        // interactive shell work at all - they assume they're really root
        // and refuse otherwise. It's cosmetic, not a real privilege change:
        // proot is unprivileged ptrace, so actual file access is still
        // governed by the real Android app UID underneath either way.
        // BUT `claude` itself hard-refuses --dangerously-skip-permissions
        // when it sees UID 0, for its own good reason - so the one-shot
        // voice invocation (ClaudeVoiceBridge) needs this OFF to report the
        // real non-root UID instead and actually be allowed to run.
        // Who the guest thinks it is. Both are cosmetic - proot is unprivileged
        // ptrace, so real file access is governed by the Android app UID either
        // way - but tools do read it:
        //  - fake root (-0): what apt / `larz install` need; they refuse to run
        //    otherwise. `claude` hard-refuses --dangerously-skip-permissions
        //    when it sees UID 0.
        //  - the guest's `larz` user (-i 1000:1000): the default. The guest
        //    passwd has larz as uid/gid 1000, so `id`, the prompt and ~ all say
        //    larz, and claude accepts --dangerously-skip-permissions.
        // (Not -i with the raw app UID: that has no passwd entry, so the shell
        // would greet you as "I have no name!".)
        args += if (fakeRoot) listOf("-0") else listOf("-i", "$LARZ_UID:$LARZ_UID")
        args += listOf(
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
            args += listOf("-b", "$shared:/home/larz/storage/shared")
        }
        // Shell-UID command bridge (see LarzPrivService + the `larz-priv`
        // guest script) - the per-install token that authenticates the
        // guest to that loopback service. ensurePrivToken() also creates
        // the file if this is the first launch.
        env.ensurePrivToken()
        args += listOf("-b", "${env.privTokenFile.absolutePath}:/root/.larz-priv-token")
        // Voice turn log (see LarzEnv.voiceLogFile / ClaudeVoiceBridge) -
        // readable from the terminal too: `tail -f ~/voice/voice.log`.
        args += listOf("-b", "${env.voiceLogFile.absolutePath}:/root/voice/voice.log")
        // Wake-word hearing log (see LarzEnv.wakeLogFile / VoiceActivity) -
        // `tail -f ~/voice/wake.log` to monitor what the wake-word loop is
        // actually hearing over a longer session than the in-app panel keeps.
        args += listOf("-b", "${env.wakeLogFile.absolutePath}:/root/voice/wake.log")
        return args
    }

    private const val LARZ_UID = 1000

    /** The guest environment for [root] (fake root) or the `larz` user. */
    private fun guestEnv(root: Boolean): List<String> = listOf(
        "/usr/bin/env", "-i",
        "HOME=${if (root) "/root" else "/home/larz"}",
        "USER=${if (root) "root" else "larz"}",
        "LOGNAME=${if (root) "root" else "larz"}",
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
    fun prootArgv(env: LarzEnv, asRoot: Boolean = false): List<String> {
        env.ensureGuestPaths()
        val args = commonArgs(env, fakeRoot = asRoot)
        args += listOf("-w", if (asRoot) "/root" else "/home/larz")
        args += guestEnv(asRoot)
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
    fun prootArgvForCommand(
        env: LarzEnv, workdir: String, guestCmd: List<String>, fakeRoot: Boolean = false
    ): List<String> {
        env.ensureGuestPaths()
        val args = commonArgs(env, fakeRoot)
        args += listOf("-w", workdir)
        args += guestEnv(fakeRoot)
        args += guestCmd
        return args
    }

    /** Environment for the proot *process* (not the guest). */
    fun prootEnv(env: LarzEnv): Array<String> = buildList {
        add("PROOT_TMP_DIR=${env.prootTmp.absolutePath}")
        add("PROOT_LOADER=${env.prootLoader.absolutePath}")
        if (env.prootLoader32.exists()) add("PROOT_LOADER_32=${env.prootLoader32.absolutePath}")
        // Must be the CANONICAL path (symlinks resolved), not just an absolute one: proot
        // only turns an emulated hard link's target back into a guest path when this
        // directory is textually under the rootfs it resolved itself. Android's
        // filesDir is /data/user/0/<pkg>/files, a symlink to /data/data/<pkg>/files, so
        // the plain path mismatched, the link targets stayed host paths, and every file
        // with a second hard link failed to open (git add/commit/clone all break).
        add("PROOT_L2S_DIR=${runCatching { env.l2s.canonicalPath }.getOrDefault(env.l2s.absolutePath)}")
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
