<#
.SYNOPSIS
    只读汇报本地「环境隐藏」现状：设备 + assets 隐藏资源包 manifest + 运行模式 + 模块/特性/keybox/检测器。

.DESCRIPTION
    本脚本**只读**：不写入、不装模块、不改属性、不写 keybox。

    数据来源：
      * 本地：manager/app/src/main/assets/hiding/manifest.json（Read-HidingManifest 强校验；校验失败即退出非 0，
             绝不把 sha256/字节数不匹配降级成警告）
      * 设备：uname -r、ksud debug info、ksud module list、/data/adb/ksu/.feature_config、
              /data/adb/tricky_store/keybox.xml 的**存在性/字节数/sha256 前 8 位**、pm list packages

    判定纪律：
      * 版本判定**只用相等**（绝不用小于号）：AlwaysStrong 版 tricky_store 声明 versionCode=104，
        被它替换的构建声明 235/307，用小于号会把「已装」误判成「更旧」。
      * PathMask 六个分支共用 moduleId=pathmask，所以「哪个分支已装」必须额外比 module.updateJson 的末段
        （<branch>.json）。
      * ksud module list 的 JSON 里 enabled/update 等字段是**字符串** "true"/"false"，不是布尔。
      * 运行模式三态：Lkm / GkiBuiltIn / Unknown；Unknown 时模式过滤必须是 no-op（fail-closed）。
      * 绝不打印 keybox.xml 的内容（含明文 EC 私钥）：只报存在性、字节数与 sha256 前 8 位。

.PARAMETER Serial
    目标设备序列号。省略时：恰好一台 device 态设备就用它；0 台或多台都明确报错并退出非 0。

.PARAMETER Json
    输出**一个** JSON 对象（供后续脚本消费），不输出人类可读报告。

.PARAMETER SelfTest
    只跑离线自检（Invoke-HidingLibSelfTest，不需要设备、不碰 adb），以其返回码退出。

.EXAMPLE
    pwsh -File _tools\hiding-status.ps1
.EXAMPLE
    pwsh -File _tools\hiding-status.ps1 -Serial 67a86199 -Json
.EXAMPLE
    pwsh -File _tools\hiding-status.ps1 -SelfTest
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [switch]$Json,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

# 同目录点源纯函数库（本文件只做 IO 编排，判定逻辑全在库里，便于 -SelfTest 离线验证）。
. (Join-Path $PSScriptRoot 'hiding-lib.ps1')

if ($SelfTest) {
    # 离线自检：必须有能失败的负向对照，否则不算自检。
    try {
        $code = Invoke-HidingLibSelfTest
    } catch {
        Write-Host ("SELFTEST FAILED: " + $_.Exception.Message)
        exit 1
    }
    exit $code
}

$repoRoot  = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $repoRoot

$notes = New-Object System.Collections.ArrayList
function Add-Note([string]$m) { [void]$notes.Add($m) }
function Say([string]$m) { if (-not $Json) { Write-Host $m } }

# ---------------- adb ----------------
# 约定：<workspace>\platform-tools\adb.exe 优先，PATH 兜底。
$adb = $null
$adbCandidate = Join-Path (Join-Path $workspace 'platform-tools') 'adb.exe'
if (Test-Path -LiteralPath $adbCandidate) {
    $adb = $adbCandidate
} else {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { $adb = $cmd.Source }
}
if ([string]::IsNullOrEmpty($adb)) {
    Write-Host ("没有找到 adb：既没有 " + $adbCandidate + "，PATH 里也没有 adb。")
    exit 2
}

# ---------------- 设备状态（在任何其它 shell 动作之前完成判定） ----------------
$devRes = Invoke-Adb -Adb $adb -Serial '' -Arguments @('devices')
if ($devRes.ExitCode -ne 0) {
    Write-Host ("adb devices 失败（退出码 " + $devRes.ExitCode + "）：")
    foreach ($l in @($devRes.Output)) { Write-Host ("  " + [string]$l) }
    exit 2
}

$all = @()
foreach ($line in @($devRes.Output)) {
    $t = ([string]$line).Trim()
    if ($t -eq '') { continue }
    if ($t -match '^List of devices') { continue }
    if ($t -match '^\*') { continue }
    if ($t -match '^(\S+)\s+(\S+)') {
        $all += [ordered]@{ serial = $Matches[1]; state = $Matches[2] }
    }
}
$online = @($all | Where-Object { $_.state -ceq 'device' })

$target = $null
if (-not [string]::IsNullOrEmpty($Serial)) {
    $hit = @($all | Where-Object { $_.serial -ceq $Serial })
    if ($hit.Count -eq 0) {
        Write-Host ("指定的设备 " + $Serial + " 不在 adb devices 列表里。当前：")
        foreach ($d in $all) { Write-Host ("  " + $d.serial + "`t" + $d.state) }
        exit 2
    }
    if ($hit[0].state -cne 'device') {
        Write-Host ("设备 " + $Serial + " 状态是 '" + $hit[0].state + "'，不是 device 态。")
        if ($hit[0].state -ceq 'unauthorized') { Write-Host "  unauthorized：需要在设备上确认 USB 调试授权弹窗。" }
        Write-Host "不做任何后续操作。"
        exit 2
    }
    $target = $hit[0]
} else {
    if ($online.Count -eq 0) {
        Write-Host "没有处于 device 状态的设备（不碰 adb shell/su，直接退出）。当前 adb devices："
        if ($all.Count -eq 0) { Write-Host "  （列表为空）" }
        foreach ($d in $all) { Write-Host ("  " + $d.serial + "`t" + $d.state) }
        Write-Host "提示：unauthorized 需要在设备上确认授权；offline 可试 adb reconnect。"
        exit 2
    }
    if ($online.Count -gt 1) {
        Write-Host "检测到多台 device 态设备，请用 -Serial 明确指定："
        foreach ($d in $online) { Write-Host ("  " + $d.serial) }
        exit 2
    }
    $target = $online[0]
}
$serial = [string]$target.serial
$state  = [string]$target.state

# ---------------- 本地 manifest（强校验） ----------------
$manifestBlock = $null
$entries = @()
try {
    $m = Read-HidingManifest -RepoRoot $repoRoot
    $entries = @($m.Entries)
    $mcats = [ordered]@{}
    foreach ($c in @('module', 'pathmask', 'detector', 'data')) {
        $mcats[$c] = (@($entries | Where-Object { [string](Get-HidingField $_ 'category') -ceq $c })).Count
    }
    $manifestBlock = [ordered]@{
        path        = [string]$m.Path
        sha256      = [string]$m.Sha256
        version     = $m.Manifest.version
        generatedAt = $m.Manifest.generatedAt
        sourcePack  = $m.Manifest.sourcePack
        includes    = $m.Manifest.includes
        entryCount  = $entries.Count
        totalBytes  = $m.Manifest.totalBytes
        categories  = $mcats
    }
} catch {
    # 完整性失败绝不降级为警告：报告无法给出可信的条目现状，直接以非 0 退出。
    $msg = $_.Exception.Message
    if ($Json) {
        $errObj = [ordered]@{ ok = $false; stage = 'manifest'; serial = $serial; state = $state; error = $msg }
        Write-Output ($errObj | ConvertTo-Json -Depth 6)
    } else {
        Write-Host ("隐藏资源包 manifest 校验失败（不降级为警告）：" + $msg)
        Write-Host ("路径：" + (Join-Path (Join-Path $repoRoot 'manager\app\src\main\assets\hiding') 'manifest.json'))
    }
    exit 1
}

# ---------------- 设备侧只读探测 ----------------
$ksud = Get-HidingKsudPath

$unameRes = Invoke-AdbShell -Adb $adb -Serial $serial -Command 'uname -r'
$kernelRelease = ''
if ($unameRes.ExitCode -eq 0) {
    $kernelRelease = ((@($unameRes.Output) -join ' ')).Trim()
} else {
    Add-Note ("uname -r 失败（退出码 " + $unameRes.ExitCode + "）：无法判定 PathMask 分支推荐")
}

$diRes = Invoke-SuShell -Adb $adb -Serial $serial -Command ($ksud + ' debug info')
$diLines = @($diRes.Output)
$suUsable = ($diRes.ExitCode -ge 0)
if (-not $suUsable) {
    Add-Note ("su 不可用（" + ((@($diLines) -join ' ').Trim()) + "）⇒ 归因两条：① 设备未授权/未 root；② SELinux 拒绝 su（App 自身 root shell 仍可用）。运行模式/模块/特性一律按「未知」处理，不猜。")
}
$rm = Get-RuntimeModeFromDebugInfo -Lines $diLines
$runtimeMode = [string]$rm.Mode
$packMode = ''
if ($runtimeMode -ceq 'Lkm') { $packMode = 'lkm' }
elseif ($runtimeMode -ceq 'GkiBuiltIn') { $packMode = 'gki' }
if ($runtimeMode -ceq 'Unknown') {
    Add-Note '未检测到运行模式（Unknown）⇒ 模式过滤按 no-op 处理：报告显示全部内容，不断言适用性。'
}

$verRes = Invoke-SuShell -Adb $adb -Serial $serial -Command ($ksud + ' -V')
$ksudVersion = ''
if ($verRes.ExitCode -eq 0) { $ksudVersion = ((@($verRes.Output) -join ' ')).Trim() }

$mlRes = Invoke-SuShell -Adb $adb -Serial $serial -Command ($ksud + ' module list')
$mods = @()
$modulesAvailable = $false
if ($mlRes.ExitCode -eq 0) {
    # 只用 stdout，并**从首个 '[' 切到末个 ']'**：ksud 可能在前面打印别的东西（日志/警告）。
    # 解析本身交给库函数 ConvertFrom-HidingModuleList —— 它已修掉 PowerShell 5.1
    # `@(ConvertFrom-Json ...)` 的包装陷阱（会把结果再包一层成 1 元素数组，
    # 导致按 id 匹配全部落空、把所有已装模块误报成未装；真实踩过）。
    $raw = (@($mlRes.Output) -join "`n")
    $i0 = $raw.IndexOf('[')
    $i1 = $raw.LastIndexOf(']')
    if ($i0 -ge 0 -and $i1 -gt $i0) {
        $mods = @(ConvertFrom-HidingModuleList -Text $raw)
        $modulesAvailable = $true
    } else {
        Add-Note 'ksud module list 输出里没有 JSON 数组（未找到 [ ... ]）'
    }
} else {
    Add-Note ("ksud module list 失败（退出码 " + $mlRes.ExitCode + "）：模块/PathMask 的「已安装」判定为未知，不猜。")
}

# 特性配置：base64 读回后本地解码（绝不手推二进制、也绝不写回）。
$fcRes = Invoke-SuShell -Adb $adb -Serial $serial -Command 'base64 /data/adb/ksu/.feature_config'
$fcBytes = $null
$fcParse = $null
$fcWhy = ''
if ($fcRes.ExitCode -ne 0) {
    $fcWhy = "读取失败（退出码 " + $fcRes.ExitCode + "）：" + ((@($fcRes.Output) -join ' ').Trim())
} else {
    $b64 = ((@($fcRes.Output) -join '') -replace '\s', '')
    if ($b64 -eq '') {
        $fcWhy = '文件为空或不存在'
    } else {
        try {
            $fcBytes = [Convert]::FromBase64String($b64)
            $fcParse = Read-FeatureConfig -Bytes $fcBytes
            $fcWhy = [string]$fcParse.Why
        } catch {
            $fcWhy = "解码失败：" + $_.Exception.Message
        }
    }
}

# keybox：只报存在性/字节数/sha256 前 8 位；命令里没有任何读取内容的动作。
$kbRes = Invoke-SuShell -Adb $adb -Serial $serial -Command 'test -s /data/adb/tricky_store/keybox.xml && wc -c /data/adb/tricky_store/keybox.xml && sha256sum /data/adb/tricky_store/keybox.xml || echo KEYBOX_MISSING'
$kbPresent = $null
$kbSize = $null
$kbSha8 = $null
if ($kbRes.ExitCode -ne 0) {
    $kbPresent = $null
} else {
    foreach ($l in @($kbRes.Output)) {
        $t = ([string]$l).Trim()
        if ($t -ceq 'KEYBOX_MISSING') { $kbPresent = $false; continue }
        if ($t -match '^(\d+)\s+\S') { $kbSize = [int64]$Matches[1]; $kbPresent = $true; continue }
        if ($t -match '^([0-9a-fA-F]{64})') {
            $kbSha8 = $Matches[1].Substring(0, 8).ToLowerInvariant()
            $kbPresent = $true
            continue
        }
    }
    if ($null -eq $kbPresent) { $kbPresent = $false }
}

# 已安装包集合（检测器判定用）。
$pmRes = Invoke-AdbShell -Adb $adb -Serial $serial -Command 'pm list packages'
$pkgList = @()
$pmAvailable = $false
if ($pmRes.ExitCode -eq 0) {
    $pkgList = @(@($pmRes.Output) | Where-Object { ([string]$_).StartsWith('package:') } | ForEach-Object { ([string]$_).Substring(8).Trim() })
    $pmAvailable = $true
} else {
    Add-Note ("pm list packages 失败（退出码 " + $pmRes.ExitCode + "）：检测器「已安装」判定为未知。")
}

# ---------------- 逐条目现状 ----------------
$rows = @()
$installedCount = 0
$unknownCount = 0
$pendingCount = 0
foreach ($e in $entries) {
    $cat = [string](Get-HidingField $e 'category')
    $row = [ordered]@{
        id         = [string](Get-HidingField $e 'id')
        category   = $cat
        name       = [string](Get-HidingField $e 'name')
        version    = [string](Get-HidingField $e 'version')
        versionCode = Get-HidingField $e 'versionCode'
        moduleId   = [string](Get-HidingField $e 'moduleId')
        branch     = [string](Get-HidingField $e 'branch')
        order      = Get-HidingEntryOrder $e
        appliesHere = [bool](Test-HidingEntryAppliesTo -Entry $e -Mode $packMode)
        installed  = $false
        pendingReboot = $false
        why        = ''
    }
    $st = $null
    if ($cat -ceq 'detector') {
        if ($pmAvailable) { $st = Test-HidingEntryInstalled -Entry $e -Modules @() -InstalledPackages $pkgList }
        else { $st = @{ Installed = $null; PendingReboot = $false; Why = 'pm list packages 不可用：无法判定（不猜）' } }
    } elseif ($cat -ceq 'data') {
        $st = @{ Installed = $false; PendingReboot = $false; Why = 'data 类目无「已安装」概念' }
    } else {
        if ($modulesAvailable) { $st = Test-HidingEntryInstalled -Entry $e -Modules $mods -InstalledPackages @() }
        else { $st = @{ Installed = $null; PendingReboot = $false; Why = 'ksud module list 不可用：无法判定（不猜）' } }
    }
    $row.installed = $st.Installed
    $row.pendingReboot = [bool]$st.PendingReboot
    $row.why = [string]$st.Why
    if ($null -eq $st.Installed) { $unknownCount++ }
    elseif ($st.Installed) { $installedCount++ }
    if ($st.PendingReboot) { $pendingCount++ }
    $rows += $row
}

# ---------------- 报告 ----------------
$detectors = @($rows | Where-Object { $_.category -ceq 'detector' })
$report = [ordered]@{
    ok          = $true
    generatedAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')
    repoRoot    = $repoRoot
    adb         = $adb
    device      = [ordered]@{ serial = $serial; state = $state; all = @($all) }
    manifest    = $manifestBlock
    kernel      = [ordered]@{ release = $kernelRelease }
    runtimeMode = [ordered]@{ mode = $runtimeMode; packMode = $packMode; why = [string]$rm.Why }
    ksud        = [ordered]@{ version = $ksudVersion; debugInfo = @($diLines) }
    su          = [ordered]@{ usable = $suUsable }
    modules     = [ordered]@{
        available = $modulesAvailable
        count     = $mods.Count
        note      = 'ksud module list 的 enabled/update 等字段是字符串 "true"/"false"，不是布尔'
        list      = @($mods)
    }
    featureConfig = [ordered]@{
        path        = '/data/adb/ksu/.feature_config'
        bytes       = $(if ($null -ne $fcBytes) { $fcBytes.Length } else { $null })
        parsed      = $(if ($null -ne $fcParse) { [bool]$fcParse.Ok } else { $null })
        selinuxHide = $(if ($null -ne $fcParse -and $fcParse.Ok) { $fcParse.Features['selinux_hide'] } else { $null })
        features    = $(if ($null -ne $fcParse -and $fcParse.Ok) { $fcParse.Features } else { $null })
        why         = $fcWhy
    }
    keybox      = [ordered]@{
        path         = '/data/adb/tricky_store/keybox.xml'
        present      = $kbPresent
        sizeBytes    = $kbSize
        sha256Prefix = $kbSha8
        note         = '只读：只报存在性/字节数/sha256 前 8 位；内容（含明文 EC 私钥）绝不显示'
    }
    detectors   = [ordered]@{
        available      = $pmAvailable
        packageTotal   = @($pkgList).Count
        installedCount = (@($detectors | Where-Object { $_.installed -eq $true })).Count
        missingCount   = (@($detectors | Where-Object { $_.installed -eq $false })).Count
        unknownCount   = (@($detectors | Where-Object { $null -eq $_.installed })).Count
    }
    summary     = [ordered]@{
        entries        = $entries.Count
        installed      = $installedCount
        pendingReboot  = $pendingCount
        unknown        = $unknownCount
    }
    entries     = @($rows)
    notes       = @($notes)
}

if ($Json) {
    Write-Output ($report | ConvertTo-Json -Depth 8)
    exit 0
}

# ---- 人类可读 ----
Say "=== 设备 ==="
Say ("serial       : " + $serial)
Say ("state        : " + $state)
Say ("adb          : " + $adb)
Say ("内核 release : " + $(if ($kernelRelease -eq '') { '（未取到）' } else { $kernelRelease }))
Say ("ksud 版本    : " + $(if ($ksudVersion -eq '') { '（未取到）' } else { $ksudVersion }))

Say ""
Say "=== 隐藏资源包 manifest（本地强校验通过） ==="
Say ("路径         : " + $manifestBlock.path)
Say ("sha256       : " + $manifestBlock.sha256)
Say ("version      : " + $manifestBlock.version + "   generatedAt: " + $manifestBlock.generatedAt)
Say ("条目数       : " + $manifestBlock.entryCount)
Say ("总字节       : " + ("{0:N0} ({1:N2} MB)" -f [int64]$manifestBlock.totalBytes, ([int64]$manifestBlock.totalBytes / 1MB)))
Say ("分类计数     : " + (($manifestBlock.categories.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) -join ' '))
Say ("includes     : " + (($manifestBlock.includes | ConvertTo-Json -Compress)))

Say ""
Say "=== 运行模式（ksud debug info，三态） ==="
Say ("模式         : " + $runtimeMode + "   pack 模式: " + $(if ($packMode -eq '') { '（未知 ⇒ 过滤 no-op）' } else { $packMode }))
Say ("依据         : " + [string]$rm.Why)
if ($diLines.Count -gt 0) {
    Say "原始输出     :"
    foreach ($l in $diLines) { Say ("  " + [string]$l) }
} else {
    Say "原始输出     : （空）"
}
Say ("su 可用      : " + $suUsable)

Say ""
Say "=== 特性配置 /data/adb/ksu/.feature_config（只读解码） ==="
if ($null -eq $fcParse) {
    Say ("不可解析     : " + $fcWhy)
} else {
    Say ("可解析       : " + $fcParse.Ok + "   字节数: " + $fcBytes.Length)
    Say ("说明         : " + $fcWhy)
    if ($fcParse.Ok) {
        foreach ($k in @('su_compat', 'kernel_umount', 'sulog', 'adb_root', 'selinux_hide', 'webview_zygote_umount')) {
            $mark = ''
            if ($k -ceq 'selinux_hide') { $mark = '   <-- 关注项' }
            Say ("  " + $k.PadRight(26) + " = " + $fcParse.Features[$k] + $mark)
        }
    }
}

Say ""
Say "=== keybox（只读，绝不显示内容） ==="
Say ("路径         : /data/adb/tricky_store/keybox.xml")
$kbDisp = '?（未探测到：su 不可用）'
if ($kbPresent -eq $true) { $kbDisp = 'True' }
elseif ($kbPresent -eq $false) { $kbDisp = 'False' }
Say ("存在（test -s）: " + $kbDisp)
Say ("字节数       : " + $(if ($null -eq $kbSize) { '（未取到）' } else { $kbSize }))
Say ("sha256 前 8  : " + $(if ($null -eq $kbSha8) { '（未取到）' } else { $kbSha8 }))

Say ""
Say "=== 检测器（pm list packages） ==="
Say ("已装设备包总数: " + @($pkgList).Count + "（pm 可用=" + $pmAvailable + "）")
Say ("检测器条目    : 已装 " + $report.detectors.installedCount + " / 未装 " + $report.detectors.missingCount + " / 未知 " + $report.detectors.unknownCount)
foreach ($d in $detectors) {
    Say ("  " + $d.id.PadRight(46) + " installed=" + $d.installed)
}

Say ""
Say ("=== pack 条目现状（" + $entries.Count + " 条；版本判定只相等，pathmask 额外比 updateJson 分支） ===")
Say ("{0,-9} {1,-30} {2,4} {3,-14} {4,-9} {5,-5} {6}" -f 'category', 'id', 'ord', 'version', 'installed', 'pend', 'why')
foreach ($r in ($rows | Sort-Object -Property @{ Expression = { $_.order } }, @{ Expression = { $_.id } })) {
    # 未知必须显式写成 '?'：$null 会被 -f 渲染成空串，看上去和 False 一样，那就是猜。
    $instDisp = '?'
    if ($r.installed -eq $true) { $instDisp = 'True' }
    elseif ($r.installed -eq $false) { $instDisp = 'False' }
    $pendDisp = ''
    if ($r.pendingReboot) { $pendDisp = 'PEND' }
    Say ("{0,-9} {1,-30} {2,4} {3,-14} {4,-9} {5,-5} {6}" -f $r.category, $r.id, $r.order, $r.version, $instDisp, $pendDisp, $r.why)
}

Say ""
Say "=== 汇总 ==="
Say ("条目 " + $entries.Count + "：已装 " + $installedCount + " / 待重启 " + $pendingCount + " / 未知 " + $unknownCount)
if ($notes.Count -gt 0) {
    Say ""
    Say "=== 警告 / 归因（不可静默忽略） ==="
    foreach ($n in $notes) { Say ("  ! " + [string]$n) }
}
Say ""
Say "只读报告结束（未做任何写入）。"
exit 0
