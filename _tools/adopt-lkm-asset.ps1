# adopt-lkm-asset.ps1 -- take a module built OUTSIDE this machine (upstream DDK CI) and adopt it as
# the shipped LKM asset, after proving it really is a PROJECT-identity build.
#
# WHY: the fork ships a prebuilt userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko because this
# machine has no Linux/DDK toolchain (no WSL, no Docker, no make/bash; Windows clang + NDK only).
# The asset in the tree today is 'upstream v3.3.0 + two in-place constant replacements' (path B):
# correct cert hash and DER length, but KSU_MANAGER_PACKAGE is compiled OUT, so a signer match alone
# decides the crown. A build from source (path A) also carries the package-name check.
#   path A: .github/workflows/build-lkm-fork.yml -> upstream build-lkm.yml matrix, 8 KMIs x 2 ABIs,
#           artifacts <abi>-<kmi>-lkm fetched from the ci/lkm branch (identity comes from the
#           kernel/Kbuild defaults; no make variables needed)
#   path B: _tools/patch-init-boot.py --ko-in <upstream ko> --ko-out <asset>
# This script checks which one a given .ko is, and refuses anything that is not project-signed.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI (GBK here).
#
# EXIT: 0 when the candidate passed every mandatory check (and, unless -DryRun, was adopted).
param(
    # Candidate module produced by CI (or anywhere else).
    [Parameter(Mandatory=$true)][string]$Ko,
    [string]$WorkTree = '',
    [string]$Root = '',
    [string]$ExpectedPackage = 'me.weishu.kernelsu.hide',
    # Target KMI inside the asset tree; must match the file name rust-embed looks up
    # (late_load.rs / boot_patch.rs use format!("{kmi}_kernelsu.ko")).
    [string]$Kmi = 'android15-6.6',
    # Target ABI directory inside the asset tree (rust-embed folder = bin/<abi>):
    #   aarch64 -> the 64-bit ARM module that a boot-image patch injects
    #   x86_64  -> the x86_64 module (upstream ships it as well)
    [ValidateSet('aarch64', 'x86_64')][string]$Abi = 'aarch64',
    # Accept a path-B (byte-patched) module instead of requiring the package-name check.
    [switch]$AllowPatchedAsset,
    # Version the candidate must carry. Empty = the pin in manager/gradle.properties. Only the
    # compiled driver version matters: the manager refuses anything below MINIMAL_SUPPORTED_KERNEL.
    [int]$ExpectedVersion = 0,
    # Adopt anyway when the version is unreadable or differs. Refusing is the default because CI
    # shipped 16 for weeks while the workflow itself looked correct.
    [switch]$AllowVersionMismatch,
    # Validate only: do not copy anything into the tree.
    [switch]$DryRun,
    # Run _tools/verify-identity.ps1 after adopting.
    [switch]$Verify
)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $WorkTree) { $WorkTree = Split-Path -Leaf (Split-Path -Parent $PSScriptRoot) }
$ws = Join-Path $Root $WorkTree
$fails = 0
function Check { param([string]$Name, [bool]$Ok, [string]$Detail)
    $tag = if ($Ok) { 'PASS' } else { 'FAIL' }
    if (-not $Ok) { $script:fails++ }
    Write-Host ('[{0}] {1}' -f $tag, $Name)
    if ($Detail) { Write-Host ('       {0}' -f $Detail) }
}
. (Join-Path $PSScriptRoot 'identity-lib.ps1')  # Find-ExpectedSize / Find-LlvmObjdump (shared)

function Warn2 { param([string]$Name, [string]$Detail)
    Write-Host ('[WARN] {0}' -f $Name)
    if ($Detail) { Write-Host ('       {0}' -f $Detail) }
}

if (-not (Test-Path -LiteralPath $Ko)) { Write-Host "candidate module not found: $Ko"; exit 2 }
$Ko = (Resolve-Path -LiteralPath $Ko).Path

$signerJson = Join-Path $ws '_tools\project-signer.json'
if (-not (Test-Path -LiteralPath $signerJson)) { Write-Host "missing $signerJson"; exit 2 }
$signer = Get-Content -LiteralPath $signerJson -Raw | ConvertFrom-Json
$wantHash = "$($signer.certSha256)".ToLower()
$wantSize = [int]$signer.certDerSize
# Identities that must NOT appear in a project build.
$officialHash = 'c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6'
$debugHash    = 'f9af24bdd3c0a4947c06eb3a3bd40c25f08e19fd1a07393ec07c6ace40e63d0e'

$bytes = [System.IO.File]::ReadAllBytes($Ko)
$text  = [System.Text.Encoding]::ASCII.GetString($bytes)
$sha   = (Get-FileHash -Algorithm SHA256 $Ko).Hash
Write-Host ('candidate: {0}' -f $Ko)
Write-Host ('       size={0} sha256={1}' -f $bytes.Length, $sha)
Write-Host ('       expected: certSha256={0} certDerSize={1} (0x{2:X}) package={3}' -f $wantHash, $wantSize, $wantSize, $ExpectedPackage)
Write-Host ''

# 1. ELF / architecture sanity -- catches a truncated or non-module download early.
$isElf = ($bytes.Length -gt 64 -and $bytes[0] -eq 0x7F -and $bytes[1] -eq 0x45 -and $bytes[2] -eq 0x4C -and $bytes[3] -eq 0x46)
Check 'candidate is an ELF file' $isElf ('magic={0:X2}{1:X2}{2:X2}{3:X2}' -f $bytes[0], $bytes[1], $bytes[2], $bytes[3])
if ($isElf) {
    $wantMachine = if ($Abi -eq 'aarch64') { 183 } else { 62 }
    $wantName    = if ($Abi -eq 'aarch64') { 'aarch64 (e_machine=183)' } else { 'x86_64 (e_machine=62)' }
    $machine = [BitConverter]::ToUInt16($bytes, 18)
    Check ('candidate is ' + $wantName) ($machine -eq $wantMachine) ('e_machine={0}' -f $machine)
}

# 2. Project identity constants.
$nHash = ([regex]::Matches($text, [regex]::Escape($wantHash))).Count
$nOfficial = ([regex]::Matches($text, [regex]::Escape($officialHash))).Count
$nDebug = ([regex]::Matches($text, [regex]::Escape($debugHash))).Count
Check 'candidate whitelists the PROJECT cert SHA-256' ($nHash -ge 1) ('hash-literal x{0} (want >= 1)' -f $nHash)
Check 'candidate does NOT whitelist the official cert' ($nOfficial -eq 0) ('official literal x{0} (want 0)' -f $nOfficial)
Check 'candidate does NOT whitelist the public debug cert' ($nDebug -eq 0) ('debug literal x{0} (want 0)' -f $nDebug)

# 3. DER length, located by instruction shape for the candidate's own architecture (see the helper).
$size = Find-ExpectedSize -Bytes $bytes -Want $wantSize -Path $Ko
if ($null -eq $size.Ok) {
    Warn2 'candidate DER-length check unavailable' ('{0} {1} -- install the NDK llvm-objdump to disassemble x86_64 code' -f $Abi, $size.Found)
} else {
    Check 'candidate expects the project cert DER length' $size.Ok ('{0} cmp-size imm={1} (want {2} = 0x{3:X}) [{4}]' -f $Abi, $size.Found, $wantSize, $wantSize, $size.Why)
}

# 4. Which path produced it: only a from-source build carries the package-name check.
$nPkg = ([regex]::Matches($text, [regex]::Escape($ExpectedPackage))).Count
$pathA = ($nPkg -ge 1)
if ($pathA) {
    Write-Host ('[PASS] candidate carries the compiled-in package name (path A, from source)')
    Write-Host ('       ''{0}'' x{1}' -f $ExpectedPackage, $nPkg)
} elseif ($AllowPatchedAsset) {
    Warn2 'candidate has NO package-name check (path B, byte-patched constants)' "a signer match alone decides the crown; pass without -AllowPatchedAsset to refuse this"
} else {
    Check 'candidate carries the compiled-in package name' $false "'$ExpectedPackage' x$nPkg -- this is a path-B asset; re-run with -AllowPatchedAsset to accept it deliberately"
}

# 5. Compiled-in driver version. The version comes from kernel/Kbuild: an explicit KSU_VERSION_CODE
# wins, then manager/gradle.properties, then the git commit count, and only if all of those fail does
# it fall back to 16 -- which the manager rejects outright. Anchored on the real instruction shape, so
# a stale artifact cannot pass by accident.
$pinnedVer = Get-PinnedKsuVersion -PropsPath (Join-Path $ws 'manager\gradle.properties')
$wantVer = $pinnedVer
if ($ExpectedVersion -gt 0) { $wantVer = [int]$ExpectedVersion }
$candVer = Find-KsuVersion -Path $Ko
if ($null -eq $wantVer) {
    Warn2 'no pinned KSU_VERSION_CODE' ('add one to ' + (Join-Path $ws 'manager\gradle.properties'))
} elseif ($null -eq $candVer.Ok) {
    if ($AllowVersionMismatch) { Warn2 'candidate driver version not readable' ("$($candVer.Why) -- accepted because -AllowVersionMismatch") }
    else { Check 'candidate driver version is readable' $false ("$($candVer.Why) -- pass -AllowVersionMismatch only if you really cannot disassemble it") }
} elseif (-not $candVer.Ok) {
    if ($AllowVersionMismatch) { Warn2 'candidate driver version not found' ("$($candVer.Why) -- accepted because -AllowVersionMismatch") }
    else { Check 'candidate carries a recognisable driver version' $false "$($candVer.Why) -- rebuild it with the pinned kernel/Kbuild" }
} elseif ([int]$candVer.Version -ne [int]$wantVer) {
    if ($AllowVersionMismatch) { Warn2 'candidate driver version differs from the required one' ("version=$($candVer.Version) want=$wantVer -- accepted because -AllowVersionMismatch") }
    else { Check 'candidate driver version equals the required one' $false ("version=$($candVer.Version) (want $wantVer) -- this module was built without manager/gradle.properties; do not adopt it") }
} else {
    Write-Host ('[PASS] candidate driver version = {0}' -f $wantVer)
    Write-Host ('       {0}' -f $candVer.Found)
}

if ($fails -gt 0) { Write-Host ''; Write-Host ('ADOPT: REFUSED ({0} failure(s))' -f $fails); exit 1 }

$target = Join-Path $ws ("userspace\ksud\bin\{0}\{1}_kernelsu.ko" -f $Abi, $Kmi)
if ((Test-Path -LiteralPath $target)) {
    $oldSha = (Get-FileHash -Algorithm SHA256 $target).Hash
    if ($oldSha -eq $sha) {
        Write-Host ''
        Write-Host ('ADOPT: already current -- {0} has the same sha256' -f $target)
    } elseif ($DryRun) {
        Write-Host ''
        Write-Host ('ADOPT: -DryRun -- would replace {0}' -f $target)
        Write-Host ('       old sha256={0}' -f $oldSha)
        Write-Host ('       new sha256={0}' -f $sha)
    } else {
        Copy-Item -LiteralPath $Ko -Destination $target -Force
        Write-Host ''
        Write-Host ('ADOPT: replaced {0}' -f $target)
        Write-Host ('       old sha256={0}' -f $oldSha)
        Write-Host ('       new sha256={0}' -f $sha)
    }
} elseif ($DryRun) {
    Write-Host ''
    Write-Host ('ADOPT: -DryRun -- target does not exist yet, would create {0}' -f $target)
} else {
    Copy-Item -LiteralPath $Ko -Destination $target -Force
    Write-Host ''
    Write-Host ('ADOPT: created {0}' -f $target)
}

if (-not $DryRun -and -not ($oldSha -eq $sha)) {
    Write-Host ''
    Write-Host 'NEXT (run in this order -- the daemon is rust-embedded into the APK):'
    Write-Host '  1. powershell -NoProfile -ExecutionPolicy Bypass -File _tools\build-ksud.ps1'
    Write-Host "  2. powershell -NoProfile -ExecutionPolicy Bypass -File _tools\build-manager.ps1 -Task ':app:assembleRelease'"
    Write-Host '  3. powershell -NoProfile -ExecutionPolicy Bypass -File _tools\verify-identity.ps1'
    Write-Host '  4. re-patch the boot image so the flashed module is the new one:'
    Write-Host ('     python _tools\patch-init-boot.py --base <stock init_boot> --out <out.img> --ko-in userspace\ksud\bin\' + $Abi + '\' + $Kmi + '_kernelsu.ko --replace-ko --manager-appid <uid%100000>')
}

if ($Verify -and -not $DryRun) {
    Write-Host ''
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ws '_tools\verify-identity.ps1')
    exit $LASTEXITCODE
}
Write-Host ''
Write-Host 'ADOPT: OK'
exit 0
