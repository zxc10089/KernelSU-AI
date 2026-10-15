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
      * 检测器安装   = su -c "pm install -r -t --user 0 <绝对 cache 路径>"；裁决 = 输出里独立一行 Success（退出码不算数）
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

.PARAMETER SoftRebootOnBlock
    metamodule（hybrid_mount）装好后 ksud 会拒绝后续安装，直到有一次重启让「待生效变更」落地
    （设备实测横幅：❌ Installation Blocked / Current state: Pending changes / Error: Metamodule
    installation blocked）。默认**不**自动重启：只把该步如实记为失败，原因改写成「需先重启或
    ksud soft-reboot 再继续安装」。加本开关后，同一次运行内检测到阻塞就执行一次 ksud soft-reboot、
    等设备回来并把该步重试（最多 2 次边界），使一次运行能走完 6 步。实测 soft-reboot 即可清除该状态，
    且 /data/local/tmp 暂存文件在 soft-reboot 后仍在，重试无需重新 push。

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
    [switch]$PathMaskScope,
    [switch]$Detectors,
    [switch]$SkipModules,
    [switch]$SoftRebootOnBlock,
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
# dry-run 计划行的状态字：一步都没执行，所以只能写「待执行」。
# 严禁改成「成功」——那会把计划渲染成执行结果（真实踩过）。
$DryRunStepStatus = '待执行'

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

# 冻结文案（执行结果）：第 n/m 步 · <name> · 成功 | 失败:<error>
# 第三个分支只服务 dry-run 的**计划展示**（$DryRunStepStatus='待执行'）。
# 绝不允许把未执行的步骤渲染成「成功」——那是在自报完成。
function Format-StepLine {
    param($Step)
    if ($Step.status -ceq '成功') { return ("第 {0}/{1} 步 · {2} · 成功" -f $Step.index, $Step.total, $Step.name) }
    if ($Step.status -ceq '失败') { return ("第 {0}/{1} 步 · {2} · 失败:{3}" -f $Step.index, $Step.total, $Step.name, $Step.error) }
    return ("第 {0}/{1} 步 · {2} · {3}" -f $Step.index, $Step.total, $Step.name, $Step.status)
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

# pm install 的**唯一**裁决入口：不论走 su -c 还是 adb shell，都必须同时满足
# 「退出码 0」与「输出里有独立一行 Success」。分开判会漏掉真实存在的
# 「退出码 0 + Failure [...]」情形（设备上实测过 INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
function Test-PmInstallDone {
    param([int]$ExitCode, [string[]]$Output)
    $txt = ((@($Output)) -join "`n").Trim()
    $ok = ($ExitCode -eq 0) -and (Test-PmInstallOutput $txt)
    $why = if ([string]::IsNullOrWhiteSpace($txt)) { "exit $ExitCode" } else { $txt }
    return [pscustomobject]@{ Ok = $ok; Why = $why; Output = $txt }
}

# ksud 在 metamodule（hybrid_mount）存在**待生效变更**时会拒绝后续安装，并打印一大段 ASCII 横幅：
#   ❌ Installation Blocked / A metamodule with custom installer is active /
#   Current state: Pending changes / Action required: Reboot to apply changes first /
#   Error: Metamodule installation blocked
# 这不是「这个模块装不上」：设备只是需要一次重启（ksud soft-reboot 也可以）才能继续装。
# 把横幅原样当成安装失败原因会把归因指到错误方向——设备上实测：hybrid_mount 装完后，
# 后续 4 个模块步骤全部拿到同一段横幅，看上去像 4 个模块各自装不上。
function Test-HidingMetamoduleBlock {
    param([string]$Output)
    # 只看这一句：ksud 的 ASCII 横幅和紧邻的 `- Error: Metamodule installation blocked` 行都含它。
    return ([string]$Output -match '(?i)Metamodule installation blocked')
}

function Get-HidingInstallFailureReason {
    param([string]$Output)
    $txt = [string]$Output
    if (Test-HidingMetamoduleBlock $txt) {
        return 'Metamodule 待生效变更阻塞（ksud: Pending changes）：需先重启或 ksud soft-reboot 再继续安装，本步未安装'
    }
    return $txt
}

# 阻塞之后该做什么（纯函数，自检直接断言真值表）。
#   'retry-after-soft-reboot' = 开关显式打开且还有边界额度；'fail' = 如实记为失败。
# 默认任何阻塞都只是 'fail'：自动重启会打断本次运行的中途状态，绝不能默认打开。
function Get-HidingBlockedInstallAction {
    param([bool]$IsMetamoduleBlock, [bool]$AllowSoftReboot, [int]$UsedSoftReboots, [int]$MaxSoftReboots = 2)
    if (-not $IsMetamoduleBlock) { return 'fail' }
    if (-not $AllowSoftReboot) { return 'fail' }
    if ($UsedSoftReboots -ge $MaxSoftReboots) { return 'fail' }
    return 'retry-after-soft-reboot'
}

# 一次 soft-reboot 边界：ksud soft-reboot -> 等 sys.boot_completed=1 -> 复核 root。
# root 复核不过就 Ok=$false，调用方据此 fail-closed（绝不带着「可能没 root」继续写设备）。
function Invoke-HidingSoftRebootBoundary {
    param([string]$Adb, [string]$Serial, [int]$WaitSeconds = 180)
    $ksud = Get-HidingKsudPath
    $rb = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command ("$ksud soft-reboot")
    if (-not $rb.Ok) { return @{ Ok = $false; Why = ('ksud soft-reboot 失败：' + $rb.Why) } }
    $booted = $false
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $b = Invoke-AdbShell -Adb $Adb -Serial $Serial -Command 'getprop sys.boot_completed'
        if ((((@($b.Output)) -join '').Trim()) -ceq '1') { $booted = $true; break }
    }
    if (-not $booted) { return @{ Ok = $false; Why = ("soft-reboot 后 $WaitSeconds 秒内 sys.boot_completed 未变 1") } }
    Start-Sleep -Seconds 5
    $id = Invoke-SuShell -Adb $Adb -Serial $Serial -Command 'id'
    if ($id.ExitCode -ne 0 -or (((@($id.Output)) -join ' ') -notmatch 'uid=0')) {
        return @{ Ok = $false; Why = 'soft-reboot 后 root 复核失败（su 不可用或非 uid=0）' }
    }
    return @{ Ok = $true; Why = 'soft-reboot 完成，root 复核通过' }
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

    # (a2) dry-run 不得把**未执行**的步骤渲染成「成功」（真实踩过：计划行曾被喂 Status '成功'）
    Assert-HidingTruth -Condition ($DryRunStepStatus -cne '成功' -and $DryRunStepStatus -cne '失败') -Message 'dry-run 步骤状态不得是 成功/失败'
    $planLine = Format-StepLine (New-StepResult -Index 1 -Total 5 -Name 'hybrid_mount' -Status $DryRunStepStatus)
    Assert-HidingTruth -Condition ($planLine -ceq '第 1/5 步 · hybrid_mount · 待执行') -Message ("dry-run 计划行文案不符：'" + $planLine + "'")
    Assert-HidingTruth -Condition ($planLine -notmatch '成功|失败') -Message 'dry-run 计划行出现了执行结果字样'
    # 结构闸：dry-run 代码块内不得出现任何执行结果字面量（否则就是计划冒充结果）。
    # 定位代码块必须用**行首锚定**的正则。曾用 $src.IndexOf('if (-not $Apply) {') 去搜，
    # 结果搜到了这条断言**自己**里的那个字面量（自指），闸门形同虚设 —— 已用反证确认过。
    $src = [System.IO.File]::ReadAllText($PSCommandPath, (New-Object System.Text.UTF8Encoding($false)))
    $mDry = [regex]::Match($src, '(?m)^if \(-not \$Apply\) \{')
    if (-not $mDry.Success) { throw 'onekey 结构闸失效：找不到 dry-run 代码块，请修正本断言' }
    $mStop = [regex]::Match($src.Substring($mDry.Index), '(?m)^\s*exit 0\b')
    if (-not $mStop.Success) { throw 'onekey 结构闸失效：dry-run 代码块里没有 exit 0' }
    $dryBlock = $src.Substring($mDry.Index, $mStop.Index)
    Assert-HidingTruth -Condition ($dryBlock -notmatch '成功|失败') -Message 'dry-run 代码块里出现了 成功/失败 字面量：计划不得冒充执行结果'

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

    # (e2) PathMask 作用域治理：只写模块出厂默认值，且 deny 名单必须并入本 pack 的检测器包名
    $pm = @(Get-PathMaskScopePlan -ExtraDenyPackages @('com.chunqiunativecheck'))
    Assert-HidingTruth -Condition ((@($pm | Where-Object { [string](Get-HidingField $_ 'Name') -ceq 'scope_mode.conf' })).Count -eq 1) -Message 'onekey 的 pathmask 治理计划缺少 scope_mode.conf 步骤'
    Assert-HidingTruth -Condition ((@($pm | Where-Object { [string](Get-HidingField $_ 'Command') -match '(?<![a-z])global' })).Count -eq 0) -Message 'onekey 的 pathmask 治理计划不得写入 global'
    $detPkgs = @($real.Manifest.Entries |
        Where-Object { [string](Get-HidingField $_ 'category') -ceq 'detector' } |
        ForEach-Object { [string](Get-HidingField $_ 'packageName') })
    Assert-HidingTruth -Condition ($detPkgs.Count -ge 1) -Message '真实 manifest 的 detector 条目取不到 packageName（pathmask 的 deny 名单会残缺）'
    Assert-HidingTruth -Condition ((@($detPkgs | Where-Object { [string]::IsNullOrWhiteSpace($_) })).Count -eq 0) -Message '真实 manifest 里有 detector 条目缺 packageName'

    # (f) pm install 的裁决规则：只有独立一行 Success 才算装好；Failure 绝不能被判为成功。
    Assert-HidingTruth -Condition (Test-PmInstallOutput "Performing Streamed Install`nSuccess") -Message 'onekey 裁决：Success 未被识别为成功'
    Assert-HidingTruth -Condition (-not (Test-PmInstallOutput 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]')) -Message 'onekey 裁决：Failure 被误判为成功'
    Assert-HidingTruth -Condition (-not (Test-PmInstallOutput '')) -Message 'onekey 裁决：空输出被误判为成功'
    Assert-HidingTruth -Condition (-not (Test-PmInstallOutput 'Successfully done')) -Message 'onekey 裁决：含 Success 子串的非成功输出被误判为成功'
    # Test-PmInstallDone 是 rooted / 非 rooted 两条路径共用的唯一入口，必须同时看退出码与 Success 行。
    Assert-HidingTruth -Condition ((Test-PmInstallDone -ExitCode 0 -Output @('Success')).Ok) -Message 'onekey 裁决：exit 0 + Success 必须判为成功'
    Assert-HidingTruth -Condition (-not (Test-PmInstallDone -ExitCode 0 -Output @('Failure [INSTALL_FAILED_ALREADY_EXISTS: x]')).Ok) -Message 'onekey 裁决：exit 0 + Failure 必须判为失败（rooted 路径假成功回归）'
    Assert-HidingTruth -Condition (-not (Test-PmInstallDone -ExitCode 1 -Output @('Success')).Ok) -Message 'onekey 裁决：exit 1 + Success 必须判为失败'

    # (f2) metamodule 待生效变更：ksud 的 ASCII 横幅必须被改写成「需要先重启/soft-reboot」，
    # 否则 4 个模块步骤会各自把同一段横幅当成自己的安装失败原因（设备上实测踩到）。
    $blocked = "  _  __`n❌ Installation Blocked`n│ A metamodule with custom installer is active`n│ Current state: Pending changes`n│ Action required: Reboot to apply changes first`n- Error: Metamodule installation blocked"
    $whyBlocked = Get-HidingInstallFailureReason $blocked
    Assert-HidingTruth -Condition ($whyBlocked -match '待生效变更阻塞') -Message 'onekey 归因：metamodule 阻塞横幅没被改写'
    Assert-HidingTruth -Condition ($whyBlocked -notmatch 'Installation Blocked') -Message 'onekey 归因：metamodule 阻塞横幅被原样透传'
    Assert-HidingTruth -Condition ($whyBlocked -match 'soft-reboot') -Message 'onekey 归因：阻塞原因里没有给出可执行动作（重启 / soft-reboot）'
    Assert-HidingTruth -Condition ((Get-HidingInstallFailureReason 'no space left') -ceq 'no space left') -Message 'onekey 归因：普通失败原因被判成了阻塞'
    Assert-HidingTruth -Condition ((Get-HidingInstallFailureReason '') -ceq '') -Message 'onekey 归因：空输出被改写'
    Assert-HidingTruth -Condition (Test-HidingMetamoduleBlock '- Error: Metamodule installation blocked') -Message 'onekey 判定：ksud 阻塞行必须被识别'
    Assert-HidingTruth -Condition (-not (Test-HidingMetamoduleBlock 'no space left on device')) -Message 'onekey 判定：普通失败不得被当成 metamodule 阻塞'

    # (f3) metamodule 阻塞的边界决策真值表：默认不自动重启；只有「开关打开 + 还有额度」才 soft-reboot 重试。
    Assert-HidingTruth -Condition ((Get-HidingBlockedInstallAction -IsMetamoduleBlock $true -AllowSoftReboot $false -UsedSoftReboots 0) -ceq 'fail') -Message 'onekey 边界：默认（不开开关）遇到阻塞必须如实失败'
    Assert-HidingTruth -Condition ((Get-HidingBlockedInstallAction -IsMetamoduleBlock $false -AllowSoftReboot $true -UsedSoftReboots 0) -ceq 'fail') -Message 'onekey 边界：非阻塞失败不得触发重启'
    Assert-HidingTruth -Condition ((Get-HidingBlockedInstallAction -IsMetamoduleBlock $true -AllowSoftReboot $true -UsedSoftReboots 0) -ceq 'retry-after-soft-reboot') -Message 'onekey 边界：开关打开且有额度必须重试'
    Assert-HidingTruth -Condition ((Get-HidingBlockedInstallAction -IsMetamoduleBlock $true -AllowSoftReboot $true -UsedSoftReboots 2 -MaxSoftReboots 2) -ceq 'fail') -Message 'onekey 边界：额度用尽必须停止重试（不得无限重启）'
    Assert-HidingTruth -Condition ((Get-HidingBlockedInstallAction -IsMetamoduleBlock $true -AllowSoftReboot $true -UsedSoftReboots 1 -MaxSoftReboots 2) -ceq 'retry-after-soft-reboot') -Message 'onekey 边界：额度未用尽应继续重试'
    Assert-HidingTruth -Condition (-not (Test-PmInstallDone -ExitCode -1 -Output @()).Ok) -Message 'onekey 裁决：su 不可用(-1) 必须判为失败'

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
# PathMask 的作用域配置跨重装存活、每次开机被重申：只装模块不治理配置，等于把一条可能被改坏的作用域
# （例如 global ⇒ 全机每个 App 都看不到被遮路径）一遍遍重申。装 pathmask 时自动带治理步骤，也可以用
# -PathMaskScope 单独治理一台已经装好模块的设备。
$planHasPathMask = (@($planInfo.Plan | Where-Object { [string](Get-HidingField $_ 'category') -ceq 'pathmask' }).Count -gt 0)
if ($PathMaskScope -or $planHasPathMask) { [void]$extra.Add('pathmask-scope') }
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
        Write-Host (Format-StepLine (New-StepResult -Index $n -Total $total -Name $id -Status $DryRunStepStatus))
    }
    foreach ($x in $extra) {
        $n++
        Write-Host (Format-StepLine (New-StepResult -Index $n -Total $total -Name $x -Status $DryRunStepStatus))
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
    -or (-not [string]::IsNullOrWhiteSpace($TargetListPath)) `
    -or $PathMaskScope -or $planHasPathMask
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
# metamodule 阻塞边界的额度（只有 -SoftRebootOnBlock 才用得到）
$usedSoftReboots = 0
$maxSoftReboots = 2

# 暂存目录：有 root 用 su，没有 root 用 adb shell（/data/local/tmp 对 shell 本来就可写）。
# 关键点：**push 是以 adb shell 用户（uid 2000）执行的**，而 su 建出来的目录属主是 root
# （root:root 0755，首次运行的常见情形）。此时每个 adb push 都会报
# "remote couldn't create file: Permission denied"，5 个模块步骤一起失败，报错却指向 push。
# 所以建完必须把属主交回 shell 并收紧到 0700（不用 0777：本目录还暂存 keybox.xml /
# target.txt，777 会让设备上别的应用能读或替换暂存的私钥）。
$mk = $null
if ($root.Ok) {
    $mk = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command ("mkdir -p " + $StageDir + " && chown 2000:2000 " + $StageDir + " && chmod 700 " + $StageDir)
} else {
    $rawMk = Invoke-AdbShell -Adb $Adb -Serial $Serial -Command ("mkdir -p " + $StageDir + " && chmod 700 " + $StageDir)
    $mk = [pscustomobject]@{ Ok = ($rawMk.ExitCode -eq 0); Why = ((@($rawMk.Output)) -join "`n").Trim() }
}
if (-not $mk.Ok) {
    Write-Host ('hiding-onekey: 无法创建暂存目录 ' + $StageDir + '：' + $mk.Why)
    exit 5
}
# 可写性探针：以 push 的身份（adb shell）在目录里建一个临时文件再删掉。
# 目录已存在但不可写时 mkdir -p 依然返回 0，不实测的话失败会推迟到每个模块步骤各报一次
# push 错误（现象：5 步全挂、原因却指向 push）。这里一次性 fail-closed 并把原因写在报错里。
$wprobe = Invoke-AdbShell -Adb $Adb -Serial $Serial -Command ("touch " + $StageDir + "/.write_probe && rm -f " + $StageDir + "/.write_probe")
if ($wprobe.ExitCode -ne 0) {
    $whyW = ((@($wprobe.Output)) -join "`n").Trim()
    Write-Host ('hiding-onekey: 暂存目录 ' + $StageDir + ' 对 adb shell 不可写（push 必然失败）：' + $whyW)
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
            if (-not $inst.Ok -and (Test-HidingMetamoduleBlock $inst.Why)) {
                # hybrid_mount（metamodule）刚装好时 ksud 会阻塞后续安装，直到有一次重启落地该变更。
                $act = Get-HidingBlockedInstallAction -IsMetamoduleBlock $true -AllowSoftReboot ([bool]$SoftRebootOnBlock) `
                    -UsedSoftReboots $usedSoftReboots -MaxSoftReboots $maxSoftReboots
                if ($act -ceq 'retry-after-soft-reboot') {
                    $usedSoftReboots++
                    Write-Host ('  · ' + $id + '：metamodule 待生效变更 → 第 ' + $usedSoftReboots + ' 次 soft-reboot 边界，等设备回来后重试本步')
                    $bd = Invoke-HidingSoftRebootBoundary -Adb $Adb -Serial $Serial
                    if (-not $bd.Ok) {
                        $err = 'soft-reboot 边界失败：' + $bd.Why
                    } else {
                        # 暂存 zip 在 /data/local/tmp，soft-reboot 后仍在（设备实测），无需重新 push。
                        $inst = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command ("$ksud module install $remote")
                    }
                }
            }
            if (-not $inst.Ok -and [string]::IsNullOrWhiteSpace($err)) {
                $err = 'ksud module install 失败：' + (Get-HidingInstallFailureReason $inst.Why)
            }
            if ($inst.Ok) { $err = '' }
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
                if (-not $inst.Ok) { $err = 'ksud module install 失败：' + (Get-HidingInstallFailureReason $inst.Why) }
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
    elseif ($x -ceq 'pathmask-scope') {
        $idx++
        $err = ''
        # 检测器包名取自冻结清单（category=detector 的 packageName），与 App 快捷安装卡同源。
        # 治理只写模块出厂默认值，绝不碰用户的 target_path.conf。
        $denyExtra = @($planInfo.Manifest.Entries |
            Where-Object { [string](Get-HidingField $_ 'category') -ceq 'detector' } |
            ForEach-Object { [string](Get-HidingField $_ 'packageName') })
        $psPlan = @(Get-PathMaskScopePlan -ExtraDenyPackages $denyExtra)
        if ($psPlan.Count -eq 0) {
            $err = 'pathmask 作用域计划为空（拒绝静默当作成功）'
        } else {
            foreach ($k in $psPlan) {
                $cmd = [string](Get-HidingField $k 'Command')
                $r = Invoke-DeviceSu -Adb $Adb -Serial $Serial -Command $cmd
                if (-not $r.Ok) { $err = ('pathmask 作用域步骤失败（' + [string](Get-HidingField $k 'Name') + '）：' + $r.Why); break }
            }
        }
        if ([string]::IsNullOrWhiteSpace($err)) {
            $s = New-StepResult -Index $idx -Total $total -Name 'pathmask-scope' -Status '成功'
            [void]$steps.Add($s); Write-Host (Format-StepLine $s)
        } else {
            $s = New-StepResult -Index $idx -Total $total -Name 'pathmask-scope' -Status '失败' -Error $err
            [void]$steps.Add($s); [void]$failures.Add('pathmask-scope'); Write-Host (Format-StepLine $s)
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
            # 两条路径的裁决必须走同一个入口 Test-PmInstallDone：否则 rooted 路径退回「退出码即裁决」，
            # 而真实设备上 pm install 失败时退出码同样是 0 —— 那就是假成功。
            if ($root.Ok) {
                $raw = Invoke-SuShell -Adb $Adb -Serial $Serial -Command ("pm install -r -t --user 0 $remote")
            } else {
                $raw = Invoke-AdbShell -Adb $Adb -Serial $Serial -Command ("pm install -r -t --user 0 $remote")
            }
            $i = Test-PmInstallDone -ExitCode $raw.ExitCode -Output $raw.Output
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
