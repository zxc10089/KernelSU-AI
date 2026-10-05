# identity-lib.ps1 -- shared locator for the compile-time identity constants inside a kernelsu.ko.
# Dot-source it:   . (Join-Path $PSScriptRoot 'identity-lib.ps1')
# Used by adopt-lkm-asset.ps1, verify-identity.ps1 and build-ksud.ps1 so that all three gates
# agree on what "the module carries the project identity" means.
# ASCII only (Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI).

function Find-LlvmObjdump {
    # x86 code is variable-length, so a byte scan cannot tell where an immediate starts (the
    # offset depends on modrm/SIB/disp: the CI matrix produced 'cmpl $834, %r12d' in seven KMIs
    # and 'cmpl $834, 8(%rsp)' in android12-5.10). llvm-objdump ships with the Android NDK; a
    # missing one is reported as unavailable, never guessed around.
    $cands = @()
    foreach ($root in @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT)) {
        if ($root) { $cands += (Join-Path $root 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-objdump.exe') }
    }
    $localSdk = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } else { '' }
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $localSdk, 'C:\Android\sdk')) {
        if (-not $sdk) { continue }
        $ndk = Join-Path $sdk 'ndk'
        if (Test-Path $ndk) {
            $cands += (Get-ChildItem -Path $ndk -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-objdump.exe' })
        }
    }
    $onPath = Get-Command 'llvm-objdump' -ErrorAction SilentlyContinue
    if ($onPath) { $cands += $onPath.Source }
    foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}

function Find-ExpectedSize {
    param([byte[]]$Bytes, [int]$Want, [string]$Path = '')
    # The DER-length check compares against a compile-time constant (KSU_EXPECTED_SIZE; 834 = 0x342
    # for the project key), so it is located by instruction shape, never by a fixed file offset:
    #   aarch64: SUBS WZR, W<n>, #imm   -- fixed-width, decoded from the word
    #   x86_64 : cmp $imm32, r/m       -- disassembled, because the instruction is variable-length
    # The source register is a compiler choice, not a contract: the 8-KMI CI matrix produced
    # 'cmp w21, #834' in seven modules and 'cmp w8, #834' in android12-5.10, and the official
    # v3.3.0 ko uses 'cmp w21, #827'. Accepting any Rn is what makes the check hold for all of them.
    $res = @{ Ok = $false; Found = ''; Why = '' }
    if ($Want -le 0) { return $res }
    $machine = if ($Bytes.Length -gt 20) { [BitConverter]::ToUInt16($Bytes, 18) } else { 0 }
    if ($machine -eq 183) {
        $seen = @{}
        for ($i = 0x1000; $i -lt ($Bytes.Length - 4); $i += 4) {
            $w = [int64][BitConverter]::ToUInt32($Bytes, $i)
            if (($w -band 0x7F80001F) -eq 0x7100001F) {
                $imm = ($w -shr 10) -band 0xFFF
                $seen[$imm] = $i
                if ($imm -eq $Want) {
                    return @{ Ok = $true; Found = ('0x{0:X}' -f $imm); Why = ('aarch64 cmp-to-wzr @0x{0:x}' -f $i) }
                }
            }
        }
        $sample = (($seen.Keys | Sort-Object | Select-Object -First 4) | ForEach-Object { '0x{0:X}' -f $_ }) -join ','
        return @{ Ok = $false; Found = ("absent (wzr-compare set: $($seen.Count) value(s), e.g. $sample)"); Why = 'aarch64 byte scan' }
    }
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) {
        return @{ Ok = $null; Found = 'n/a (no file path given for disassembly)'; Why = 'x86_64 needs -Path' }
    }
    $od = Find-LlvmObjdump
    if (-not $od) {
        return @{ Ok = $null; Found = 'n/a (llvm-objdump not found)'; Why = 'x86_64 disassembly unavailable' }
    }
    $wantHex = ('0x{0:x}' -f $Want)
    # No '--' end-of-options marker: llvm-objdump 14 rejects it ("unknown argument '--'").
    # PowerShell passes the path as one argument, so a path with spaces is fine without it.
    $lines = & $od -d --no-show-raw-insn $Path 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $lines -or $lines.Count -eq 0) {
        return @{ Ok = $null; Found = ("n/a (llvm-objdump exit $LASTEXITCODE, $(@($lines).Count) line(s))"); Why = 'x86_64 disassembly failed' }
    }
    $re = '\bcmp[a-z]*\s+\$(' + [regex]::Escape($wantHex) + '|' + $Want + ')\b'
    foreach ($line in $lines) {
        if ($line -match $re) {
            return @{ Ok = $true; Found = ('0x{0:X}' -f $Want); Why = 'x86_64 objdump cmp' }
        }
    }
    return @{ Ok = $false; Found = 'absent (no cmp against the wanted size)'; Why = 'x86_64 objdump cmp' }
}
