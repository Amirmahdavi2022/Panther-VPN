#!/usr/bin/env bash
# Same job as fetch-android-assets.ps1, for builders that aren't Windows.
#
# The PowerShell script is still the reference for what gets pinned; if you change a version
# there, change it here too. The release build fails loudly when NOTICE.md and the pinned
# Aether version drift apart, so a half-updated pin gets caught rather than shipped.
set -euo pipefail

AETHER_VERSION="${AETHER_CORE_VERSION:-v1.9.0}"
BYEDPI_VERSION="v0.17.3"
BYEDPI_COMMIT="7efde1b1296eaaa187b70e951894dde17527489c"
HEV_VERSION="2.16.0"
HEV_COMMIT="0a05221275a51a884d93328c55fc2fbc9e9b6974"
# The Global engine ships as an official prebuilt Android library. The publisher does not sign
# this asset, so the hash below is the only integrity check there is.
GLOBAL_CORE_VERSION="v2.0.40"
GLOBAL_CORE_SHA256="6e5a1402013e755b2e5e10a2715b18462fc06b6a8c1d610ffcd21f2fa80dfa1e"
# The Stealth engine's core is built here from source rather than downloaded. The published
# Android binaries cover arm64 and x86_64 only, and Panther also ships armeabi-v7a; building all
# three from one pinned commit beats mixing two provenances for one engine.
STEALTH_CORE_VERSION="v26.3.27"
STEALTH_CORE_COMMIT="d2758a023cd7f4174a5a5fa4ff66e487d4342ba0"
NDK_VERSION="27.2.12479018"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="$root/android/app/src/main/jniLibs"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'   # macOS builders have no sha256sum
  fi
}

resolve_ndk() {
  local candidates=()
  [ -n "${ANDROID_NDK_HOME:-}" ] && candidates+=("$ANDROID_NDK_HOME")
  [ -n "${ANDROID_NDK_ROOT:-}" ] && candidates+=("$ANDROID_NDK_ROOT")
  [ -n "${ANDROID_HOME:-}" ] && candidates+=("$ANDROID_HOME/ndk/$NDK_VERSION")
  [ -n "${ANDROID_SDK_ROOT:-}" ] && candidates+=("$ANDROID_SDK_ROOT/ndk/$NDK_VERSION")
  candidates+=("$HOME/Library/Android/sdk/ndk/$NDK_VERSION" "$HOME/Android/Sdk/ndk/$NDK_VERSION")
  for c in "${candidates[@]}"; do
    if [ -d "$c" ]; then printf '%s' "$c"; return 0; fi
  done
  echo "Android NDK $NDK_VERSION is required. Install it with: sdkmanager 'ndk;$NDK_VERSION'" >&2
  return 1
}

mkdir -p "$destination"

# --- Aether core (Turbo) -------------------------------------------------------------------
abis=("armeabi-v7a" "arm64-v8a" "x86_64")
archives=("aether-android-armv7.tar.gz" "aether-android-arm64.tar.gz" "aether-android-x86_64.tar.gz")

for i in "${!abis[@]}"; do
  abi="${abis[$i]}"
  archive="${archives[$i]}"
  base="https://github.com/CluvexStudio/Aether/releases/download/$AETHER_VERSION"
  mkdir -p "$destination/$abi"

  curl -fsSL -o "$temp/$archive" "$base/$archive"
  curl -fsSL -o "$temp/$archive.sha256" "$base/$archive.sha256"

  expected="$(awk '{print $1}' "$temp/$archive.sha256" | tr -d '\r' | tr 'A-F' 'a-f')"
  if ! [[ "$expected" =~ ^[a-f0-9]{64}$ ]]; then
    echo "Invalid Aether checksum file for $abi." >&2; exit 1
  fi
  actual="$(sha256 "$temp/$archive")"
  if [ "$actual" != "$expected" ]; then
    echo "Aether checksum mismatch for $abi. Expected $expected, got $actual." >&2; exit 1
  fi

  expanded="$temp/aether-$abi"
  mkdir -p "$expanded"
  tar -xzf "$temp/$archive" -C "$expanded"
  core="$(find "$expanded" -type f -name aether | head -n 1)"
  if [ -z "$core" ]; then echo "Aether executable was not found in $archive." >&2; exit 1; fi
  cp -f "$core" "$destination/$abi/libaether.so"
  echo "Prepared verified Aether core for $abi"
done

# --- Global engine library ------------------------------------------------------------------
libs_dir="$root/android/app/libs"
mkdir -p "$libs_dir"
global_zip="$temp/global-core.zip"
echo "Downloading the Global engine library $GLOBAL_CORE_VERSION"
curl -fsSL -o "$global_zip" \
  "https://github.com/Psiphon-Labs/psiphon-tunnel-core/releases/download/$GLOBAL_CORE_VERSION/Psiphon-Android-Library.zip"
unzip -oq "$global_zip" -d "$temp/global-core"
extracted="$(find "$temp/global-core" -type f -name 'ca.psiphon.aar' | head -n 1)"
if [ -z "$extracted" ]; then echo "ca.psiphon.aar was not in the published archive." >&2; exit 1; fi
actual="$(sha256 "$extracted")"
if [ "$actual" != "$GLOBAL_CORE_SHA256" ]; then
  echo "Global engine library hash mismatch. Expected $GLOBAL_CORE_SHA256, got $actual." >&2; exit 1
fi
cp -f "$extracted" "$libs_dir/ca.psiphon.aar"
echo "Global engine library verified and staged"

# --- Stealth engine core -------------------------------------------------------------------
# 🚨 This is deliberately NOT the published Android library. That library and the Global engine's
# library are both gomobile builds carrying the same go/Seq classes and the same libgojni.so, so
# an APK can only ever load one of them - the native merge step fails, and forcing it through
# would wire one engine to the other's native code. Shipping the core as its own executable, the
# way the Turbo core already does, leaves the two engines nothing to collide over.
if ! command -v go >/dev/null 2>&1; then
  echo "Go is required to build the Stealth engine core. Install Go 1.26 or newer." >&2
  exit 1
fi
echo "Building the Stealth engine core $STEALTH_CORE_VERSION with $(go version)"
stealth_src="$temp/xray-core"
git clone --quiet --branch "$STEALTH_CORE_VERSION" --depth 1 \
  https://github.com/XTLS/Xray-core.git "$stealth_src"
stealth_head="$(git -C "$stealth_src" rev-parse HEAD)"
if [ "$stealth_head" != "$STEALTH_CORE_COMMIT" ]; then
  echo "Stealth core commit mismatch. Expected $STEALTH_CORE_COMMIT, got $stealth_head." >&2
  exit 1
fi

# Reads the ELF machine field, so a binary built for the wrong architecture is caught here rather
# than as an app that starts and instantly dies on one class of phone.
elf_machine() {
  od -An -tx1 -j18 -N2 "$1" 2>/dev/null | tr -d ' \n'
}

stealth_abis=("arm64-v8a" "armeabi-v7a" "x86_64")
stealth_goarch=("arm64" "arm" "amd64")
stealth_elf=("b700" "2800" "3e00")

for i in "${!stealth_abis[@]}"; do
  abi="${stealth_abis[$i]}"
  mkdir -p "$destination/$abi"
  output="$destination/$abi/libxray.so"
  rm -f "$output"

  # The core's own release workflow builds android/arm64 and android/amd64 and has no 32-bit ARM
  # target at all. android is tried first for consistency; linux is the fallback and is equally
  # correct here, because CGO is off so the binary is static and Android is Linux. The usual
  # reason to care about that difference is name resolution, and the config hands the core its
  # own resolver precisely so it never asks the platform.
  built=""
  for goos in android linux; do
    if CGO_ENABLED=0 GOOS="$goos" GOARCH="${stealth_goarch[$i]}" GOARM=7 \
        go build -C "$stealth_src" -o "$output" -trimpath -buildvcs=false \
        -gcflags="all=-l=4" -ldflags="-s -w -buildid=" ./main 2>/dev/null; then
      built="$goos"
      break
    fi
  done
  if [ -z "$built" ]; then echo "Could not build the Stealth core for $abi." >&2; exit 1; fi
  if [ ! -s "$output" ]; then echo "The Stealth core for $abi is empty." >&2; exit 1; fi

  machine="$(elf_machine "$output")"
  if [ "$machine" != "${stealth_elf[$i]}" ]; then
    echo "Stealth core for $abi has ELF machine $machine, expected ${stealth_elf[$i]}." >&2
    exit 1
  fi
  chmod 0755 "$output"
  size_mb=$(( $(stat -c%s "$output" 2>/dev/null || stat -f%z "$output") / 1048576 ))
  echo "Built the Stealth core for $abi ($built/${stealth_goarch[$i]}, ${size_mb} MB)"
done

# --- HEV TUN->SOCKS bridge ------------------------------------------------------------------
hev_source="$temp/hev-socks5-tunnel"
git clone --quiet --branch "$HEV_VERSION" --depth 1 --recurse-submodules \
  https://github.com/heiher/hev-socks5-tunnel.git "$hev_source"
checked_out="$(git -C "$hev_source" rev-parse HEAD)"
if [ "$checked_out" != "$HEV_COMMIT" ]; then
  echo "Unexpected HEV commit $checked_out; expected $HEV_COMMIT." >&2; exit 1
fi
if git -C "$hev_source" submodule status --recursive | grep -q '^[+-]'; then
  echo "HEV submodules do not match the pinned release." >&2; exit 1
fi

ndk_root="$(resolve_ndk)"
"$ndk_root/ndk-build" \
  "NDK_PROJECT_PATH=$hev_source" \
  "APP_BUILD_SCRIPT=$hev_source/Android.mk" \
  "NDK_APPLICATION_MK=$hev_source/Application.mk" \
  'APP_ABI=armeabi-v7a arm64-v8a x86_64' \
  'APP_CFLAGS=-O3 -DPKGNAME=hev/htproxy' \
  "NDK_LIBS_OUT=$temp/hev-libs" \
  "NDK_OUT=$temp/hev-obj" \
  -j 4

for abi in "${abis[@]}"; do
  library="$temp/hev-libs/$abi/libhev-socks5-tunnel.so"
  if [ ! -f "$library" ]; then echo "HEV JNI library is missing for $abi." >&2; exit 1; fi
  cp -f "$library" "$destination/$abi/libhev-socks5-tunnel.so"
  echo "Built HEV JNI bridge for $abi"
done

# --- the local shaping proxy used by the spoof dial route -------------------------------------
#
# Built from source, pinned by tag AND by the commit that tag resolves to. A tag can be moved, so
# pinning the version alone would let the source change under a number that looks unchanged - and
# this is a binary that shapes the user's traffic, which is the last thing to take on trust.
#
# It is a program, not a library, and ships as libbyedpi.so because the installer's native library
# directory is the only place an app targeting API 29+ may execute a file it shipped.

byedpi_source="$temp/byedpi"
git clone --quiet --branch "$BYEDPI_VERSION" --depth 1 \
  https://github.com/hufrea/byedpi.git "$byedpi_source"
byedpi_head="$(git -C "$byedpi_source" rev-parse HEAD)"
if [ "$byedpi_head" != "$BYEDPI_COMMIT" ]; then
  echo "Unexpected byedpi commit $byedpi_head; expected $BYEDPI_COMMIT." >&2; exit 1
fi

toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$toolchain" ]; then
  toolchain="$ndk_root/toolchains/llvm/prebuilt/darwin-x86_64/bin"
fi
if [ ! -d "$toolchain" ]; then
  echo "No usable NDK toolchain under $ndk_root." >&2; exit 1
fi

byedpi_sources=""
for f in "$byedpi_source"/*.c; do
  case "$(basename "$f")" in
    win_service.c) continue ;;   # the Windows service entry point; there is no such thing here
  esac
  byedpi_sources="$byedpi_sources $f"
done

for abi in "${abis[@]}"; do
  case "$abi" in
    arm64-v8a)   triple="aarch64-linux-android" ;;
    armeabi-v7a) triple="armv7a-linux-androideabi" ;;
    x86_64)      triple="x86_64-linux-android" ;;
    *) echo "No toolchain triple for $abi." >&2; exit 1 ;;
  esac
  # -D_DEFAULT_SOURCE comes from byedpi's own Makefile and is not optional: without it c99 hides
  # the POSIX declarations this source needs and the build fails in a way that reads like a broken
  # checkout rather than a missing flag.
  # shellcheck disable=SC2086
  "$toolchain/${triple}24-clang" \
    -D_DEFAULT_SOURCE -std=c99 -O2 -w \
    -I"$byedpi_source" \
    -o "$destination/$abi/libbyedpi.so" \
    $byedpi_sources
  size="$(stat -c%s "$destination/$abi/libbyedpi.so" 2>/dev/null || stat -f%z "$destination/$abi/libbyedpi.so")"
  if [ "$size" -lt 20000 ]; then
    echo "byedpi for $abi is only $size bytes; something did not link." >&2; exit 1
  fi
  echo "Built the shaping proxy for $abi ($size bytes)"
done
