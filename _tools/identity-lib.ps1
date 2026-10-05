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

function Get-KsuDisassembly {
    param([string]$Path, [switch]$WithRelocations)
    # One module -> its llvm-objdump text. -WithRelocations adds the R_* lines, which is the only
    # way to see where an address really points: a .ko is an ET_REL object, so an encoded
    # displacement is still 0 and the '#' comment objdump prints points at the NEXT INSTRUCTION,
    # not at the data. Measured: 'movq (%rip), %rax  # 0xc53c <do_get_info+0x1c>' is really a load
    # from '.L__const.do_get_info.cmd' in .rodata.cst16.
    $od = Find-LlvmObjdump
    if (-not $od) { return @{ Ok = $null; Lines = @(); Why = 'llvm-objdump not found' } }
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return @{ Ok = $null; Lines = @(); Why = ('no such file: ' + $Path) } }
    # No '--' end-of-options marker: llvm-objdump 14 rejects it ('unknown argument').
    $argv = @('-d')
    if ($WithRelocations) { $argv += '-r' }
    $argv += @('--no-show-raw-insn', $Path)
    $lines = & $od @argv 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $lines -or @($lines).Count -eq 0) {
        return @{ Ok = $null; Lines = @(); Why = ('llvm-objdump exit ' + $LASTEXITCODE + ', ' + @($lines).Count + ' line(s)') }
    }
    return @{ Ok = $true; Lines = @($lines); Why = '' }
}

function Get-DisasmFunction {
    param([string[]]$Lines, [string]$NamePrefix)
    # Slices every function whose name starts with NamePrefix out of one dump. Label lines look like
    # '000000000000d6e0 <do_get_info>:' (address at column 0, space, '<name>:'). Relocation lines are
    # indented and carry no '<name>:', so they stay attached to the body they belong to.
    $out = @()
    $cur = $null
    foreach ($ln in $Lines) {
        if ($ln -match '^[0-9a-fA-F]{4,}\s+<([^>]+)>:') {
            if ($cur -and $cur.Name.StartsWith($NamePrefix)) { $out += $cur }
            $cur = @{ Name = $Matches[1]; Lines = New-Object System.Collections.ArrayList }
            continue
        }
        if ($cur) { [void]$cur.Lines.Add($ln) }
    }
    if ($cur -and $cur.Name.StartsWith($NamePrefix)) { $out += $cur }
    return $out
}

function Get-KsuSectionMap {
    param([string]$Path)
    # section name -> @{ Off; Size }, read straight out of the ELF64 section headers. The NDK's
    # llvm-objdump 14 prints only 'Idx Name Size VMA Type' for -h (NO file-offset column), so the
    # bytes of a section are not recoverable from its text output; and this way the resolver needs
    # no extra tool. Executable sections are skipped: a data constant is never read out of .text.
    $map = @{}
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return $map }
    $b = [System.IO.File]::ReadAllBytes($Path)
    if ($b.Length -lt 64) { return $map }
    $shoff = [int64][BitConverter]::ToUInt64($b, 0x28)
    $entsz = [int][BitConverter]::ToUInt16($b, 0x3A)
    $num = [int][BitConverter]::ToUInt16($b, 0x3C)
    $strIdx = [int][BitConverter]::ToUInt16($b, 0x3E)
    if ($num -le 0 -or $entsz -le 0) { return $map }
    $strOff = [int64]0
    $o = [int64]($shoff + $strIdx * $entsz)
    if ($o -ge 0 -and ($o + 40) -le $b.Length) { $strOff = [int64][BitConverter]::ToUInt64($b, $o + 24) }
    for ($i = 0; $i -lt $num; $i++) {
        $o = [int64]($shoff + $i * $entsz)
        if ($o -lt 0 -or ($o + 64) -gt $b.Length) { break }
        $nameOff = [int64][BitConverter]::ToUInt32($b, [int]$o)
        $flags = [int64][BitConverter]::ToUInt64($b, [int]($o + 8))
        $off = [int64][BitConverter]::ToUInt64($b, [int]($o + 24))
        $size = [int64][BitConverter]::ToUInt64($b, [int]($o + 32))
        if ($size -le 0 -or ($flags -band 0x4) -ne 0) { continue }
        $name = ''
        if ($strOff -gt 0) {
            $s = [int]($strOff + $nameOff)
            if ($s -ge 0 -and $s -lt $b.Length) {
                $e = $s
                while ($e -lt $b.Length -and $b[$e] -ne 0) { $e++ }
                if ($e -gt $s) { $name = [System.Text.Encoding]::ASCII.GetString($b, $s, $e - $s) }
            }
        }
        if (-not $name) { continue }
        $map[$name] = @{ Off = $off; Size = $size }
    }
    return $map
}

function Get-KsuSymbolMap {
    param([string]$Path)
    # 'llvm-objdump -t' -> name -> @{ Section; Value }. Needed for the x86_64 literal pool, whose
    # relocation names a compiler-generated local symbol ('.L__const.do_get_info.cmd') instead of a
    # section, so the section map alone cannot resolve it.
    $map = @{}
    $od = Find-LlvmObjdump
    if (-not $od -or -not $Path -or -not (Test-Path -LiteralPath $Path)) { return $map }
    foreach ($ln in @(& $od -t $Path 2>$null)) {
        $t = @($ln.Trim() -split '\s+')
        if ($t.Count -lt 5) { continue }
        if ($t[0] -notmatch '^[0-9a-fA-F]+$') { continue }
        $sec = ''
        $j = 2
        if ($t[$j] -match '^[.]' -or $t[$j] -eq 'UND' -or $t[$j] -eq 'ABS') { $sec = $t[$j] } else { $j = 3; if ($t.Count -gt $j -and $t[$j] -match '^[.]') { $sec = $t[$j] } }
        if (-not $sec) { continue }
        $name = $t[$j + 2]
        if (-not $name) { continue }
        $map[$name] = @{ Section = $sec; Value = [int64][Convert]::ToInt64($t[0], 16) }
    }
    return $map
}

function Get-InsnAt {
    param([string[]]$Lines, [int]$Index)
    # Parses one instruction line plus any R_* line that belongs to it.
    $m = [regex]::Match($Lines[$Index], '^\s*([0-9a-fA-F]+):\s+([a-z][a-z0-9.]*)(?:\s+(.*))?$')
    if (-not $m.Success) { return $null }
    $ops = ''
    if ($m.Groups[3].Success) { $ops = $m.Groups[3].Value.Trim() }
    $rel = ''
    for ($k = $Index + 1; $k -le ($Index + 2) -and $k -lt $Lines.Count; $k++) {
        if ($Lines[$k].Trim() -eq '') { break }
        $r = [regex]::Match($Lines[$k], '^\s*[0-9a-fA-F]+:\s+(R_[A-Z0-9_]+)\s+(\S+)')
        if ($r.Success) { $rel = ($rel + ' ' + $r.Groups[1].Value + ' ' + $r.Groups[2].Value).Trim() } else { break }
    }
    return @{ Mn = $m.Groups[2].Value; Ops = $ops; Rel = $rel }
}

function Split-RelocTarget {
    param([string]$Target)
    # '.rodata+0x5930' / '.L__const.do_get_info.cmd-0x4' / 'current_task' -> name + addend.
    $name = $Target
    $addend = [int64]0
    if ($Target -match '^(.*?)([+-])(0x[0-9a-fA-F]+)$') {
        $name = $Matches[1]
        $addend = [int64][Convert]::ToInt64($Matches[3].Substring(2), 16)
        if ($Matches[2] -eq '-') { $addend = 0 - $addend }
    }
    return @{ Name = $name; Addend = $addend }
}

function Read-ModuleQword {
    param([byte[]]$Bytes, [hashtable]$Sections, [string]$Section, [int64]$Off)
    # Section-relative offset -> the 8 bytes there, or $null when they are not in this file.
    if (-not $Sections.ContainsKey($Section)) { return $null }
    $s = $Sections[$Section]
    if ($Off -lt 0) { return $null }
    if ($s.Size -gt 0 -and ($Off + 8) -gt $s.Size) { return $null }
    $fo = [int64]($s.Off + $Off)
    if ($fo -lt 0 -or ($fo + 8) -gt $Bytes.Length) { return $null }
    return [uint64][BitConverter]::ToUInt64($Bytes, [int]$fo)
}

function Add-VersionCandidate {
    param([System.Collections.ArrayList]$List, [uint64]$Word, [string]$At, [switch]$RequireLkmFlag)
    # A get-info version/flags word: low 32 bits = version, high 32 bits = flags. In the packed
    # form the flags are already the LKM bit (1) plus optional runtime bits (3/5/7); in the literal
    # pool the compiler leaves flags = 0 and ORs the LKM bit in at runtime, so 0 is accepted too.
    $flags = [int32]($Word -shr 32)
    $ver = [int64]($Word -band [uint64]4294967295)
    if ($flags -notin @(0, 1, 3, 5, 7)) { return }
    # The packed (immediate) form always carries the LKM bit, so a flags==0 word there is the
    # features/uapi pair or a runtime flag store, never the version. The literal pool leaves
    # flags 0 on purpose, so only that path keeps RequireLkmFlag off.
    if ($RequireLkmFlag -and $flags -eq 0) { return }
    if ($ver -lt 1 -or $ver -gt 400000) { return }
    [void]$List.Add(@{ Version = [int]$ver; Flags = $flags; At = $At })
}

function Get-ImmediateVersionCandidates {
    param([string[]]$Lines, [string]$Abi)
    # The version and the flags are one 64-bit constant, materialised differently per ABI:
    #   aarch64: mov x10, #16 | movk x10, #1, lsl #32 | str x10, [sp, #8]
    #            (a version above 0xFFFF adds 'movk x10, #hi, lsl #16' first; the mov may be
    #             printed as 'mov w10, #16', so both register widths are tracked by number)
    #   x86_64 : movabsq $4294967312, %rax     (0x100000010 = version 16, flags 1)
    $found = New-Object System.Collections.ArrayList
    if ($Abi -eq 'aarch64') {
        $regs = @{}
        for ($i = 0; $i -lt $Lines.Count; $i++) {
            $insn = Get-InsnAt -Lines $Lines -Index $i
            if (-not $insn) { continue }
            if ($insn.Mn -eq 'mov' -or $insn.Mn -eq 'movk') {
                $q = [regex]::Match($insn.Ops, '^[wx](\d+),\s+#(\d+)(?:,\s+lsl\s+#(\d+))?$')
                if ($q.Success) {
                    $r = [int]$q.Groups[1].Value
                    $imm = [uint64]$q.Groups[2].Value
                    $sh = 0
                    if ($q.Groups[3].Success) { $sh = [int]$q.Groups[3].Value }
                    if ($insn.Mn -eq 'mov') { $regs[$r] = $imm } else {
                        $old = [uint64]0
                        if ($regs.ContainsKey($r)) { $old = [uint64]$regs[$r] }
                        $regs[$r] = $old -bor ($imm -shl $sh)
                    }
                }
                continue
            }
            if ($insn.Mn -eq 'str') {
                # 64-bit store only: the version/flags pair is one qword, and a 32-bit 'str w<r>'
                # here is the runtime flag overwrite (measured: 'str w8, [sp, #12]' with 3/5/7).
                $q = [regex]::Match($insn.Ops, '^x(\d+),\s+\[')
                if ($q.Success) {
                    $r = [int]$q.Groups[1].Value
                    if ($regs.ContainsKey($r)) { Add-VersionCandidate -List $found -Word ([uint64]$regs[$r]) -At $Lines[$i].Trim() -RequireLkmFlag }
                }
            }
        }
    } else {
        foreach ($ln in $Lines) {
            if ($ln -match ':\s+movabsq?\s+\$(\d+),') {
                Add-VersionCandidate -List $found -Word ([uint64]([int64]$Matches[1])) -At $ln.Trim() -RequireLkmFlag
            }
        }
    }
    return $found
}

function Get-PoolVersionCandidates {
    param([string[]]$Lines, [string]$Abi, [byte[]]$Bytes, [hashtable]$Sections, [hashtable]$Symbols)
    # The other shape: the constant lives in a data section and the code loads it.
    #   aarch64: adrp x9, .rodata+0x5930 | add x9, x9, #0 | ldp x11, x9, [x9] | stp x11, x9, [sp, #8]
    #   x86_64 : movq (%rip), %rax  R_X86_64_PC32 .L__const.do_get_info.cmd-0x4
    # The lowest resolved address of one constant block is its first field = version (struct order).
    $raw = @()
    if ($Abi -eq 'aarch64') {
        $ptrs = @{}
        for ($i = 0; $i -lt $Lines.Count; $i++) {
            $insn = Get-InsnAt -Lines $Lines -Index $i
            if (-not $insn) { continue }
            if ($insn.Mn -eq 'adrp' -and $insn.Rel -match 'R_AARCH64_ADR_PREL_PG_HI21\s+(\S+)') {
                $q = [regex]::Match($insn.Ops, '^[wx](\d+),')
                if ($q.Success) {
                    $t = Split-RelocTarget -Target $Matches[1]
                    if ($Sections.ContainsKey($t.Name)) { $ptrs[[int]$q.Groups[1].Value] = @{ Section = $t.Name; Off = $t.Addend } }
                }
                continue
            }
            if ($insn.Mn -eq 'add') {
                $q = [regex]::Match($insn.Ops, '^[wx](\d+),\s*[wx](\d+),\s*#(\d+)$')
                if ($q.Success -and $q.Groups[1].Value -eq $q.Groups[2].Value) {
                    $r = [int]$q.Groups[1].Value
                    if ($ptrs.ContainsKey($r)) { $ptrs[$r].Off = [int64]$ptrs[$r].Off + [int64]$q.Groups[3].Value }
                }
                continue
            }
            if ($insn.Mn -eq 'ldr' -or $insn.Mn -eq 'ldp') {
                $q = [regex]::Match($insn.Ops, '^[wx](\d+)(?:,\s*[wx](\d+))?,\s*\[[wx](\d+)\]$')
                if (-not $q.Success) { continue }
                $base = [int]$q.Groups[3].Value
                if (-not $ptrs.ContainsKey($base)) { continue }
                $p = $ptrs[$base]
                $w = Read-ModuleQword -Bytes $Bytes -Sections $Sections -Section $p.Section -Off $p.Off
                if ($null -ne $w) { $raw += @{ Word = [uint64]$w; At = ("{0}+0x{1:x} via ldr/ldp (reloc target)" -f $p.Section, $p.Off); Addr = [int64]$p.Off } }
            }
        }
    } else {
        for ($i = 0; $i -lt $Lines.Count; $i++) {
            $insn = Get-InsnAt -Lines $Lines -Index $i
            if (-not $insn) { continue }
            if ($insn.Mn -notmatch '^movq$') { continue }
            if ($insn.Ops -notmatch '^\(%rip\),') { continue }
            if ($insn.Rel -notmatch 'R_X86_64_[A-Z0-9_]+[XL]?\s+(\S+)') { continue }
            $t = Split-RelocTarget -Target $Matches[1]
            if (-not $Symbols.ContainsKey($t.Name)) { continue }
            $sym = $Symbols[$t.Name]
            # A PC32 relocation measures from the end of the disp32 field, hence the +4.
            $addr = [int64]($sym.Value + $t.Addend + 4)
            $w = Read-ModuleQword -Bytes $Bytes -Sections $Sections -Section $sym.Section -Off $addr
            if ($null -ne $w) { $raw += @{ Word = [uint64]$w; At = ("{0}+0x{1:x} ({2}) via movq (%rip)" -f $sym.Section, $addr, $t.Name); Addr = $addr } }
        }
    }
    $found = New-Object System.Collections.ArrayList
    if ($raw.Count -eq 0) { return $found }
    # Manual minimum: Measure-Object needs a property-bearing collection and returns nothing at all
    # for a single-element array, which silently dropped the only candidate (measured on
    # aarch64/android12-5.10, whose constant block is loaded exactly once).
    $min = [int64]0
    $first = $true
    foreach ($h in $raw) {
        if ($first -or [int64]$h.Addr -lt $min) { $min = [int64]$h.Addr; $first = $false }
    }
    foreach ($h in $raw) {
        if ([int64]$h.Addr -eq $min) { Add-VersionCandidate -List $found -Word ([uint64]$h.Word) -At $h.At }
    }
    return $found
}

function Find-KsuVersion {
    param([string]$Path)
    # Locates the compile-time KERNEL_SU_VERSION the module reports over the get-info supercall --
    # the value the manager compares against MINIMAL_SUPPORTED_KERNEL (32513) and against its own
    # versionCode. It is a build constant (kernel/Kbuild -DKSU_VERSION=...), so a module built
    # without usable git metadata silently ships the upstream fallback 16, and every manager past
    # 32513 then refuses the LKM at runtime while the module still loads. Measured on device
    # 67a86199: 'ksud debug info' printed version 16 and the UI showed the hard version banner.
    # Ok=$true  -> the module was read and carries this version (compare it with the pinned one).
    # Ok=$false -> the module was read but no version constant was found (shape changed: inspect).
    # Ok=$null  -> the module or the tooling could not be read at all (callers must only WARN).
    $res = @{ Ok = $false; Version = 0; Flags = 0; Found = ''; Why = '' }
    $dis = Get-KsuDisassembly -Path $Path -WithRelocations
    if ($dis.Ok -ne $true) { return @{ Ok = $null; Version = 0; Flags = 0; Found = ('n/a (' + $dis.Why + ')'); Why = 'disassembly unavailable' } }
    $bodies = @(Get-DisasmFunction -Lines $dis.Lines -NamePrefix 'do_get_info')
    if ($bodies.Count -eq 0) {
        return @{ Ok = $false; Version = 0; Flags = 0; Found = 'no do_get_info* function in the dump'; Why = 'symbol missing' }
    }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $sections = Get-KsuSectionMap -Path $Path
    $symbols = Get-KsuSymbolMap -Path $Path
    $immediate = New-Object System.Collections.ArrayList
    $pool = New-Object System.Collections.ArrayList
    foreach ($b in $bodies) {
        $bodyLines = @($b.Lines)
        $abi = 'aarch64'
        if (($bodyLines -join ' ') -match ':\s+movabsq?\s+\$') { $abi = 'x86_64' }
        foreach ($c in @(Get-ImmediateVersionCandidates -Lines $bodyLines -Abi $abi)) { [void]$immediate.Add($c) }
        if ($immediate.Count -eq 0) {
            foreach ($c in @(Get-PoolVersionCandidates -Lines $bodyLines -Abi $abi -Bytes $bytes -Sections $sections -Symbols $symbols)) { [void]$pool.Add($c) }
        }
    }
    $picked = @()
    $kind = ''
    if ($immediate.Count -gt 0) { $picked = @($immediate); $kind = 'version/flags qword' } elseif ($pool.Count -gt 0) { $picked = @($pool); $kind = 'literal-pool constant' }
    if ($picked.Count -eq 0) {
        return @{ Ok = $false; Version = 0; Flags = 0; Found = 'no version constant found in do_get_info*'; Why = 'instruction shape not recognised' }
    }
    $versions = @($picked | ForEach-Object { $_.Version } | Sort-Object -Unique)
    if ($versions.Count -gt 1) {
        return @{ Ok = $false; Version = 0; Flags = 0; Found = ('disagreeing versions: ' + ($versions -join ',')); Why = ('ambiguous ' + $kind) }
    }
    $lkm = @($picked | Where-Object { $_.Flags -eq 1 })
    $pick = if ($lkm.Count -gt 0) { $lkm[0] } else { $picked[0] }
    return @{ Ok = $true; Version = [int]$pick.Version; Flags = [int]$pick.Flags; Found = ('version={0} flags=0x{1:X}' -f $pick.Version, $pick.Flags); Why = ($kind + '; store: ' + $pick.At) }
}

function Get-PinnedKsuVersion {
    param([string]$PropsPath)
    # The fork's single version source of truth. manager/gradle.properties drives BOTH the APK
    # versionCode (manager/build.gradle.kts) and, since 2026-10-05, the module's -DKSU_VERSION
    # (kernel/Kbuild reads the same file). Returns $null when the file or the line is absent, so a
    # caller can WARN instead of comparing against a made-up 0.
    if (-not $PropsPath -or -not (Test-Path -LiteralPath $PropsPath)) { return $null }
    $line = Select-String -Path $PropsPath -Pattern '^\s*KSU_VERSION_CODE\s*=\s*(\d+)' | Select-Object -First 1
    if ($line -and $line.Matches.Count -gt 0 -and $line.Matches[0].Groups[1].Value) { return [int]$line.Matches[0].Groups[1].Value }
    return $null
}
