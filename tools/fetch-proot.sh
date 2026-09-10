#!/usr/bin/env bash
# fetch-proot.sh - stage proot + everything it dynamically links into
# app/src/main/jniLibs, so the APK ships them and Android unpacks them
# (executable) into nativeLibraryDir.
#
# Source: the Termux apt repo. Termux's proot links:
#     libtalloc.so.2        (package: libtalloc)
#     libandroid-shmem.so   (package: libandroid-shmem)
#     libc.so / liblog.so   (bionic - already on the device)
#
# Android only extracts and lets you exec files named exactly lib*.so, and its
# linker matches by that name - so a versioned soname like libtalloc.so.2 must
# be renamed AND patched (soname + proot's DT_NEEDED) or proot dies at launch
# with: CANNOT LINK EXECUTABLE ... library "libtalloc.so.2" not found.
#
#   tools/fetch-proot.sh            # both arches
#
# Needs: curl, binutils (ar), patchelf. Verify on a device before trusting -
# the loader ABI must match proot's build.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HERE/app/src/main/jniLibs"
BASEURL="https://packages.termux.dev/apt/termux-main/pool/main"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v patchelf >/dev/null || { echo "patchelf is required"; exit 1; }

# newest <pkg>_<ver>_<arch>.deb listed under a pool dir
latest_deb() {  # $1 = pool subpath (e.g. p/proot), $2 = pkg, $3 = termux arch
  curl -fsSL "$BASEURL/$1/" \
    | grep -oE "${2}_[0-9a-zA-Z.:~+-]+_$3\.deb" | sort -V | tail -n1
}

extract_deb() {  # $1 = pool subpath, $2 = pkg, $3 = termux arch  -> echoes usr/ dir
  local deb; deb="$(latest_deb "$1" "$2" "$3")"
  [ -n "$deb" ] || { echo "no $2 .deb for $3" >&2; return 1; }
  echo ">> $2: $deb" >&2
  local d="$WORK/$2-$3"
  mkdir -p "$d"
  curl -fsSL "$BASEURL/$1/$deb" -o "$d/p.deb"
  ( cd "$d" && ar x p.deb && tar -xf data.tar.* )
  echo "$d/data/data/com.termux/files/usr"
}

install_arch() {  # $1 = termux arch, $2 = android abi
  local abi="$2" out="$DEST/$2"
  mkdir -p "$out"

  local proot talloc shmem
  proot="$(extract_deb  p/proot            proot           "$1")"
  talloc="$(extract_deb libt/libtalloc     libtalloc       "$1")"
  shmem="$(extract_deb  liba/libandroid-shmem libandroid-shmem "$1")"

  install -m644 "$proot/bin/proot"            "$out/libproot.so"
  install -m644 "$proot/libexec/proot/loader" "$out/libproot-loader.so"
  [ -f "$proot/libexec/proot/loader32" ] && \
    install -m644 "$proot/libexec/proot/loader32" "$out/libproot-loader32.so" || true

  # libtalloc.so.2.x.x -> libtalloc.so, and fix its soname to match
  install -m644 "$(readlink -f "$talloc/lib/libtalloc.so.2")" "$out/libtalloc.so"
  patchelf --set-soname libtalloc.so "$out/libtalloc.so"

  install -m644 "$shmem/lib/libandroid-shmem.so" "$out/libandroid-shmem.so"

  # point proot at the renamed libtalloc and drop the Termux RUNPATH
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$out/libproot.so"
  patchelf --set-rpath '$ORIGIN' "$out/libproot.so"

  echo ">> $abi NEEDED after patch:"
  patchelf --print-needed "$out/libproot.so" | sed 's/^/     /'
}

install_arch aarch64 arm64-v8a
install_arch arm     armeabi-v7a || echo "(armeabi-v7a optional - skipped)"

echo "done - jniLibs:"
find "$DEST" -type f | sed "s#$HERE/##" | sort
