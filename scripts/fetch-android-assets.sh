#!/usr/bin/env bash
# Fetches and verifies every native component the Android build ships.
#
# The release build fails loudly when NOTICE.md and a pinned core version drift apart, so a
# half-updated pin gets caught rather than shipped.
set -euo pipefail

AETHER_VERSION="${AETHER_CORE_VERSION:-v2.1.0}"
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
# The optional fast TUN bridge ships as the publisher's prebuilt JNI libraries. Only the JNI
# library is copied: it links the engine statically and needs nothing else at runtime.
FAST_BRIDGE_VERSION="v1.0.0"
FAST_BRIDGE_SHA256="02eb23f6597411b9abcec2146014e9de54323cd1f77ea1baa3c22c6da5ea47d7"
# The Beacon engine is built here from source for the same reason the Stealth core is: its
# publisher ships a library, not a program, and that library cannot live in the same APK as the
# Global engine - both are gomobile builds and both carry libgojni.so. Building the engine as its
# own executable sidesteps that entirely. Building it per release also keeps its bootstrap config
# fresh, which matters: a stale one is what makes it stop working.
LANTERN_VERSION="v7.6.241"
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

# --- Fast TUN bridge ------------------------------------------------------------------------
bridge_zip="$temp/fast-bridge.zip"
echo "Downloading the fast TUN bridge $FAST_BRIDGE_VERSION"
curl -fsSL -o "$bridge_zip" \
  "https://github.com/Noisemux/zeptun/releases/download/$FAST_BRIDGE_VERSION/zeptun-android-jniLibs.zip"
actual="$(sha256 "$bridge_zip")"
if [ "$actual" != "$FAST_BRIDGE_SHA256" ]; then
  echo "Fast bridge archive hash mismatch. Expected $FAST_BRIDGE_SHA256, got $actual." >&2; exit 1
fi
unzip -oq "$bridge_zip" -d "$temp/fast-bridge"
for abi in "${abis[@]}"; do
  library="$temp/fast-bridge/jniLibs/$abi/libzeptun-jni.so"
  if [ ! -f "$library" ]; then echo "Fast bridge library is missing for $abi." >&2; exit 1; fi
  cp -f "$library" "$destination/$abi/libzeptun-jni.so"
  echo "Prepared verified fast bridge for $abi"
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

# --- the Beacon engine --------------------------------------------------------------------
#
# A small main() around the upstream library, written here rather than vendored, so there is no
# third-party source in this repository and the pin above is the only thing that decides what is
# built.

lantern_src="$temp/lanterncore"
mkdir -p "$lantern_src"
cat > "$lantern_src/main.go" <<'LANTERN_MAIN'
package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/getlantern/flashlight/v7"
	"github.com/getlantern/flashlight/v7/client"
	"github.com/getlantern/flashlight/v7/common"
	"github.com/getlantern/flashlight/v7/stats"
)

// The free-tier credentials the upstream Android SDK ships. They are what authenticates to the
// engine's own API; without them no configuration is fetched and nothing connects.
const (
	deviceID = "a34113"
	userID   = int64(381696446)
	token    = "K1qttSsZruN"
)

func main() {
	socksAddr := flag.String("socks", "127.0.0.1:1821", "address for the local SOCKS5 listener")
	httpAddr := flag.String("http", "127.0.0.1:0", "address for the local HTTP listener")
	configDir := flag.String("configdir", "", "directory the engine keeps its fetched config in")
	appName := flag.String("appname", "lantern", "application name reported upstream")
	appVersion := flag.String("appversion", "7.6.241", "application version reported upstream")
	proxyAll := flag.Bool("proxyall", true, "send every connection through the engine")
	flag.Parse()

	if *configDir == "" {
		fmt.Fprintln(os.Stderr, "configdir is required")
		os.Exit(2)
	}
	if err := os.MkdirAll(*configDir, 0o700); err != nil {
		fmt.Fprintf(os.Stderr, "cannot use configdir %v: %v\n", *configDir, err)
		os.Exit(2)
	}

	userConfig := common.NewUserConfigData(*appName, deviceID, userID, token, map[string]string{}, "")

	// 🚨 Must not be nil. The library only installs one of its own when VPN mode is on, and the
	// SOCKS5 handler calls it on every single connection - so a nil here is a crash on the first
	// request, which would read as the engine not working rather than as a missing argument.
	// Addresses pass through untouched: the tunnel in front already decided where traffic goes.
	reverseDNS := func(addr string) (string, error) { return addr, nil }

	runner, err := flashlight.New(
		*appName,
		*appVersion,
		time.Now().Format("2006-01-02"),
		*configDir,
		false,                        // VPN mode would try to bind 127.0.0.1:53
		func() bool { return false }, // disconnected
		func() bool { return *proxyAll },
		func() bool { return false }, // allowPrivateHosts
		func() bool { return true },  // autoReport
		map[string]interface{}{},
		userConfig,
		stats.NewTracker(),
		func() bool { return false }, // isPro
		func() string { return "" },  // lang, desktop only
		reverseDNS,
		func(category, action, label string) {},
	)
	if err != nil {
		fmt.Fprintf(os.Stderr, "the engine did not start: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("lantern core starting, config dir %v\n", *configDir)

	go runner.Run(*httpAddr, *socksAddr, nil, func(err error) {
		fmt.Fprintf(os.Stderr, "lantern error: %v\n", err)
	})

	go func() {
		if addr, ok := client.Socks5Addr(2 * time.Minute); ok {
			fmt.Printf("lantern core ready: SOCKS5 listening on %v\n", addr)
		}
	}()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
}
LANTERN_MAIN

# A replace directive in a dependency's go.mod is ignored - only the main module's count - so the
# library's three are repeated here, or the build resolves packages its source never targeted.
cat > "$lantern_src/go.mod" <<LANTERN_MOD
module lanterncore

go 1.24.2

replace github.com/keighl/mandrill => github.com/getlantern/mandrill v0.0.0-20221004112352-e7c04248adcb

replace github.com/eycorsican/go-tun2socks => github.com/getlantern/go-tun2socks v1.16.12-0.20201218023150-b68f09e5ae93

replace github.com/tetratelabs/wazero => github.com/refraction-networking/wazero v1.7.1-w

require github.com/getlantern/flashlight/v7 $LANTERN_VERSION
LANTERN_MOD

( cd "$lantern_src" && GOFLAGS=-mod=mod go mod tidy >/dev/null )

lantern_abis=("arm64-v8a" "armeabi-v7a" "x86_64")
lantern_goarch=("arm64" "arm" "amd64")
lantern_elf=("b700" "2800" "3e00")
lantern_triple=("aarch64-linux-android24" "armv7a-linux-androideabi24" "x86_64-linux-android24")

for i in "${!lantern_abis[@]}"; do
  abi="${lantern_abis[$i]}"
  mkdir -p "$destination/$abi"
  output="$destination/$abi/liblantern.so"
  rm -f "$output"

  # CGO is not optional: two of the library's dependencies have no pure-Go path at all, so the
  # build goes through the NDK's compiler rather than Go's own.
  #
  # -checklinkname=0 is required by a dependency pulled in ONLY on android, which reaches into the
  # standard library with //go:linkname to work around Android blocking the netlink calls Go's
  # resolver uses. Without it the link fails on net.zoneCache.
  #
  # -static-libstdc++ is what makes the result runnable on a phone: without it the binary NEEDs
  # libc++_shared.so, which is an NDK library and is not on a device, and the process would die at
  # exec before running a line of its own code.
  CGO_ENABLED=1 GOOS=android GOARCH="${lantern_goarch[$i]}" GOARM=7 \
    CC="$toolchain/${lantern_triple[$i]}-clang" \
    CXX="$toolchain/${lantern_triple[$i]}-clang++" \
    CGO_LDFLAGS="-static-libstdc++" \
    go build -C "$lantern_src" -o "$output" -trimpath -buildvcs=false -buildmode=pie \
    -ldflags="-s -w -buildid= -checklinkname=0" .

  if [ ! -s "$output" ]; then echo "The Beacon core for $abi is empty." >&2; exit 1; fi
  machine="$(elf_machine "$output")"
  if [ "$machine" != "${lantern_elf[$i]}" ]; then
    echo "Beacon core for $abi has ELF machine $machine, expected ${lantern_elf[$i]}." >&2; exit 1
  fi

  # Checked rather than trusted, because the one library that must not appear here is the one a
  # default build links against. A phone has no libc++_shared.so.
  for lib in $(readelf -d "$output" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
    case "$lib" in
      libc.so|libm.so|libdl.so|liblog.so) ;;
      *) echo "Beacon core for $abi links $lib, which Android does not ship." >&2; exit 1 ;;
    esac
  done
  echo "Built the Beacon core for $abi ($(stat -c%s "$output") bytes)"
done
