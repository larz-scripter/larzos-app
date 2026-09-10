#!/usr/bin/env bash
# fetch-proot.sh - place proot + its loader into app/src/main/jniLibs so the
# APK ships them and Android unpacks them (executable) into nativeLibraryDir.
#
# Source: the Termux apt repo's `proot` package (arm64 + arm). Termux renames
# nothing; we rename the binary -> libproot.so and the loaders -> lib*.so so
# they survive Android's "only lib*.so is extracted and exec-able" rule.
#
#   tools/fetch-proot.sh            # both arches
#
# Verify on a device before trusting: the loader ABI must match proot's build.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HERE/app/src/main/jniLibs"
POOL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Termux .deb filenames carry the version; grab whatever the pool index lists.
index="$(curl -fsSL "$POOL/")"
get_latest() {  # $1 = termux arch (aarch64 | arm)
  echo "$index" | grep -oE "proot_[0-9a-zA-Z.:~+-]+_$1\.deb" | sort -V | tail -n1
}

install_arch() {  # $1 = termux arch, $2 = android abi
  local deb; deb="$(get_latest "$1")"
  [ -n "$deb" ] || { echo "no proot .deb for $1"; return 1; }
  echo ">> $1: $deb"
  curl -fsSL "$POOL/$deb" -o "$WORK/p.deb"
  ( cd "$WORK" && ar x p.deb && mkdir -p x && tar -C x -xf data.tar.* )
  local base="$WORK/x/data/data/com.termux/files/usr"
  mkdir -p "$DEST/$2"
  install -m644 "$base/bin/proot"                 "$DEST/$2/libproot.so"
  install -m644 "$base/libexec/proot/loader"      "$DEST/$2/libproot-loader.so"
  [ -f "$base/libexec/proot/loader32" ] && \
    install -m644 "$base/libexec/proot/loader32"  "$DEST/$2/libproot-loader32.so" || true
  # proot links libtalloc dynamically in the Termux build
  for so in "$base"/lib/libtalloc.so*; do
    [ -e "$so" ] && install -m644 "$so" "$DEST/$2/libtalloc.so" || true
  done
  rm -rf "$WORK/x" "$WORK"/*.tar.* "$WORK/debian-binary" "$WORK/p.deb"
}

install_arch aarch64 arm64-v8a
install_arch arm     armeabi-v7a || echo "(arm optional)"

echo "done - jniLibs:"
find "$DEST" -type f | sed "s#$HERE/##"
