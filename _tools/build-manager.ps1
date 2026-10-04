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
$tmp = Join-Path $tc 'tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP  = $tmp
$env:GRADLE_OPTS = "-Dorg.gradle.native=false -Djava.io.tmpdir=$tmp"

$gradle = Join-Path $tc 'gradle-9.7.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) { throw "Gradle distribution missing: $gradle" }

$args = @($Task, '--console=plain', '--stacktrace')
if ($NoDaemon) { $args += '--no-daemon' }
if ($Offline)  { $args += '--offline' }

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
