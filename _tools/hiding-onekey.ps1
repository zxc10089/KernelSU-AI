<#
.SYNOPSIS
    一键隐藏终端编排器：把「环境隐藏」页面的一键语义，以及报告里 §4.1 属性洗白 / §4.4 keybox 完整性层，
    忠实搬到 PC 侧本地终端执行（adb + su），复用而不 fork 冻结契约。

.DESCRIPTION
    默认（不带 -Apply）**只打印计划**，不碰设备。加 -Apply 才执行，且执行前必须通过 root 预检。

    契约来源（全部来自仓库，不猜）：
      * 一键集合     = _tools/hiding_manifest.schema.json 的 oneKeyHide（baseSet = hybrid_mount / zygisksu /
                       tricky_store / hma_oss_zygisk，pathmask 取推荐分支且只取一个，可选 zygisk_lsposed 与
                       Automatic_brick_rescue），计划顺序与去重由 hiding-lib.ps1 的 Get-HidingPlan 决定
                       （逐字对应 HideEnvironmentViewModel.kt:512-542 buildOneKeyPlan）。
      * 单步文案     = 「第 n/m 步 · <name> · 成功|失败:<error>」（schema 的 frozenLiterals）
      * 末行         = 「需重启后生效」（schema 的 frozenLiterals，必须逐字）
      * 模块安装     = ksud module install <绝对 cache 路径>，**串行**（schema 的 installStrategy）
      * 检测器安装   = su -c "pm install -r -t --user 0 <绝对 cache 路径>"；退出码即裁决
      * 属性洗白     = hiding-props-module 常驻模块（resetprop -n -f，见 _tools/hiding-props-module/），
                       比一次性 resetprop 更可靠：framework 重建 ro.boot.* 后 service.sh 会二次重放
      * keybox       = Get-KeyboxPlan 的 6 步固定序列（chattr -i -> 写 tmp -> chown 0:0 -> chmod 600 -> mv -> test -s）
      * §4.4 完整性层 = keybox.xml -> /data/adb/tricky_store/keybox.xml；security_patch.txt 的 system/vendor
                        写入 ro.build.version.security_patch 与 ro.vendor.build.security_patch（报告 :136，
                        来自 omk.zip 的 post-fs-data.sh）；静态 target.txt -> /data/adb/tricky_store/target.txt
                        （每小时按 pm list packages -3 剔 OMK_SKIP 的动态重建是模块自己的职责，不在本工具范围）。
                        这三个文件都只从本地路径装入，绝不进 APK。
      * 概不执行报告 §4.5 的痕迹清理（删 /storage/emulated/0 下的 *.img/*.json/*.xml/*.zip 与 /storage/emulated/0/MT2/）：
        那是销毁用户数据，本工具永不代劳。

    安全纪律：
      * 默认 dry-run；任何写设备动作都必须显式 -Apply。
      * root 预检不过就 fail-closed 退出，绝不在「不知道有没有 root」的情况下瞎试。
      * keybox.xml 含明文 EC 私钥：只从本地文件装入，内容永不打印、永不写入日志、永不进 APK/git。
      * 未知一律显式写成 '?'，绝不把 $null 渲染成空串冒充 False。

.PARAMETER Apply
    真的执行。省略则只打印计划（dry-run）。

.PARAMETER InstallLsposed
    追加 zygisk_lsposed（仅在 zygisksu 也在计划内时生效，与 Manager 一键语义一致）。

.PARAMETER AddBrickRescue
    追加 Automatic_brick_rescue。

.PARAMETER InstallPropsModule
    把 _tools/hiding-props-module/ 打包成模块 zip 并安装（报告 §4.1 的 22 条属性洗白，常驻重放）。

.PARAMETER KeyboxPath
    本地 keybox.xml 路径（用于报告 §4.4 的 OMK/TrickyStore 完整性层）。只在 -Apply 时使用。
    该文件含私钥：本脚本只做 chown/chmod/搬运，绝不读取或回显其内容。

.PARAMETER SecurityPatchPath
    本地 security_patch.txt 路径（报告 §4.4，来自 omk.zip 的 post-fs-data.sh:3069-3076）。
    格式三行 KEY=VALUE：system= / boot= / vendor=，值必须形如 YYYY-MM-DD。映射：
    ro.build.version.security_patch ← system，ro.vendor.build.security_patch ← vendor
    （`boot` 键不映射到属性）。缺键或值不合法就跳过该属性，绝不拿别的键顶替。

.PARAMETER TargetListPath
    本地 target.txt 路径（报告 §4.4）。静态装入 /data/adb/tricky_store/target.txt，作为模块每小时
    动态重建（pm list packages -3 剔 OMK_SKIP 后 sort -u 取并集）的起点。内容约 220 行包名，不打印。

.PARAMETER Detectors
    额外安装 9 个检测器 APK（不需要 root：有 root 走 su -c pm install，没有 root 直接走 adb shell pm install）。

.PARAMETER SkipModules
    跳过资源包模块 / pathmask 的安装（那部分需要 root）。与 -Detectors 搭配可在没有 root 的设备上
    只做检测器安装：`-Apply -Detectors -SkipModules`。

.PARAMETER Serial / AdbPath / RepoRoot
    设备序列号 / adb 路径 / 仓库根，省略时自动推断。

.PARAMETER Json
    以 JSON 输出计划与逐步结果（便于机器消费）。

.PARAMETER SelfTest
    离线自检：库自检 + 本脚本的计划文案/末行/root 预检逻辑断言。不碰设备。

.EXAMPLE
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
    .\hiding-onekey.ps1                       # 只看计划
    .\hiding-onekey.ps1 -Apply                # 执行
    .\hiding-onekey.ps1 -Apply -InstallPropsModule -KeyboxPath D:\keybox.xml
    .\hiding-onekey.ps1 -Apply -KeyboxPath D:\keybox.xml -SecurityPatchPath D:\security_patch.txt -TargetListPath D:\target.txt
    .\hiding-onekey.ps1 -SelfTest

Exit codes: 0 = 成功（dry-run 或 apply 全绿），2 = 自检失败，3 = 没有可用设备，
            4 = 设备没有 root（fail-closed，不做任何写入），5 = 至少一步失败，6 = 参数/前置错误。
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$RepoRoot = '',
    [string]$AdbPath = '',
    [switch]$Apply,
    [switch]$InstallLsposed,
    [switch]$AddBrickRescue,
    [switch]$InstallPropsModule,
    [string]$KeyboxPath = '',
    [string]$SecurityPatchPath = '',
    [string]$TargetListPath = '',
    [switch]$Detectors,
    [switch]$SkipModules,
    [switch]$Json,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($scriptDir)) { $scriptDir = (Get-Location).Path }
. (Join-Path $scriptDir 'hiding-lib.ps1')

if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Split-Path -Parent $scriptDir }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$workspaceRoot = Split-Path -Parent $RepoRoot
$AssetsRoot = Join-Path $RepoRoot 'manager\app\src\main\assets'
$PropsModuleDir = Join-Path $scriptDir 'hiding-props-module'
$StageDir = '/data/local/tmp/hiding'
$RestartHint = '需重启后生效'

# ------------------------------------------------------------------ helpers

function New-StepResult {
    param([int]$Index, [int]$Total, [string]$Name, [string]$Status, [string]$Error = '')
    return [pscustomobject]@{
        index  = $Index
        total  = $Total
        name   = $Name
        status = $Status
        error  = $Error
    }
}

# 冻结文案：第 n/m 步 · <name> · 成功|失败:<error>
function Format-StepLine {
    param($Step)
    if ($Step.status -ceq '成功') { return ("第 {0}/{1} 步 · {2} · 成功" -f $Step.index, $Step.total, $Step.name) }
    return ("第 {0}/{1} 步 · {2} · 失败:{3}" -f $Step.index, $Step.total, $Step.name, $Step.error)
}

function Resolve-HidingAdbPath {
    param([string]$Explicit, [string]$Workspace)
    if (-not [string]::IsNullOrWhiteSpace($Explicit)) {
        if (-not (Test-Path -LiteralPath $Explicit -PathType Leaf)) { throw "hiding-onekey: -AdbPath not found: $Explicit" }
        return $Explicit
    }
    $cand = Join-Path $Workspace 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $cand -PathType Leaf) { return $cand }
    $cmd = Get-Command 'adb' -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    throw "hiding-onekey: adb not found. Pass -AdbPath, or place platform-tools\adb.exe under $Workspace"
}

function Select-HidingDevice {
    param([string]$Adb, [string]$Wanted)
    $r = Invoke-Adb -Adb $Adb -Serial '' -Arguments @('devices')
    $all = New-Object System.Collections.ArrayList
    foreach ($l in @($r.Output)) {
        $s = [string]$l
        if ($s -match '^\*\s*daemon') { continue }
        if ($s -match '^\s*List of devices attached') { continue }
        if ([string]::IsNullOrWhiteSpace($s)) { continue }
        $p = $s -split '\s+'
        if ($p.Count -ge 2) { [void]$all.Add([pscustomobject]@{ Serial = $p[0]; State = $p[1] }) }
    }
    $all = @($all)
    if (-not [string]::IsNullOrWhiteSpace($Wanted)) {
        $hit = @($all | Where-Object { $_.Serial -ceq $Wanted })
        if ($hit.Count -eq 0) { throw "hiding-onekey: device '$Wanted' is not attached." }
        if ($hit[0].State -cne 'device') { throw "hiding-onekey: device '$Wanted' is in state '$($hit[0].State)', not 'device'." }
        return $Wanted
    }
    $online = @($all | Where-Object { $_.State -ceq 'device' })
    if ($online.Count -eq 0) { throw "hiding-onekey: no device in 'device' state (attached: $((@($all | ForEach-Object { $_.Serial + '=' + $_.State })) -join ', '))." }
    if ($online.Count -gt 1) { throw "hiding-onekey: $($online.Count) devices are usable; pass -Serial to choose one." }
    return [string]$online[0].Serial
}

function Test-DeviceRoot {
    param([string]$Adb, [string]$Serial)
    $r = Invoke-SuShell -Adb $Adb -Serial $Serial -Command 'id'
    if ($null -eq $r) { return [pscustomobject]@{ Ok = $false; Why = 'Invoke-SuShell returned nothing'; Output = '' } }
    $out = ((@($r.Output)) -join ' ').Trim()
    if ($r.ExitCode -eq -1) {
        return [pscustomobject]@{ Ok = $false; Why = "su 不可用：$out"; Output = $out }
    }
    if ($r.ExitCode -ne 0) {
        return [pscustomobject]@{ Ok = $false; Why = "su -c id 退出码 $($r.ExitCode)：$out"; Output = $out }
    }
    if ($out -notmatch 'uid=0') {
        return [pscustomobject]@{ Ok = $false; Why = "su 可用但没有 root：$out"; Output = $out }
    }
    return [pscustomobject]@{ Ok = $true; Why = $out; Output = $out }
}

# 设备侧单次 su 执行；返回 @{Ok;Why;Output}
function Invoke-DeviceSu {
    param([string]$Adb, [string]$Serial, [string]$Command)
    $r = Invoke-SuShell -Adb $Adb -Serial $Serial -Command $Command
    if ($null -eq $r) { return [pscustomobject]@{ Ok = $false; Why = 'no result'; Output = '' } }
    $out = ((@($r.Output)) -join "`n").Trim()
    if ($r.ExitCode -eq 0) { return [pscustomobject]@{ Ok = $true; Why = ''; Output = $out } }
    $why = if ([string]::IsNullOrWhiteSpace($out)) { "exit $($r.ExitCode)" } else { $out }
    return [pscustomobject]@{ Ok = $false; Why = $why; Output = $out }
}

function Push-HidingAsset {
    param([string]$Adb, [string]$Serial, [string]$LocalPath, [string]$RemotePath)
    $r = Invoke-Adb -Adb $Adb -Serial $Serial -Arguments @('push', $LocalPath, $RemotePath)
    $out = ((@($r.Output)) -join "`n").Trim()
    if ($r.ExitCode -eq 0 -and $out -notmatch '(?m)^adb:.*error') { return [pscustomobject]@{ Ok = $true; Why = ''; Output = $out } }
    return [pscustomobject]@{ Ok = $false; Why = $out; Output = $out }
}

# pm install 的裁决：输出里必须出现独立一行 Success。Failure [...] 一律算失败，
# 绝不因为「退出码是 0」就当成装好了（pm install 失败时退出码也可能是 0）。
function Test-PmInstallOutput {
    param([string]$Output)
    if ([string]::IsNullOrWhiteSpace($Output)) { return $false }
    return [bool]($Output -match '(?m)^Success$')
}

# ------------------------------------------------------------------ planning

function Get-OneKeyPlan {
    param([string]$RepoRoot, [string]$KernelRelease, [string]$RuntimeMode,
          [bool]$Lsposed, [bool]$BrickRescue)
    $m = Read-HidingManifest -RepoRoot $RepoRoot
    $mode = $null
    if ($RuntimeMode -ceq 'Lkm') { $mode = 'lkm' }
    elseif ($RuntimeMode -ceq 'GkiBuiltIn') { $mode = 'gki' }
    $profile = Get-HidingRuntimeProfile -Entries @($m.Entries) -KernelRelease $KernelRelease -RuntimeMode $mode
    $plan = Get-HidingPlan -Entries $profile.VisibleEntries -RecommendedIds $profile.RecommendedIds `
        -InstallLsposed:$Lsposed -AddBrickRescue:$BrickRescue
    return [pscustomobject]@{
        Manifest        = $m
        FilterMode      = $mode
        VisibleCount    = @($profile.VisibleEntries).Count
        PathMaskMatched = [bool]$profile.PathMaskMatched
        RecommendedIds  = @($profile.RecommendedIds)
        Plan            = @($plan)
    }
}

# ------------------------------------------------------------------ self-test

function Invoke-HidingOnekeySelfTest {
    param([string]$RepoRoot)

    Write-Host '[1/2] hiding-lib.ps1 self-test ...'
    [void](Invoke-HidingLibSelfTest -RepoRoot $RepoRoot)

    Write-Host '[2/2] onekey contract assertions ...'

    # (a) 冻结单步文案必须逐字如此
    $ok = New-StepResult -Index 2 -Total 5 -Name 'tricky_store' -Status '成功'
    $line = Format-StepLine $ok
    Assert-HidingTruth -Condition ($line -ceq '第 2/5 步 · tricky_store · 成功') -Message ("onekey 单步文案不符：'" + $line + "'")
    $bad = New-StepResult -Index 3 -Total 5 -Name 'zygisksu' -Status '失败' -Error 'no space left'
    $line2 = Format-StepLine $bad
    Assert-HidingTruth -Condition ($line2 -ceq '第 3/5 步 · zygisksu · 失败:no space left') -Message ("onekey 失败文案不符：'" + $line2 + "'")

    # (b) 末行文案冻结
    Assert-HidingTruth -Condition ($RestartHint -ceq '需重启后生效') -Message 'onekey 末行文案被改动'

    # (c) 计划必须来自真实 manifest，且 base 集合逐条落地、pathmask 至多一条
    $real = Get-OneKeyPlan -RepoRoot $RepoRoot -KernelRelease '6.1.75-android14-g' -RuntimeMode 'Lkm' -Lsposed $false -BrickRescue $false
    $ids = @($real.Plan | ForEach-Object { [string](Get-HidingField $_ 'id') })
    Assert-HidingTruth -Condition ($ids.Count -ge 4) -Message ("onekey 计划过短：$($ids -join ',')")
    foreach ($base in @('hybrid_mount', 'zygisksu', 'tricky_store', 'hma_oss_zygisk')) {
        Assert-HidingTruth -Condition ($ids -contains $base) -Message ("onekey 计划缺少 base 条目 '$base'：$($ids -join ',')")
    }
    $pmIds = @($real.Plan | Where-Object { [string](Get-HidingField $_ 'category') -ceq 'pathmask' })
    Assert-HidingTruth -Condition ($pmIds.Count -le 1) -Message ("onekey 计划里有 $($pmIds.Count) 条 pathmask（必须至多 1 条）")
    Assert-HidingTruth -Condition (-not ($ids -contains 'zygisk_lsposed')) -Message 'onekey 未加 -InstallLsposed 却含 zygisk_lsposed'
    Assert-HidingTruth -Condition (-not ($ids -contains 'Automatic_brick_rescue')) -Message 'onekey 未加 -AddBrickRescue 却含 Automatic_brick_rescue'

    # (d) 负控：不装 root 时必须判为没有 root（这里用一个必然解析失败的执行通道模拟）
    $fake = Test-DeviceRoot -Adb 'definitely-not-adb' -Serial '' 
    Assert-HidingTruth -Condition ($fake.Ok -eq $false) -Message 'onekey root 预检负控失败：假的 adb 竟然被判为 Ok'

    # (e) keybox 计划必须是 6 步固定序列，否则不许用于 -Apply
    $kb = Get-KeyboxPlan -SourcePath '/tmp/keybox.xml' -TargetPath '/data/adb/tricky_store/keybox.xml'
    Assert-HidingTruth -Condition (@($kb).Count -eq 6) -Message ("onekey keybox 计划步数应为 6，实际 " + @($kb).Count)
    $names = (@($kb) | ForEach-Object { [string](Get-HidingField $_ 'Stage') + ':' + [string](Get-HidingField $_ 'Name') }) -join ' | '
    Assert-HidingTruth -Condition ($names -ceq 'S7:chattr -i | S7:write-tmp | S7:chown 0:0 | S7:chmod 600 | S7:mv -f | S7:test -s') -Message ("onekey keybox 步骤序列不符：" + $names)

    # (f) pm install 的裁决规则：只有独立一行 Success 才算装好；Failure 绝不能被判为成功。
    Assert-HidingTruth -Condition (Test-PmInstallOutput "Performing Streamed Install`nSuccess") -Message 'onekey 裁决：Success 未被识别为成功'
    Assert-HidingTruth -Condition (-not (Test-PmInstallOutput 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]')) -Message 'onekey 裁决：Failure 被误判为成功'
    Assert-HidingTruth -Condition (-not (Test-PmInstallOutput '')) -Message 'onekey 裁决：空输出被误判为成功'
    Assert-HidingTruth -Condition (-not (Test-PmInstallOutput 'Successfully done')) -Message 'onekey 裁决：含 Success 子串的非成功输出被误判为成功'

    # (g) §4.4 完整性层（keybox 之外的另两块）：security patch 伪装 + target.txt 静态装入
    $spProbe = @(Get-SecurityPatchPlan -SecurityPatchContent "system=2025-01-01`nboot=2025-01-01`nvendor=2025-01-01")
    Assert-HidingTruth -Condition ($spProbe.Count -eq 2) -Message ("onekey security-patch 计划步数应为 2，实际 " + $spProbe.Count)
    $spProps = (@($spProbe) | ForEach-Object { [string](Get-HidingField $_ 'Name') }) -join ','
    Assert-HidingTruth -Condition ($spProps -ceq 'ro.build.version.security_patch,ro.vendor.build.security_patch') -Message ("onekey security-patch 属性顺序不符：" + $spProps)
    Assert-HidingTruth -Condition ((@($spProbe | Where-Object { [string](Get-HidingField $_ 'Command') -notmatch '^/data/adb/ksu/bin/resetprop ' })).Count -eq 0) -Message 'onekey security-patch 命令必须走 /data/adb/ksu/bin/resetprop 绝对路径'
    Assert-HidingTruth -Condition (@(Get-SecurityPatchPlan -SecurityPatchContent 'system=garbage').Count -eq 0) -Message 'onekey security-patch 负控：非法值必须 0 步（不得静默灌进系统属性）'
    $tlProbe = @(Get-TargetListPlan -SourcePath '/data/local/tmp/hiding/target.txt' -TargetPath '/data/adb/tricky_store/target.txt')
    Assert-HidingTruth -Condition ($tlProbe.Count -eq 5) -Message ("onekey target-list 计划步数应为 5，实际 " + $tlProbe.Count)
    Assert-HidingTruth -Condition ((@($tlProbe | Where-Object { [string](Get-HidingField $_ 'Command') -match '/data/adb/tricky_store/target\.txt' })).Count -eq 4) -Message 'onekey target-list 必须有 4 步指向目标文件（mkdir 那一步除外）'

    Write-Host 'ONEKEY SELFTEST OK'
}

if ($SelfTest) {
    try {
        Invoke-HidingOnekeySelfTest -RepoRoot $RepoRoot
        exit 0
    } catch {
        Write-Host ('ONEKEY SELFTEST FAILED: ' + $_.Exception.Message)
        exit 2
    }
}

# ------------------------------------------------------------------ main
$Adb = Resolve-HidingAdbPath -Explicit $AdbPath -Workspace $workspaceRoot
try { $Serial = Select-HidingDevice -Adb $Adb -Wanted $Serial } catch { Write-Host $_.Exception.Message; exit 3 }

if (-not [string]::IsNullOrWhiteSpace($KeyboxPath) -and -not (Test-Path -LiteralPath $KeyboxPath -PathType Leaf)) {
    Write-Host ("hiding-onekey: -KeyboxPath 不存在：" + $KeyboxPath)
    exit 6
}
if (-not [string]::IsNullOrWhiteSpace($SecurityPatchPath) -and -not (Test-Path -LiteralPath $SecurityPatchPath -PathType Leaf)) {
    Write-Host ("hiding-onekey: -SecurityPatchPath 不存在：" + $SecurityPatchPath)
    exit 6
}
if (-not [string]::IsNullOrWhiteSpace($TargetListPath) -and -not (Test-Path -LiteralPath $TargetListPath -PathType Leaf)) {
    Write-Host ("hiding-onekey: -TargetListPath 不存在：" + $TargetListPath)
    exit 6
}
if ($InstallPropsModule -and -not (Test-Path -LiteralPath (Join-Path $PropsModuleDir 'module.prop') -PathType Leaf)) {
    Write-Host ("hiding-onekey: 找不到 props 模块：" + $PropsModuleDir)
    exit 6
}

# root 预检：只在真的要写设备时才作为闸门（dry-run 不需要 root，也必须能出计划）。
$root = Test-DeviceRoot -Adb $Adb -Serial $Serial

# 运行模式：从 ksud debug info 取。没有 root 时必然取不到 => Unknown => 模式过滤按 no-op
# （有意的 fail-closed：模式未知时显示全部条目，而不是错误地替用户过滤掉一半）。
$di = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command ("$(Get-HidingKsudPath) debug info")
$rm = Get-RuntimeModeFromDebugInfo -Lines @($di.Output -split "`n")
$kernelRelease = ((@((Invoke-AdbShell -Adb $Adb -Serial $Serial -Command 'uname -r').Output)) -join ' ').Trim()

$planInfo = Get-OneKeyPlan -RepoRoot $RepoRoot -KernelRelease $kernelRelease -RuntimeMode ([string]$rm.Mode) `
    -Lsposed ([bool]$InstallLsposed) -BrickRescue ([bool]$AddBrickRescue)
$planIds = @($planInfo.Plan | ForEach-Object { [string](Get-HidingField $_ 'id') })

$extra = New-Object System.Collections.ArrayList
if ($InstallPropsModule) { [void]$extra.Add('properties') }
if (-not [string]::IsNullOrWhiteSpace($KeyboxPath)) { [void]$extra.Add('keybox') }
if (-not [string]::IsNullOrWhiteSpace($SecurityPatchPath)) { [void]$extra.Add('security-patch') }
if (-not [string]::IsNullOrWhiteSpace($TargetListPath)) { [void]$extra.Add('target-list') }
if ($Detectors) { [void]$extra.Add('detectors') }
$extra = @($extra)

if (-not $Apply) {
    Write-Host ''
    Write-Host '=== 一键隐藏 · 计划（dry-run，未做任何写入） ==='
    Write-Host ("设备         : {0}" -f $Serial)
    Write-Host ("内核 release : {0}" -f $kernelRelease)
    Write-Host ("运行模式     : {0}   （过滤模式: {1}）" -f $rm.Mode, $(if ($null -eq $planInfo.FilterMode) { 'no-op（模式未知）' } else { $planInfo.FilterMode }))
    if ($root.Ok) {
        Write-Host ("root         : 可用（{0}）=> -Apply 可以执行" -f $root.Output)
    } else {
        Write-Host ("root         : 不可用 => -Apply 会被拒绝（fail-closed）")
        Write-Host ("               " + $root.Why)
    }
    Write-Host ("可见条目     : {0}   pathmask 命中: {1}  推荐: {2}" -f $planInfo.VisibleCount, $planInfo.PathMaskMatched, ((@($planInfo.RecommendedIds) -join ', ') -replace '^$', '(无)'))
    Write-Host ''
    $n = 0
    $total = $planIds.Count + $extra.Count
    foreach ($id in $planIds) {
        $n++
        Write-Host (Format-StepLine (New-StepResult -Index $n -Total $total -Name $id -Status '成功'))
    }
    foreach ($x in $extra) {
        $n++
        Write-Host (Format-StepLine (New-StepResult -Index $n -Total $total -Name $x -Status '成功'))
    }
    Write-Host ''
    Write-Host $RestartHint
    Write-Host ''
    Write-Host '（这是计划。加 -Apply 才会真的执行。）'
    exit 0
}

# ---------------- apply ----------------
# 闸门：-Apply 才走到这里，进门前必须确认 root 可用；不确定就一步都不写（fail-closed）。
# 闸门：只有「确实需要 root 的步骤」才要求 root。检测器安装用 adb shell 的 pm install
# 即可完成（不需要 root），所以 -Detectors -SkipModules 在没有 root 的设备上也能跑。
$needRoot = ((-not $SkipModules) -and $planIds.Count -gt 0) -or $InstallPropsModule `
    -or (-not [string]::IsNullOrWhiteSpace($KeyboxPath)) `
    -or (-not [string]::IsNullOrWhiteSpace($SecurityPatchPath)) `
    -or (-not [string]::IsNullOrWhiteSpace($TargetListPath))
if ($needRoot -and -not $root.Ok) {
    Write-Host 'hiding-onekey: 设备没有可用的 root，已停止，未做任何写入（fail-closed）。'
    Write-Host ('  探测结果：' + $root.Why)
    Write-Host '  两条归因（都未验证，请按实际排查）：'
    Write-Host '    (1) 设备未刷入 / 未激活 KernelSU，或 Manager 的 appid 与引导镜像里烘入的 appid 不一致（身份闸门会拒绝）。'
    Write-Host '    (2) KernelSU 已激活但 sucompat 未开启，或 /system/bin/su 不存在 => adb shell 取不到 su。'
    Write-Host '  可用 _tools\hiding-status.ps1 查看模块/特性现状；root 打通后重跑本脚本 -Apply。'
    Write-Host '  只想装检测器（不需要 root）可以：-Apply -Detectors -SkipModules'
    exit 4
}
if (-not $root.Ok) {
    Write-Host ('hiding-onekey: 没有 root，已跳过所有需要 root 的步骤；只执行不需要 root 的步骤。')
}

$steps = New-Object System.Collections.ArrayList
$failures = New-Object System.Collections.ArrayList
$runModules = ((-not $SkipModules) -and ($planIds.Count -gt 0))
$total = $(if ($runModules) { $planIds.Count } else { 0 }) + $extra.Count
$idx = 0
$ksud = Get-HidingKsudPath

# 暂存目录：有 root 用 su，没有 root 用 adb shell（/data/local/tmp 对 shell 本来就可写）。
$mk = $null
if ($root.Ok) {
    $mk = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command ("mkdir -p " + $StageDir)
} else {
    $rawMk = Invoke-AdbShell -Adb $Adb -Serial $Serial -Command ("mkdir -p " + $StageDir)
    $mk = [pscustomobject]@{ Ok = ($rawMk.ExitCode -eq 0); Why = ((@($rawMk.Output)) -join "`n").Trim() }
}
if (-not $mk.Ok) {
    Write-Host ('hiding-onekey: 无法创建暂存目录 ' + $StageDir + '：' + $mk.Why)
    exit 5
}

# 1) 资源包条目：先 push 到 /data/local/tmp/hiding，再串行 ksud module install（需要 root）
if ($runModules) {
foreach ($entry in $planInfo.Plan) {
    $idx++
    $id = [string](Get-HidingField $entry 'id')
    $rel = ([string](Get-HidingField $entry 'asset')).Replace('/', '\')
    $local = Join-Path $AssetsRoot $rel
    $remote = "$StageDir/$id.zip"
    $err = ''
    if (-not (Test-Path -LiteralPath $local -PathType Leaf)) {
        $err = "本地资产缺失：$local"
    } else {
        $push = Push-HidingAsset -Adb $Adb -Serial $Serial -LocalPath $local -RemotePath $remote
        if (-not $push.Ok) {
            $err = 'push 失败：' + $push.Why
        } else {
            $inst = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command ("$ksud module install $remote")
            if (-not $inst.Ok) { $err = 'ksud module install 失败：' + $inst.Why }
        }
    }
    if ([string]::IsNullOrWhiteSpace($err)) {
        $s = New-StepResult -Index $idx -Total $total -Name $id -Status '成功'
        [void]$steps.Add($s); Write-Host (Format-StepLine $s)
    } else {
        $s = New-StepResult -Index $idx -Total $total -Name $id -Status '失败' -Error $err
        [void]$steps.Add($s); [void]$failures.Add($id); Write-Host (Format-StepLine $s)
    }
}
}

# 2) 属性洗白常驻模块（报告 §4.1）
foreach ($x in $extra) {
    if ($x -ceq 'properties') {
        $idx++
        $err = ''
        $zip = Join-Path ([System.IO.Path]::GetTempPath()) ('ksu-hide-props-' + [guid]::NewGuid().ToString('N') + '.zip')
        try {
            Compress-Archive -Path (Join-Path $PropsModuleDir '*') -DestinationPath $zip -Force
            $push = Push-HidingAsset -Adb $Adb -Serial $Serial -LocalPath $zip -RemotePath "$StageDir/ksu_hide_props.zip"
            if (-not $push.Ok) {
                $err = 'push 失败：' + $push.Why
            } else {
                $inst = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command ("$ksud module install $StageDir/ksu_hide_props.zip")
                if (-not $inst.Ok) { $err = 'ksud module install 失败：' + $inst.Why }
            }
        } catch {
            $err = $_.Exception.Message
        } finally {
            if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue }
        }
        if ([string]::IsNullOrWhiteSpace($err)) {
            $s = New-StepResult -Index $idx -Total $total -Name 'properties' -Status '成功'
            [void]$steps.Add($s); Write-Host (Format-StepLine $s)
        } else {
            $s = New-StepResult -Index $idx -Total $total -Name 'properties' -Status '失败' -Error $err
            [void]$steps.Add($s); [void]$failures.Add('properties'); Write-Host (Format-StepLine $s)
        }
    }
    elseif ($x -ceq 'keybox') {
        $idx++
        $err = ''
        # keybox 绝不读取内容：只在设备侧用 test -s / chattr / chown / chmod / mv 搬运。
        $remoteTmp = "$StageDir/keybox.xml"
        $push = Push-HidingAsset -Adb $Adb -Serial $Serial -LocalPath $KeyboxPath -RemotePath $remoteTmp
        if (-not $push.Ok) {
            $err = 'push 失败：' + $push.Why
        } else {
            $kb = Get-KeyboxPlan -SourcePath $remoteTmp -TargetPath '/data/adb/tricky_store/keybox.xml'
            foreach ($k in @($kb)) {
                $cmd = [string](Get-HidingField $k 'Command')
                $r = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command $cmd
                if (-not $r.Ok) { $err = ("keybox 步骤失败（" + [string](Get-HidingField $k 'Name') + "）：" + $r.Why); break }
            }
        }
        if ([string]::IsNullOrWhiteSpace($err)) {
            $s = New-StepResult -Index $idx -Total $total -Name 'keybox' -Status '成功'
            [void]$steps.Add($s); Write-Host (Format-StepLine $s)
        } else {
            $s = New-StepResult -Index $idx -Total $total -Name 'keybox' -Status '失败' -Error $err
            [void]$steps.Add($s); [void]$failures.Add('keybox'); Write-Host (Format-StepLine $s)
        }
    }
    elseif ($x -ceq 'security-patch') {
        $idx++
        $err = ''
        # §4.4 OMK：把 security_patch.txt 的 system/vendor 写进两个系统属性。值必须是 YYYY-MM-DD，
        # 缺键或值不合法就少一条步骤（判定在 Get-SecurityPatchPlan 里，绝不拿别的键顶替）。
        $content = ''
        try {
            $content = [System.IO.File]::ReadAllText($SecurityPatchPath)
        } catch {
            $err = '读取失败：' + $_.Exception.Message
        }
        if ([string]::IsNullOrWhiteSpace($err)) {
            $spPlan = @(Get-SecurityPatchPlan -SecurityPatchContent $content)
            if ($spPlan.Count -eq 0) {
                $err = 'security_patch.txt 里没有可用的 system= / vendor=（值须为 YYYY-MM-DD）'
            } else {
                foreach ($k in $spPlan) {
                    $cmd = [string](Get-HidingField $k 'Command')
                    $r = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command $cmd
                    if (-not $r.Ok) { $err = ('security patch 步骤失败（' + [string](Get-HidingField $k 'Name') + '）：' + $r.Why); break }
                }
            }
        }
        if ([string]::IsNullOrWhiteSpace($err)) {
            $s = New-StepResult -Index $idx -Total $total -Name 'security-patch' -Status '成功'
            [void]$steps.Add($s); Write-Host (Format-StepLine $s)
        } else {
            $s = New-StepResult -Index $idx -Total $total -Name 'security-patch' -Status '失败' -Error $err
            [void]$steps.Add($s); [void]$failures.Add('security-patch'); Write-Host (Format-StepLine $s)
        }
    }
    elseif ($x -ceq 'target-list') {
        $idx++
        $err = ''
        # §4.4：静态 target.txt 原子装到 /data/adb/tricky_store/target.txt；每小时动态重建是模块自己的职责。
        $remoteTmp = "$StageDir/target.txt"
        $push = Push-HidingAsset -Adb $Adb -Serial $Serial -LocalPath $TargetListPath -RemotePath $remoteTmp
        if (-not $push.Ok) {
            $err = 'push 失败：' + $push.Why
        } else {
            $tpPlan = @(Get-TargetListPlan -SourcePath $remoteTmp -TargetPath '/data/adb/tricky_store/target.txt')
            foreach ($k in $tpPlan) {
                $cmd = [string](Get-HidingField $k 'Command')
                $r = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command $cmd
                if (-not $r.Ok) { $err = ('target.txt 步骤失败（' + [string](Get-HidingField $k 'Name') + '）：' + $r.Why); break }
            }
        }
        if ([string]::IsNullOrWhiteSpace($err)) {
            $s = New-StepResult -Index $idx -Total $total -Name 'target-list' -Status '成功'
            [void]$steps.Add($s); Write-Host (Format-StepLine $s)
        } else {
            $s = New-StepResult -Index $idx -Total $total -Name 'target-list' -Status '失败' -Error $err
            [void]$steps.Add($s); [void]$failures.Add('target-list'); Write-Host (Format-StepLine $s)
        }
    }
    elseif ($x -ceq 'detectors') {
        $idx++
        $err = ''
        $fails = New-Object System.Collections.ArrayList
        foreach ($entry in @($planInfo.Manifest.Entries | Where-Object { [string](Get-HidingField $_ 'category') -ceq 'detector' })) {
            $id = [string](Get-HidingField $entry 'id')
            $rel = ([string](Get-HidingField $entry 'asset')).Replace('/', '\')
            $local = Join-Path $AssetsRoot $rel
            $remote = "$StageDir/$id.apk"
            # 单个检测器失败不中断其余：一个签名不匹配的包不该拖住另外 8 个。
            $p = Push-HidingAsset -Adb $Adb -Serial $Serial -LocalPath $local -RemotePath $remote
            if (-not $p.Ok) { [void]$fails.Add("$id push 失败：" + $p.Why); continue }
            # 检测器安装不需要 root：有 root 时用 su（与包内 installStrategy 一致），没有 root 直接走 adb shell。
            if ($root.Ok) {
                $i = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command ("pm install -r -t --user 0 $remote")
            } else {
                $raw = Invoke-AdbShell -Adb $Adb -Serial $Serial -Command ("pm install -r -t --user 0 $remote")
                $txt = ((@($raw.Output)) -join "`n").Trim()
                $why = if ([string]::IsNullOrWhiteSpace($txt)) { "exit $($raw.ExitCode)" } else { $txt }
                $i = [pscustomobject]@{ Ok = ($raw.ExitCode -eq 0 -and (Test-PmInstallOutput $txt)); Why = $why }
            }
            if (-not $i.Ok) { [void]$fails.Add("$id 安装失败：" + $i.Why) }
        }
        if ($fails.Count -gt 0) { $err = ($fails -join ' | ') }
        if ([string]::IsNullOrWhiteSpace($err)) {
            $s = New-StepResult -Index $idx -Total $total -Name 'detectors' -Status '成功'
            [void]$steps.Add($s); Write-Host (Format-StepLine $s)
        } else {
            $s = New-StepResult -Index $idx -Total $total -Name 'detectors' -Status '失败' -Error $err
            [void]$steps.Add($s); [void]$failures.Add('detectors'); Write-Host (Format-StepLine $s)
        }
    }
}

Write-Host ''
Write-Host $RestartHint

if ($Json) {
    $obj = [ordered]@{
        ok            = ($failures.Count -eq 0)
        device        = $Serial
        kernelRelease = $kernelRelease
        runtimeMode   = [string]$rm.Mode
        filterMode    = $planInfo.FilterMode
        plan          = $planIds
        steps         = @($steps)
        failures      = @($failures)
        restartHint   = $RestartHint
    }
    $obj | ConvertTo-Json -Depth 6
}

if ($failures.Count -gt 0) {
    Write-Host ('hiding-onekey: ' + $failures.Count + ' 步失败：' + ($failures -join ', '))
    exit 5
}
exit 0
