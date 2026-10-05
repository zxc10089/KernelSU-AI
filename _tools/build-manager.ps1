# Build the KernelSU manager with the toolchain laid out as <workspace>/.toolchain (see _tools/README.md).
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI (GBK here).
#
# Why not gradlew: gradle-wrapper downloads from services.gradle.org, whose TLS chain the
# JDK cannot verify (PKIX path building failed). We pre-download the distribution from the
# Tencent mirror and invoke it directly instead.
param(
    [string]$Task = ':app:assembleDebug',
    # Active worktree name under $root; defaults to this repository's directory name.
    [string]$WorkTree = '',
    # Workspace root that contains the repository and the .toolchain directory.
    [string]$Root = '',
    [switch]$NoDaemon,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'

$root = if ($Root) { $Root } else { Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $WorkTree) { $WorkTree = Split-Path -Leaf (Split-Path -Parent $PSScriptRoot) }
$tc   = Join-Path $root '.toolchain'

$env:JAVA_HOME        = Join-Path $tc 'jdk-21'
$env:ANDROID_HOME     = Join-Path $tc 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $tc 'android-home'
$env:GRADLE_USER_HOME = Join-Path $tc 'gradle-home'
$env:PATH             = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"

# The sandbox blocks the default %TEMP% on C:\, and Gradle's native-service bootstrap
# cannot unpack/load native-platform.dll -> "Could not initialize native services".
# org.gradle.native=false falls back to the pure-Java implementations.
# The same sandbox forbids Gradle's file-system watching: the watcher cannot open the current
# thread ("Couldn't open current thread, error = 5") and the whole build session aborts, so
# org.gradle.vfs.watch=false and --no-watch-fs are always set here.
$tmp = Join-Path $tc 'tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP  = $tmp
$env:GRADLE_OPTS = "-Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false -Djava.io.tmpdir=$tmp"

$gradle = Join-Path $tc 'gradle-9.7.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) { throw "Gradle distribution missing: $gradle" }

$args = @($Task, '--console=plain', '--stacktrace', '--no-watch-fs')
if ($NoDaemon) { $args += '--no-daemon' }
if ($Offline)  { $args += '--offline' }

# --- project release signing identity ---------------------------------------------------------
# The manager's v2 signing certificate is part of the kernel-enforced identity: the patched LKM
# whitelists its SHA-256 (public facts in _tools/project-signer.json), so a release signed with the
# old public debug key cannot be crowned on a device running the project-key LKM. The PRIVATE
# values live in _artifacts\builder\keystore\keystore.properties (never committed); read them here
# and hand them to the apksign plugin as gradle properties (-PKEYSTORE_FILE=... etc., the property
# names manager/app/build.gradle.kts configures). Without the file the build keeps its previous
# behaviour (Android debug keystore) and _tools/verify-identity.ps1 reports the cert mismatch.
$ksProps = Join-Path $root '_artifacts\builder\keystore\keystore.properties'
if (Test-Path $ksProps) {
    $map = @{}
    foreach ($raw in Get-Content -LiteralPath $ksProps) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $eq = $line.IndexOf('=')
        if ($eq -lt 1) { continue }
        $map[$line.Substring(0, $eq).Trim()] = $line.Substring($eq + 1).Trim()
    }
    foreach ($pair in @(@('KEYSTORE_FILE', 'storeFile'), @('KEYSTORE_PASSWORD', 'storePassword'),
                        @('KEY_ALIAS', 'keyAlias'), @('KEY_PASSWORD', 'keyPassword'))) {
        $value = $map[$pair[1]]
        if (-not $value) { throw "keystore.properties has no value for '$($pair[1])' ($ksProps)" }
        $args += "-P$($pair[0])=$value"
    }
    Write-Host "SIGNING=$ksProps (alias $($map['keyAlias']))"
} else {
    Write-Host "SIGNING=Android debug keystore (no $ksProps)"
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
Write-Host "gradle $Task ($WorkTree)"

Set-Location (Join-Path $root "$WorkTree\manager")

# Gradle writes harmless diagnostics to stderr (e.g. "WARNING: A restricted method in
# java.lang.System has been called"). Under $ErrorActionPreference='Stop', PowerShell 5.1
# promotes the first such line to a terminating error and aborts the caller mid-build, even
# though Gradle keeps running. The exit code is the only success signal here.
$ErrorActionPreference = 'Continue'
& $gradle @args 2>&1 | ForEach-Object { "$_" }
$code = $LASTEXITCODE
Write-Host "EXITCODE=$code"
exit $code
