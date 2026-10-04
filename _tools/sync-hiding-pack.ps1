<#
.SYNOPSIS
    把「隐藏环境资源包」打包进 Manager 的 assets，并生成 manifest.json。

.DESCRIPTION
    默认只入包**隐藏实现端**（7 个功能模块 ZIP + 6 个 PathMask 内核模块分支）。
    检测类 APK 与数据文件为显式 opt-in，理由见 -IncludeDetectors / -IncludeKeybox 注释。

    产物布局（manager/app/src/main/assets/hiding/）：
        manifest.json
        modules/<id>.zip           功能模块（Zygin Next / SUSFS / LSPosed / ...）
        pathmask/<branch>.zip      PathMask LKM，按 Android-内核 分支
        detectors/<pkg>.apk        [opt-in]
        data/...                   [opt-in]

.EXAMPLE
    pwsh -File _tools\sync-hiding-pack.ps1
    pwsh -File _tools\sync-hiding-pack.ps1 -IncludeDetectors
#>
[CmdletBinding()]
param(
    # 隐藏环境资源包源目录（必填）：模块 zip、路径掩码 zip、检测器 APK 与 HMA 预置配置的来源。
    [Parameter(Mandatory = $true)]
    [string]$SourcePack,
    # 检测类 APK 含商用闭源软件（MT 管理器 bin.mt.plus.canary）与体积大户（88MB），默认不入包。
    [switch]$IncludeDetectors,
    # keybox.xml 含**明文 EC 私钥**（泄露型凭据），默认绝不入包分发。
    [switch]$IncludeKeybox,
    # .feature_config / security_patch.txt / target.txt 等配置数据。
    [switch]$IncludeData
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repo  = Split-Path -Parent $PSScriptRoot
$src   = $SourcePack
$dst   = Join-Path $repo 'manager\app\src\main\assets\hiding'
$log   = Join-Path (Split-Path -Parent $repo) '.toolchain\sync-hiding-pack.log'

function Say([string]$m) {
    Write-Host $m
    Add-Content -Path $log -Value $m
}

if (-not (Test-Path $src)) { throw "资源包目录不存在: $src" }

# 源文件(相对 $src) -> 稳定展示 id。
# 键可以是含子目录的相对路径：模块循环用 Join-Path $src $kv.Key 定位源文件，
# 所以「新版本只存在于 新的模块\ 子目录」的模块直接指向那里即可（该目录是只读源，不移动、不改名）。
# 2026-10-03 修订：更新两个模块到新版本、新增 hybrid_mount、移除 Violet。
#   * 2/6.zip -> 新的模块\ 下的新版本（HMA-OSS oss-173 / LSPosed v2.2.0）
#   * 新增 hybrid_mount（Hybrid Mount v6.2.3-rc.2，module.prop 声明 metamodule=1）=> 必须最先安装，见 orderTable
#   * 移除 5.zip -> Violet（按使用者的选择去除）
# 2026-10-03 修订 2：tricky_store 换用 AlwaysStrong 版本。
#   * tricky_store 的源由 新的模块\TEESimulator-RS-v6.0.1-307.zip 换成 新的模块\AlwaysStrong-v1.0.4.zip。
#     AlwaysStrong 的 module.prop 里 id 仍是 `tricky_store`（version=v1.0.4 / versionCode=104）⇒ 展示 id、
#     orderTable(30)、modesTable($null) 以及 HidingPack.kt:88 的 oneKeyBase "tricky_store" 全部无需改动；
#     installedIds() 按 moduleId 匹配，故设备上已装的旧 tricky_store 仍会被视为「已安装」（版本不参与判定）。
$moduleIds = [ordered]@{
    '新的模块\Hybrid Mount-v6.2.3-rc.2.zip'  = 'hybrid_mount'
    '1.zip' = 'zygisksu'
    '新的模块\LSPosed-v2.2.0.zip'            = 'zygisk_lsposed'
    '新的模块\AlwaysStrong-v1.0.4.zip'       = 'tricky_store'
    '新的模块\HMA-OSS Zygisk-oss-173.zip'    = 'hma_oss_zygisk'
    '8.zip' = 'susfs4ksu'
    '9.zip' = 'Automatic_brick_rescue'
}
$pathmaskBranches = [ordered]@{
    'a12-5.10.zip' = 'android12-5.10'
    'a13-5.10.zip' = 'android13-5.10'
    'a13-5.15.zip' = 'android13-5.15'
    'a14-6.1.zip'  = 'android14-6.1'
    'a15-6.6.zip'  = 'android15-6.6'
    'a16-6.12.zip' = 'android16-6.12'
}
# 分支级源覆盖：某些分支的新版本只以「单个 zip」的形式存在于 新的模块\ 目录（不是 pathmask\aXX-X.Y.zip）。
# 键 = $pathmaskBranches 的值（分支名），值 = 相对 $src 的源路径；未列出的分支仍走 pathmask\<文件>。
$pathmaskSourceOverride = @{
    'android15-6.6' = '新的模块\PathMask 路径遮罩-2.8.2.zip'
}
# 检测 APK 元数据来源：资源包分析记录（包名/版本已由 AXML 解析确认）
$detectors = [ordered]@{
    '1.apk' = @{ pkg = 'io.github.vvb2060.mahoshojo';      ver = '4.4.1';        name = 'momo 真机检测' }
    '2.apk' = @{ pkg = 'com.byxiaorun.detector';           ver = '1.1.1';        name = 'ruru 如如检测' }
    '3.apk' = @{ pkg = 'com.zhenxi.hunter';                ver = '6.65';         name = 'Hunter 猎人检测' }
    '4.apk' = @{ pkg = 'luna.safe.luna';                   ver = '1.4.3.5';      name = 'Luna 检测' }
    '5.apk' = @{ pkg = 'io.github.huskydg.memorydetector';  ver = '2.1';          name = '紫色放大镜 内存检测器' }
    '6.apk' = @{ pkg = 'wu.keyChain.test';                 ver = '2.0.3';        name = '密钥认证 KeyChain 测试' }
    '7.apk' = @{ pkg = 'com.tsng.applistdetector';         ver = '1.3.2';        name = '应用列表检测器' }
    '8.apk' = @{ pkg = 'com.chunqiunativecheck';           ver = '3.9';          name = '春秋检测' }
    '9.apk' = @{ pkg = 'bin.mt.plus.canary';               ver = '2.26.8-clone'; name = 'MT 管理器（商用闭源）' }
}

# ---- 契约 _tools/hiding_manifest.schema.json ----
# modesTable 的 'pathmask' 键作用于 **6 个 pathmask 条目全部**(它们共用 id 前缀 pathmask-)：
# 依据 = 随包 pathmask.ko 由 service.sh insmod 加载（自述 "Android LKM path masking"）=> 只有 lkm。
# 'susfs4ksu' 依据 = module.prop 自述 "compiled with patched kernel source" => 需要打补丁内核 => gki。
# 其余条目无法从模块自述判定 => modes 必须整体省略（缺省语义 = lkm/gki 两种模式都适用）。
$modesTable = [ordered]@{
    'pathmask'    = @('lkm')
    'susfs4ksu'   = @('gki')
    'hybrid_mount'   = $null
    'zygisksu'    = $null
    'zygisk_lsposed' = $null
    'tricky_store'   = $null
    'hma_oss_zygisk' = $null
    'Automatic_brick_rescue' = $null
    'detector-*'     = $null
}
# orderTable: 数字越小越先安装；缺省 100。
# hybrid_mount = 5 是**契约要求**：其 module.prop 声明 metamodule=1（挂载后端），
# 该模块必须第一个安装 => 必须是全包最小 order。
$orderTable = [ordered]@{
    'hybrid_mount'           = 5
    'zygisksu'               = 10
    'zygisk_lsposed'         = 20
    'tricky_store'           = 30
    'hma_oss_zygisk'         = 40
    'pathmask-*'             = 60
    'susfs4ksu'              = 70
    'Automatic_brick_rescue' = 80
    'detector-*'             = 100
}

function Get-EntryModes([string]$id) {
    # 返回 $null 表示「两种模式都适用」——调用方必须整体省略 modes 键，绝不能写 null 到 JSON。
    # 逗号运算符必须保留：PowerShell 函数返回单元素数组时会**自动解包**成标量，
    # 那样 modes=@('gki') 会退化成字符串 "gki"（JSON 里是标量而非数组，违反冻结 schema）。
    if ($modesTable.Contains($id)) { return ,$modesTable[$id] }
    return $null
}
function Get-EntryOrder([string]$id) {
    if ($orderTable.Contains($id) -and $null -ne $orderTable[$id]) { return [int]$orderTable[$id] }
    return 100
}
# modes/order 统一在这里落到条目上。键序必须与冻结 schema 的字段列表一致：modes 在 order 之前。
# 做法：先写 order；若该条目有条目级 modes 证据，就把 order 摘下来、挂上 modes、再把 order 挂回去。
# 这样 sourceFile(末字段) -> [modes] -> order 的落笔顺序在两种情况下都成立。
# 注意 modes 与 order 的**查表键不同**：pathmask 条目在 modesTable 里查 'pathmask'
#（6 个分支共用同一条证据），而 orderTable 查 'pathmask-*'。
function Add-EntryModesOrder($item) {
    $orderKey = $item.id
    $modesKey = $item.id
    if ($item.category -eq 'detector') { $orderKey = 'detector-*'; $modesKey = 'detector-*' }
    elseif ($item.category -eq 'pathmask') { $orderKey = 'pathmask-*'; $modesKey = 'pathmask' }
    $item['order'] = Get-EntryOrder $orderKey
    $modeList = Get-EntryModes $modesKey
    if ($null -ne $modeList) {
        $order = $item['order']
        $item.Remove('order')
        $item['modes'] = $modeList
        $item['order'] = $order
    }
}

function Read-ModuleProp([string]$zipPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -match '^module\.prop$' } | Select-Object -First 1
        if (-not $entry) { return $null }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            $map = @{}
            foreach ($line in ($reader.ReadToEnd() -split "`n")) {
                $t = $line.Trim()
                if ($t -match '^([A-Za-z0-9_.]+)\s*=\s*(.*)$') { $map[$Matches[1]] = $Matches[2].Trim() }
            }
            return $map
        } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

function Get-Sha256([string]$p) { return (Get-FileHash -Path $p -Algorithm SHA256).Hash.ToLowerInvariant() }

# ---------- 清空并重建 ----------
if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
foreach ($sub in 'modules', 'pathmask', 'detectors', 'data') {
    New-Item -ItemType Directory -Force -Path (Join-Path $dst $sub) | Out-Null
}
Remove-Item $log -ErrorAction SilentlyContinue

$entries = New-Object System.Collections.ArrayList

Say "=== sync start $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK') ==="
Say "src = $src"
Say "dst = $dst"

# ---------- 功能模块 ----------
foreach ($kv in $moduleIds.GetEnumerator()) {
    $file = Join-Path $src $kv.Key
    if (-not (Test-Path $file)) { Say "SKIP missing $($kv.Key)"; continue }
    $id = $kv.Value
    $prop = Read-ModuleProp $file
    $out = Join-Path (Join-Path $dst 'modules') "$id.zip"
    Copy-Item $file $out -Force
    $item = [ordered]@{
        id          = $id
        # 真·ksud 模块 id（= /data/adb/modules/<moduleId>），用于「已安装」探测；与展示用 id 解耦。
        moduleId    = if ($prop -and $prop['id']) { $prop['id'] } else { $id }
        name        = if ($prop) { $prop['name'] } else { $id }
        version     = if ($prop) { $prop['version'] } else { '' }
        versionCode = if ($prop -and $prop['versionCode']) { [int]$prop['versionCode'] } else { 0 }
        author      = if ($prop) { $prop['author'] } else { '' }
        description = if ($prop) { $prop['description'] } else { '' }
        category    = 'module'
        asset       = "hiding/modules/$id.zip"
        sizeBytes   = (Get-Item $out).Length
        sha256      = Get-Sha256 $out
        sourceFile  = $kv.Key
    }
    Add-EntryModesOrder $item
    [void]$entries.Add($item)
    Say ("module  {0,-24} {1,-28} {2,10} B" -f $id, $item.version, $item.sizeBytes)
}

# ---------- PathMask（按分支） ----------
foreach ($kv in $pathmaskBranches.GetEnumerator()) {
    $branch = $kv.Value
    # 分支源覆盖优先（见 $pathmaskSourceOverride）：新版本只以单独 zip 形式提供时走这里。
    $relPath = if ($pathmaskSourceOverride.ContainsKey($branch)) { $pathmaskSourceOverride[$branch] } else { "pathmask\$($kv.Key)" }
    $file = Join-Path $src $relPath
    if (-not (Test-Path $file)) { Say "SKIP missing $relPath"; continue }
    $prop = Read-ModuleProp $file
    $outName = "$branch.zip"
    $out = Join-Path (Join-Path $dst 'pathmask') $outName
    Copy-Item $file $out -Force
    $item = [ordered]@{
        id          = "pathmask-$branch"
        # 6 个分支的 module.prop 里 id 全是 "pathmask"（只有 updateJson 指向的分支名不同），
        # 所以「哪个分支装了」必须靠 moduleId + updateJson 标记判断，不能靠 id。
        moduleId    = if ($prop -and $prop['id']) { $prop['id'] } else { 'pathmask' }
        name        = if ($prop) { $prop['name'] } else { 'PathMask' }
        version     = if ($prop) { $prop['version'] } else { '' }
        versionCode = if ($prop -and $prop['versionCode']) { [int]$prop['versionCode'] } else { 0 }
        author      = if ($prop) { $prop['author'] } else { '' }
        description = "PathMask LKM ($branch)"
        category    = 'pathmask'
        branch      = $branch
        asset       = "hiding/pathmask/$outName"
        sizeBytes   = (Get-Item $out).Length
        sha256      = Get-Sha256 $out
        sourceFile  = $relPath.Replace('\', '/')
    }
    Add-EntryModesOrder $item
    [void]$entries.Add($item)
    Say ("pathmask {0,-24} {1,10} B" -f $branch, $item.sizeBytes)
}

# ---------- 检测 APK（opt-in） ----------
if ($IncludeDetectors) {
    foreach ($kv in $detectors.GetEnumerator()) {
        $file = Join-Path $src $kv.Key
        if (-not (Test-Path $file)) { Say "SKIP missing $($kv.Key)"; continue }
        $meta = $kv.Value
        $out = Join-Path (Join-Path $dst 'detectors') "$($meta.pkg).apk"
        Copy-Item $file $out -Force
        $item = [ordered]@{
            id          = "detector-$($meta.pkg)"
            name        = $meta.name
            version     = $meta.ver
            versionCode = 0
            author      = ''
            description = "检测器（对抗验证端）"
            category    = 'detector'
            packageName = $meta.pkg
            asset       = "hiding/detectors/$($meta.pkg).apk"
            sizeBytes   = (Get-Item $out).Length
            sha256      = Get-Sha256 $out
            sourceFile  = $kv.Key
        }
        Add-EntryModesOrder $item
        [void]$entries.Add($item)
        Say ("detector {0,-32} {1,10} B" -f $meta.pkg, $item.sizeBytes)
    }
} else {
    Say "detectors: SKIPPED (opt-in, -IncludeDetectors)"
}

# ---------- 配置数据（opt-in） ----------
if ($IncludeData) {
    foreach ($f in '.feature_config', 'security_patch.txt', 'target.txt') {
        $file = Join-Path $src $f
        if (-not (Test-Path $file)) { Say "SKIP missing $f"; continue }
        $out = Join-Path (Join-Path $dst 'data') $f
        Copy-Item $file $out -Force
        [void]$entries.Add([ordered]@{
            id = "data-$f"; name = $f; version = ''; versionCode = 0; author = ''
            description = '资源包配置数据'; category = 'data'
            asset = "hiding/data/$f"; sizeBytes = (Get-Item $out).Length
            sha256 = Get-Sha256 $out; sourceFile = $f
        })
        Say ("data    {0,-24} {1,10} B" -f $f, (Get-Item $out).Length)
    }
} else {
    Say "data: SKIPPED (opt-in, -IncludeData)"
}

if ($IncludeKeybox) {
    Say "!!! WARNING: keybox.xml contains a PLAINTEXT EC PRIVATE KEY. Bundling it redistributes a leaked credential."
    $file = Join-Path $src 'keybox.xml'
    $out = Join-Path (Join-Path $dst 'data') 'keybox.xml'
    Copy-Item $file $out -Force
    [void]$entries.Add([ordered]@{
        id = 'data-keybox.xml'; name = 'keybox.xml'; version = ''; versionCode = 0; author = ''
        description = 'Attestation keybox (CONTAINS PRIVATE KEY)'; category = 'data'
        asset = 'hiding/data/keybox.xml'; sizeBytes = (Get-Item $out).Length
        sha256 = Get-Sha256 $out; sourceFile = 'keybox.xml'
    })
} else {
    Say "keybox.xml: SKIPPED (deliberate: contains plaintext EC private key)"
}

# ---------- HMA-OSS 预置配置 ----------
# 非 manifest 条目：冻结 schema 只允许 module / pathmask / detector / data 四类 category，
# 因此该文件只作为 assets 附属资源随包分发（管理器按固定 asset 路径读取），不写进 manifest.json。
# 用途：一键隐藏在末尾把它写进 HMA-OSS 守护进程的运行时配置（仅当运行时配置为空时）。
$hmaCfg = '新的模块\config.json'
$hmaCfgFile = Join-Path $src $hmaCfg
if (Test-Path $hmaCfgFile) {
    $out = Join-Path $dst 'hma_config.json'
    Copy-Item $hmaCfgFile $out -Force
    Say ("hma-config {0,10} B  {1}" -f (Get-Item $out).Length, (Get-Sha256 $out))
} else {
    Say "hma-config: SKIPPED (missing $hmaCfg)"
}

# ---------- manifest.json ----------
# 自检：每个条目必须有整数 order；modes 若出现必须是 lkm/gki 的非空数组；
# 且键序必须与冻结 schema 的字段列表逐字一致（modes 必须紧跟 sourceFile，order 必须收尾）。
foreach ($e in $entries) {
    if ($e.category -notin @('module', 'pathmask', 'detector', 'data')) { throw "invalid category: $($e.category)" }
    if (-not $e.Contains('order') -or $e['order'] -isnot [int]) { throw "entry $($e.id) missing integer order" }
    if ($e.Contains('modes')) {
        $ml = $e['modes']
        # 必须显式判 [array]：PowerShell 单元素数组在函数返回/赋值处会解包成标量，
        # 一旦解包，ConvertTo-Json 会把 modes 写成字符串而不是数组（违反冻结 schema）。
        if ($ml -isnot [array] -or $ml.Count -eq 0) { throw "entry $($e.id) has invalid modes" }
        foreach ($mv in $ml) { if ($mv -ne 'lkm' -and $mv -ne 'gki') { throw "entry $($e.id) has invalid mode '$mv'" } }
    }
    $base = @('id')
    if ($e.category -in 'module', 'pathmask') { $base += 'moduleId' }
    $base += @('name', 'version', 'versionCode', 'author', 'description', 'category')
    if ($e.category -eq 'pathmask') { $base += 'branch' }
    if ($e.category -eq 'detector') { $base += 'packageName' }
    $base += @('asset', 'sizeBytes', 'sha256', 'sourceFile', 'modes', 'order')
    $keys = @($e.Keys)
    $expectNoModes = @($base | Where-Object { $_ -ne 'modes' })
    if ($keys -notcontains 'modes') {
        if (($keys -join '>') -ne ($expectNoModes -join '>')) { throw "entry $($e.id) key order mismatch: $($keys -join '>')" }
    } else {
        if (($keys -join '>') -ne ($base -join '>')) { throw "entry $($e.id) key order mismatch: $($keys -join '>')" }
    }
}
$manifest = [ordered]@{
    version        = 2
    generatedAt    = (Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')
    sourcePack     = '隐藏环境资源包'
    includes       = [ordered]@{
        modules   = $true
        pathmask  = $true
        detectors = [bool]$IncludeDetectors
        data      = [bool]$IncludeData
        keybox    = [bool]$IncludeKeybox
    }
    totalBytes     = ($entries | ForEach-Object { $_.sizeBytes } | Measure-Object -Sum).Sum
    entries        = $entries
}
$manifestPath = Join-Path $dst 'manifest.json'
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8

Say ("manifest entries = {0}" -f $entries.Count)
$nModule   = ($entries | Where-Object { $_.category -eq 'module' }).Count
$nPathmask = ($entries | Where-Object { $_.category -eq 'pathmask' }).Count
$nDetector = ($entries | Where-Object { $_.category -eq 'detector' }).Count
Say ("manifest split   = modules {0} / pathmask {1} / detectors {2}" -f $nModule, $nPathmask, $nDetector)
Say ("manifest total   = {0:N2} MB" -f ($manifest.totalBytes / 1MB))
Say "manifest path     = $manifestPath"
Say "=== sync done ==="
