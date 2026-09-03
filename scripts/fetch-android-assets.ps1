$ErrorActionPreference = "Stop"

$aetherVersion = if ($env:AETHER_CORE_VERSION) { $env:AETHER_CORE_VERSION } else { "v1.8.0" }
$hevVersion = "2.16.0"
# The Global engine ships as an official prebuilt Android library, so nothing here is
# built from source. Pinned by hash: the publisher does not sign this asset.
$globalCoreVersion = "v2.0.40"
$globalCoreSha256 = "6e5a1402013e755b2e5e10a2715b18462fc06b6a8c1d610ffcd21f2fa80dfa1e"
$stealthCoreVersion = "v26.3.27"
$stealthCoreCommit = "d2758a023cd7f4174a5a5fa4ff66e487d4342ba0"
$hevCommit = "0a05221275a51a884d93328c55fc2fbc9e9b6974"
$ndkVersion = "27.2.12479018"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$destination = Join-Path $root "android/app/src/main/jniLibs"
$nativeBase = if ($env:PUBLIC) { Join-Path $env:PUBLIC "FirsthamAetherGuiNative" } else { Join-Path ([System.IO.Path]::GetTempPath()) "FirsthamAetherGuiNative" }
$temp = Join-Path $nativeBase ([guid]::NewGuid().ToString("N"))
$targets = @(
    @{ Abi = "armeabi-v7a"; Archive = "aether-android-armv7.tar.gz" },
    @{ Abi = "arm64-v8a"; Archive = "aether-android-arm64.tar.gz" },
    @{ Abi = "x86_64"; Archive = "aether-android-x86_64.tar.gz" }
)

function Get-Sha256([string]$Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha256.ComputeHash($stream))).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $stream.Dispose()
        $sha256.Dispose()
    }
}

function Resolve-NdkRoot {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    $possible = @()
    if ($env:ANDROID_NDK_HOME) { $possible += $env:ANDROID_NDK_HOME }
    if ($env:ANDROID_NDK_ROOT) { $possible += $env:ANDROID_NDK_ROOT }
    if ($env:ANDROID_HOME) { $possible += (Join-Path $env:ANDROID_HOME "ndk/$ndkVersion") }
    if ($env:ANDROID_SDK_ROOT) { $possible += (Join-Path $env:ANDROID_SDK_ROOT "ndk/$ndkVersion") }
    if ($env:LOCALAPPDATA) { $possible += (Join-Path $env:LOCALAPPDATA "Android/Sdk/ndk/$ndkVersion") }
    if ($localAppData) { $possible += (Join-Path $localAppData "Android/Sdk/ndk/$ndkVersion") }
    $candidates = @($possible | Where-Object { Test-Path -LiteralPath $_ -PathType Container })
    if (-not $candidates) {
        throw "Android NDK $ndkVersion is required. Install it with sdkmanager 'ndk;$ndkVersion'."
    }
    $resolved = (Resolve-Path -LiteralPath $candidates[0]).Path
    if ($IsWindows -or $PSVersionTable.PSEdition -eq "Desktop") {
        $short = (& cmd.exe /d /c "for %I in (`"$resolved`") do @echo %~sI").Trim()
        if ($short -and (Test-Path -LiteralPath $short -PathType Container)) { return $short }
    }
    return $resolved
}

function Expand-WindowsSymlinkPlaceholders([string]$SourceRoot) {
    if (-not $IsWindows -and $PSVersionTable.PSEdition -ne "Desktop") { return }
    $links = @()
    Get-ChildItem -LiteralPath $SourceRoot -Recurse -File | Where-Object Length -lt 260 | ForEach-Object {
        $raw = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue
        if ($null -eq $raw) { return }
        $targetText = $raw.Trim()
        if ($targetText -notmatch '^\.\.?[/\\][^\r\n]+$') { return }
        $target = Join-Path $_.DirectoryName ($targetText -replace '/', '\')
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $links += [pscustomobject]@{ Link = $_.FullName; Target = (Resolve-Path -LiteralPath $target).Path }
        }
    }
    foreach ($link in $links) {
        Copy-Item -LiteralPath $link.Target -Destination $link.Link -Force
    }
}

try {
    New-Item -ItemType Directory -Force $temp | Out-Null
    New-Item -ItemType Directory -Force $destination | Out-Null

    foreach ($target in $targets) {
        $abiDir = Join-Path $destination $target.Abi
        New-Item -ItemType Directory -Force $abiDir | Out-Null
        $archive = Join-Path $temp $target.Archive
        $checksum = "$archive.sha256"
        $base = "https://github.com/CluvexStudio/Aether/releases/download/$aetherVersion"
        Invoke-WebRequest -UseBasicParsing "$base/$($target.Archive)" -OutFile $archive
        Invoke-WebRequest -UseBasicParsing "$base/$($target.Archive).sha256" -OutFile $checksum
        $expected = ((Get-Content -LiteralPath $checksum -Raw).Trim() -split "\s+")[0]
        if ($expected -notmatch '^[a-fA-F0-9]{64}$') { throw "Invalid Aether checksum for $($target.Abi)." }
        $actual = Get-Sha256 $archive
        if ($actual -ne $expected.ToLowerInvariant()) { throw "Aether Android checksum mismatch for $($target.Abi)." }

        $expanded = Join-Path $temp "aether-$($target.Abi)"
        New-Item -ItemType Directory $expanded | Out-Null
        Copy-Item -LiteralPath $archive -Destination (Join-Path $expanded "core.tar.gz")
        Push-Location $expanded
        try {
            & tar -xzf "core.tar.gz"
            if ($LASTEXITCODE -ne 0) { throw "Could not extract $($target.Archive)." }
        }
        finally { Pop-Location }
        $core = Get-ChildItem -LiteralPath $expanded -Recurse -File -Filter "aether" | Select-Object -First 1
        if (-not $core) { throw "Aether executable was not found in $($target.Archive)." }
        Copy-Item -LiteralPath $core.FullName -Destination (Join-Path $abiDir "libaether.so") -Force
        Write-Host "Prepared verified Aether core for $($target.Abi)"
    }

    $hevSource = Join-Path $temp "hev-socks5-tunnel"
    & git clone --quiet --branch $hevVersion --depth 1 --recurse-submodules https://github.com/heiher/hev-socks5-tunnel.git $hevSource
    if ($LASTEXITCODE -ne 0) { throw "Could not fetch HEV Socks5 Tunnel $hevVersion." }
    $checkedOutCommit = (& git -C $hevSource rev-parse HEAD).Trim()
    if ($checkedOutCommit -ne $hevCommit) { throw "Unexpected HEV commit $checkedOutCommit; expected $hevCommit." }
    $submoduleState = & git -C $hevSource submodule status --recursive
    if ($LASTEXITCODE -ne 0 -or ($submoduleState | Where-Object { $_ -match '^[+-]' })) {
        throw "HEV submodules do not match the pinned release."
    }
    Expand-WindowsSymlinkPlaceholders $hevSource

    # --- Global engine library ---------------------------------------------------------------
    # An official prebuilt AAR, so there is no Go toolchain and no gomobile step here. It carries
    # its own libgojni.so for every ABI; the app's abiFilters drop the x86 one we do not ship.
    $libsDir = Join-Path $root "android/app/libs"
    New-Item -ItemType Directory -Force $libsDir | Out-Null
    $globalAar = Join-Path $libsDir "ca.psiphon.aar"
    $globalZip = Join-Path $temp "global-core.zip"
    $globalUrl = "https://github.com/Psiphon-Labs/psiphon-tunnel-core/releases/download/$globalCoreVersion/Psiphon-Android-Library.zip"
    Write-Host "Downloading the Global engine library $globalCoreVersion"
    Invoke-WebRequest -UseBasicParsing $globalUrl -OutFile $globalZip
    $globalExtract = Join-Path $temp "global-core"
    Expand-Archive -LiteralPath $globalZip -DestinationPath $globalExtract -Force
    $extracted = Get-ChildItem -LiteralPath $globalExtract -Recurse -Filter "ca.psiphon.aar" | Select-Object -First 1
    if (-not $extracted) { throw "ca.psiphon.aar was not in the published archive." }
    # The publisher does not sign this asset, so the pin above is the only integrity check there
    # is. Fail rather than build against something we did not review.
    $actual = Get-Sha256 $extracted.FullName
    if ($actual -ne $globalCoreSha256) {
        throw "Global engine library hash mismatch. Expected $globalCoreSha256, got $actual."
    }
    Copy-Item -LiteralPath $extracted.FullName -Destination $globalAar -Force
    Write-Host "Global engine library verified and staged ($([math]::Round((Get-Item $globalAar).Length / 1MB, 1)) MB)"

    # --- Stealth engine core ---------------------------------------------------------------
    # Built from source, NOT from the published Android library. That library and the Global
    # engine's library are both gomobile builds carrying the same go/Seq classes and the same
    # libgojni.so, so an APK can only ever load one of the two. The core ships as its own
    # executable instead, the way the Turbo core already does.
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # a native command writing to stderr must not abort us
    if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
        throw "Go is required to build the Stealth engine core. Install Go 1.26 or newer."
    }
    Write-Host "Building the Stealth engine core $stealthCoreVersion with $(go version)"
    $stealthSrc = Join-Path $temp "xray-core"
    git clone --quiet --branch $stealthCoreVersion --depth 1 https://github.com/XTLS/Xray-core.git $stealthSrc
    $stealthHead = (git -C $stealthSrc rev-parse HEAD).Trim()
    if ($stealthHead -ne $stealthCoreCommit) {
        throw "Stealth core commit mismatch. Expected $stealthCoreCommit, got $stealthHead."
    }

    $stealthTargets = @(
        @{ Abi = 'arm64-v8a';   Arch = 'arm64'; Elf = 0xB7 },
        @{ Abi = 'armeabi-v7a'; Arch = 'arm';   Elf = 0x28 },
        @{ Abi = 'x86_64';      Arch = 'amd64'; Elf = 0x3E }
    )
    foreach ($target in $stealthTargets) {
        $abiDir = Join-Path $destination $target.Abi
        New-Item -ItemType Directory -Force $abiDir | Out-Null
        $output = Join-Path $abiDir "libxray.so"
        Remove-Item -Force -ErrorAction SilentlyContinue $output

        # android first for consistency with the core's own release build; linux is an equally
        # correct fallback for 32-bit ARM, which that build has no target for. CGO is off, so the
        # binary is static either way, and the config gives the core its own resolver so it never
        # depends on the platform for name resolution.
        $built = $null
        foreach ($goos in @('android','linux')) {
            $env:CGO_ENABLED = '0'; $env:GOOS = $goos; $env:GOARCH = $target.Arch; $env:GOARM = '7'
            # Do NOT swallow stderr here. When both targets fail this output is the only
            # evidence of why, and PowerShell 5.1 can turn a native command's stderr into a
            # terminating error, which would hide the real message behind a generic one.
            $log = & go build -C $stealthSrc -o $output -trimpath -buildvcs=false -gcflags="all=-l=4" -ldflags="-s -w -buildid=" ./main 2>&1
            if ($LASTEXITCODE -eq 0 -and (Test-Path $output)) { $built = $goos; break }
            Write-Host "  $goos/$($target.Arch) did not build: $($log -join ' ')"
        }
        Remove-Item Env:CGO_ENABLED, Env:GOOS, Env:GOARCH, Env:GOARM -ErrorAction SilentlyContinue
        if (-not $built) { throw "Could not build the Stealth core for $($target.Abi)." }
        if ((Get-Item $output).Length -eq 0) { throw "The Stealth core for $($target.Abi) is empty." }

        # The ELF machine field, so a binary for the wrong architecture is caught here rather than
        # as an app that starts and instantly dies on one class of phone.
        $head = [System.IO.File]::ReadAllBytes($output)[0..19]
        if ($head[18] -ne $target.Elf) {
            throw "Stealth core for $($target.Abi) has ELF machine $($head[18]), expected $($target.Elf)."
        }
        Write-Host "Built the Stealth core for $($target.Abi) ($built/$($target.Arch), $([math]::Round((Get-Item $output).Length / 1MB, 1)) MB)"
    }
    $ErrorActionPreference = $previousErrorAction

    $ndkRoot = Resolve-NdkRoot
    $ndkBuild = Join-Path $ndkRoot $(if ($IsWindows -or $PSVersionTable.PSEdition -eq "Desktop") { "ndk-build.cmd" } else { "ndk-build" })
    $libsOut = Join-Path $temp "hev-libs"
    $objOut = Join-Path $temp "hev-obj"
    & $ndkBuild "NDK_PROJECT_PATH=$hevSource" "APP_BUILD_SCRIPT=$(Join-Path $hevSource 'Android.mk')" "NDK_APPLICATION_MK=$(Join-Path $hevSource 'Application.mk')" 'APP_ABI=armeabi-v7a arm64-v8a x86_64' 'APP_CFLAGS=-O3 -DPKGNAME=hev/htproxy' "NDK_LIBS_OUT=$libsOut" "NDK_OUT=$objOut" -j 4
    if ($LASTEXITCODE -ne 0) { throw "HEV JNI build failed." }

    foreach ($target in $targets) {
        $library = Join-Path $libsOut "$($target.Abi)/libhev-socks5-tunnel.so"
        if (-not (Test-Path -LiteralPath $library -PathType Leaf)) { throw "HEV JNI library is missing for $($target.Abi)." }
        Copy-Item -LiteralPath $library -Destination (Join-Path $destination "$($target.Abi)/libhev-socks5-tunnel.so") -Force
        Write-Host "Built HEV JNI bridge for $($target.Abi)"
    }
}
finally {
    if (Test-Path -LiteralPath $temp) {
        $resolvedTemp = [System.IO.Path]::GetFullPath($temp)
        $resolvedBase = [System.IO.Path]::GetFullPath($nativeBase)
        if (-not $resolvedTemp.StartsWith($resolvedBase, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean an unexpected native build path: $resolvedTemp"
        }
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
