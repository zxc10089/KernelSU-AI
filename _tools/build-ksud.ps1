# Build the ksud Rust daemon (libksud.so) for this fork.
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI (GBK here).
#
# WHY THIS SCRIPT EXISTS
#   ksud bakes its manager package name in at COMPILE time:
#     userspace/ksud/src/defs.rs  ->  pub const DEFAULT_PACKAGE_NAME: &str = env!("KSU_PACKAGE_NAME");
#     userspace/ksud/build.rs     ->  falls back to "me.weishu.kernelsu" when the var is unset.
#   With the upstream fallback the daemon force-stops and `am start`s the OFFICIAL manager
#   (late_load.rs) and writes its boot backup under the OFFICIAL data dir (boot_patch.rs),
#   so a late-load install crowns me.weishu.kernelsu instead of this fork. The upstream repo
#   sets KSU_PACKAGE_NAME nowhere (userspace/ksud/ has no Makefile), so it MUST be set here.
#   Keep this value in sync with KSU_PACKAGE_NAME in manager/gradle.properties.
#
# GIT DEPENDENCIES -- resolved, do not re-diagnose
#   ksud depends on crates that exist ONLY as git dependencies:
#     android-bootimg (5ec1cff)     kernlog (kstep)
#     java-properties (KernelSU2)   rustix 0.38.34 (KernelSU2)
#     adb_client (KernelSU2)        prop-rs / prop-rs-android (KernelSU2)
#   CAREFUL: the KernelSU2 org was RENAMED from the old name "Kernel-SU". The old name still shows up
#   in an org listing, but it serves NO git content -- github.com/Kernel-SU/<r>.git/info/refs answers
#   401 "Repository not found" and raw/codeload/jsDelivr answer 404. That dead name is why Cargo could
#   not resolve the workspace before. Cargo.toml and Cargo.lock must both say KernelSU2, and every
#   revision they pin exists there (verified against the live API: adb_client d97a9664,
#   java-properties 42a4aa94, ksu_props 6f572310, android-bootimg 150425b0, rustix 4a53fbc7).
#   A successful run needs no other change: this script already sets the fork identity.
param(
    [string]$PackageName = 'me.weishu.kernelsu.hide',
    # Active worktree name under $root (see build-manager.ps1); defaults to this repo's directory.
    [string]$WorkTree = '',
    # Workspace root that contains the repository and the .toolchain directory.
    [string]$Root = '',
    # Android API level cargo-ndk links the daemon against.
    # cargo-ndk DEFAULTS TO 21, and bionic only exports __system_property_read_callback -- which
    # ksud calls from getprop (userspace/ksud/src/utils.rs:36,143) -- from API 26. With the default
    # the build gets all the way to the final ksud link and dies there with:
    #   ld.lld: error: undefined symbol: __system_property_read_callback
    #   referenced by ...ksud-*.o:(ksud::utils::getprop)
    # Keep this equal to manager/build.gradle.kts androidMinSdkVersion (31): the daemon only ever
    # runs on a device that can install the manager, so the app's minSdk is the correct floor.
    [int]$Platform = 31,
    # R3 static checks (docs/DEVELOPMENT.md 8.4). These switches are mutually combinable and
    # short-circuit the build: nothing is linked and nothing is published, so they need neither the
    # dlltool shim nor a working NDK linker. They DO still need cargo-ndk for clippy/check (see the
    # R3 branch below). The script exits with the checks' own exit code (0 only when every
    # requested check passed), which makes R3 a repeatable command instead of a one-off.
    [switch]$Fmt,
    [switch]$Clippy,
    [switch]$Check,
    [switch]$NoVerify
)
$ErrorActionPreference = 'Stop'

$root = if ($Root) { $Root } else { Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $WorkTree) { $WorkTree = Split-Path -Leaf (Split-Path -Parent $PSScriptRoot) }
$ws   = Join-Path $root $WorkTree

# --- Rust/cross-compilation environment -----------------------------------------------------
# Set inline rather than dot-sourcing: the execution policy may refuse ". file.ps1".
# The Rust toolchain lives in <workspace>/.toolchain/rust-dev/.
$env:RUSTUP_HOME = Join-Path $root '.toolchain\rust-dev\rustup'
$env:CARGO_HOME  = Join-Path $root '.toolchain\rust-dev\cargo'
$env:PATH        = "$(Join-Path $env:CARGO_HOME 'bin');$env:PATH"

# Host toolchain for proc-macros / build scripts / cargo-ndk: the box has NO MSVC linker, so the
# windows-gnu host is used (bundled self-contained MinGW runtime links out of the box).
$env:RUSTUP_TOOLCHAIN = 'stable-x86_64-pc-windows-gnu'

# --- strip build-machine paths out of the shipped binary ------------------------------------
# Rust embeds every panic site's source path as a string literal (file!()), so a plain build
# leaves THIS machine's absolute paths inside libksud.so -- the cargo home, the vendored crate
# directory under the workspace, the target dir. rustc rewrites those prefixes at compile time,
# and the rewrite holds for debug info as well as for panic messages. Remapping only the two
# roots that can appear is enough: CARGO_HOME and the target dir both live under $root.
# A caller-supplied RUSTFLAGS is preserved (appended), and changing this value changes cargo's
# fingerprint, so switching it on costs one full rebuild of the dependency graph.
$remapPathPrefix = "--remap-path-prefix=$root=/workspace --remap-path-prefix=$env:USERPROFILE=/home"
if ($env:RUSTFLAGS) { $env:RUSTFLAGS = "$remapPathPrefix $env:RUSTFLAGS" } else { $env:RUSTFLAGS = $remapPathPrefix }
Write-Host "RUSTFLAGS=$env:RUSTFLAGS"

# --- dlltool shim: rustc cannot use the toolchain's own GNU dlltool here --------------------
# rustc runs `dlltool` (resolved from PATH) to synthesise an import library for every raw-dylib
# link; windows-sys and getrandom both do that in the HOST build. rustup's windows-gnu toolchain
# ships dlltool.exe but NOT the GNU assembler that dlltool drives, and dlltool looks for `as`
# NEXT TO ITSELF, so the build died with:
#   dlltool.exe: run: <self-contained>\as  --64 -o x.h.o x.h.s
#   dlltool.exe: No such file or directory
#   dlltool.exe: CreateProcess
#   error calling dlltool 'dlltool.exe': program not found
# (Switching the host to the msvc triple is not an option: the box has no MSVC linker and no
# Windows SDK.) The NDK ships LLVM's llvm-dlltool, which handles rustc's exact argument set and
# needs no assembler, so expose it to rustc as `dlltool.exe` in a shim dir that comes FIRST on
# PATH. Its DLL closure is hard-linked beside it -- same volume, so that costs no disk space and
# keeps the NDK bin off PATH (where its clang could confuse host C builds).
$shim        = Join-Path $root '.toolchain\rust-dev\dlltool-shim'
$shimDlltool = Join-Path $shim 'dlltool.exe'
if (-not (Test-Path $shimDlltool)) {
    $ndkBin      = Join-Path $root '.toolchain\android-sdk\ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin'
    $llvmDlltool = Join-Path $ndkBin 'llvm-dlltool.exe'
    if (-not (Test-Path $llvmDlltool)) { throw "llvm-dlltool.exe not found in the NDK: $llvmDlltool" }
    New-Item -ItemType Directory -Force -Path $shim | Out-Null
    Copy-Item -Force $llvmDlltool $shimDlltool
    foreach ($dll in Get-ChildItem -LiteralPath $ndkBin -Filter '*.dll' -File) {
        $dst = Join-Path $shim $dll.Name
        if (Test-Path $dst) { continue }
        New-Item -ItemType HardLink -Path $dst -Target $dll.FullName -ErrorAction SilentlyContinue | Out-Null
        if (-not (Test-Path $dst)) { Copy-Item -LiteralPath $dll.FullName -Destination $dst -Force }
    }
}
if (-not (Test-Path $shimDlltool)) { throw "dlltool shim missing: $shimDlltool" }

$hostTriple        = 'x86_64-pc-windows-gnu'
$hostSelfContained = Join-Path $env:RUSTUP_HOME "toolchains\$($env:RUSTUP_TOOLCHAIN)\lib\rustlib\$hostTriple\bin\self-contained"
$env:PATH = "$shim;$hostSelfContained;$env:PATH"

# --- host C compiler: needed by every crate that compiles C for the HOST ---------------------
# The box has NO C compiler at all, and exactly one crate needs one: rust-embed's "compression"
# feature pulls include-flate -> zstd -> zstd-sys, and include-flate-codegen is a PROC-MACRO, so
# zstd's C is compiled for x86_64-pc-windows-gnu (the host) even though ksud targets Android.
# Without this the build dies in zstd-sys's build script with:
#   error occurred in cc-rs: failed to find tool "gcc.exe": program not found
# .toolchain/mingw64 is a MinGW-w64 GCC built for the MSVCRT runtime, which is the runtime the
# rustup windows-gnu toolchain links against (its self-contained dir ships libmsvcrt.a and no
# libucrt.a); a UCRT-flavoured toolchain would risk CRT-mismatch link errors.
# ONLY the host target's CC/AR are overridden, so rustc keeps using the bundled self-contained gcc
# as its LINKER, and cargo-ndk's CC_<android-triple> still wins for the Android objects.
# cc-rs probes both spellings of the target inside the variable name, so set both.
$mingwBin = Join-Path $root '.toolchain\mingw64\bin'
$mingwGcc = Join-Path $mingwBin 'gcc.exe'
$mingwAr  = Join-Path $mingwBin 'ar.exe'
if (-not (Test-Path $mingwGcc)) {
    throw "host C compiler missing: $mingwGcc -- see docs/DEVELOPMENT.md (host C compiler)"
}
foreach ($name in @("CC_$hostTriple", "CC_$($hostTriple -replace '-', '_')")) {
    [System.Environment]::SetEnvironmentVariable($name, $mingwGcc, 'Process')
}
foreach ($name in @("AR_$hostTriple", "AR_$($hostTriple -replace '-', '_')")) {
    [System.Environment]::SetEnvironmentVariable($name, $mingwAr, 'Process')
}
Write-Host "host CC=$mingwGcc"

# --- host LINKER -------------------------------------------------------------------------------
# The rustup windows-gnu toolchain ships a link-only gcc stub next to its self-contained CRT
# objects; that stub cannot resolve the CRT objects and import libraries by itself (the host link
# then dies with "ld: cannot find crt2.o" / "cannot find -lkernel32"), so rustc's host linker is
# pointed at the complete MinGW-w64 GCC instead. This only affects the host target's build scripts
# and proc-macros; Android objects keep using cargo-ndk's clang via CC_<android-triple>.
$mingwLinker = Join-Path $mingwBin 'x86_64-w64-mingw32-gcc.exe'
if (-not (Test-Path $mingwLinker)) {
    throw "host linker missing: $mingwLinker"
}
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = $mingwLinker
Write-Host "host LINKER=$mingwLinker"

$env:ANDROID_NDK_HOME = Join-Path $root '.toolchain\android-sdk\ndk\29.0.14206865'
$env:LIBCLANG_PATH    = Join-Path $env:ANDROID_NDK_HOME 'toolchains\llvm\prebuilt\windows-x86_64\bin'

# Git over HTTPS. THIS MACHINE'S SCHANNEL IS BROKEN: git, curl and Invoke-WebRequest all fail with
#   schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS (0x8009030e)
# for EVERY host, so pointing git at a mirror does not help -- the failure happens before a request
# is ever sent (a mirror was tried and failed identically). Git for Windows also ships an OpenSSL
# backend, and forcing it bypasses schannel completely: direct github.com then works. Passed through
# GIT_CONFIG_* so no global git config is modified.
#
# COUNT MUST equal the number of KEY_n/VALUE_n pairs below. PowerShell 5.1 does NOT create an
# environment variable when it is assigned an empty string, so a pair with an empty VALUE makes git
# abort with "missing config value GIT_CONFIG_VALUE_n" / "unable to parse command-line config".
# Never set a VALUE to ''.
$env:GIT_CONFIG_COUNT    = '1'
$env:GIT_CONFIG_KEY_0    = 'http.sslBackend'
$env:GIT_CONFIG_VALUE_0  = 'openssl'
# Ignore machine-level git config too: whatever is configured there is what selects the broken
# schannel backend in the first place. Git still finds its bundled CA store without it.
$env:GIT_CONFIG_NOSYSTEM = '1'
$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE     = 'never'

$env:CARGO_TERM_COLOR = 'never'

# The sandbox's system TEMP is not writable for spawned toolchain processes; the mingw linker
# needs a writable TMP to emit its .def files.
$tmp = Join-Path $root '.toolchain\rust-dev\tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TMP = $tmp
$env:TEMP = $tmp
$env:TMPDIR = $tmp

if ($PackageName -ne 'me.weishu.kernelsu') {
    # userspace/ksud/build.rs reads KSU_PACKAGE_NAME from the ENVIRONMENT and bakes it into the
    # daemon through cargo:rustc-env. Printing it is NOT enough: without this export the daemon is
    # built from the upstream fallback and ksud then launches the OFFICIAL manager's activity.
    $env:KSU_PACKAGE_NAME = $PackageName
    Write-Host "KSU_PACKAGE_NAME=$env:KSU_PACKAGE_NAME (fork identity, exported to cargo)"
} else {
    throw "KSU_PACKAGE_NAME must not be the upstream package name; got $PackageName"
}

if ($Platform -lt 26) {
    throw "Platform must be >= 26 (bionic exports __system_property_read_callback only from API 26); got $Platform"
}

$cargo = Join-Path $env:CARGO_HOME 'bin\cargo.exe'
if (-not (Test-Path $cargo)) { throw "cargo missing: $cargo" }
if (-not (Test-Path $env:ANDROID_NDK_HOME)) { throw "NDK missing: $env:ANDROID_NDK_HOME" }

Write-Host "RUSTUP_HOME=$env:RUSTUP_HOME"
Write-Host "CARGO_HOME=$env:CARGO_HOME"
Write-Host "ANDROID_NDK_HOME=$env:ANDROID_NDK_HOME"
Write-Host "workspace=$ws\Cargo.toml"

Set-Location $ws

$targets = @('arm64-v8a', 'x86_64')
# cargo-ndk takes ABI names (-t arm64-v8a) but cargo writes artifacts under the RUST TRIPLE:
#   target/<triple>/release/ksud      <-- the real built binary (userspace/ksud declares package
#                                         name `ksud` and no [lib]/[[bin]], so no `lib` prefix)
#   target/<abi>/release/libksud.so   <-- WRONG: this path never exists
# It is PACKAGED as libksud.so only because the jniLibs layout requires that file name.
$abiToTriple = @{
    'arm64-v8a' = 'aarch64-linux-android'
    'x86_64'    = 'x86_64-linux-android'
}
foreach ($abi in $targets) {
    if (-not $abiToTriple.ContainsKey($abi)) { throw "no Rust target triple mapped for ABI $abi" }
}
# Uppercase -P is cargo-ndk's platform/API level (see the $Platform comment in param()).
$cargoArgs = @('ndk', '-P', "$Platform")
foreach ($t in $targets) { $cargoArgs += @('-t', $t) }
$cargoArgs += @('--', 'build', '--release')

# --- R3 static checks (docs/DEVELOPMENT.md 8.4) ------------------------------------------------
# The doc spells R3 as `cargo ndk check/clippy/fmt`, and that is right: `fmt` is host-only, but
# clippy/check DO need cargo-ndk even though nothing links -- the build scripts of zstd-sys and
# lz4-sys compile C **for the Android triple**, and only cargo-ndk injects CC_<android-triple>.
# Measured 2026-10-03 without cargo-ndk: all four checks died with exit 101 and
#   error: failed to run custom build command for `zstd-sys v2.0.16+zstd.1.5.7`
#   error: failed to run custom build command for `lz4-sys v1.11.1+lz4-1.10.0`
#   CC_aarch64-linux-android = None / failed to find tool "aarch64-linux-android-clang"
#   error occurred in cc-rs: failed to find tool "clang.exe": program not found
# This branch sits AFTER the host-CC block because the host build scripts run as well.
if ($Fmt -or $Clippy -or $Check) {
    $ErrorActionPreference = 'Continue'
    $script:r3Rc = 0
    function Invoke-R3Check {
        param([string]$Label, [string[]]$Argv)
        Write-Host "cargo $($Argv -join ' ')"
        & $cargo @Argv 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
        Write-Host "${Label}_EXITCODE=$code"
        if ($code -ne 0) { $script:r3Rc = 1 }
    }
    if ($Fmt) { Invoke-R3Check 'FMT' @('fmt', '--all', '--', '--check') }
    if ($Clippy -or $Check) {
        # Same ABI prefix as the build itself, so cargo-ndk hands the NDK clang to the C build
        # scripts; the labels use the ABI name because that is what the build path prints.
        foreach ($t in $targets) {
            $r3Base = @('ndk', '-P', "$Platform", '-t', $t, '--')
            if ($Clippy) { Invoke-R3Check "CLIPPY_$t" ($r3Base + @('clippy', '-p', 'ksud', '--all-targets')) }
            if ($Check) { Invoke-R3Check "CHECK_$t" ($r3Base + @('check', '-p', 'ksud', '--all-targets')) }
        }
    }
    Write-Host "R3_EXITCODE=$script:r3Rc"
    exit $script:r3Rc
}

# cargo/git write harmless diagnostics to stderr; under $ErrorActionPreference='Stop' PowerShell
# 5.1 promotes the first such line to a terminating error and aborts the caller mid-build. The
# exit code is the only success signal (same convention as build-manager.ps1).
$ErrorActionPreference = 'Continue'
Write-Host "cargo $($cargoArgs -join ' ')"
& $cargo @cargoArgs 2>&1 | ForEach-Object { "$_" }
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
Write-Host "EXITCODE=$code"
if ($code -ne 0) { exit $code }

# --- locate, verify and publish the two JNI libraries ---------------------------------------
$targetDir = Join-Path $ws 'target'

function Get-Bytes {
    param([string]$Path)
    return [System.IO.File]::ReadAllBytes($Path)
}

foreach ($abi in $targets) {
    $src = Join-Path $targetDir "$($abiToTriple[$abi])\release\ksud"
    if (-not (Test-Path $src)) { throw "built lib missing for $abi : $src" }
    $bytes = Get-Bytes $src
    $text  = [System.Text.Encoding]::ASCII.GetString($bytes)

    # Acceptance A-c. TWO traps make the obvious check worthless -- both measured on the real
    # stock libksud.so (arm64-v8a / x86_64:
    #   99AAA607E9C9DA6A0E898366ECF0A14DD67224EA726D952D1CE575E62D3B5C41 /
    #   5617CEC45C6F953C8A0DE82A7B70D368DE2C633410A4121DBC80D4A6817D0F82):
    #  1) The daemon stores the package name and the activity suffix as SEPARATE format arguments
    #     ("{}" + "/me.weishu.kernelsu.ui.MainActivity"), so the concatenated launch target
    #     "<pkg>/me.weishu.kernelsu.ui.MainActivity" NEVER occurs in the binary. Checking for it
    #     can never fail and proves nothing.
    #  2) "me.weishu.kernelsu" is a PREFIX of "me.weishu.kernelsu.hide", so a naive count of the
    #     upstream name is always >= the fork count and also proves nothing.
    # The discriminating signal is the bare upstream literal counted with a negative lookahead,
    # excluding the two legitimate remainders: the fork id and the activity class path.
    $nHide     = ([regex]::Matches($text, [regex]::Escape($PackageName))).Count
    $nUpstream = ([regex]::Matches($text, 'me\.weishu\.kernelsu(?!\.hide|\.ui)')).Count
    $nActivity = ([regex]::Matches($text, [regex]::Escape('/me.weishu.kernelsu.ui.MainActivity'))).Count
    $sha   = (Get-FileHash -Algorithm SHA256 $src).Hash

    Write-Host "ABI=$abi size=$($bytes.Length) sha256=$sha"
    Write-Host "  fork '$PackageName' x$nHide ; bare upstream 'me.weishu.kernelsu' x$nUpstream ; activity suffix x$nActivity"

    if ($NoVerify) {
        Write-Host "  VERIFY SKIPPED (-NoVerify)"
    } else {
        if ($nHide -lt 1)     { throw "A-c FAILED [$abi]: fork package '$PackageName' not found in built lib" }
        if ($nUpstream -ne 0) { throw "A-c FAILED [$abi]: bare upstream package name still present (x$nUpstream)" }
        if ($nActivity -lt 1) { throw "A-c FAILED [$abi]: activity suffix '/me.weishu.kernelsu.ui.MainActivity' not found" }
    }
}

# publish
$ErrorActionPreference = 'Continue'
foreach ($abi in $targets) {
    $src = Join-Path $targetDir "$($abiToTriple[$abi])\release\ksud"
    # Single distribution location. The manager's jniLibs are what a Gradle build packages into
    # the APK, and upstream CI stages the daemon there too. Do NOT copy into userspace\ksud\bin\<abi>:
    # bin\x86_64 is the rust-embed asset folder of the x86_64 daemon (src/assets.rs, folder = "bin/x86_64"),
    # so a copy left there is embedded into the next x86_64 build -- the binary grows and the daemon
    # then extracts a stale daemon copy into its own binary dir at runtime.
    foreach ($dest in @(
        (Join-Path $ws "manager\app\src\main\jniLibs\$abi\libksud.so")
    )) {
        $dir = Split-Path -Parent $dest
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        Copy-Item -Force $src $dest
        $dsha = (Get-FileHash -Algorithm SHA256 $dest).Hash
        $ssha = (Get-FileHash -Algorithm SHA256 $src).Hash
        $mark = if ($dsha -eq $ssha) { 'OK' } else { 'MISMATCH' }
        Write-Host "COPY $mark $dest sha256=$dsha"
        if ($mark -ne 'OK') { $script:copyFailed = $true }
    }
}
$ErrorActionPreference = 'Stop'
if ($script:copyFailed) { throw "copy verification failed" }

Write-Host "DONE"
exit 0
