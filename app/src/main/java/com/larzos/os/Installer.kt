package com.larzos.os

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the LarzOS arm64 rootfs and unpacks it into [LarzEnv.rootfs].
 * Reports coarse progress via [onProgress] (0..100, or -1 for indeterminate).
 */
class Installer(private val env: LarzEnv) {

    fun interface Progress { fun update(pct: Int, msg: String) }

    // Claude Code's sign-in (~/.claude/.credentials.json), settings and every
    // conversation live in the guest's home. A base-system update wipes and
    // re-extracts the whole rootfs, which used to sign you out of Claude and,
    // worse, orphan the voice session ClaudeVoiceBridge resumes (its id and
    // marker live outside the rootfs, its transcript did not).
    private val claudeState = listOf(".claude", ".claude.json")
    private val claudeStash: File get() = File(env.root, "claude-state-stash")

    /**
     * Park Claude's state outside the rootfs across the swap. Best effort: a
     * failure only means signing in again, so it must never fail the install.
     * An existing stash is deliberately kept, not cleared - it is the only
     * copy if a previous install was killed between stashing and restoring.
     */
    private fun stashClaudeState() {
        runCatching {
            val stash = claudeStash.apply { mkdirs() }
            for (name in claudeState) {
                val src = File(env.rootfs, "root/$name")
                if (!src.exists()) continue
                val dest = File(stash, name)
                dest.deleteRecursively()
                src.renameTo(dest)   // same filesystem: an atomic move, not a copy
            }
        }
    }

    /** Put the stash back into the fresh rootfs; keep it if any entry failed. */
    private fun restoreClaudeState() {
        runCatching {
            val stash = claudeStash
            if (!stash.isDirectory) return
            val home = File(env.rootfs, "root").apply { mkdirs() }
            var allBack = true
            for (name in claudeState) {
                val kept = File(stash, name)
                if (!kept.exists()) continue
                val dest = File(home, name)
                dest.deleteRecursively()
                if (!kept.renameTo(dest)) allBack = false
            }
            if (allBack) stash.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    fun install(onProgress: Progress) {
        env.ensureDirs()
        val abi = LarzSession.supportedAbi ?: throw IOException("unsupported CPU")
        val asset = when (abi) {
            "arm64-v8a" -> BuildConfig.ROOTFS_ARM64
            else -> BuildConfig.ROOTFS_ARM64 // only arm64 published today
        }
        val url = "${BuildConfig.ROOTFS_BASE_URL}/$asset"

        onProgress.update(-1, "Connecting…")
        val tmpTar = File(env.root, "rootfs.tar.gz.part")
        download(url, tmpTar, onProgress)

        onProgress.update(-1, "Unpacking the system…")
        stashClaudeState()
        if (env.rootfs.exists()) env.rootfs.deleteRecursively()
        env.rootfs.mkdirs()
        extractTarGz(tmpTar, env.rootfs, onProgress)
        tmpTar.delete()
        restoreClaudeState()

        postExtractFixups()
        // line 1 = the rootfs asset this was built from, so the app knows to
        // re-unpack when a newer LarzOS base ships.
        env.installedMarker.writeText(BuildConfig.ROOTFS_ARM64 + "\n" + System.currentTimeMillis())
        onProgress.update(100, "Ready")
    }

    private fun download(urlStr: String, dest: File, onProgress: Progress) {
        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.connect()
        // GitHub "latest/download" 302s to a signed S3 URL; HttpURLConnection
        // follows same-scheme redirects but not always cross-host — handle one hop.
        if (conn.responseCode in 300..399) {
            val loc = conn.getHeaderField("Location") ?: throw IOException("redirect with no Location")
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.connect()
        }
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $urlStr")

        val total = conn.contentLengthLong
        var read = 0L
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); read += n
                    if (total > 0) onProgress.update((read * 60 / total).toInt(), "Downloading… ${read / 1_000_000} MB")
                }
            }
        }
        conn.disconnect()
    }

    private fun extractTarGz(tar: File, into: File, onProgress: Progress) {
        val symlinks = ArrayList<Pair<File, String>>()
        val hardlinks = ArrayList<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(tar.inputStream()))).use { tin ->
            var entry = tin.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name.removePrefix("./").removePrefix("/")
                if (name.isEmpty() || name.contains("..")) { entry = tin.nextEntry; continue }
                val outFile = File(into, name)
                when {
                    entry.isDirectory -> outFile.mkdirs()
                    entry.isSymbolicLink -> symlinks += outFile to entry.linkName
                    entry.isLink ->  // hard link - resolve in a later pass
                        hardlinks += outFile to File(into, entry.linkName.removePrefix("./").removePrefix("/"))
                    else -> {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { tin.copyTo(it, 64 * 1024) }
                        val mode = entry.mode
                        outFile.setExecutable(mode and 0b001_000_000 != 0, false)
                        outFile.setWritable(true, true)
                        outFile.setReadable(true, false)
                    }
                }
                if (++count % 400 == 0) onProgress.update(-1, "Unpacking… $count files")
                entry = tin.nextEntry
            }
        }
        // hard links: real link where possible, otherwise a plain copy so the
        // file still exists (a dangling `claude` is why this matters).
        for ((link, target) in hardlinks) {
            if (!target.exists()) continue
            link.parentFile?.mkdirs()
            link.delete()
            val linked = runCatching { android.system.Os.link(target.path, link.path) }.isSuccess
            if (!linked) runCatching {
                target.inputStream().use { i -> link.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                link.setExecutable(target.canExecute(), false)
            }
        }
        // symlinks last, so their targets already exist
        for ((link, target) in symlinks) {
            link.parentFile?.mkdirs()
            link.delete()
            runCatching { android.system.Os.symlink(target, link.path) }
        }
    }

    /** Things the guest expects that don't survive a plain tar unpack on Android. */
    private fun postExtractFixups() {
        // resolv.conf so DNS works inside the guest
        File(env.rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        // hosts
        File(env.rootfs, "etc/hosts").apply {
            if (!exists()) { parentFile?.mkdirs(); writeText("127.0.0.1 localhost\n127.0.0.1 larzos\n") }
        }
        // make sure the shells are executable
        listOf("usr/bin/larzsh", "bin/bash", "bin/sh", "usr/bin/env").forEach {
            File(env.rootfs, it).setExecutable(true, false)
        }

        // Bind-mount targets for LarzSession.prootArgv - proot needs these
        // to already exist in the guest before it can bind onto them.
        File(env.rootfs, "root/storage/shared").mkdirs()
        File(env.rootfs, "root/.larz-priv-token").apply {
            parentFile?.mkdirs()
            if (!exists()) writeText("")
        }
        // ClaudeVoiceBridge's fixed working directory, so `-c/--session-id`
        // conversation state stays consistent turn to turn.
        File(env.rootfs, "root/voice").mkdirs()

        // `larz-priv` - the guest-side client for LarzPrivService (shell-UID
        // commands via Shizuku). See that class for the wire protocol.
        File(env.rootfs, "usr/local/bin/larz-priv").apply {
            parentFile?.mkdirs()
            writeText(LARZ_PRIV_SCRIPT)
            setExecutable(true, false)
        }
    }

    companion object {
        private const val LARZ_PRIV_SCRIPT = """#!/bin/bash
# larz-priv - run a command at Android's "shell" UID, via the LarzOS app's
# Shizuku bridge (LarzPrivService, 127.0.0.1 only). Needs Shizuku connected
# once: LarzOS app > gear icon > System access > Connect Shizuku.
# Usage: larz-priv <command> [args...]
PORT=8199
TOKEN_FILE=/root/.larz-priv-token

if [ $# -eq 0 ]; then
  echo "usage: larz-priv <command> [args...]" >&2
  exit 2
fi
if [ ! -s "${'$'}TOKEN_FILE" ]; then
  echo "larz-priv: no bridge token yet - open the LarzOS app once first." >&2
  exit 127
fi

exec 3<>"/dev/tcp/127.0.0.1/${'$'}PORT" 2>/dev/null || {
  echo "larz-priv: bridge not reachable - open LarzOS > System access and connect Shizuku." >&2
  exit 127
}
printf '%s\n' "${'$'}(cat "${'$'}TOKEN_FILE")" >&3
printf '%s\n' "${'$'}*" >&3

code=1
while IFS= read -r line <&3; do
  case "${'$'}line" in
    __LARZ_EXIT__:*) code="${'$'}{line#__LARZ_EXIT__:}" ;;
    __LARZ_ERR__:*) echo "larz-priv: ${'$'}{line#__LARZ_ERR__:}" >&2 ;;
    *) printf '%s\n' "${'$'}line" ;;
  esac
done
exec 3<&- 3>&-
exit "${'$'}code"
"""
    }
}
