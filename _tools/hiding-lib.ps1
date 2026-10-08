<#
.SYNOPSIS
    「环境隐藏」PC 侧终端编排工具（hiding-status.ps1 / hiding-onekey.ps1）的纯函数库。

.DESCRIPTION
    本文件是「隐藏ROOT」实现原理分析报告 → 本地终端工具链的公共底座：
    manifest 校验、内核分支匹配、运行模式三态、一键隐藏计划、adb/su 调用、特性配置解码、
    以及 §4.1 属性洗白 / §4.4 keybox 原子装入 / §4.5 安全清痕 三个纯计划构造器。

    设计约束（与 docs/开发文档.md §7.1 一致）：
    * 本文件只定义函数：没有文件级 param()，也没有任何顶层语句 —— 点源加载本身零副作用。
      常量同样用函数暴露（Get-HidingKsudPath / Get-HidingResetpropPath），不做顶层赋值。
    * 「$ErrorActionPreference = 'Stop'」写在每个会碰 IO/外部命令的函数内部（函数局部作用域），
      这样既保证这些函数内部任何失败都是终止性错误，又不会改变调用方会话的偏好。
    * 结果一律用 @{ Ok = ...; Found = ...; Why = ... } 形状；$null / 'Unknown' 表示
      「不可得」，调用方只能如实告警，绝不能猜（unknown 必须是 no-op，fail-closed）。
    * 本文件含中文：必须带 UTF-8 BOM，否则 Windows PowerShell 5.1 会按 ANSI 读成乱码。
      仓库先例：docs/开发文档.md:290、docs/DEVELOPMENT.md:433。

    与 App 的一致性（冻结语义，逐字等价，工具绝不能与 App 给出不同答案）：
    * Match-KernelBranch        ← ui/screen/hiding/HideEnvironmentUiState.kt:10-25 branchMatchesKernel
    * Get-HidingRuntimeProfile  ← data/model/HidingPack.kt:132-136 appliesTo + :73-89 HidingPackIds
    * Get-HidingPlan            ← ui/viewmodel/HideEnvironmentViewModel.kt:512-542 buildOneKeyPlan
    * Test-HidingEntryInstalled ← data/repository/HidingPackRepository.kt:118-148
                                  （版本只用「相等」比较，绝不用 '<'；PathMask 另比 updateJson 分支）
    * manifest 字段/资产布局    ← _tools/sync-hiding-pack.ps1 + _tools/hiding_manifest.schema.json
                                  （这两个文件是冻结契约，本工具只读、绝不修改）

    设备侧路径证据（userspace/ksud/src/defs.rs、assets.rs）：
    * ksud            = /data/adb/ksud            (DAEMON_PATH = ADB_DIR + "ksud")
    * resetprop       = /data/adb/ksu/bin/resetprop  (RESETPROP_PATH = BINARY_DIR + "resetprop")
    * 模块目录        = /data/adb/modules/、/data/adb/modules_update/
    * KSU_GET_INFO_FLAG_LKM = (1U << 0)           (uapi/supercall.h:24) → flags bit0 = LKM

.NOTES
    §7 明确不做：报告第 15 步的按扩展名通配删除（rm -f *.img/*.json/*.xml/*.zip）与 MT2/ 删除。
    本库里 Get-CleanupPlan 的用例会断言「没有任何一条命令含通配删除」，防止有人后来加上。
#>

function Get-HidingKsudPath {
    # ksud 设备侧绝对路径（defs.rs:17 DAEMON_PATH）。一律用绝对路径：su -c 的 PATH 不保证含 /data/adb/ksu/bin。
    return '/data/adb/ksud'
}

function Get-HidingResetpropPath {
    # resetprop 是 ksud 的多调用符号链接（assets.rs:11,26-29）。
    return '/data/adb/ksu/bin/resetprop'
}

# ---------------------------------------------------------------------------
# 内部取值助手：同时对 PSCustomObject（ConvertFrom-Json）与 hashtable（调用方手工构造）生效。
# 字段名按 JSON 语义做**区分大小写**查找，找不到返回 $null（= 不可得，绝不猜）。
# ---------------------------------------------------------------------------
function Get-HidingField {
    param($Entry, [string]$Name)
    if ($null -eq $Entry) { return $null }
    if ($Entry -is [System.Collections.IDictionary]) {
        # 必须用 ",$Entry[$k]" 返回：函数输出流会**枚举**数组值，直接 return @('gki') 会退化成字符串 'gki'，
        # 于是 Get-HidingEntryModes 的 "-isnot [array]" 判定把真实 modes 当成「非数组 → 两模式都适用」。
        foreach ($k in $Entry.Keys) { if ([string]$k -ceq $Name) { return ,$Entry[$k] } }
        return $null
    }
    $p = $Entry.PSObject.Properties[$Name]
    if ($null -ne $p) { return ,$p.Value }
    return $null
}

function Get-HidingEntryModes {
    # 逐字镜像 schema 的 parserRules：缺失/非数组 modes → 空集（= lkm/gki 两种模式都适用）。
    # 返回 @() 或小写 token 数组；用 ",$modes" 返回以避免单元素数组被 PowerShell 解包（sync-hiding-pack.ps1:130 同款坑）。
    param($Entry)
    $modes = @()
    $v = Get-HidingField $Entry 'modes'
    if ($null -eq $v) { return ,$modes }
    if ($v -isnot [array]) { return ,$modes }
    foreach ($m in $v) {
        if ($null -eq $m) { continue }
        $t = ([string]$m).Trim()
        if ($t -ne '') { $modes += $t.ToLowerInvariant() }
    }
    return ,$modes
}

function Get-HidingEntryOrder {
    # 逐字镜像 schema 的 parserRules：缺失/非整数 order → 100。
    # App 端是 intOrNull("order") = (opt(key) as? Number)?.toInt() ⇒ JSON 字符串 order 也必须落到 100。
    param($Entry)
    $v = Get-HidingField $Entry 'order'
    if ($null -eq $v) { return 100 }
    if ($v -is [string] -or $v -is [bool] -or $v -is [char]) { return 100 }
    if ($v -is [ValueType]) { return [int]$v }
    return 100
}

function Get-HidingEntryModuleKey {
    # App 端 key = moduleId ?: id（HidingPackRepository.kt:121 / ViewModel:534）。
    param($Entry)
    $mid = [string](Get-HidingField $Entry 'moduleId')
    if ([string]::IsNullOrWhiteSpace($mid)) { return [string](Get-HidingField $Entry 'id') }
    return $mid
}

function Test-HidingEntryAppliesTo {
    # 逐字镜像 HidingPack.kt:132-136 appliesTo：
    #   modes 空 → 适用；runtimeMode 未知（$null/'') → 适用（未知是 no-op）；否则 mode ∈ modes。
    param($Entry, [string]$Mode)
    $modes = Get-HidingEntryModes $Entry
    if ($modes.Count -eq 0) { return $true }
    if ([string]::IsNullOrEmpty($Mode)) { return $true }
    foreach ($m in $modes) { if ($m -ceq $Mode) { return $true } }
    return $false
}

function Match-KernelBranch {
    <#
    .SYNOPSIS
        PathMask 分支是否属于当前内核 —— HideEnvironmentUiState.kt:20-25 branchMatchesKernel 的逐字移植。
    .DESCRIPTION
        expected = branch 最后一个 '-' 之后的文本（trim），且必须整体匹配 ^\d+\.\d+$；
        actual   = kernelRelease 中第一个 \d+\.\d+(?:\.\d+)? 匹配；
        返回 actual == expected 或 actual 以 "$expected." 开头。
        这条「后缀必须是点号」的规则就是 6.12 不会匹配 6.120 的原因。
    #>
    param([string]$Branch, [string]$KernelRelease)
    if ([string]::IsNullOrEmpty($Branch)) { return $false }
    $expected = $Branch
    $idx = $Branch.LastIndexOf('-')
    if ($idx -ge 0) { $expected = $Branch.Substring($idx + 1) }
    $expected = $expected.Trim()
    if ($expected -notmatch '^\d+\.\d+$') { return $false }
    if ([string]::IsNullOrEmpty($KernelRelease)) { return $false }
    $m = [regex]::Match($KernelRelease, '\d+\.\d+(?:\.\d+)?')
    if (-not $m.Success) { return $false }
    $actual = $m.Value
    if ($actual -ceq $expected) { return $true }
    return $actual.StartsWith($expected + '.')
}

function Compare-HidingEntryOrder {
    # installOrder = compareBy({order}, {id})，Kotlin 的 String.compareTo 是**序数**比较，
    # 而 PowerShell 的 Sort-Object 是区域性比较（[string]::CompareOrdinal('B','a') = -31 说明二者不同），
    # 所以这里显式用 CompareOrdinal，保证与 App 的排序结果完全一致。
    param($A, $B)
    $c = (Get-HidingEntryOrder $A) - (Get-HidingEntryOrder $B)
    if ($c -ne 0) { return $c }
    return [string]::CompareOrdinal([string](Get-HidingField $A 'id'), [string](Get-HidingField $B 'id'))
}

function Select-MinHidingEntry {
    # Kotlin minWithOrNull：只在严格更小时替换 ⇒ 并列时保留**先遇到**的那个。
    param($Entries)
    $best = $null
    foreach ($e in @($Entries)) {
        if ($null -eq $e) { continue }
        if ($null -eq $best) { $best = $e; continue }
        if ((Compare-HidingEntryOrder $e $best) -lt 0) { $best = $e }
    }
    return $best
}

function Sort-HidingEntries {
    # Kotlin sortedWith 是稳定排序 ⇒ 这里用稳定插入排序（条目 ≤ 数十个，代价可忽略），
    # 排序键 (order, id) 为序数比较，与 App 完全一致。
    param($Entries)
    $arr = @(@($Entries) | Where-Object { $null -ne $_ })
    for ($i = 1; $i -lt $arr.Count; $i++) {
        $cur = $arr[$i]
        $j = $i - 1
        while ($j -ge 0 -and ((Compare-HidingEntryOrder $cur $arr[$j]) -lt 0)) {
            $arr[$j + 1] = $arr[$j]
            $j--
        }
        $arr[$j + 1] = $cur
    }
    return ,$arr
}

function Assert-HidingTruth {
    # 断言失败即 throw（绝不降级为 warning）。$null 条件按 false 处理：不可得不得当作通过。
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw ("hiding assertion failed: " + $Message) }
}

function Read-HidingManifest {
    <#
    .SYNOPSIS
        校验并读出冻结的 pack manifest（<RepoRoot>\manager\app\src\main\assets\hiding\manifest.json）。
    .DESCRIPTION
        校验（任一不满足即 throw，绝不降级为警告）：
          * 解析前剥掉前导 UTF-8 BOM（生成方用 Set-Content -Encoding utf8，PS 5.1 会写 BOM）；
          * version 必须为 2；
          * totalBytes 必须等于 entries[].sizeBytes 的精确和；
          * totalBytes/sizeBytes 都是整数（非整数视为契约破坏）；
          * 每个 entry 的 asset 必须存在于 <RepoRoot>\manager\app\src\main\assets\ 之下；
            其 sizeBytes 必须等于磁盘长度、sha256 必须等于 Get-FileHash；
          * entry 至少一条，category 必须在冻结的封闭集合内。
    #>
    param([string]$RepoRoot)
    $ErrorActionPreference = 'Stop'
    if ([string]::IsNullOrWhiteSpace($RepoRoot)) { throw 'Read-HidingManifest: -RepoRoot 不能为空' }
    $assetsRoot = Join-Path (Join-Path (Join-Path $RepoRoot 'manager') 'app\src\main\assets') ''
    $assetsRoot = (Join-Path $RepoRoot 'manager\app\src\main\assets')
    $hidingDir = Join-Path $assetsRoot 'hiding'
    $path = Join-Path $hidingDir 'manifest.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Read-HidingManifest: manifest 不存在: $path" }

    $bytes = [System.IO.File]::ReadAllBytes($path)
    $start = 0
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { $start = 3 }
    if ($bytes.Length -ge 2 -and (($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) -or ($bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF))) {
        throw "Read-HidingManifest: manifest 是 UTF-16（契约要求 UTF-8）: $path"
    }
    $text = [System.Text.Encoding]::UTF8.GetString($bytes, $start, $bytes.Length - $start)
    try { $manifest = $text | ConvertFrom-Json } catch { throw "Read-HidingManifest: manifest JSON 解析失败 ($path): $($_.Exception.Message)" }

    if ([int]$manifest.version -ne 2) { throw "Read-HidingManifest: version 必须为 2，实际为 '$($manifest.version)' ($path)" }
    $entries = @($manifest.entries)
    if ($entries.Count -eq 0) { throw "Read-HidingManifest: entries 为空（manifest 未包含任何条目）($path)" }

    $sum = [long]0
    foreach ($e in $entries) {
        $eid = [string](Get-HidingField $e 'id')
        $cat = [string](Get-HidingField $e 'category')
        if ($cat -cnotin @('module', 'pathmask', 'detector', 'data')) {
            throw "Read-HidingManifest: entry '$eid' 的 category '$cat' 不在冻结集合 module/pathmask/detector/data 内"
        }
        $szRaw = Get-HidingField $e 'sizeBytes'
        if ($null -eq $szRaw -or ($szRaw -is [string]) -or -not ($szRaw -is [ValueType])) {
            throw "Read-HidingManifest: entry '$eid' 的 sizeBytes 不是整数"
        }
        $rel = [string](Get-HidingField $e 'asset')
        if ([string]::IsNullOrWhiteSpace($rel)) { throw "Read-HidingManifest: entry '$eid' 的 asset 为空" }
        $assetPath = Join-Path $assetsRoot ($rel.Replace('/', '\'))
        if (-not (Test-Path -LiteralPath $assetPath -PathType Leaf)) {
            throw "Read-HidingManifest: entry '$eid' 的 asset 不存在: $assetPath"
        }
        $actualSize = [long](Get-Item -LiteralPath $assetPath).Length
        if ($actualSize -ne [long]$szRaw) {
            echo "" | Out-Null
            throw ("Read-HidingManifest: entry '{0}' 的 sizeBytes 不匹配（manifest={1}，磁盘={2}）: {3}" -f $eid, [long]$szRaw, $actualSize, $assetPath)
        }
        $actualSha = (Get-FileHash -LiteralPath $assetPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $wantSha = ([string](Get-HidingField $e 'sha256')).Trim().ToLowerInvariant()
        if ($actualSha -ne $wantSha) {
            throw ("Read-HidingManifest: entry '{0}' 的 sha256 不匹配（manifest={1}，磁盘={2}）: {3}" -f $eid, $wantSha, $actualSha, $assetPath)
        }
        $sum += [long]$szRaw
    }
    if ($null -eq $manifest.totalBytes -or -not ($manifest.totalBytes -is [ValueType])) {
        throw "Read-HidingManifest: totalBytes 不是整数 ($path)"
    }
    if ([long]$manifest.totalBytes -ne $sum) {
        throw ("Read-HidingManifest: totalBytes 不等于 ΣsizeBytes（totalBytes={0}，Σ={1}）: {2}" -f [long]$manifest.totalBytes, $sum, $path)
    }
    $manifestSha = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    return @{
        Ok       = $true
        Manifest = $manifest
        Entries  = @($entries)
        Path     = $path
        Sha256   = $manifestSha
    }
}

function Get-HidingRuntimeProfile {
    <#
    .SYNOPSIS
        按运行模式过滤可见条目，并算出推荐的 PathMask 条目 id（镜像 ViewModel:122-126）。
    .PARAMETER RuntimeMode
        'lkm' / 'gki' / $null（或 ''）= 未知。未知是 **no-op**：不过滤任何条目（fail-closed）。
        传入其它字符串直接 throw：未知必须是「不猜」，而不是被静默当作已知。
    #>
    param($Entries, [string]$KernelRelease = '', [string]$RuntimeMode)
    $mode = ''
    if (-not [string]::IsNullOrEmpty($RuntimeMode)) {
        $n = $RuntimeMode.Trim().ToLowerInvariant()
        if ($n -ne 'lkm' -and $n -ne 'gki') {
            throw "Get-HidingRuntimeProfile: 未知 RuntimeMode '$RuntimeMode'（只接受 'lkm'/'gki'/$null；未知必须传 `$null 而不是猜）"
        }
        $mode = $n
    }
    $visible = @()
    $recommended = @()
    foreach ($e in @($Entries)) {
        if ($null -eq $e) { continue }
        if (-not (Test-HidingEntryAppliesTo $e $mode)) { continue }
        $visible += $e
        if ([string](Get-HidingField $e 'category') -cne 'pathmask') { continue }
        $id = [string](Get-HidingField $e 'id')
        if ([string]::IsNullOrWhiteSpace($id)) { continue }
        if (Match-KernelBranch -Branch ([string](Get-HidingField $e 'branch')) -KernelRelease $KernelRelease) {
            $recommended += $id
        }
    }
    return @{
        VisibleEntries = @($visible)
        RecommendedIds = @($recommended)
        PathMaskMatched = ($recommended.Count -gt 0)
    }
}

function Get-RecommendedPathmaskId {
    # 一键隐藏要用的那一个 PathMask id：recommendedIds ∩ visible 中 (order,id) 最小者；没有则 $null。
    # （ViewModel:516-519 的 minWithOrNull(installOrder)；plan §2.3 列的 Get-RecommendedPathmaskId 同名函数。）
    param($Entries, $RecommendedIds)
    $recSet = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    foreach ($r in @($RecommendedIds)) {
        if (-not [string]::IsNullOrEmpty($r)) { [void]$recSet.Add([string]$r) }
    }
    $cand = @()
    foreach ($e in @($Entries)) {
        if ($null -eq $e) { continue }
        if ([string](Get-HidingField $e 'category') -cne 'pathmask') { continue }
        if ($recSet.Contains([string](Get-HidingField $e 'id'))) { $cand += $e }
    }
    $best = Select-MinHidingEntry $cand
    if ($null -eq $best) { return $null }
    return [string](Get-HidingField $best 'id')
}

function Get-HidingPlan {
    <#
    .SYNOPSIS
        冻结的一键隐藏计划 —— HideEnvironmentViewModel.kt:512-542 buildOneKeyPlan 的逐字移植。
    .DESCRIPTION
        1) base = 可见的 category=module 且 id ∈ {hybrid_mount, zygisksu, tricky_store, hma_oss_zygisk}；
        2) 再加**恰好一个** PathMask：recommendedIds ∩ 可见中 (order,id) 最小者（没有就跳过）；
        3) -InstallLsposed 且已选集合里含 zygisksu 时，追加 id=zygisk_lsposed 的 module 条目；
        4) -AddBrickRescue 时追加 id=Automatic_brick_rescue 的 module 条目；
        5) 按 moduleId(缺省 id) 去重，保留 (order,id) 最小者；最后按 (order,id) 升序稳定排序。
        -Entries 必须是**已按运行模式过滤后的可见集合**（调用方先跑 Get-HidingRuntimeProfile）。
    #>
    param($Entries, $RecommendedIds, [switch]$InstallLsposed, [switch]$AddBrickRescue)
    $baseIds = @('hybrid_mount', 'zygisksu', 'tricky_store', 'hma_oss_zygisk')
    $list = @(@($Entries) | Where-Object { $null -ne $_ })

    $recSet = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    foreach ($r in @($RecommendedIds)) {
        if (-not [string]::IsNullOrEmpty($r)) { [void]$recSet.Add([string]$r) }
    }

    $picked = New-Object System.Collections.ArrayList
    foreach ($e in $list) {
        if ([string](Get-HidingField $e 'category') -cne 'module') { continue }
        if ($baseIds -ccontains [string](Get-HidingField $e 'id')) { [void]$picked.Add($e) }
    }
    $pmBest = Select-MinHidingEntry (@($list | Where-Object {
        [string](Get-HidingField $_ 'category') -ceq 'pathmask' -and $recSet.Contains([string](Get-HidingField $_ 'id'))
    }))
    if ($null -ne $pmBest) { [void]$picked.Add($pmBest) }

    if ($InstallLsposed) {
        $hasZygiskSu = $false
        foreach ($p in $picked) { if ([string](Get-HidingField $p 'id') -ceq 'zygisksu') { $hasZygiskSu = $true; break } }
        if ($hasZygiskSu) {
            foreach ($e in $list) {
                if ([string](Get-HidingField $e 'category') -ceq 'module' -and [string](Get-HidingField $e 'id') -ceq 'zygisk_lsposed') {
                    [void]$picked.Add($e); break
                }
            }
        }
    }
    if ($AddBrickRescue) {
        foreach ($e in $list) {
            if ([string](Get-HidingField $e 'category') -ceq 'module' -and [string](Get-HidingField $e 'id') -ceq 'Automatic_brick_rescue') {
                [void]$picked.Add($e); break
            }
        }
    }

    # 去重：groupBy(moduleId ?: id)，每组取 (order,id) 最小者。Kotlin groupBy 保留首次出现顺序，
    # 但紧随其后的 sortedWith 是全序排序，所以组序不影响结果。
    $groups = New-Object 'System.Collections.Specialized.OrderedDictionary'
    foreach ($e in $picked) {
        $key = Get-HidingEntryModuleKey $e
        if ($groups.Contains($key)) { $groups[$key] = @($groups[$key]) + @($e) } else { $groups[$key] = @($e) }
    }
    $deduped = New-Object System.Collections.ArrayList
    foreach ($k in $groups.Keys) {
        $winner = Select-MinHidingEntry @($groups[$k])
        if ($null -ne $winner) { [void]$deduped.Add($winner) }
    }
    # 注意：这里**不能**写 "return ,@(Sort-HidingEntries $deduped)"。
    # 当命令替换 @(...) 里恰好只有一个输出项、而该项本身是数组时，@() 会再包一层，
    # 逗号又包一层，赋值时只解一层 ⇒ 调用方拿到 Object[1]{Object[n]} 的嵌套数组
    # （实测：$p[0] 是数组而不是条目，于是 $p[0].id 变成成员枚举 → 与 App 的语义不一致）。
    # 用 -NoEnumerate 明确表达「整块数组当作一个值返回」，无歧义。
    $sorted = Sort-HidingEntries $deduped
    Write-Output -NoEnumerate $sorted
}

function Test-HidingEntryInstalled {
    <#
    .SYNOPSIS
        某个 pack 条目在设备上是否已安装（+ 是否待重启）—— HidingPackRepository.kt:118-148 的移植。
    .DESCRIPTION
        * detector：packageName ∈ InstalledPackages（pm list packages）；
        * data：无安装概念，恒 false；
        * module/pathmask：按 moduleId(缺省 id) 找到模块，版本判定**只用相等**（绝不用 '<'：
          AlwaysStrong 版 tricky_store 声明 versionCode=104，被它替换的构建声明 235/307，
          用 '<' 会把已装判成更旧）；entry.versionCode ≤ 0 时忽略版本；
        * pathmask 额外比 updateJson 的末段 == "<branch>.json"（六个变体共用 moduleId=pathmask，
          只有 updateJson 能区分分支）；
        * PendingReboot：已安装且模块的 update 标志为字符串 "true"（ksud 落在 /data/adb/modules_update）。
        ksud module list 的值全是**字符串**（module.rs:868 Vec<HashMap<String,String>>），
        所以这里对 versionCode 也做字符串归一化后比较。
    .PARAMETER Modules
        `ksud module list` 解析出来的模块对象数组（每项含 id/versionCode/updateJson/update/enabled…）。
    #>
    param($Entry, $Modules, [string[]]$InstalledPackages = @())
    $id = [string](Get-HidingField $Entry 'id')
    $cat = [string](Get-HidingField $Entry 'category')
    $pkgs = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    foreach ($p in @($InstalledPackages)) { if (-not [string]::IsNullOrEmpty($p)) { [void]$pkgs.Add([string]$p) } }

    if ($cat -ceq 'data') {
        return @{ Installed = $false; PendingReboot = $false; Why = "data 类目无「已安装」概念（$id）" }
    }
    if ($cat -ceq 'detector') {
        $pkg = [string](Get-HidingField $Entry 'packageName')
        $has = $pkgs.Contains($pkg)
        return @{ Installed = $has; PendingReboot = $false; Why = $(if ($has) { "已安装包 $pkg" } else { "未安装包 $pkg" }) }
    }

    $key = Get-HidingEntryModuleKey $Entry
    $mod = $null
    foreach ($m in @($Modules)) {
        if ($null -eq $m) { continue }
        if ([string](Get-HidingField $m 'id') -ceq $key) { $mod = $m; break }
    }
    if ($null -eq $mod) {
        return @{ Installed = $false; PendingReboot = $false; Why = "设备上没有模块 id=$key" }
    }
    $wantVc = [int](Get-HidingField $Entry 'versionCode')
    if ($null -eq (Get-HidingField $Entry 'versionCode')) { $wantVc = 0 }
    $gotVc = ([string](Get-HidingField $mod 'versionCode')).Trim()
    if ($wantVc -gt 0) {
        if ($gotVc -ne [string]$wantVc) {
            return @{ Installed = $false; PendingReboot = $false; Why = "模块 $key 已装但版本不等（设备 versionCode='$gotVc'，pack=$wantVc；判据是相等，不是 '<'）" }
        }
    }
    $branch = [string](Get-HidingField $Entry 'branch')
    if ($cat -ceq 'pathmask' -and -not [string]::IsNullOrEmpty($branch)) {
        $uj = [string](Get-HidingField $mod 'updateJson')
        $seg = $uj
        $slash = $uj.LastIndexOf('/')
        if ($slash -ge 0) { $seg = $uj.Substring($slash + 1) }
        if ($seg -cne ($branch + '.json')) {
            return @{ Installed = $false; PendingReboot = $false; Why = "pathmask 已装但分支不符（updateJson 末段='$seg'，期望 '$branch.json'）" }
        }
    }
    $upd = ([string](Get-HidingField $mod 'update')).Trim().ToLowerInvariant()
    $pending = ($upd -eq 'true')
    return @{
        Installed     = $true
        PendingReboot = $pending
        Why           = $(if ($pending) { "已装模块 $key，update=true（待重启生效）" } else { "已装模块 $key" })
    }
}

function Invoke-Adb {
    <#
    .SYNOPSIS
        调用 adb，返回 @{ ExitCode; Output }。非零退出码**绝不抛异常**（由调用方判定）。
    .DESCRIPTION
        -Serial 为空时不加 -s（用于 adb devices / adb start-server）。
        adb 可执行文件不存在时返回 ExitCode=-1 并给出说明行，而不是抛异常：
        调用方必须按「不可用 → 告警，绝不猜」处理。
    #>
    param([string]$Adb, [string]$Serial, [string[]]$Arguments)
    $ErrorActionPreference = 'Stop'
    $exe = $null
    if (-not [string]::IsNullOrWhiteSpace($Adb)) {
        if (Test-Path -LiteralPath $Adb -PathType Leaf) {
            $exe = (Resolve-Path -LiteralPath $Adb).Path
        } else {
            $cmd = Get-Command -Name $Adb -ErrorAction SilentlyContinue
            if ($null -ne $cmd -and -not [string]::IsNullOrEmpty($cmd.Path)) { $exe = $cmd.Path }
        }
    }
    if ([string]::IsNullOrEmpty($exe)) {
        return @{ ExitCode = -1; Output = @("adb executable not found: '$Adb'") }
    }
    $argv = New-Object System.Collections.ArrayList
    if (-not [string]::IsNullOrEmpty($Serial)) { [void]$argv.Add('-s'); [void]$argv.Add($Serial) }
    foreach ($a in @($Arguments)) { [void]$argv.Add([string]$a) }

    # Windows PowerShell 5.1 陷阱：$ErrorActionPreference='Stop' 时把原生 stderr 用 2>&1 合并
    # 会把 ErrorRecord 变成终止性错误（NativeCommandError），所以只在这一句调用期间降级为 Continue。
    $ErrorActionPreference = 'Continue'
    $raw = & $exe @argv 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($null -eq $code) { $code = -1 }
    $lines = @()
    foreach ($item in @($raw)) {
        if ($null -eq $item) { continue }   # @($null).Count 在 PS 5.1 里是 1，必须显式过滤
        $s = [string]$item
        if ($s -ne '') { $lines += $s }
    }
    return @{ ExitCode = [int]$code; Output = @($lines) }
}

function Invoke-AdbShell {
    # adb -s <serial> shell <command>；<command> 作为单个 argv 元素传给 adb，避免 PowerShell 二次分词。
    param([string]$Adb, [string]$Serial, [string]$Command)
    if ([string]::IsNullOrEmpty($Command)) { throw 'Invoke-AdbShell: -Command 不能为空' }
    return Invoke-Adb -Adb $Adb -Serial $Serial -Arguments @('shell', $Command)
}

function Invoke-SuShell {
    <#
    .SYNOPSIS
        adb -s <serial> shell su -c <command>；su 被拒时返回 ExitCode=-1 且输出行以 'su unavailable:' 开头。
    .DESCRIPTION
        su -c 只吃**一个**参数，所以这里把 command 用单引号包成一个 argv 元素（内部单引号按 POSIX 规则
        转义为 '\''），整串作为单个参数交给 adb shell。复合命令请改用脚本推送（报告 §4.2/计划 §4.2）：
        单引号包裹后内层仍然只有一层 sh 解析，&& / 管道可以正常工作，但不要在此拼接双引号。
        被拒的判据：退出码非 0 且输出命中 su 拒绝特征（permission denied / su: not found / …），
        或输出直接含 "su: not found"/"su: permission denied" 这类强特征。
        绝不静默继续：su 不可用时返回 -1，调用方必须按失败处理。
    #>
    param([string]$Adb, [string]$Serial, [string]$Command)
    $ErrorActionPreference = 'Stop'
    if ([string]::IsNullOrEmpty($Command)) { throw 'Invoke-SuShell: -Command 不能为空' }
    $quoted = "'" + $Command.Replace("'", "'\''") + "'"
    $remote = 'su -c ' + $quoted
    $r = Invoke-AdbShell -Adb $Adb -Serial $Serial -Command $remote
    $text = (@($r.Output) -join "`n")
    if ($r.ExitCode -ne 0 -and $text -match '(?i)(permission denied|operation not permitted|inaccessible or not found|not found|invalid option|usage: su)') {
        return @{ ExitCode = -1; Output = @("su unavailable: " + $text) }
    }
    if ($text -match '(?i)su:\s*(not found|permission denied)') {
        return @{ ExitCode = -1; Output = @("su unavailable: " + $text) }
    }
    return $r
}

function Get-RuntimeModeFromDebugInfo {
    <#
    .SYNOPSIS
        从 `ksud debug info` 的输出解析运行模式三态：Lkm / GkiBuiltIn / Unknown。
    .DESCRIPTION
        ksud 实际输出（cli.rs:722-736）形如：
            version: 1234
            flags: 0x1
            uapi_version: 2
            features: 0x0
            lkm: true
            late_load: false
            runtime_mode: lkm
            pr_build: false
        判据：flags 的 bit0（KSU_GET_INFO_FLAG_LKM = 1U << 0，uapi/supercall.h:24）为主证据，
        `lkm:` 行为次证据，`runtime_mode:` 行只用于补全 Why。bit0 为 0 ⇒ GkiBuiltIn；
        没有任何可解析的证据 ⇒ Unknown（调用方必须把 Unknown 当 no-op，并打印「未检测到运行模式」）。
        Why 里给出**证据行原文**，便于人工复核。
    #>
    param([string[]]$Lines)
    $flagsLine = $null
    $lkmLine = $null
    $modeLine = $null
    foreach ($l in @($Lines)) {
        if ($null -eq $l) { continue }
        $t = ([string]$l).Trim()
        if ($t -match '^flags:\s*(.+)$') { if ($null -eq $flagsLine) { $flagsLine = $t } }
        elseif ($t -match '^lkm:\s*(\S+)\s*$') { if ($null -eq $lkmLine) { $lkmLine = $t } }
        elseif ($t -match '^runtime_mode:\s*(\S+)\s*$') { if ($null -eq $modeLine) { $modeLine = $t } }
    }
    $said = $null
    if ($null -ne $lkmLine) { $said = ($lkmLine -replace '^lkm:\s*', '').Trim().ToLowerInvariant() }
    if ($null -ne $flagsLine) {
        $rawv = ($flagsLine -replace '^flags:\s*', '').Trim()
        $val = $null
        if ($rawv -match '^0[xX]([0-9a-fA-F]+)$') { $val = [System.Convert]::ToInt64($Matches[1], 16) }
        elseif ($rawv -match '^\d+$') { $val = [int64]$rawv }
        if ($null -ne $val) {
            $isLkm = (($val -band 1) -eq 1)
            $why = $flagsLine
            if ($null -ne $lkmLine) { $why = $why + ' / ' + $lkmLine }
            if ($null -ne $modeLine) { $why = $why + ' / ' + $modeLine }
            if ($null -ne $said -and (($said -eq 'true') -ne $isLkm)) {
                $why = $why + '（注意：lkm: 行与 flags bit0 不一致，以 flags 为准）'
            }
            if ($isLkm) { return @{ Mode = 'Lkm'; Why = $why } }
            return @{ Mode = 'GkiBuiltIn'; Why = $why }
        }
    }
    if ($null -ne $said) {
        if ($said -eq 'true') { return @{ Mode = 'Lkm'; Why = $lkmLine } }
        if ($said -eq 'false') { return @{ Mode = 'GkiBuiltIn'; Why = $lkmLine } }
    }
    if ($null -ne $modeLine) {
        $m = ($modeLine -replace '^runtime_mode:\s*', '').Trim().ToLowerInvariant()
        if ($m -eq 'lkm' -or $m -eq 'late-load') { return @{ Mode = 'Lkm'; Why = $modeLine } }
        if ($m -eq 'built-in') { return @{ Mode = 'GkiBuiltIn'; Why = $modeLine } }
    }
    return @{ Mode = 'Unknown'; Why = 'ksud debug info 未给出可解析的 flags:/lkm: 行（未检测到运行模式）' }
}

function Read-UInt32LE {
    param([byte[]]$Bytes, [int]$Offset)
    return ([long]$Bytes[$Offset] + ([long]$Bytes[$Offset + 1] * 256) + ([long]$Bytes[$Offset + 2] * 65536) + ([long]$Bytes[$Offset + 3] * 16777216))
}

function Read-UInt64LE {
    param([byte[]]$Bytes, [int]$Offset)
    $v = [long]0
    for ($i = 7; $i -ge 0; $i--) { $v = $v * 256 + [long]$Bytes[$Offset + $i] }
    return $v
}

function Read-FeatureConfig {
    <#
    .SYNOPSIS
        解码 /data/adb/ksu/.feature_config。
    .DESCRIPTION
        小端布局：magic u32 = 0x7F4B5355（字节 55 53 4b 7f），随后若干 u32 头字段，
        然后是 N 条 12 字节记录 (u32 id, u64 value)。
        id 表：su_compat=0, kernel_umount=1, sulog=2, adb_root=3, selinux_hide=4, webview_zygote_umount=5。

        头部有两种解释，本函数**同时**支持，且要求「记录恰好填满整个文件」：
          * versioned（实证，优先）：magic + u32 version + u32 count + N×12
            证据：只读资源包里的 .feature_config 恰好 72 字节，magic 后为 01 00 00 00（version=1）
            再是 05 00 00 00（count=5），12 + 5×12 = 72 完全吻合；报告 §6 的十六进制同样如此。
          * plain（任务书写法）：magic + u32 count + N×12
        两种布局各自要求精确长度，所以至多有一种能对上 ⇒ 不存在猜测；都对不上就 Ok=$false。
        Features 只列**文件里真实存在**的 id（名字映射见上；未知 id 记为 feature_<id>）；
        已知 id 若文件里没有记录，则不出现在 Features 里（$null = 不可得），只在 Why 里列出。
        值不是 0/1 时如实回填原值并置 Ok=$false（契约说好是 0|1，越界必须报出来）。
    #>
    param([byte[]]$Bytes)
    $names = @{ 0 = 'su_compat'; 1 = 'kernel_umount'; 2 = 'sulog'; 3 = 'adb_root'; 4 = 'selinux_hide'; 5 = 'webview_zygote_umount' }
    if ($null -eq $Bytes -or $Bytes.Length -eq 0) {
        return @{ Ok = $false; Features = [ordered]@{}; Why = '未取得 .feature_config（空/不可读）' }
    }
    if ($Bytes.Length -lt 12) {
        return @{ Ok = $false; Features = [ordered]@{}; Why = (".feature_config 过短：" + $Bytes.Length + ' 字节') }
    }
    $magic = Read-UInt32LE $Bytes 0
    if ($magic -ne 0x7F4B5355) {
        return @{ Ok = $false; Features = [ordered]@{}; Why = ('magic 不匹配：0x{0:x8}（期望 0x7f4b5355）' -f $magic) }
    }
    $layout = $null
    $version = $null
    $count = 0
    $c2 = [int](Read-UInt32LE $Bytes 8)
    if ((12 + 12 * $c2) -eq $Bytes.Length) { $layout = 'versioned'; $version = [int](Read-UInt32LE $Bytes 4); $count = $c2 }
    else {
        $c1 = [int](Read-UInt32LE $Bytes 4)
        if ((8 + 12 * $c1) -eq $Bytes.Length) { $layout = 'plain'; $count = $c1 }
    }
    if ($null -eq $layout) {
        return @{ Ok = $false; Features = [ordered]@{}; Why = ('.feature_config 布局无法解析：头部记录数与文件长度 ' + $Bytes.Length + ' 字节不符') }
    }
    $features = [ordered]@{}
    $warnings = @()
    $startOff = 8
    if ($layout -eq 'versioned') { $startOff = 12 }
    for ($i = 0; $i -lt $count; $i++) {
        $o = $startOff + 12 * $i
        $id = [int](Read-UInt32LE $Bytes $o)
        $val = Read-UInt64LE $Bytes ($o + 4)
        if ($names.ContainsKey($id)) { $name = $names[$id] } else { $name = 'feature_' + $id }
        $features[$name] = $val
        if ($val -ne 0 -and $val -ne 1) { $warnings += ($name + '=' + $val + '（不是 0/1）') }
    }
    $absent = @()
    foreach ($k in ($names.Keys | Sort-Object)) {
        $n = $names[$k]
        if (-not $features.Contains($n)) { $absent += ($n + '(' + $k + ')') }
    }
    $why = 'layout=' + $layout
    if ($null -ne $version) { $why = $why + ' version=' + $version }
    $why = $why + ' count=' + $count
    if ($absent.Count -gt 0) { $why = $why + '；文件内无记录: ' + ($absent -join ',') }
    if ($warnings.Count -gt 0) { $why = $why + '；' + ($warnings -join '；') }
    return @{ Ok = ($warnings.Count -eq 0); Features = $features; Why = $why }
}

function Get-PropertyPlan {
    <#
    .SYNOPSIS
        §4.1 属性洗白计划（纯构造器，无副作用）：有序步骤 @{ Stage; Name; Command; Why }。
    .DESCRIPTION
        逐条来自报告 §4.1 的表格（调试态/解锁态/OEM/SELinux/反模拟器/ADB/工具痕迹）。
        命令一律用 /data/adb/ksu/bin/resetprop（ksud 多调用符号链接），一律带 -n（--skip-svc，
        直接写 mmap 属性区，boot 脚本上下文里没有 property_service 也能生效）；删除用 -n -d。
        Stage 固定 'S6'（计划 §2.3 的 S6 属性洗白阶段），便于 hiding-onekey.ps1 的 -Only/-Skip 分组。
        报告里 `persist.com.luckyzyx.luckytool.*` 是**通配模式**，而 resetprop 不接受通配，
        因此那一条是「只列出命中项」的探查步：删除必须由调用方按实际属性名逐条执行（绝不猜名字）。
    #>
    param([string]$ResetpropPath = '/data/adb/ksu/bin/resetprop')
    $rp = $ResetpropPath
    $table = @(
        @{ Op = 'set'; Name = 'ro.debuggable'; Value = '0'; Why = '报告 §4.1 调试态' }
        @{ Op = 'set'; Name = 'ro.secure'; Value = '1'; Why = '报告 §4.1 调试态' }
        @{ Op = 'set'; Name = 'ro.adb.secure'; Value = '1'; Why = '报告 §4.1 调试态' }
        @{ Op = 'set'; Name = 'ro.build.type'; Value = 'user'; Why = '报告 §4.1 调试态' }
        @{ Op = 'set'; Name = 'ro.build.tags'; Value = 'release-keys'; Why = '报告 §4.1 调试态' }
        @{ Op = 'set'; Name = 'ro.boot.verifiedbootstate'; Value = 'green'; Why = '报告 §4.1 解锁态' }
        @{ Op = 'set'; Name = 'vendor.boot.verifiedbootstate'; Value = 'green'; Why = '报告 §4.1 解锁态' }
        @{ Op = 'set'; Name = 'ro.boot.flash.locked'; Value = '1'; Why = '报告 §4.1 解锁态' }
        @{ Op = 'set'; Name = 'ro.boot.vbmeta.device_state'; Value = 'locked'; Why = '报告 §4.1 解锁态' }
        @{ Op = 'set'; Name = 'vendor.boot.vbmeta.device_state'; Value = 'locked'; Why = '报告 §4.1 解锁态' }
        @{ Op = 'set'; Name = 'ro.secureboot.lockstate'; Value = 'locked'; Why = '报告 §4.1 解锁态' }
        @{ Op = 'set'; Name = 'ro.boot.warranty_bit'; Value = '0'; Why = '报告 §4.1 解锁态' }
        @{ Op = 'set'; Name = 'ro.oem_unlock_supported'; Value = '0'; Why = '报告 §4.1 OEM 解锁' }
        @{ Op = 'set'; Name = 'sys.oem_unlock_allowed'; Value = '0'; Why = '报告 §4.1 OEM 解锁' }
        @{ Op = 'set'; Name = 'ro.boot.realme.lockstate'; Value = '1'; Why = '报告 §4.1 OEM 解锁' }
        @{ Op = 'set'; Name = 'ro.boot.selinux'; Value = 'enforcing'; Why = '报告 §4.1 SELinux' }
        @{ Op = 'set'; Name = 'ro.build.selinux'; Value = '1'; Why = '报告 §4.1 SELinux' }
        @{ Op = 'set'; Name = 'ro.kernel.qemu'; Value = '0'; Why = '报告 §4.1 反模拟器' }
        @{ Op = 'set'; Name = 'ro.boot.qemu'; Value = '0'; Why = '报告 §4.1 反模拟器' }
        @{ Op = 'set'; Name = 'ro.hardware.virtual_device'; Value = '0'; Why = '报告 §4.1 反模拟器' }
        @{ Op = 'set'; Name = 'sys.usb.adb.disabled'; Value = '1'; Why = '报告 §4.1 ADB 痕迹' }
        @{ Op = 'set'; Name = 'service.adb.root'; Value = '0'; Why = '报告 §4.1 ADB 痕迹' }
        @{ Op = 'set'; Name = 'service.adb.tcp.port'; Value = '-1'; Why = '报告 §4.1 ADB 痕迹（-1 = 关闭 tcp 调试）' }
        @{ Op = 'del'; Name = 'persist.service.adb.enable'; Why = '报告 §4.1 ADB 痕迹（删除）' }
        @{ Op = 'del'; Name = 'persist.service.debuggable'; Why = '报告 §4.1 ADB 痕迹（删除）' }
        @{ Op = 'del'; Name = 'persist.zygote.app_data_isolation'; Why = '报告 §4.1 工具痕迹（删除）' }
        @{ Op = 'del'; Name = 'persist.hyperceiler.log.level'; Why = '报告 §4.1 工具痕迹（删除）' }
        @{ Op = 'list'; Name = 'persist.com.luckyzyx.luckytool.*'; Why = '报告 §4.1 工具痕迹（通配模式；resetprop 不接受通配，本步只列出命中项，删除由调用方按实际名字逐条执行）。本步永不失败：退出码 1 只表示没有命中项，命令以 || true 收尾以明确吞掉它' }
    )
    $steps = New-Object System.Collections.ArrayList
    foreach ($t in $table) {
        if ($t.Op -eq 'set') {
            [void]$steps.Add(@{ Stage = 'S6'; Name = $t.Name; Command = ($rp + ' -n ' + $t.Name + ' ' + $t.Value); Why = $t.Why })
        } elseif ($t.Op -eq 'del') {
            [void]$steps.Add(@{ Stage = 'S6'; Name = $t.Name; Command = ($rp + ' -n -d ' + $t.Name); Why = $t.Why })
        } else {
            # 裸 resetprop 会列出全部属性（resetprop.rs:228-234，格式 `[name]: [value]`，退出 0），
            # 所以这里能 grep 出命中项。追加 `|| true`：grep 无命中时退出 1，而"没有这个痕迹"
            # 正是我们想要的结果，绝不能让它被调用方当成一步失败。
            [void]$steps.Add(@{ Stage = 'S6'; Name = ('enumerate:' + $t.Name); Command = ($rp + ' | grep ' + $t.Name + ' || true'); Why = $t.Why })
        }
    }
    return ,@($steps)
}

function Get-KeyboxPlan {
    <#
    .SYNOPSIS
        §4.4/§8.5 keybox 原子装入计划（纯构造器，无副作用）。
    .DESCRIPTION
        六步固定序列：chattr -i → 写 .tmp → chown 0:0 → chmod 600 → mv -f → test -s，
        目标 /data/adb/tricky_store/keybox.xml（源默认取本工具自己的暂存目录 /data/local/tmp/ksu-hide/，
        见计划 §4.2/§9：不用 /sdcard）。
        红线：任何一步都**不得**读取或打印文件内容（keybox.xml 含明文 EC 私钥）。
        调用方职责：先在设备上把源文件放好（本计划不负责推送）；目录不存在时由 tricky_store 模块创建。
    #>
    param(
        [string]$SourcePath = '/data/local/tmp/ksu-hide/keybox.xml',
        [string]$TargetPath = '/data/adb/tricky_store/keybox.xml'
    )
    $tmp = $TargetPath + '.tmp'
    $steps = @(
        @{ Stage = 'S7'; Name = 'chattr -i'; Command = ('chattr -i ' + $TargetPath + ' 2>/dev/null || true'); Why = '第 1 步：清不可变位（文件不存在时 chattr 返回非 0，用 || true 明确吞掉）' }
        @{ Stage = 'S7'; Name = 'write-tmp'; Command = ('rm -f ' + $tmp + ' && cp -f ' + $SourcePath + ' ' + $tmp); Why = '第 2 步：写同目录 .tmp，保证随后的 mv 是原子替换' }
        @{ Stage = 'S7'; Name = 'chown 0:0'; Command = ('chown 0:0 ' + $tmp); Why = '第 3 步：属主 root:root' }
        @{ Stage = 'S7'; Name = 'chmod 600'; Command = ('chmod 600 ' + $tmp); Why = '第 4 步：600，私钥只允许 root 读写' }
        @{ Stage = 'S7'; Name = 'mv -f'; Command = ('mv -f ' + $tmp + ' ' + $TargetPath); Why = '第 5 步：原子替换目标文件' }
        @{ Stage = 'S7'; Name = 'test -s'; Command = ('test -s ' + $TargetPath); Why = '第 6 步：非空校验，退出码即判据；失败必须回滚 .tmp 并报「校验失败」，绝不读取内容' }
    )
    return ,$steps
}

function Get-SecurityPatchPlan {
    <#
    .SYNOPSIS
        §4.4 OMK 的 security patch 伪装计划（纯构造器，无副作用）。
    .DESCRIPTION
        报告原文（:136，来自 omk.zip → post-fs-data.sh:3069-3076）：把
        ro.vendor.build.security_patch 与 ro.build.version.security_patch 改成
        security_patch.txt 里的值。security_patch.txt 是三行 KEY=VALUE：
            system=2025-01-01
            boot=2025-01-01
            vendor=2025-01-01
        映射：ro.build.version.security_patch ← system；ro.vendor.build.security_patch ← vendor。
        （报告只动这两个属性，`boot` 键不映射到任何属性。）
        值必须形如 YYYY-MM-DD；缺键或值不合法时**该属性少一条步骤**，绝不拿别的键顶替，
        也绝不把自由文本灌进系统属性。
    .OUTPUTS
        步骤数组（0–2 项），每项 @{Stage;Name;Command;Why}，Stage 恒为 'S8'。
    #>
    param(
        [string]$SecurityPatchContent = '',
        [string]$ResetpropPath = '/data/adb/ksu/bin/resetprop'
    )
    $map = @{}
    foreach ($line in ($SecurityPatchContent -split "`r?`n")) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -le 0) { continue }
        $k = $t.Substring(0, $i).Trim().ToLowerInvariant()
        $v = $t.Substring($i + 1).Trim()
        if ($v -match '^\d{4}-\d{2}-\d{2}$') { $map[$k] = $v }
    }
    $steps = @()
    foreach ($pair in @(
            @{ Key = 'system'; Prop = 'ro.build.version.security_patch' }
            @{ Key = 'vendor'; Prop = 'ro.vendor.build.security_patch' })) {
        $k = [string]$pair.Key
        if (-not $map.ContainsKey($k)) { continue }
        $val = [string]$map[$k]
        $steps += @{
            Stage   = 'S8'
            Name    = [string]$pair.Prop
            Command = ($ResetpropPath + ' -n ' + [string]$pair.Prop + ' ' + $val)
            Why     = ('§4.4 OMK：security_patch.txt 的 ' + $k + '=' + $val + ' 写入 ' + [string]$pair.Prop)
        }
    }
    # 返回值语义（实测，别改成 `return ,$steps` 或 Write-Output -NoEnumerate）：
    #   本函数把步骤**逐个**放进管线，调用方必须用 @(...) 快照：$plan = @(Get-SecurityPatchPlan ...)。
    #   若写成 `,$steps` / -NoEnumerate，整个数组会作为**单个对象**输出，于是 `@(cmd).Count` 恒为 1
    #   （0 步和 2 步都得到 1），调用方还会拿到「包着真数组的 1 元素数组」，foreach 只转一圈。
    #   逐个输出的代价是：0 步时赋值形式得到 $null（`@($null).Count` 为 1），所以空结果断言要用
    #   @(cmd).Count 的内联形式，不能先赋值再数。
    $steps
}

function Get-TargetListPlan {
    <#
    .SYNOPSIS
        §4.4 完整性层的 target.txt 静态装入计划（纯构造器，无副作用）。
    .DESCRIPTION
        报告原文：编排第 13 步把 target.txt cp 到 /data/adb/tricky_store/target.txt；该列表列出
        参与密钥认证伪造的包（约 220 个，含 com.android.vending / com.google.android.gms /
        com.google.android.gsf）。每小时按 `pm list packages -3` 剔除 OMK_SKIP 后与旧文件 sort -u
        取并集的动态重建是**模块自己的职责**（add.zip service.sh:578-622），本计划只把静态列表
        原子放到位，作为动态重建的起点。
        本计划不读取、不打印列表内容（它含约 220 行包名，刷屏且无意义）。
    .OUTPUTS
        步骤数组（5 项），每项 @{Stage;Name;Command;Why}，Stage 恒为 'S9'。
    #>
    param(
        [string]$SourcePath = '/data/local/tmp/hiding/target.txt',
        [string]$TargetPath = '/data/adb/tricky_store/target.txt'
    )
    $dir = $TargetPath.Substring(0, $TargetPath.LastIndexOf('/'))
    $steps = @(
        @{ Stage = 'S9'; Name = 'mkdir -p'; Command = ('mkdir -p ' + $dir); Why = '目录不存在时由本步创建（tricky_store 模块也会建，这里只是不依赖它）' }
        @{ Stage = 'S9'; Name = 'cp -f'; Command = ('cp -f ' + $SourcePath + ' ' + $TargetPath); Why = '静态列表覆盖到目标；动态重建由模块每小时做，静态列表是它的起点' }
        @{ Stage = 'S9'; Name = 'chown 0:0'; Command = ('chown 0:0 ' + $TargetPath); Why = '属主 root:root' }
        @{ Stage = 'S9'; Name = 'chmod 600'; Command = ('chmod 600 ' + $TargetPath); Why = '只允许 root 读写（父目录本身已是 0700）' }
        @{ Stage = 'S9'; Name = 'test -s'; Command = ('test -s ' + $TargetPath); Why = '非空校验，退出码即判据；失败必须报「列表为空」而不是静默继续' }
    )
    # 同上：逐个输出，调用方用 @(...) 快照；不要用 `,$steps` 或 -NoEnumerate。
    $steps
}

function Get-CleanupPlan {
    <#
    .SYNOPSIS
        §4.5「安全清痕」计划（纯构造器，无副作用）：有序步骤 @{ Stage; Name; Command; Why }。
    .DESCRIPTION
        **只**包含安全子集：
          * §4.5① 检测 App 的 /storage/emulated/0/Android/data/<pkg> 数据（精确路径，无通配）；
          * §4.5② MT管理器/HyperCeiler/LuckyTool 等工具缓存（同样是精确包名路径）；
          * §4.5 编排层第 16 步 /storage/emulated/0/violet/（报告工具自己的暂存目录）；
          * 本工具自己的暂存 /data/local/tmp/ksu-hide/（计划 §9 选定的暂存位置，清痕必须连自己一起清）；
          * §4.5⑤ 收尾 sync（重启前落盘）。
        **明确不做**（计划 §7 红线，本函数用自检用例兜底）：
          * 编排层第 15 步：在 /storage/emulated/0(/Download) 下按扩展名通配删除
            （rm -f *.img/*.json/*.xml/*.zip/target.txt）与整个 /storage/emulated/0/MT2/ 删除
            —— 可能误删用户自己的刷机包/备份；
          * §4.5③ resetprop 全量洗白：由 Get-PropertyPlan 负责，这里不重复（避免两份真相）；
          * §4.5④ odex/art/LSPosed 缓存：报告没有给出具体路径，而这两处是**引导相关**目录，
            猜一个 rm -rf 目标属于计划 §7 点名的高风险动作 ⇒ 只接受调用方用 -ArtCachePaths 显式传入。
        -Entries 可选：用于从 pack 的 detector 条目里取 packageName（推荐），避免硬编码包名清单。
    #>
    param(
        $Entries,
        [string[]]$PackageNames = @(),
        [string[]]$ToolPackages = @('bin.mt.plus.canary', 'com.sevtinge.hyperceiler', 'com.luckyzyx.luckytool'),
        [string[]]$ArtCachePaths = @(),
        [string]$StagingPath = '/data/local/tmp/ksu-hide',
        [string]$VioletPath = '/storage/emulated/0/violet'
    )
    $steps = New-Object System.Collections.ArrayList
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)

    $appData = New-Object System.Collections.ArrayList
    foreach ($p in @($PackageNames)) { if (-not [string]::IsNullOrWhiteSpace($p)) { [void]$appData.Add([string]$p) } }
    foreach ($e in @($Entries)) {
        if ($null -eq $e) { continue }
        if ([string](Get-HidingField $e 'category') -cne 'detector') { continue }
        $pkg = [string](Get-HidingField $e 'packageName')
        if (-not [string]::IsNullOrWhiteSpace($pkg)) { [void]$appData.Add($pkg) }
    }
    foreach ($p in $appData) {
        if (-not $seen.Add($p)) { continue }
        [void]$steps.Add(@{
            Stage = 'S10'; Name = ('app-data:' + $p)
            Command = ('rm -rf /storage/emulated/0/Android/data/' + $p)
            Why = '报告 §4.5① 检测 App 数据目录（精确路径，无通配）'
        })
    }
    foreach ($p in @($ToolPackages)) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        if (-not $seen.Add([string]$p)) { continue }
        [void]$steps.Add(@{
            Stage = 'S10'; Name = ('tool-cache:' + $p)
            Command = ('rm -rf /storage/emulated/0/Android/data/' + $p)
            Why = '报告 §4.5② 工具缓存（包名需在执行前人工核对；路径不存在时 rm -rf 是空操作）'
        })
    }
    foreach ($p in @($ArtCachePaths)) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        [void]$steps.Add(@{
            Stage = 'S10'; Name = ('art-cache:' + $p)
            Command = ('rm -rf ' + $p)
            Why = '报告 §4.5④ odex/art/LSPosed 缓存（报告未给具体路径，仅按调用方显式传入的路径执行）'
        })
    }
    [void]$steps.Add(@{
        Stage = 'S10'; Name = 'violet-staging'
        Command = ('rm -rf ' + $VioletPath + '/')
        Why = '报告 §4.5 编排层第 16 步：清掉报告工具的暂存目录（不是本工具的）'
    })
    [void]$steps.Add(@{
        Stage = 'S10'; Name = 'staging'
        Command = ('rm -rf ' + $StagingPath)
        Why = '清掉本工具自己的暂存目录（计划 §9：/data/local/tmp/ksu-hide/），避免留下痕迹'
    })
    [void]$steps.Add(@{
        Stage = 'S10'; Name = 'sync'
        Command = 'sync'
        Why = '报告 §4.5⑤ 收尾：把缓存落盘后再重启'
    })
    return ,@($steps)
}

function ConvertFrom-HexString {
    # 测试/取证用：把 '55 53 4b 7f' 或 '55534b7f' 变成字节数组。
    param([string]$Hex)
    if ([string]::IsNullOrWhiteSpace($Hex)) { return ,@() }
    $clean = ($Hex -replace '[^0-9a-fA-F]', '')
    if (($clean.Length % 2) -ne 0) { throw "ConvertFrom-HexString: 十六进制长度必须是偶数（$($clean.Length)）" }
    $out = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $clean.Length; $i += 2) {
        [void]$out.Add([Convert]::ToByte($clean.Substring($i, 2), 16))
    }
    return ,[byte[]]$out.ToArray()
}

function Invoke-HidingLibSelfTest {
    <#
    .SYNOPSIS
        正/负向对照自检。全部通过打印 SELFTEST OK；否则 throw 并点名失败用例。
    .DESCRIPTION
        「负向对照」的含义：该检查必须**有能力失败**。所以每个负控都同时有一个正控
        （同一路径在未破坏输入时必须成功），否则一个「什么都 throw」的实现也能骗过自检。
        自检不需要真机：manifest 校验用临时目录里合成的最小 pack，adb/su 用例用不存在的可执行文件。
        可用 -RepoRoot 追加一次「真实 manifest」正控（hiding-status.ps1 会这样调用）。
    #>
    param([string]$RepoRoot = '')
    $ErrorActionPreference = 'Stop'

    function Assert-HidingThrows {
        param([scriptblock]$Body, [string]$CaseName)
        $threw = $false
        try { $null = & $Body } catch { $threw = $true }
        Assert-HidingTruth -Condition $threw -Message ($CaseName + ' —— 期望抛出异常，但没有抛出')
    }

    # ===== 1. Read-HidingManifest：正控（合成最小 pack，含无 BOM/带 BOM 两种写法）+ 负控 =====
    $tmpRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('ksu-hide-selftest-' + [guid]::NewGuid().ToString('N'))
    try {
        $modDir = Join-Path $tmpRoot 'manager\app\src\main\assets\hiding\modules'
        New-Item -ItemType Directory -Force -Path $modDir | Out-Null
        $fakeZip = Join-Path $modDir 'fake.zip'
        $payload = [byte[]]@(0..255) + [byte[]]@(1..200)   # 456 字节，内容无关紧要
        [System.IO.File]::WriteAllBytes($fakeZip, $payload)
        $fakeSha = (Get-FileHash -LiteralPath $fakeZip -Algorithm SHA256).Hash.ToLowerInvariant()
        $fakeLen = [long](Get-Item -LiteralPath $fakeZip).Length
        $mPath = Join-Path $tmpRoot 'manager\app\src\main\assets\hiding\manifest.json'
        $good = [ordered]@{
            version     = 2
            generatedAt = '2026-01-01T00:00:00+08:00'
            sourcePack  = 'selftest'
            includes    = [ordered]@{ modules = $true; pathmask = $false; detectors = $false; data = $false; keybox = $false }
            totalBytes  = $fakeLen
            entries     = @([ordered]@{
                id = 'fake'; moduleId = 'fake'; name = 'fake'; version = '1.0'; versionCode = 1
                author = 'selftest'; description = 'selftest'; category = 'module'
                asset = 'hiding/modules/fake.zip'; sizeBytes = $fakeLen; sha256 = $fakeSha
                sourceFile = '1.zip'; order = 5
            })
        }
        $json = $good | ConvertTo-Json -Depth 8
        # 带 BOM（真实生成方的写法）
        [System.IO.File]::WriteAllText($mPath, $json, (New-Object System.Text.UTF8Encoding($true)))
        $m = Read-HidingManifest -RepoRoot $tmpRoot
        Assert-HidingTruth -Condition ($m.Ok -eq $true) -Message 'Read-HidingManifest 正控：带 BOM 的合法 manifest 应当通过'
        Assert-HidingTruth -Condition (@($m.Entries).Count -eq 1) -Message 'Read-HidingManifest 正控：Entries 应为 1 条'
        Assert-HidingTruth -Condition ($m.Sha256 -match '^[0-9a-f]{64}$') -Message 'Read-HidingManifest 正控：Sha256 应为 64 位小写十六进制'
        # 不带 BOM
        [System.IO.File]::WriteAllText($mPath, $json, (New-Object System.Text.UTF8Encoding($false)))
        $m2 = Read-HidingManifest -RepoRoot $tmpRoot
        Assert-HidingTruth -Condition ($m2.Ok -eq $true) -Message 'Read-HidingManifest 正控：无 BOM 的合法 manifest 也应当通过'
        # 负控 1：篡改 sizeBytes
        [System.IO.File]::WriteAllText($mPath, (($good | ConvertTo-Json -Depth 8) -replace ('"sizeBytes":\s*' + $fakeLen), ('"sizeBytes": ' + ($fakeLen + 1) + ',')), (New-Object System.Text.UTF8Encoding($true)))
        Assert-HidingThrows { Read-HidingManifest -RepoRoot $tmpRoot } 'Read-HidingManifest 负控：篡改 sizeBytes 必须 throw'
        Assert-HidingThrows { Read-HidingManifest -RepoRoot $tmpRoot } 'Read-HidingManifest 负控：篡改 sizeBytes 必须 throw（重复确认不降级）'
        # 负控 2：篡改 sha256
        $tamperedSha = ($good | ConvertTo-Json -Depth 8) -replace $fakeSha, ('0' * 64)
        [System.IO.File]::WriteAllText($mPath, $tamperedSha, (New-Object System.Text.UTF8Encoding($true)))
        Assert-HidingThrows { Read-HidingManifest -RepoRoot $tmpRoot } 'Read-HidingManifest 负控：篡改 sha256 必须 throw'
        # 负控 3：篡改 totalBytes
        [System.IO.File]::WriteAllText($mPath, (($good | ConvertTo-Json -Depth 8) -replace ('"totalBytes":\s*' + $fakeLen), ('"totalBytes": ' + ($fakeLen + 1) + ',')), (New-Object System.Text.UTF8Encoding($true)))
        Assert-HidingThrows { Read-HidingManifest -RepoRoot $tmpRoot } 'Read-HidingManifest 负控：totalBytes ≠ ΣsizeBytes 必须 throw'
        # 负控 4：version ≠ 2
        [System.IO.File]::WriteAllText($mPath, (($good | ConvertTo-Json -Depth 8) -replace '"version":\s*2', '"version": 1'), (New-Object System.Text.UTF8Encoding($true)))
        Assert-HidingThrows { Read-HidingManifest -RepoRoot $tmpRoot } 'Read-HidingManifest 负控：version≠2 必须 throw'
        # 负控 5：asset 不存在
        [System.IO.File]::WriteAllText($mPath, (($good | ConvertTo-Json -Depth 8) -replace 'hiding/modules/fake\.zip', 'hiding/modules/missing.zip'), (New-Object System.Text.UTF8Encoding($true)))
        Assert-HidingThrows { Read-HidingManifest -RepoRoot $tmpRoot } 'Read-HidingManifest 负控：asset 缺失必须 throw'

        # ===== 2. Match-KernelBranch：正控 =====
        Assert-HidingTruth -Condition (Match-KernelBranch -Branch 'android14-6.1' -KernelRelease '6.1.75-android14-11-g1234abc') -Message 'Match-KernelBranch 正控：6.1.75 属于 android14-6.1'
        Assert-HidingTruth -Condition (Match-KernelBranch -Branch 'android16-6.12' -KernelRelease '6.12.0-android16-8-gabc') -Message 'Match-KernelBranch 正控：6.12.0 属于 android16-6.12'
        Assert-HidingTruth -Condition (Match-KernelBranch -Branch 'android15-6.6' -KernelRelease '6.6-distro') -Message 'Match-KernelBranch 正控：纯 6.6 等于 6.6'
        Assert-HidingTruth -Condition (Match-KernelBranch -Branch 'android12-5.10' -KernelRelease '5.10.209-android12-9-gabc') -Message 'Match-KernelBranch 正控：5.10.209 属于 android12-5.10'
        # 负控：6.12 绝不能匹配 6.120（App 靠 "$expected." 前缀规则挡住）
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'android16-6.12' -KernelRelease '6.120.5-android16')) -Message 'Match-KernelBranch 负控：6.12 不得匹配 6.120'
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'android14-6.1' -KernelRelease '6.10.0-android14')) -Message 'Match-KernelBranch 负控：6.1 不得匹配 6.10'
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'android13-5.15' -KernelRelease '5.10.1-android13')) -Message 'Match-KernelBranch 负控：5.15 不得匹配 5.10'
        # 负控：畸形 branch
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'malformed' -KernelRelease '6.6.30')) -Message 'Match-KernelBranch 负控：畸形 branch（无 '-' 且非 x.y）必须为 false'
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'android16-' -KernelRelease '6.6.30')) -Message 'Match-KernelBranch 负控：尾段为空必须为 false'
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'android16-6' -KernelRelease '6.6.30')) -Message 'Match-KernelBranch 负控：尾段 6（非 x.y）必须为 false'
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'android16-6.12.1' -KernelRelease '6.12.1')) -Message 'Match-KernelBranch 负控：尾段必须整体匹配 x.y'
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch '' -KernelRelease '6.6.30')) -Message 'Match-KernelBranch 负控：空 branch 必须为 false'
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'android14-6.1' -KernelRelease '')) -Message 'Match-KernelBranch 负控：空 kernelRelease 必须为 false'
        Assert-HidingTruth -Condition (-not (Match-KernelBranch -Branch 'android14-6.1' -KernelRelease 'no-version-here')) -Message 'Match-KernelBranch 负控：无版本号的 release 必须为 false'

        # ===== 3. Get-HidingRuntimeProfile：appliesTo 镜像 + 推荐 =====
        $eZyg = @{ id = 'zygisksu'; category = 'module'; order = 10 }
        $eSus = @{ id = 'susfs4ksu'; category = 'module'; order = 70; modes = @('gki') }
        $ePm14 = @{ id = 'pathmask-android14-6.1'; category = 'pathmask'; moduleId = 'pathmask'; branch = 'android14-6.1'; order = 60; modes = @('lkm') }
        $ePm16 = @{ id = 'pathmask-android16-6.12'; category = 'pathmask'; moduleId = 'pathmask'; branch = 'android16-6.12'; order = 60; modes = @('lkm') }
        $eOdd = @{ id = 'odd'; category = 'module'; order = 50; modes = 'gki' }   # 非数组 modes → 空集（两模式都适用）
        $profileEntries = @($eZyg, $eSus, $ePm14, $ePm16, $eOdd)
        $pLkm = Get-HidingRuntimeProfile -Entries $profileEntries -KernelRelease '6.1.75-android14-11-gabc' -RuntimeMode 'lkm'
        Assert-HidingTruth -Condition (@($pLkm.VisibleEntries).Count -eq 4) -Message 'Get-HidingRuntimeProfile 正控：lkm 模式可见 4 条（susfs4ksu=仅 gki 被滤掉）'
        Assert-HidingTruth -Condition (@(@($pLkm.VisibleEntries) | Where-Object { $_.id -eq 'susfs4ksu'  }).Count -eq 0) -Message 'Get-HidingRuntimeProfile 正控：lkm 模式下 susfs4ksu 必须不可见'
        Assert-HidingTruth -Condition (@(@($pLkm.VisibleEntries) | Where-Object { $_.id -eq 'odd'  }).Count -eq 1) -Message 'Get-HidingRuntimeProfile 正控：非数组 modes 视为空集（两模式都适用）'
        Assert-HidingTruth -Condition ($pLkm.PathMaskMatched -eq $true) -Message 'Get-HidingRuntimeProfile 正控：6.1.75 应命中 android14-6.1'
        Assert-HidingTruth -Condition (($pLkm.RecommendedIds -join ',') -eq 'pathmask-android14-6.1') -Message 'Get-HidingRuntimeProfile 正控：推荐 id 只应是命中的那一个'
        $pGki = Get-HidingRuntimeProfile -Entries $profileEntries -KernelRelease '6.1.75-android14-11-gabc' -RuntimeMode 'gki'
        Assert-HidingTruth -Condition (@(@($pGki.VisibleEntries) | Where-Object { $_.id -eq 'susfs4ksu'  }).Count -eq 1) -Message 'Get-HidingRuntimeProfile 正控：gki 模式下 susfs4ksu 可见'
        Assert-HidingTruth -Condition ($pGki.PathMaskMatched -eq $false) -Message 'Get-HidingRuntimeProfile 负控：gki 模式下 PathMask（仅 lkm）必须全部不可见 ⇒ 无推荐'
        $pUnknown = Get-HidingRuntimeProfile -Entries $profileEntries -KernelRelease '6.1.75-android14-11-gabc' -RuntimeMode $null
        Assert-HidingTruth -Condition (@($pUnknown.VisibleEntries).Count -eq 5) -Message 'Get-HidingRuntimeProfile 正控：RuntimeMode=$null（未知）必须不过滤任何条目'
        $pEmpty = Get-HidingRuntimeProfile -Entries $profileEntries -KernelRelease '6.1.75-android14-11-gabc' -RuntimeMode ''
        Assert-HidingTruth -Condition (@($pEmpty.VisibleEntries).Count -eq 5) -Message 'Get-HidingRuntimeProfile 正控：RuntimeMode 为空字符串也必须不过滤'
        $pNone = Get-HidingRuntimeProfile -Entries $profileEntries -KernelRelease '9.9.9' -RuntimeMode 'lkm'
        Assert-HidingTruth -Condition ($pNone.PathMaskMatched -eq $false) -Message 'Get-HidingRuntimeProfile 负控：无匹配内核时 PathMaskMatched 必须为 false'
        Assert-HidingTruth -Condition (@($pNone.RecommendedIds).Count -eq 0) -Message 'Get-HidingRuntimeProfile 负控：无匹配内核时 RecommendedIds 必须为空'
        Assert-HidingThrows { Get-HidingRuntimeProfile -Entries $profileEntries -KernelRelease '6.1' -RuntimeMode 'bogus' } 'Get-HidingRuntimeProfile 负控：未知 RuntimeMode 字符串必须 throw（绝不猜）'

        # ===== 4. Get-HidingPlan：buildOneKeyPlan 逐字移植 =====
        $hm = @{ id = 'hybrid_mount'; moduleId = 'hybrid_mount'; category = 'module'; order = 5 }
        $zs = @{ id = 'zygisksu'; moduleId = 'zygisksu'; category = 'module'; order = 10 }
        $ls = @{ id = 'zygisk_lsposed'; moduleId = 'zygisk_lsposed'; category = 'module'; order = 20 }
        $ts = @{ id = 'tricky_store'; moduleId = 'tricky_store'; category = 'module'; order = 30 }
        $hma = @{ id = 'hma_oss_zygisk'; moduleId = 'hma_oss_zygisk'; category = 'module'; order = 40 }
        $br = @{ id = 'Automatic_brick_rescue'; moduleId = 'Automatic_brick_rescue'; category = 'module'; order = 80 }
        $planEntries = @($hm, $zs, $ls, $ts, $hma, $br, $ePm14, $ePm16)
        $p1 = Get-HidingPlan -Entries $planEntries -RecommendedIds @('pathmask-android14-6.1')
        $ids1 = (@($p1) | ForEach-Object { $_.id }) -join ','
        Assert-HidingTruth -Condition ($ids1 -eq 'hybrid_mount,zygisksu,tricky_store,hma_oss_zygisk,pathmask-android14-6.1') -Message ('Get-HidingPlan 正控：基础计划应按 order 升序且只含一个 PathMask，实际=' + $ids1)
        Assert-HidingTruth -Condition ((@($p1)[0].id) -eq 'hybrid_mount') -Message 'Get-HidingPlan 正控：hybrid_mount(order=5, metamodule) 必须排第一'
        $p2 = Get-HidingPlan -Entries $planEntries -RecommendedIds @('pathmask-android14-6.1') -InstallLsposed
        $ids2 = (@($p2) | ForEach-Object { $_.id }) -join ','
        Assert-HidingTruth -Condition ($ids2 -eq 'hybrid_mount,zygisksu,zygisk_lsposed,tricky_store,hma_oss_zygisk,pathmask-android14-6.1') -Message ('Get-HidingPlan 正控：-InstallLsposed 应把 zygisk_lsposed(order=20) 插到 zygisksu 之后，实际=' + $ids2)
        $p3 = Get-HidingPlan -Entries $planEntries -RecommendedIds @('pathmask-android14-6.1') -InstallLsposed -AddBrickRescue
        $ids3 = (@($p3) | ForEach-Object { $_.id }) -join ','
        Assert-HidingTruth -Condition ($ids3 -eq 'hybrid_mount,zygisksu,zygisk_lsposed,tricky_store,hma_oss_zygisk,pathmask-android14-6.1,Automatic_brick_rescue') -Message ('Get-HidingPlan 正控：-AddBrickRescue 应追加 Automatic_brick_rescue(order=80) 于末尾，实际=' + $ids3)
        # 负控：-InstallLsposed 但可见集合里没有 zygisksu ⇒ 不得出现 zygisk_lsposed
        $noZyg = @($planEntries | Where-Object { $_.id -ne 'zygisksu' })
        $p4 = Get-HidingPlan -Entries $noZyg -RecommendedIds @('pathmask-android14-6.1') -InstallLsposed
        Assert-HidingTruth -Condition (@(@($p4) | Where-Object { $_.id -eq 'zygisk_lsposed'  }).Count -eq 0) -Message 'Get-HidingPlan 负控：没有 zygisksu 时 -InstallLsposed 绝不能加入 zygisk_lsposed'
        # 负控：recommendedIds 为空 ⇒ 不得有任何 PathMask
        $p5 = Get-HidingPlan -Entries $planEntries -RecommendedIds @()
        Assert-HidingTruth -Condition (@(@($p5) | Where-Object { $_.category -eq 'pathmask'  }).Count -eq 0) -Message 'Get-HidingPlan 负控：RecommendedIds 为空时不得加入任何 PathMask'
        # 正控：recommendedIds 命中两个分支 ⇒ 只能取 (order,id) 最小者（pathmask-android14-6.1）
        $p6 = Get-HidingPlan -Entries $planEntries -RecommendedIds @('pathmask-android14-6.1', 'pathmask-android16-6.12')
        Assert-HidingTruth -Condition (@(@($p6) | Where-Object { $_.category -eq 'pathmask'  }).Count -eq 1) -Message 'Get-HidingPlan 正控：恰好一个 PathMask'
        Assert-HidingTruth -Condition (@(@($p6) | Where-Object { $_.category -eq 'pathmask'  })[0].id -eq 'pathmask-android14-6.1') -Message 'Get-HidingPlan 正控：两个候选时取 (order,id) 最小者'
        # 正控/负控：按 moduleId 去重（同 id 同 moduleId 两条，order 10 与 90 ⇒ 只留 10）
        $dupe = @($hm, $zs, @{ id = 'zygisksu'; moduleId = 'zygisksu'; category = 'module'; order = 90 }, $ts, $hma)
        $p7 = Get-HidingPlan -Entries $dupe -RecommendedIds @()
        Assert-HidingTruth -Condition (@(@($p7) | Where-Object { $_.id -eq 'zygisksu'  }).Count -eq 1) -Message 'Get-HidingPlan 正控：同 moduleId 必须去重为一条'
        Assert-HidingTruth -Condition (@(@($p7) | Where-Object { $_.id -eq 'zygisksu'  })[0].order -eq 10) -Message 'Get-HidingPlan 正控：去重必须保留 (order,id) 最小者'
        # 正控：排序必须与 App 的序数比较一致（大小写混排时区域性比较会给出不同答案）
        $caseEntries = @(
            @{ id = 'Automatic_brick_rescue'; moduleId = 'Automatic_brick_rescue'; category = 'module'; order = 80 }
            @{ id = 'hybrid_mount'; moduleId = 'hybrid_mount'; category = 'module'; order = 5 }
        )
        $p8 = Get-HidingPlan -Entries $caseEntries -RecommendedIds @()
        Assert-HidingTruth -Condition ((@($p8)[0].id) -eq 'hybrid_mount') -Message 'Get-HidingPlan 正控：order 优先于 id'
        Assert-HidingTruth -Condition (@(@($p8) | Where-Object { $_.id -eq 'Automatic_brick_rescue' }).Count -eq 0) -Message 'Get-HidingPlan 负控：未加 -AddBrickRescue 时不得包含 Automatic_brick_rescue'
        # 序数比较专项：Kotlin 的 String.compareTo 是**序数**比较（大写 A 先于小写 a），
        # 而 PowerShell 的 Sort-Object 默认是区域性比较（会给出 a,A 的顺序）。所以这里直接对
        # (order,id) 全序断言：order 全相同时 id 必须是 A,a,b。
        $tie = Sort-HidingEntries -Entries @(@{ id = 'b'; order = 50 }, @{ id = 'A'; order = 50 }, @{ id = 'a'; order = 50 })
        $tieIds = (@($tie) | ForEach-Object { $_.id }) -join ','
        Assert-HidingTruth -Condition ($tieIds -eq 'A,a,b') -Message ("Get-HidingPlan 正控：order 相同时必须按 id 序数比较（大写先于小写），实际=" + $tieIds)

        # ===== 5. Test-HidingEntryInstalled：相等比较 + updateJson 分支 =====
        $mods = @(
            @{ id = 'zygisksu'; versionCode = '104'; update = 'false'; updateJson = '' }
            @{ id = 'tricky_store'; versionCode = '104'; update = 'true'; updateJson = '' }
            @{ id = 'pathmask'; versionCode = '252'; update = 'false'; updateJson = 'https://example.invalid/pathmask/android14-6.1.json' }
        )
        $stOk = Test-HidingEntryInstalled -Entry @{ id = 'zygisksu'; moduleId = 'zygisksu'; category = 'module'; versionCode = 104 } -Modules $mods
        Assert-HidingTruth -Condition ($stOk.Installed -eq $true) -Message 'Test-HidingEntryInstalled 正控：versionCode 相等应判已装'
        $stOld = Test-HidingEntryInstalled -Entry @{ id = 'zygisksu'; moduleId = 'zygisksu'; category = 'module'; versionCode = 100 } -Modules $mods
        Assert-HidingTruth -Condition ($stOld.Installed -eq $false) -Message 'Test-HidingEntryInstalled 负控：版本不等必须判未装（绝不能用小于号把 104 当更旧）'
        Assert-HidingTruth -Condition ($stOld.Why -match '相等') -Message 'Test-HidingEntryInstalled 负控：说明里必须写明判据是相等'
        $stAny = Test-HidingEntryInstalled -Entry @{ id = 'zygisksu'; moduleId = 'zygisksu'; category = 'module'; versionCode = 0 } -Modules $mods
        Assert-HidingTruth -Condition ($stAny.Installed -eq $true) -Message 'Test-HidingEntryInstalled 正控：pack versionCode ≤ 0 时忽略版本'
        $stPmOk = Test-HidingEntryInstalled -Entry @{ id = 'pathmask-android14-6.1'; moduleId = 'pathmask'; category = 'pathmask'; branch = 'android14-6.1'; versionCode = 252 } -Modules $mods
        Assert-HidingTruth -Condition ($stPmOk.Installed -eq $true) -Message 'Test-HidingEntryInstalled 正控：pathmask moduleId+updateJson 分支都匹配才算已装'
        $stPmNo = Test-HidingEntryInstalled -Entry @{ id = 'pathmask-android16-6.12'; moduleId = 'pathmask'; category = 'pathmask'; branch = 'android16-6.12'; versionCode = 252 } -Modules $mods
        Assert-HidingTruth -Condition ($stPmNo.Installed -eq $false) -Message 'Test-HidingEntryInstalled 负控：moduleId 相同但 updateJson 分支不符必须判未装'
        Assert-HidingTruth -Condition ($stPmNo.Why -match '分支') -Message 'Test-HidingEntryInstalled 负控：分支不符要写进 Why'
        $stPend = Test-HidingEntryInstalled -Entry @{ id = 'tricky_store'; moduleId = 'tricky_store'; category = 'module'; versionCode = 104 } -Modules $mods
        Assert-HidingTruth -Condition ($stPend.PendingReboot -eq $true) -Message 'Test-HidingEntryInstalled 正控：update="true"（字符串）应判待重启'
        $stDet = Test-HidingEntryInstalled -Entry @{ id = 'detector-x'; category = 'detector'; packageName = 'a.b.c' } -Modules $mods -InstalledPackages @('a.b.c', 'd.e.f')
        Assert-HidingTruth -Condition ($stDet.Installed -eq $true) -Message 'Test-HidingEntryInstalled 正控：检测器按包名判已装'
        $stDetNo = Test-HidingEntryInstalled -Entry @{ id = 'detector-x'; category = 'detector'; packageName = 'zzz' } -Modules $mods -InstalledPackages @('a.b.c')
        Assert-HidingTruth -Condition ($stDetNo.Installed -eq $false) -Message 'Test-HidingEntryInstalled 负控：包名不在清单里必须判未装'
        $stData = Test-HidingEntryInstalled -Entry @{ id = 'data-x'; category = 'data' } -Modules $mods
        Assert-HidingTruth -Condition ($stData.Installed -eq $false) -Message 'Test-HidingEntryInstalled 负控：data 类目恒为未装'

        # ===== 6. Read-FeatureConfig：真实 72 字节样本 + 负控 =====
        # 样本 = 只读资源包 D:\ai\隐藏环境资源包\.feature_config 的字节（72B；magic/version=1/count=5）：
        #   id=2 value=0, id=0 value=1, id=1 value=1, id=3 value=0, id=4 value=1
        $fcHex = '55534b7f 01000000 05000000 02000000 00000000 00000000 00000000 01000000 00000000 01000000 01000000 00000000 03000000 00000000 00000000 04000000 01000000 00000000'
        $fcBytes = ConvertFrom-HexString $fcHex
        Assert-HidingTruth -Condition ($fcBytes.Length -eq 72) -Message 'Read-FeatureConfig 样本构造错误：应为 72 字节'
        $fc = Read-FeatureConfig -Bytes $fcBytes
        Assert-HidingTruth -Condition ($fc.Ok -eq $true) -Message ('Read-FeatureConfig 正控：真实样本应解码成功，实际 Why=' + $fc.Why)
        Assert-HidingTruth -Condition ($fc.Features['selinux_hide'] -eq 1) -Message 'Read-FeatureConfig 正控：selinux_hide=4 必须为 1'
        Assert-HidingTruth -Condition ($fc.Features['su_compat'] -eq 1) -Message 'Read-FeatureConfig 正控：su_compat=0 必须为 1'
        Assert-HidingTruth -Condition ($fc.Features['kernel_umount'] -eq 1) -Message 'Read-FeatureConfig 正控：kernel_umount=1 必须为 1'
        Assert-HidingTruth -Condition ($fc.Features['sulog'] -eq 0) -Message 'Read-FeatureConfig 正控：sulog=2 必须为 0'
        Assert-HidingTruth -Condition ($fc.Features['adb_root'] -eq 0) -Message 'Read-FeatureConfig 正控：adb_root=3 必须为 0'
        Assert-HidingTruth -Condition ($null -eq $fc.Features['webview_zygote_umount']) -Message 'Read-FeatureConfig 正控：文件里没有 id=5 的记录时不得伪造 0（不可得 = $null）'
        Assert-HidingTruth -Condition ($fc.Why -match 'webview_zygote_umount\(5\)') -Message 'Read-FeatureConfig 正控：缺失的已知 id 必须写进 Why'
        # 正控：plain 布局（magic + count + records），任务书写法
        $plainBytes = ConvertFrom-HexString '55534b7f 02000000 00000000 01000000 00000000 04000000 01000000 00000000'
        $fcPlain = Read-FeatureConfig -Bytes $plainBytes
        Assert-HidingTruth -Condition ($fcPlain.Ok -eq $true) -Message ('Read-FeatureConfig 正控：plain 布局（magic+count）也应解码，实际 Why=' + $fcPlain.Why)
        Assert-HidingTruth -Condition ($fcPlain.Features['selinux_hide'] -eq 1) -Message 'Read-FeatureConfig 正控：plain 布局 selinux_hide=1'
        # 负控：magic 错
        $badMagic = ConvertFrom-HexString '00000000 01000000 05000000 02000000 00000000 00000000'
        $fcBad = Read-FeatureConfig -Bytes $badMagic
        Assert-HidingTruth -Condition ($fcBad.Ok -eq $false) -Message 'Read-FeatureConfig 负控：magic 不匹配必须 Ok=$false'
        Assert-HidingTruth -Condition ($fcBad.Why -match 'magic') -Message 'Read-FeatureConfig 负控：magic 失败要说清原因'
        # 负控：截断
        $trunc = [byte[]]($fcBytes[0..67])
        $fcTrunc = Read-FeatureConfig -Bytes $trunc
        Assert-HidingTruth -Condition ($fcTrunc.Ok -eq $false) -Message 'Read-FeatureConfig 负控：截断文件必须 Ok=$false'
        # 负控：空
        Assert-HidingTruth -Condition ((Read-FeatureConfig -Bytes ([byte[]]@())).Ok -eq $false) -Message 'Read-FeatureConfig 负控：空输入必须 Ok=$false'
        Assert-HidingTruth -Condition ((Read-FeatureConfig -Bytes $null).Ok -eq $false) -Message 'Read-FeatureConfig 负控：$null 输入必须 Ok=$false'
        # 负控：值不是 0/1
        $badVal = ConvertFrom-HexString '55534b7f 01000000 01000000 04000000 07000000 00000000'
        $fcBadVal = Read-FeatureConfig -Bytes $badVal
        Assert-HidingTruth -Condition ($fcBadVal.Ok -eq $false) -Message 'Read-FeatureConfig 负控：value=7 必须 Ok=$false'
        Assert-HidingTruth -Condition ($fcBadVal.Features['selinux_hide'] -eq 7) -Message 'Read-FeatureConfig 负控：越界值要如实回填原值'
        # 正控：未知 id 如实建档
        $unkBytes = ConvertFrom-HexString '55534b7f 01000000 01000000 63000000 01000000 00000000'
        $fcUnk = Read-FeatureConfig -Bytes $unkBytes
        Assert-HidingTruth -Condition ($fcUnk.Features['feature_99'] -eq 1) -Message 'Read-FeatureConfig 正控：未知 id 记为 feature_<id>'

        # ===== 7. Get-RuntimeModeFromDebugInfo：真实输出格式 =====
        $diLkm = @('version: 1234', 'flags: 0x1', 'uapi_version: 2', 'features: 0x0', 'lkm: true', 'late_load: false', 'runtime_mode: lkm', 'pr_build: false')
        $rm1 = Get-RuntimeModeFromDebugInfo -Lines $diLkm
        Assert-HidingTruth -Condition ($rm1.Mode -eq 'Lkm') -Message 'Get-RuntimeModeFromDebugInfo 正控：flags 0x1 ⇒ Lkm'
        Assert-HidingTruth -Condition ($rm1.Why -match 'flags: 0x1') -Message 'Get-RuntimeModeFromDebugInfo 正控：Why 必须含证据行原文'
        $diGki = @('version: 1234', 'flags: 0x8', 'lkm: false', 'late_load: true', 'runtime_mode: late-load')
        $rm2 = Get-RuntimeModeFromDebugInfo -Lines $diGki
        Assert-HidingTruth -Condition ($rm2.Mode -eq 'GkiBuiltIn') -Message 'Get-RuntimeModeFromDebugInfo 正控：bit0 为 0 ⇒ GkiBuiltIn（即使 late_load 行存在）'
        $rm3 = Get-RuntimeModeFromDebugInfo -Lines @('flags: 0x9', 'lkm: true')
        Assert-HidingTruth -Condition ($rm3.Mode -eq 'Lkm') -Message 'Get-RuntimeModeFromDebugInfo 正控：0x9 仍含 bit0 ⇒ Lkm'
        $rm4 = Get-RuntimeModeFromDebugInfo -Lines @('lkm: true')
        Assert-HidingTruth -Condition ($rm4.Mode -eq 'Lkm') -Message 'Get-RuntimeModeFromDebugInfo 正控：只有 lkm: true 时也能判定'
        $rm5 = Get-RuntimeModeFromDebugInfo -Lines @('flags: 1', 'lkm: true')
        Assert-HidingTruth -Condition ($rm5.Mode -eq 'Lkm') -Message 'Get-RuntimeModeFromDebugInfo 正控：十进制 flags 也应解析'
        $rm6 = Get-RuntimeModeFromDebugInfo -Lines @('flags: 0x0', 'lkm: true')
        Assert-HidingTruth -Condition ($rm6.Mode -eq 'GkiBuiltIn') -Message 'Get-RuntimeModeFromDebugInfo 正控：flags 为主证据（与 lkm: 行冲突时以 flags 为准）'
        Assert-HidingTruth -Condition ($rm6.Why -match '不一致') -Message 'Get-RuntimeModeFromDebugInfo 正控：冲突必须在 Why 里点明'
        $rm7 = Get-RuntimeModeFromDebugInfo -Lines @('ksud: not found', 'Permission denied')
        Assert-HidingTruth -Condition ($rm7.Mode -eq 'Unknown') -Message 'Get-RuntimeModeFromDebugInfo 负控：无可解析证据必须 Unknown'
        $rm8 = Get-RuntimeModeFromDebugInfo -Lines @()
        Assert-HidingTruth -Condition ($rm8.Mode -eq 'Unknown') -Message 'Get-RuntimeModeFromDebugInfo 负控：空输出必须 Unknown'
        Assert-HidingTruth -Condition ((Get-RuntimeModeFromDebugInfo -Lines $null).Mode -eq 'Unknown') -Message 'Get-RuntimeModeFromDebugInfo 负控：$null 输出必须 Unknown'

        # ===== 8. Invoke-Adb / Invoke-AdbShell / Invoke-SuShell：不可用时不静默 =====
        $bogusAdb = Join-Path ([System.IO.Path]::GetTempPath()) 'ksu-hide-no-such-adb.exe'
        $a1 = Invoke-Adb -Adb $bogusAdb -Serial 'x' -Arguments @('devices')
        Assert-HidingTruth -Condition ($a1.ExitCode -eq -1) -Message 'Invoke-Adb 负控：adb 不存在必须返回 -1（且不抛异常）'
        Assert-HidingTruth -Condition (($a1.Output -join ' ') -match 'not found') -Message 'Invoke-Adb 负控：必须给出 adb 不存在说明'
        $a2 = Invoke-AdbShell -Adb $bogusAdb -Serial 'x' -Command 'id'
        Assert-HidingTruth -Condition ($a2.ExitCode -eq -1) -Message 'Invoke-AdbShell 负控：adb 不存在必须返回 -1'
        $a3 = Invoke-SuShell -Adb $bogusAdb -Serial 'x' -Command 'id -u'
        Assert-HidingTruth -Condition ($a3.ExitCode -eq -1) -Message 'Invoke-SuShell 负控：su 不可用必须返回 -1'
        Assert-HidingTruth -Condition (($a3.Output -join ' ') -match '^su unavailable:') -Message 'Invoke-SuShell 负控：输出行必须以 su unavailable: 开头'
        Assert-HidingThrows { Invoke-SuShell -Adb $bogusAdb -Serial 'x' -Command '' } 'Invoke-SuShell 负控：空命令必须 throw'

        # ===== 9. 三个计划构造器：形状 + §7 红线兜底 =====
        $pp = Get-PropertyPlan
        Assert-HidingTruth -Condition (@($pp).Count -eq 28) -Message ('Get-PropertyPlan 正控：应为 28 步（23 set + 4 del + 1 通配探查），实际=' + @($pp).Count)
        $ppBad = @($pp | Where-Object { $null -eq $_.Stage -or $null -eq $_.Name -or $null -eq $_.Command -or $null -eq $_.Why })
        Assert-HidingTruth -Condition ($ppBad.Count -eq 0) -Message 'Get-PropertyPlan 正控：每步都必须有 Stage/Name/Command/Why'
        Assert-HidingTruth -Condition ((@($pp | Where-Object { $_.Stage -ne 'S6' })).Count -eq 0) -Message 'Get-PropertyPlan 正控：Stage 必须都是 S6'
        Assert-HidingTruth -Condition ((@($pp | Where-Object { $_.Command -notmatch '^/data/adb/ksu/bin/resetprop ' })).Count -eq 0) -Message 'Get-PropertyPlan 正控：命令必须走 /data/adb/ksu/bin/resetprop 绝对路径'
        Assert-HidingTruth -Condition ((@($pp | Where-Object { $_.Command -match ' -n ro\.debuggable 0$' })).Count -eq 1) -Message 'Get-PropertyPlan 正控：应包含 resetprop -n ro.debuggable 0'
        Assert-HidingTruth -Condition ((@($pp | Where-Object { $_.Command -match ' -n -d persist\.service\.adb\.enable$' })).Count -eq 1) -Message 'Get-PropertyPlan 正控：删除项必须用 -n -d'
        Assert-HidingTruth -Condition ((@($pp | Where-Object { $_.Command -match 'resetprop -n -d persist\.com\.luckyzyx\.luckytool\.\*' })).Count -eq 0) -Message 'Get-PropertyPlan 负控：通配属性名不得被当成真实属性名去 delete'
        # 通配探查是"报告"，不是"动作"：grep 无命中时退出 1，而"没有这个痕迹"正是期望结果。
        # 必须显式吞掉退出码，否则将来把它接进执行器会把一台干净设备报成失败。
        Assert-HidingTruth -Condition ((@($pp | Where-Object { $_.Name -like 'enumerate:*' })).Count -eq 1) -Message 'Get-PropertyPlan 正控：应恰有 1 步通配探查'
        Assert-HidingTruth -Condition ((@($pp | Where-Object { $_.Name -like 'enumerate:*' -and $_.Command -notmatch '\|\s*true$' })).Count -eq 0) -Message 'Get-PropertyPlan 正控：通配探查步必须以 || true 收尾（退出码不得被当成一步失败）'
        $kp = Get-KeyboxPlan
        Assert-HidingTruth -Condition (@($kp).Count -eq 6) -Message ('Get-KeyboxPlan 正控：应为 6 步，实际=' + @($kp).Count)
        $kpNames = (@($kp) | ForEach-Object { $_.Name }) -join ','
        Assert-HidingTruth -Condition ($kpNames -eq 'chattr -i,write-tmp,chown 0:0,chmod 600,mv -f,test -s') -Message ('Get-KeyboxPlan 正控：步骤顺序固定为 chattr -i → .tmp → chown → chmod → mv -f → test -s，实际=' + $kpNames)
        Assert-HidingTruth -Condition ((@($kp | Where-Object { $_.Command -notmatch '/data/adb/tricky_store/keybox\.xml' })).Count -eq 0) -Message 'Get-KeyboxPlan 正控：所有命令都必须针对 /data/adb/tricky_store/keybox.xml'
        Assert-HidingTruth -Condition ((@($kp | Where-Object { $_.Command -match '(?i)\b(cat|base64|sha256sum|hexdump|od|head|tail)\b' })).Count -eq 0) -Message 'Get-KeyboxPlan 负控：绝不能出现任何会读取/打印 keybox 内容的命令'

        # ----- §4.4 OMK 的两块：security patch 伪装 + target.txt 静态装入 -----
        $sp = Get-SecurityPatchPlan -SecurityPatchContent "system=2025-01-01`nboot=2025-01-01`nvendor=2025-01-02"
        Assert-HidingTruth -Condition (@($sp).Count -eq 2) -Message ('Get-SecurityPatchPlan 正控：system+vendor 两个键 => 2 步，实际=' + @($sp).Count)
        Assert-HidingTruth -Condition ((@($sp | Where-Object { $_.Stage -ne 'S8' })).Count -eq 0) -Message 'Get-SecurityPatchPlan 正控：Stage 必须都是 S8'
        Assert-HidingTruth -Condition ((@($sp | Where-Object { $_.Command -match ' -n ro\.build\.version\.security_patch 2025-01-01$' })).Count -eq 1) -Message 'Get-SecurityPatchPlan 正控：ro.build.version.security_patch ← system'
        Assert-HidingTruth -Condition ((@($sp | Where-Object { $_.Command -match ' -n ro\.vendor\.build\.security_patch 2025-01-02$' })).Count -eq 1) -Message 'Get-SecurityPatchPlan 正控：ro.vendor.build.security_patch ← vendor（不是 system）'
        Assert-HidingTruth -Condition ((@($sp | Where-Object { $_.Command -match 'boot' })).Count -eq 0) -Message 'Get-SecurityPatchPlan 负控：报告只动两个属性，boot 键不得产生步骤'
        Assert-HidingTruth -Condition ((@($sp | Where-Object { $_.Command -notmatch '^/data/adb/ksu/bin/resetprop ' })).Count -eq 0) -Message 'Get-SecurityPatchPlan 正控：命令必须走 /data/adb/ksu/bin/resetprop 绝对路径'
        Assert-HidingTruth -Condition (@(Get-SecurityPatchPlan -SecurityPatchContent '').Count -eq 0) -Message 'Get-SecurityPatchPlan 负控：空内容必须 0 步（绝不猜）'
        Assert-HidingTruth -Condition (@(Get-SecurityPatchPlan -SecurityPatchContent "system=not-a-date`nvendor=").Count -eq 0) -Message 'Get-SecurityPatchPlan 负控：非 YYYY-MM-DD 的值必须被拒（不得灌进系统属性）'
        $tp = Get-TargetListPlan
        Assert-HidingTruth -Condition (@($tp).Count -eq 5) -Message ('Get-TargetListPlan 正控：应为 5 步，实际=' + @($tp).Count)
        $tpNames = (@($tp) | ForEach-Object { $_.Name }) -join ','
        Assert-HidingTruth -Condition ($tpNames -eq 'mkdir -p,cp -f,chown 0:0,chmod 600,test -s') -Message ('Get-TargetListPlan 正控：步骤顺序固定，实际=' + $tpNames)
        Assert-HidingTruth -Condition ((@($tp | Where-Object { $_.Stage -ne 'S9' })).Count -eq 0) -Message 'Get-TargetListPlan 正控：Stage 必须都是 S9'
        Assert-HidingTruth -Condition ((@($tp | Where-Object { $_.Command -notmatch '/data/adb/tricky_store/target\.txt' })).Count -eq 1) -Message 'Get-TargetListPlan 正控：只有 mkdir 那一步不指向 target.txt 本身'
        Assert-HidingTruth -Condition ((@($tp | Where-Object { $_.Command -match '(?i)\b(cat|head|tail|wc)\b' })).Count -eq 0) -Message 'Get-TargetListPlan 负控：不得打印那 220 行列表内容'
        $cp = Get-CleanupPlan -Entries @(@{ id = 'detector-a.b.c'; category = 'detector'; packageName = 'a.b.c' }) -ArtCachePaths @('/data/adb/lspd-cache')
        Assert-HidingTruth -Condition ((@($cp | Where-Object { $_.Stage -ne 'S10' })).Count -eq 0) -Message 'Get-CleanupPlan 正控：Stage 必须都是 S10'
        Assert-HidingTruth -Condition ((@($cp | Where-Object { $_.Command -match '\*' })).Count -eq 0) -Message 'Get-CleanupPlan 负控（§7 红线）：任何命令都不得含通配符（第 15 步按扩展名通配删除被明令禁止）'
        Assert-HidingTruth -Condition ((@($cp | Where-Object { $_.Command -match 'MT2' })).Count -eq 0) -Message 'Get-CleanupPlan 负控（§7 红线）：不得删除 /storage/emulated/0/MT2/'
        Assert-HidingTruth -Condition ((@($cp | Where-Object { $_.Command -match 'rm -rf /storage/emulated/0/Android/data/a\.b\.c' })).Count -eq 1) -Message 'Get-CleanupPlan 正控：应清掉 detector 条目的 app 数据目录'
        Assert-HidingTruth -Condition ((@($cp | Where-Object { $_.Name -eq 'violet-staging' })).Count -eq 1) -Message 'Get-CleanupPlan 正控：应含第 16 步 /storage/emulated/0/violet/'
        Assert-HidingTruth -Condition ((@($cp | Where-Object { $_.Command -eq 'rm -rf /data/adb/lspd-cache' })).Count -eq 1) -Message 'Get-CleanupPlan 正控：-ArtCachePaths 传入的路径应逐条出现'
        Assert-HidingTruth -Condition ((@($cp)[-1].Name) -eq 'sync') -Message 'Get-CleanupPlan 正控：最后一步必须是 sync'
        $cpNoArg = Get-CleanupPlan
        Assert-HidingTruth -Condition ((@($cpNoArg | Where-Object { $_.Command -match '\*' })).Count -eq 0) -Message 'Get-CleanupPlan 负控：无参数调用同样不得出现通配删除'
        Assert-HidingTruth -Condition (@($cp).Count -gt 0 -and @($cpNoArg).Count -gt 0) -Message 'Get-CleanupPlan 正控：无参数调用必须仍返回计划'

        # ===== 10. Assert-HidingTruth 本身：必须能失败 =====
        Assert-HidingTruth -Condition $true -Message 'Assert-HidingTruth 正控：$true 不应抛出'
        Assert-HidingThrows { Assert-HidingTruth -Condition $false -Message 'selftest-expected-failure' } 'Assert-HidingTruth 负控：$false 必须抛出'
        Assert-HidingThrows { Assert-HidingTruth -Condition $null -Message 'selftest-null-condition' } 'Assert-HidingTruth 负控：$null 条件必须按 false 抛出'
    } finally {
        if (Test-Path -LiteralPath $tmpRoot) { Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue }
    }

    # ===== 11. 真实 manifest 正控（可选：给了 -RepoRoot 且文件存在时）=====
    if (-not [string]::IsNullOrWhiteSpace($RepoRoot)) {
        $realManifest = Join-Path $RepoRoot 'manager\app\src\main\assets\hiding\manifest.json'
        if (Test-Path -LiteralPath $realManifest -PathType Leaf) {
            $real = Read-HidingManifest -RepoRoot $RepoRoot
            Assert-HidingTruth -Condition ($real.Ok -eq $true) -Message 'Read-HidingManifest 正控：真实 pack manifest 必须通过与资产逐项核对'
            Assert-HidingTruth -Condition (@($real.Entries).Count -ge 1) -Message 'Read-HidingManifest 正控：真实 manifest 至少要有 1 条'
        }
    }

    Write-Host 'SELFTEST OK'
    return 0
}
