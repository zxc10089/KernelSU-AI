# verify-identity.ps1 -- guard against the "install/flash yields the OFFICIAL manager" defect class.
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI (GBK here).
#
# WHY THIS EXISTS
#   Two independent things decide which manager a KernelSU fork actually crowns:
#     (1) the APK applicationId + its signing certificate  (kernel/manager/apk_sign.c matches the
#         v2 cert SHA-256 against the whitelist baked into the patched LKM), and
#     (2) the PACKAGE NAME COMPILED INTO THE DAEMON (userspace/ksud/src/defs.rs
#         DEFAULT_PACKAGE_NAME = env!("KSU_PACKAGE_NAME")). The daemon is what force-stops and
#         `am start`s the manager (late_load.rs) and where it writes boot backups (boot_patch.rs).
#
#   Defect that motivated this script: jniLibs/*/libksud.so was extracted from an OFFICIAL release
#   APK, so it carried the upstream package name and every install/flash planted an upstream daemon.
#   Nothing in a Gradle build catches that -- it is a binary, not source. So check the bytes.
#
# EXIT: 0 only when every mandatory check passes.
param(
    [string]$WorkTree = '',
    [string]$ExpectedPackage = 'me.weishu.kernelsu.hide',
    # SHA-256 of the manager signing cert whitelisted in the patched LKM (kernel-enforced identity).
    # PROJECT key since 2026-10-05: the old value here was the PUBLIC Android debug cert, which any
    # app signed with the stock debug keystore also matches -- that is the defect this script exists
    # for. Public facts: _tools/project-signer.json.
    [string]$ExpectedCert = 'ca40afa835e460be5dcfd0743296d95c3275b9e51673de8b6fd0a64665a83eec',
    # Optional explicit APK to inspect. When omitted, the newest release/debug APK is used.
    [string]$Apk = '',
    # Workspace root that contains the repository and .toolchain; defaults to this repo's parent.
    [string]$Root = '',
    # Run the classifier's own positive/negative controls and exit. Proves the checks above can BOTH
    # fail and pass, so a PASS can never be mistaken for a check that is simply unable to match.
    [switch]$SelfTest,
    # Make the LKM asset's PROVENANCE a hard gate: a module built from source compiles the manager
    # package name in (-DKSU_MANAGER_PACKAGE), the byte-patch fallback cannot. Without this switch
    # a byte-patched asset is only a warning, because it is still cert-identical to a source build.
    [switch]$RequireSourceBuild
)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $WorkTree) { $WorkTree = Split-Path -Leaf (Split-Path -Parent $PSScriptRoot) }
$ws = Join-Path $Root $WorkTree
$script:fail = 0
$script:warn = 0

function Check {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    $tag = if ($Ok) { 'PASS' } else { 'FAIL' }
    if (-not $Ok) { $script:fail++ }
    Write-Host ("[{0}] {1}" -f $tag, $Name)
    if ($Detail) { Write-Host ("       {0}" -f $Detail) }
}
function Warn {
    param([string]$Name, [string]$Detail)
    $script:warn++
    Write-Host ("[WARN] {0}" -f $Name)
    if ($Detail) { Write-Host ("       {0}" -f $Detail) }
}

# The activity CLASS path is genuinely me.weishu.kernelsu.ui.MainActivity even in this fork -- only
# the applicationId changes. But DO NOT check for the concatenated "pkg/class" string: the daemon
# stores the package name and the activity suffix as SEPARATE format arguments, so that string never
# occurs in the binary and such a check can never fail. Measured on the stock libksud.so:
#   "me\.weishu\.kernelsu\.hide"                 -> 0   (fork id absent       => the defect)
#   "me\.weishu\.kernelsu(?!\.hide|\.ui)"        -> 1   (bare upstream literal => the defect)
#   "me\.weishu\.kernelsu\.ui"                   -> 1   (activity class path, legitimate in both)
# "me.weishu.kernelsu" is also a PREFIX of "me.weishu.kernelsu.hide", so a naive count of the
# upstream name is always >= the fork count and proves nothing either. Hence the lookahead, which
# excludes exactly the two legitimate remainders: the fork id and the activity class path.
$reBareUpstream = 'me\.weishu\.kernelsu(?!\.hide|\.ui)'
$reActivityPath = '/me\.weishu\.kernelsu\.ui\.MainActivity'

function Measure-Identity {
    param([string]$Text, [string]$Expected)
    return [pscustomobject]@{
        Fork     = ([regex]::Matches($Text, [regex]::Escape($Expected))).Count
        BareUp   = ([regex]::Matches($Text, $reBareUpstream)).Count
        Activity = ([regex]::Matches($Text, $reActivityPath)).Count
    }
}

if ($SelfTest) {
    # Two synthetic binaries: one that a CORRECT rebuild must produce, one the STOCK daemon produces.
    # The strings mirror how the daemon actually stores them (package and activity suffix separate).
    $good = "x$ExpectedPackage y/me.weishu.kernelsu.ui.MainActivity z"
    $bad  = "xme.weishu.kernelsu y/me.weishu.kernelsu.ui.MainActivity z"
    $g = Measure-Identity -Text $good -Expected $ExpectedPackage
    $b = Measure-Identity -Text $bad  -Expected $ExpectedPackage
    Write-Host ("GOOD  fork={0} bareUpstream={1} activity={2}  (want >=1 / 0 / >=1)" -f $g.Fork, $g.BareUp, $g.Activity)
    Write-Host ("STOCK fork={0} bareUpstream={1} activity={2}  (want 0 / >=1 / >=1)" -f $b.Fork, $b.BareUp, $b.Activity)
    $okGood  = ($g.Fork -ge 1 -and $g.BareUp -eq 0 -and $g.Activity -ge 1)
    $okBad   = ($b.Fork -eq 0 -and $b.BareUp -ge 1 -and $b.Activity -ge 1)
    Check "classifier PASSES a correctly-built daemon" $okGood "fork=$($g.Fork) bareUpstream=$($g.BareUp)"
    Check "classifier FAILS the stock daemon" $okBad "fork=$($b.Fork) bareUpstream=$($b.BareUp)"
    Write-Host ""
    if ($script:fail -gt 0) { Write-Host "SELF-TEST: FAILED"; exit 1 }
    Write-Host "SELF-TEST: PASSED"
    exit 0
}

Write-Host "=== 1. build configuration ==="
$gradleProps = Join-Path $ws 'manager\gradle.properties'
if (Test-Path $gradleProps) {
    $line = Select-String -Path $gradleProps -Pattern '^\s*KSU_PACKAGE_NAME\s*=' | Select-Object -First 1
    if ($line) {
        $value = ($line.Line -replace '^\s*KSU_PACKAGE_NAME\s*=\s*', '').Trim()
        Check "manager/gradle.properties declares the fork applicationId" ($value -eq $ExpectedPackage) "KSU_PACKAGE_NAME=$value (expected $ExpectedPackage)"
        if ($value -eq 'me.weishu.kernelsu') {
            Write-Host "       NOTE: this is the UPSTREAM id -- the APK would install over the official manager."
        }
    } else {
        Check "manager/gradle.properties declares KSU_PACKAGE_NAME" $false "no KSU_PACKAGE_NAME line found"
    }
} else {
    Check "manager/gradle.properties present" $false $gradleProps
}

Write-Host ""
Write-Host "=== 2. daemon identity inside the shipped jniLibs (the actual defect) ==="
$jniLibs = Join-Path $ws 'manager\app\src\main\jniLibs'
$libs = @()
if (Test-Path $jniLibs) {
    $libs = Get-ChildItem -Path $jniLibs -Recurse -Filter 'libksud.so' -ErrorAction SilentlyContinue
}
if ($libs.Count -eq 0) {
    Check "at least one jniLibs/<abi>/libksud.so exists" $false $jniLibs
} else {
    foreach ($lib in $libs) {
        $abi = Split-Path -Leaf (Split-Path -Parent $lib.FullName)
        $bytes = [System.IO.File]::ReadAllBytes($lib.FullName)
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        $sha = (Get-FileHash -Algorithm SHA256 $lib.FullName).Hash
        # Same classifier the -SelfTest controls exercise -- one implementation, no drift.
        $m = Measure-Identity -Text $text -Expected $ExpectedPackage
        $nFork     = $m.Fork
        $nBareUp   = $m.BareUp
        $nActivity = $m.Activity

        Check "jniLibs/$abi/libksud.so embeds the FORK package name" ($nFork -ge 1) "fork id '$ExpectedPackage' x$nFork"
        Check "jniLibs/$abi/libksud.so has NO bare upstream package name" ($nBareUp -eq 0) "bare 'me.weishu.kernelsu' (not .hide/.ui) x$nBareUp"
        Check "jniLibs/$abi/libksud.so still references the manager activity" ($nActivity -ge 1) "activity suffix x$nActivity"
        Write-Host ("       abi={0} size={1} sha256={2}" -f $abi, $bytes.Length, $sha)
        if ($nBareUp -gt 0 -or $nFork -lt 1) {
            Write-Host "       FIX: rebuild it -- powershell.exe -NoProfile -ExecutionPolicy Bypass -File _tools\build-ksud.ps1"
        }
    }
}

Write-Host ""
Write-Host "=== 3. built APK identity (applicationId, versionCode, signing cert) ==="
if (-not $Apk) {
    $candidates = @()
    foreach ($variant in @('release', 'debug')) {
        $dir = Join-Path $ws "manager\app\build\outputs\apk\$variant"
        if (Test-Path $dir) {
            $found = Get-ChildItem -Path $dir -Filter '*.apk' -ErrorAction SilentlyContinue |
                     Sort-Object LastWriteTime -Descending
            if ($found) { $candidates += $found[0] }
        }
    }
    if ($candidates.Count -gt 0) { $Apk = $candidates[0].FullName }
}
$buildTools = Join-Path $Root '.toolchain\android-sdk\build-tools'
$bt = Get-ChildItem -Path $buildTools -Directory -ErrorAction SilentlyContinue |
      Sort-Object Name -Descending | Select-Object -First 1

if (-not $Apk -or -not (Test-Path $Apk)) {
    Warn "no built APK to inspect" "build one first: _tools\build-manager.ps1 -Task ':app:assembleRelease'"
} elseif (-not $bt) {
    Warn "no build-tools available for aapt2/apksigner" $buildTools
} else {
    Write-Host "       apk: $Apk"
    if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $env:JAVA_HOME = Join-Path $Root '.toolchain\jdk-21'
    }

    $aapt2 = Join-Path $bt.FullName 'aapt2.exe'
    if (Test-Path $aapt2) {
        $badging = & $aapt2 dump badging $Apk 2>&1 | Out-String
        $pkg = if ($badging -match "package:\s+name='([^']+)'") { $Matches[1] } else { '' }
        $vc = ''
        if ($badging -match "versionCode='(\d+)'") { $vc = $Matches[1] }
        Check "APK applicationId is the fork id" ($pkg -eq $ExpectedPackage) "package=$pkg (expected $ExpectedPackage)"
        if ($vc) { Write-Host ("       versionCode={0}" -f $vc) }
        if ($pkg -eq 'me.weishu.kernelsu') { Write-Host "       NOTE: upstream id -- this APK replaces the official manager." }
    } else {
        Warn "aapt2 not found" $aapt2
    }

    $apksigner = Join-Path $bt.FullName 'apksigner.bat'
    if (Test-Path $apksigner) {
        $certs = & $apksigner verify --print-certs $Apk 2>&1 | Out-String
        $sha = if ($certs -match 'SHA-256 digest:\s*([0-9a-fA-F]{64})') { $Matches[1].ToLower() } else { '' }
        Check "APK signing cert is the kernel-whitelisted identity" ($sha -eq $ExpectedCert.ToLower()) "sha256=$sha"
        if ($sha -and $sha -ne $ExpectedCert.ToLower()) {
            Write-Host "       The kernel crowns the manager whose v2 cert SHA-256 matches its baked-in whitelist."
            Write-Host "       A different cert means this fork can never become the manager."
        }
    } else {
        Warn "apksigner not found" $apksigner
    }
}

Write-Host ""
Write-Host "=== 4. LKM asset identity (the cert and DER length the KERNEL whitelists) ==="
# userspace/ksud/bin/<arch>/*_kernelsu.ko is the module rust-embed packs into libksud.so and the
# module a boot-image patch injects, so it must carry the PROJECT signer. If it still whitelists the
# public debug cert, any app signed with the stock debug keystore can be crowned instead of this
# manager (measured on device 67a86199: moe.nb4a.debug won the crown). The ko is a binary and there
# is no local DDK, so the constants are patched in place -- see docs/root-crown-appid-fix.md and
# _tools/patch-init-boot.py --ko-in/--ko-out.
$signerJson = Join-Path $ws '_tools\project-signer.json'
$wantHash = $ExpectedCert.ToLower()
$wantSize = 0
if (Test-Path $signerJson) {
    $signer = Get-Content -LiteralPath $signerJson -Raw | ConvertFrom-Json
    $wantHash = "$($signer.certSha256)".ToLower()
    $wantSize = [int]$signer.certDerSize
    Write-Host ("       project-signer.json: certSha256=$wantHash certDerSize=$wantSize (0x$('{0:X}' -f $wantSize))")
    if ($wantHash -ne $ExpectedCert.ToLower()) {
        Warn "ExpectedCert differs from project-signer.json" "-ExpectedCert=$($ExpectedCert.ToLower()) json=$wantHash"
    }
} else {
    Warn "no _tools/project-signer.json" "cannot read the project cert size: $signerJson"
}
$assetRoot = Join-Path $ws 'userspace\ksud\bin'
$assets = @()
if (Test-Path $assetRoot) {
    $assets = Get-ChildItem -Path $assetRoot -Recurse -Filter '*_kernelsu.ko' -File -ErrorAction SilentlyContinue
}
if ($assets.Count -eq 0) {
    Warn "no LKM asset under userspace\ksud\bin" $assetRoot
} else {
    foreach ($asset in $assets) {
        $bytes = [System.IO.File]::ReadAllBytes($asset.FullName)
        $text  = [System.Text.Encoding]::ASCII.GetString($bytes)
        $nHash = ([regex]::Matches($text, [regex]::Escape($wantHash))).Count
        $immOk = $false
        $found = ''
        for ($i = 0x1000; $i -lt ($bytes.Length - 4); $i += 4) {
            $w = [int64][BitConverter]::ToUInt32($bytes, $i)
            if (($w -band 0xFFC003FF) -eq 0x710002BF) {
                $imm = ($w -shr 10) -band 0xFFF
                if (-not $found) { $found = ('0x{0:X}' -f $imm) }
                if ($wantSize -gt 0 -and $imm -eq $wantSize) { $immOk = $true; break }
            }
        }
        $rel = $asset.FullName.Substring($ws.Length + 1)
        $sha = (Get-FileHash -Algorithm SHA256 $asset.FullName).Hash
        Check "LKM asset carries the project cert SHA-256" ($nHash -ge 1) "$rel hash-literal x$nHash (want >= 1)"
        if ($wantSize -gt 0) {
            Check "LKM asset expects the project cert DER length" $immOk "$rel cmp-size imm=$found (want $wantSize = 0x$('{0:X}' -f $wantSize))"
        }
        # PROVENANCE: -DKSU_MANAGER_PACKAGE compiles the manager package name into is_manager_apk().
        # A byte-patched asset rewrites the cert hash and the DER-length immediate only, so the
        # package gate is absent and the asset is cert-identical to a source build -- report that
        # difference instead of letting it hide behind an identical hash check.
        $nPkg = ([regex]::Matches($text, [regex]::Escape($ExpectedPackage))).Count
        $prov = if ($nPkg -ge 1) { 'path A (from source: KSU_MANAGER_PACKAGE compiled in)' } else { 'path B (byte-patched: cert only)' }
        Write-Host ("       {0} provenance={1} pkg-string x{2}" -f $rel, $prov, $nPkg)
        if ($RequireSourceBuild) {
            Check "LKM asset is a source build (manager package name compiled in)" ($nPkg -ge 1) "$rel pkg-string x$nPkg (want >= 1)"
        } elseif ($nPkg -eq 0) {
            Warn "LKM asset is a byte-patched fallback (no compiled package name)" "$rel -- adopt a DDK CI build: _tools/adopt-lkm-asset.ps1 -Ko <built.ko>"
        }
        Write-Host ("       {0} size={1} sha256={2}" -f $rel, $bytes.Length, $sha)
    }
}

Write-Host ""
if ($script:fail -gt 0) {
    Write-Host ("IDENTITY VERIFY: FAILED ({0} failure(s), {1} warning(s))" -f $script:fail, $script:warn)
    exit 1
}
Write-Host ("IDENTITY VERIFY: PASSED ({0} warning(s))" -f $script:warn)
exit 0
