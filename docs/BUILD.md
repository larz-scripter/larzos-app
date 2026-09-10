# Build notes & status

**Status: scaffold.** The repo is structured and every piece is written, but
nothing has run on a device yet. This file lists what's done and what the next
sessions need to close.

## Done

- Modern Android project — AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 26,
  Kotlin DSL, gradle 8.11.1 wrapper committed.
- LarzOS branding — app id `com.larzos.os`, name "LarzOS", cyan-λ adaptive
  icon, dark `#0B1020` / cyan `#22D3EE` theme. No third-party names anywhere.
- `LarzEnv` — all on-device paths (rootfs, proot tmp, l2s, nativeLibraryDir).
- `Installer` — downloads `larzos-rootfs-arm64.tar.gz`, extracts tar.gz with
  Apache Commons Compress (symlinks + modes + hardlinks), writes
  `/etc/resolv.conf` + `/etc/hosts`, marks installed.
- `LarzSession` — builds the `proot` argv (bind mounts, `-0`, `--link2symlink`,
  env `-i`) and the proot-process env (`PROOT_LOADER`, `PROOT_TMP_DIR`,
  `PROOT_L2S_DIR`, Android `*_ROOT`/`BOOTCLASSPATH`).
- `TerminalActivity` — `TerminalView` + `TerminalSession` wired to the proot
  argv; `LarzSessionService` foreground service.
- `tools/fetch-proot.sh` — pulls `proot` + `loader` from the Termux apt pool
  into `app/src/main/jniLibs/<abi>/lib*.so`.
- CI builds a debug APK and uploads it.

## Needs a real device / CI iteration

1. **Termux terminal API surface.** `TerminalSessionClient` and
   `TerminalViewClient` method signatures change between Termux versions. Pin
   the submodule (`cd termux-terminal && git checkout <tag>`), then let CI
   compile and fix the `override` mismatches it reports. The `TerminalSession`
   constructor's 5th arg is `transcriptRows` (Int) — confirm against the pinned
   source.
2. **proot binaries.** `fetch-proot.sh` guesses the Termux pool layout and the
   `libexec/proot/loader` path. Confirm the .deb contents; confirm the loader
   ABI matches proot (they must come from the same build). If Termux's proot
   needs `libtalloc.so` at runtime, it's copied — check `LD_LIBRARY_PATH`
   reaches it (it's set to `nativeDir`).
3. **proot args.** The bind-mount set and flags are the proot-distro / UserLAnd
   baseline. On a device, iterate: `apt-get update` working (DNS + `/etc/resolv.conf`),
   `larz-system plan`, `larz code`. Add `-b /storage`/`-b /sdcard` for file
   access, a persistent `/root` bind so a reinstall keeps the user's files.
4. **arm64 rootfs asset.** The `larzos-linux` `release.yml` now has a
   `rootfs-arm64` job — it must run green on a tag so
   `.../releases/latest/download/larzos-rootfs-arm64.tar.gz` resolves. Until a
   tag ships, point `ROOTFS_BASE_URL` at a manual upload.
5. **Bootstrap size.** The rootfs is ~90 MB with Node + Claude Code baked in.
   Consider a slim rootfs + first-run `apt install larz-claude-code` instead.
6. **NDK / packaging.** `jniLibs.useLegacyPackaging = true` + `extractNativeLibs`
   so proot lands as a real file. Verify on API 26 and API 35.

## Next steps, in order

1. Pin the termux submodule to a release tag; get CI to a green `assembleDebug`.
2. On a device: install, watch the rootfs download + unpack, get a shell prompt.
3. Fix proot args until `larz code` runs a Claude Code session.
4. Add: storage bind mounts, persistent `/root`, a "reset LarzOS" action,
   font-size controls, an extra-keys row (Esc/Tab/Ctrl/arrows).
5. Later: a VNC/desktop mode (bundle a lightweight VNC client + a guest Xvnc).
