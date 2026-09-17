# LarzOS — the phone app

Run **LarzOS** in a proot userland on Android, no root — the way UserLAnd and
Termux run Linux, but a single-purpose app that is LarzOS end to end: LarzOS
name, LarzOS icon, and it drops you straight into `larzsh` with `larz` and
`larz code` (Claude Code) ready.

Nothing on any screen says Debian, Termux, proot or UserLAnd. Linux is the
executor; the user sees LarzOS.

## How it works

1. **First launch** downloads the LarzOS arm64 root filesystem
   (`larzos-rootfs-arm64.tar.gz`, published by
   [`larz-scripter/larzos-linux`](https://github.com/larz-scripter/larzos-linux)
   releases) and unpacks it into the app's private storage.
2. **`proot`** (bundled per-ABI as `jniLibs/*/libproot.so`) fakes root and the
   bind mounts a Debian rootfs needs, with no privileges.
3. A **terminal** (Termux's `terminal-view` / `terminal-emulator`, vendored as
   a submodule) is attached to `proot … /usr/bin/larzsh -l`.
4. That's the app. `larz`, `larz-system`, `larz code`, `apt` — all work; the
   guest just has no systemd (no PID 1 in a proot), same as the Termux edition.
5. The 🖥 **Desktop** button installs and starts `larz-gui` (larzos-linux's
   Openbox/tint2 graphical desktop over VNC) inside the guest, then hands off
   to a `vnc://`-capable viewer app (e.g. [AVNC](https://f-droid.org/packages/com.gaurav.avnc/))
   to actually render it.

## Build

```sh
git clone --recurse-submodules https://github.com/larz-scripter/larzos-app
cd larzos-app
bash tools/fetch-proot.sh        # pull proot + loader into jniLibs/
./gradlew assembleDebug
```

CI (`.github/workflows/build.yml`) does the same on every push and attaches the
debug APK as an artifact; a `v*` tag also attaches it to a GitHub Release.

See **[docs/BUILD.md](docs/BUILD.md)** for status, the pieces that still need a
real device to nail down, and the next steps.

## Licence

GPLv3 (`LICENSE`) — it links Termux's GPLv3 terminal widgets.
