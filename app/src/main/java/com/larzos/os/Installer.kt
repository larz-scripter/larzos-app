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
        if (env.rootfs.exists()) env.rootfs.deleteRecursively()
        env.rootfs.mkdirs()
        extractTarGz(tmpTar, env.rootfs, onProgress)
        tmpTar.delete()

        postExtractFixups()
        env.installedMarker.writeText(System.currentTimeMillis().toString())
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
                    entry.isLink -> {  // hard link
                        val target = File(into, entry.linkName.removePrefix("./"))
                        if (target.exists()) runCatching { android.system.Os.link(target.path, outFile.path) }
                    }
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
    }
}
