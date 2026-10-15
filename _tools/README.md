# _tools · 维护脚本说明

本目录是**构建 / 校验 / 资源包同步**脚本，被 [docs/开发文档.md](../docs/开发文档.md) 第 3 节直接引用。它们不是通用构建系统，而是本分支构建环境的可复现配方。

## 前提

- Windows + Windows PowerShell 5.1 或更高。
- 工作区布局：仓库位于 `<工作区>/<仓库目录>/`，工具链位于 `<工作区>/.toolchain/`（`jdk-21`、`gradle-9.7.1`、`android-sdk`、`android-home`、`gradle-home`、`tmp`）。
- 同一目录下另有 `rust-dev`（Rust 工具链、`cargo-ndk`、`dlltool` 垫片）与 `mingw64`（宿主 C 编译器与链接器）：`build-ksud.ps1` 依赖前者构建 Android 产物，用后者编译宿主侧构建脚本，并把产物内的构建机路径重映射为 `/workspace/...`。
- 脚本用 `$PSScriptRoot` 推导仓库与工作区位置，默认按上述布局工作；布局不同时用 `-Root <工作区>` 指定，资源包同步另需 `-SourcePack <资源包目录>`。
- 执行策略：`Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force`。
- 不使用 `gradlew.bat`：本项目构建环境中 gradle wrapper 的下载链无法通过 TLS 校验，`build-manager.ps1` 直接调用预下载的 Gradle 发行包。

## 脚本清单

| 脚本 | 用途 |
| --- | --- |
| `build-manager.ps1` | 设置 `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_USER_HOME` / `GRADLE_USER_HOME` / `TEMP` 后调用 Gradle；用法 `-Task ':app:assembleRelease' -NoDaemon`，结束打印 `EXITCODE=` |
| `build-ksud.ps1` | 用 cargo-ndk 构建 ksud / ksuinit；`-PackageName` 必须等于 `manager/gradle.properties` 的 `KSU_PACKAGE_NAME`；`-Platform` 需 API 26+（默认 31）；`-Fmt` / `-Clippy` / `-Check` 只跑静态检查并以其退出码结束 |
| `sync-hiding-pack.ps1` | 把资源包同步进 `assets/hiding` 并重新生成 `manifest.json`；`-SourcePack` 必填，`-IncludeDetectors` 默认关闭、`-IncludeKeybox` 永不启用、`-IncludeData` 默认 false |
| `verify-identity.ps1` | 校验五件事：`manager/gradle.properties` 的包名、预编译 `libksud.so` 内嵌包名、APK 的 applicationId 与签名证书、内置 LKM 资产的证书哈希与 DER 长度、内置 LKM 资产的**驱动版本**（第 5 节，必须等于 `manager/gradle.properties` 的 `KSU_VERSION_CODE`；驱动版本比 32513 低时管理器首页会报「与 KernelSU 驱动版本 (16) 不匹配」）；第 4 项覆盖 `userspace\ksud\bin\**\*_kernelsu.ko` 的每一个 KMI/ABI，并报出资产产地（路径 A ＝ 编译进了 `KSU_MANAGER_PACKAGE`，路径 B ＝ 只换了证书常量），`-RequireSourceBuild` 把路径 B 直接判失败；`-SelfTest` 会跑正反例自检；第 2b 节还逐个核对资产名是否真的编进了对应的 `libksud.so`（资产已收编但二进制未重建即硬失败） |
| `measure-ui-dump.ps1` | 把 uiautomator dump 的 XML 换算成 px/dp 尺寸表 |
| `diff-tree.ps1` | 对比两棵目录树的差异 |
| `patch-init-boot.py` | 对引导镜像或裸 ko 做常量替换 / 资产替换（`--base/--out` 或 `--ko-in/--ko-out`），可顺带 `--manager-appid` 预置冠位；`--signer-size` 会按 `--from-size`（当前编进去的 DER 长度，默认 827）唯一命中比较指令并保留其寄存器编码（aarch64 的 `cmp w8/w21,#imm` 由编译器决定），`--replace-ko` 允许 ko 大小变化，此时会重排整个 newc 归档（成员大小一变，后面每个 entry 都会移位，原地覆盖会破坏归档）；写出后自查 cpio 往返与成员顺序，`--dry-run` 只报告不改动 |
| `verify-init-boot.py` | 独立复核引导镜像（不共用 producer 的解析代码：自带 boot 头解析、LZ4-legacy 解码、newc 遍历、ELF `.symtab` 查找）：ko 是否 ELF64 aarch64、项目证书哈希是否恰好一处、官方与 debug 哈希是否都已消失、`cmp w<n>,#imm` 是否等于项目证书 DER 长度、产地是路径 A 还是 B、`--expect-appid` 冠位预置；`--require-source-build` 时路径 B 直接失败，`--dump-ko` 导出内置 ko |
| `adopt-lkm-asset.ps1` | 把 CI 从源码构建出来的 ko 收编为内置资产：先证明它是项目身份（证书哈希 / DER 长度 / 无官方与 debug 哈希），再报出它是否带 `KSU_MANAGER_PACKAGE` 包名校验（路径 A 有、路径 B 没有）；`-Abi {aarch64\|x86_64}`（默认 aarch64）与 `-Kmi <kmi>` 决定落点 `userspace\ksud\bin\<abi>\<kmi>_kernelsu.ko`；`-DryRun` 只校验，`-AllowPatchedAsset` 才接受路径 B；同时拒绝**驱动版本**与 pin 不符的候选（`KSU_VERSION_CODE` 由 `kernel/Kbuild` 编进 `do_get_info()`，`Find-KsuVersion` 从反汇编直接读出该常量；`-ExpectedVersion <n>` 显式指定期望值，`-AllowVersionMismatch` 才放行） |
| `identity-lib.ps1` | 身份判据的共享实现（被 `adopt-lkm-asset.ps1` / `verify-identity.ps1` / `build-ksud.ps1` 点源加载）：`Find-LlvmObjdump` 找 NDK 的 objdump，`Find-ExpectedSize` 证明 ko 里的 DER 长度比较等于期望值——aarch64 按 `(w & 0x7F80001F) == 0x7100001F` 收集全部 wzr 比较（寄存器是编译器选择：多数 KMI 是 `w21`，android12-5.10 是 `w8`），x86_64 走反汇编匹配 `cmp $834`；`Find-KsuVersion` 从 `do_get_info` 的 `{version, flags}` 常量取驱动版本（aarch64 立即数 `str x<r>`、x86_64 `movabsq`，另有两个 KMI 走字面量池 + 重定位），`Get-PinnedKsuVersion` 读 `manager/gradle.properties` 的 pin；工具缺失时返回 `Ok=$null` 让调用方报警告而不是猜 |
| `project-signer.json` | 项目签名身份的公开事实（证书 SHA-256、DER 长度等）；私钥与口令在 `.artifacts/builder/keystore/keystore.properties`，不入库 |
| `hiding_manifest.schema.json` | 资源包 manifest 的契约（结构与管理器侧消费语义） |
| `hiding-lib.ps1` | 隐藏流水线的纯函数库（只定义函数，无顶层副作用，无文件级 `param()`）：读并强校验 manifest、`Get-HidingRuntimeProfile` / `Get-HidingPlan`（逐字对应管理器 `HideEnvironmentViewModel.buildOneKeyPlan`）、`Match-KernelBranch`、`Read-FeatureConfig`（同时接受 versioned 与 plain 两种布局，且要求记录恰好填满文件）、`Get-PropertyPlan`（28 步：22 set + 4 del + 2 只读探查）、`Get-KeyboxPlan`（6 步固定序列）、`Get-CleanupPlan`、`Invoke-Adb` / `Invoke-AdbShell` / `Invoke-SuShell`；`Invoke-HidingLibSelfTest` 是它的自检（正控 + 负控，任一条不过即抛） |
| `hiding-status.ps1` | 只读体检：设备身份、内核 release、本地 manifest 强校验、运行模式三态、`.feature_config` 解码、keybox 只探测存在性/字节数/sha256 前 8 位、检测器已装情况、逐条目 `installed/pending` 判定。`-Json` 输出单个对象；`-SelfTest` 离线自检。**未知一律渲染成 `?`，绝不把 `$null` 当 False** |
| `hiding-onekey.ps1` | 一键隐藏编排。**默认（不带 `-Apply`）只打印计划，不碰设备**；`-Apply` 才执行，且进门先做 root 预检，不确定就 fail-closed。计划复用 `hiding-lib.ps1`，单步文案冻结为 `第 n/m 步 · <名称> · 成功\|失败:<原因>`，末行恒为 `需重启后生效`。可选 `-InstallLsposed` / `-AddBrickRescue` / `-InstallPropsModule` / `-KeyboxPath` / `-Detectors` / `-SkipModules` |
| `hiding-props-module/` | 报告 §4.1 的 22 条属性洗白，做成常驻模块（`props.txt` + `post-fs-data.sh` + `service.sh` + `customize.sh`），由 `hiding-onekey.ps1 -InstallPropsModule` 打包安装 |

## 隐藏流水线（本地终端）

把分析报告里的隐藏逻辑搬到 PC 侧本地终端执行，用的是 `adb` + 设备侧 `su`，**不新增**管理器内业务逻辑。
冻结契约仍以 `assets/hiding/manifest.json` 与 `hiding_manifest.schema.json` 为准，本组脚本只消费、不生产。

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force   # 本机默认 Restricted，必须先放开
.\_tools\hiding-status.ps1 -SelfTest          # 离线自检
.\_tools\hiding-status.ps1                    # 只读体检（真机）
.\_tools\hiding-onekey.ps1                    # 只打印计划（dry-run）
.\_tools\hiding-onekey.ps1 -Apply             # 真的执行
.\_tools\hiding-onekey.ps1 -Apply -KeyboxPath D:\keybox.xml
.\_tools\hiding-onekey.ps1 -Apply -Detectors -SkipModules   # 不需要 root 的那一步
# §4.4 完整性层三件套（都只从本地文件装入，绝不进 APK）
.\_tools\hiding-onekey.ps1 -Apply -KeyboxPath D:\keybox.xml -SecurityPatchPath D:\security_patch.txt -TargetListPath D:\target.txt
```

`-SecurityPatchPath` 接 `system=` / `boot=` / `vendor=` 三行（值须为 `YYYY-MM-DD`），写入
`ro.build.version.security_patch`（← `system`）与 `ro.vendor.build.security_patch`（← `vendor`）；
缺键或值非法就跳过该属性，绝不拿别的键顶替。`-TargetListPath` 把静态 `target.txt` 装到
`/data/adb/tricky_store/target.txt`，作为模块每小时动态重建（`pm list packages -3` 剔 `OMK_SKIP` 后 `sort -u`）的起点。

退出码：`0` 成功 · `2` 自检失败 · `3` 没有可用设备 · `4` 设备没有 root（fail-closed，零写入）· `5` 至少一步失败 · `6` 参数前置错误。

几条硬规矩：

- **dry-run 永远可用**：不带 `-Apply` 时不写设备，即使没 root 也必须能出计划（运行模式取不到时按 `Unknown` ⇒ 过滤 no-op，显示全部条目，而不是错误地替用户过滤掉一半）。
- **`-Apply` 进门先验 root**，验不过就一步都不写。模块 / pathmask 安装必需 root；检测器安装不需要（`pm install` 走 adb shell 即可）。
- **`pm install` 的裁决是输出里出现独立一行 `Success`**，不是退出码——实测 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 时退出码也可能是 0。
- **keybox.xml 只搬运、不读取**：设备侧只用 `test -s` / `chattr` / `chown` / `chmod` / `mv`，内容永不回显、永不进日志、永不进 APK 或 git。
- **`sys.usb.adb.disabled` 刻意不写**：报告 §4.1 列了 `sys.usb.adb.disabled=1`，但真机实测该属性现值
  为 0，且设备上**没有任何模块写过它**（即 0 就是原厂值）。改成 1 有两个独立风险：偏离原厂值本身
  就是一条比不改更容易被读到的痕迹；该属性在该厂商 USB 栈里的语义未知，若被采信可能关掉 USB ADB
  —— 而那是本流水线唯一的控制与恢复通道。因此它被降级成只读探查步（与通配项同一机制），
  `hiding-lib.ps1` 里有一条负控断言保证它永远不会出现在写入命令里。
- **不做报告 §4.5 的痕迹清理**（删用户存储里的 `*.img` / `*.json` / `*.xml` / `*.zip` / `MT2/`）——那是销毁用户数据，本工具永不代劳。

### 改这三个脚本时必须知道的坑

- **保住 UTF-8 BOM**：三个 `.ps1` 都是「UTF-8 **带 BOM**」。Windows PowerShell 5.1 对无 BOM 的
  `.ps1` 按 ANSI 解码，文件里的中文会立刻变成乱码并**成片破坏字符串引号**，`ParseFile` 会报几十个
  其实是编码问题导致的语法错误。用编辑器改写后务必确认前三字节仍是 `EF BB BF`，并重新跑一次
  `ParseFile` + `-SelfTest`。
  - **实测陷阱：定点替换（`edit` 这类 old→new 文本替换工具）会把 BOM 丢掉。** 每次改过这三个
    文件后都必须重新验 BOM；丢了就补前三字节再存档。补的时候注意
    `(New-Object System.Text.UTF8Encoding($true)).GetBytes($text)` **不会**写出前导 BOM
    —— 那个 `true` 只让 `StreamWriter` 那条路径加 BOM，`GetBytes` 永远不加。要显式拼：
    ```powershell
    $enc  = New-Object System.Text.UTF8Encoding($false)
    $body = $enc.GetBytes($text)
    $out  = New-Object byte[] ($body.Length + 3)
    $out[0] = 0xEF; $out[1] = 0xBB; $out[2] = 0xBF
    [Array]::Copy($body, 0, $out, 3, $body.Length)
    [System.IO.File]::WriteAllBytes($path, $out)
    ```
  - 反证（负控）也会踩这个坑：先用 `Copy-Item` 存一份**带 BOM** 的字节备份，否则回滚回来的仍是
    无 BOM 版本，紧接着的自检会报一堆假的语法错误，让人误以为闸门坏了。
- **计划构造器的返回形状**：`Get-PropertyPlan` / `Get-KeyboxPlan` 用 `return ,$steps`（整个数组作为
  单个对象返回，调用方 `foreach ($x in @($plan))`）；`Get-SecurityPatchPlan` / `Get-TargetListPlan`
  是**逐个输出**（调用方必须 `$plan = @(Get-... ...)` 快照）。不要互相改：把前者换成逐个输出、或把
  后者包成 `,$steps`，都会让空/单元素计划悄悄变成「包着真数组的 1 元素数组」，`@(cmd).Count` 恒为 1。
  另外空结果的断言必须写成内联 `@(cmd).Count`，先赋值再 `@($x).Count` 会得到 1（`@($null).Count`）。

已知未验证项（如实记录，已随真机实测更新）：

- **`hiding-onekey.ps1 -Apply` 已在真机端到端跑通（2026-10-12）**：修掉两个真机缺陷后，
  `-Apply -SoftRebootOnBlock` 一次运行 6/6 全绿（exit 0），计划 = hybrid_mount / zygisksu /
  tricky_store / hma_oss_zygisk / pathmask-android15-6.6 / pathmask-scope。
  - 缺陷 D1（已修）：暂存目录 `/data/local/tmp/hiding` 由 su 建出时属主是 `root:root 0755`，而每个
    `adb push` 的身份是 shell(2000) ⇒ 5 个模块步骤一起报 `remote couldn't create file: Permission
    denied`（报错指向 push，真因在目录属主；`mkdir -p` 对已存在目录仍返回 0，所以检查不漏就必踩）。
    现在建完 `chown 2000:2000` + `chmod 700`（不用 0777：该目录还暂存 keybox.xml / target.txt），
    并补一次以 push 身份的写探针，失败时一次报清前置条件。
  - 缺陷 D2（已修）：metamodule（hybrid_mount）装好后 ksud 会拒绝后续安装，直到有一次重启把「待生效
    变更」落地（横幅 `❌ Installation Blocked / A metamodule with custom installer is active /
    Current state: Pending changes / Error: Metamodule installation blocked`）。原样透传会把同一段
    横幅记成 4 个模块各自的失败。现在改写成「需先重启或 ksud soft-reboot 再继续安装，本步未安装」，
    且默认**不**自动重启；只有显式 `-SoftRebootOnBlock` 才在本次运行内做 soft-reboot 边界（最多 2 次）
    并重试该步——实测 `ksud soft-reboot` 即可清除该状态，暂存 zip 在 `/data/local/tmp` 跨 soft-reboot
    存活，重试无需重新 push。
  - 改这两个脚本的提醒：定点编辑（`edit` 之类）会**丢掉 UTF-8 BOM**，本次就丢过一次；改完必须
    核验前三字节是 `EF BB BF`，否则 Windows PowerShell 5.1 会按 ANSI 解码中文并把引号改坏。
- **仍未在真机跑过**：`-InstallPropsModule`、`-KeyboxPath`（§4.4 的 S7 原子写入）、`-TargetListPath`、
  `-SecurityPatchPath`，以及 `-Detectors` 的 `pm install`。
- 所连设备本来就是完整隐藏栈（5 个模块 + AlwaysStrong + `selinux_hide=1` + 9/9 检测器），所以
  「缺件安装」这条路径只在 pathmask 上真正被走过一次。
- **对一台已配好的设备不要盲跑 `-Apply`**：它会重装 5 个已装模块，并且在给了对应 `-*Path` 时无条件
  覆盖 `/data/adb/tricky_store/` 下活跃的 `keybox.xml`（13,280 B，与同目录 `hbk` / `.kb_engine` /
  `boot_key.bin` / `persistent_keys/` 配套绑定）。实测 AlwaysStrong 自己的安装器会 `keybox kept
  (13280 bytes)` 主动保留，但本工具给的 `-KeyboxPath` 没有这个保护。
- **判定内核模块是否加载要等模块自己的目标等待窗过去**（pathmask 是 60 s）：窗口内读 `/proc/modules`
  只看到 `hybridmount` / `kernelsu`，会把 `boot_state=waiting-targets` 误报成「ko 没加载」。

## 红线

- **不要手改** `manager/app/src/main/assets/hiding/manifest.json`，一律用 `sync-hiding-pack.ps1` 重新生成。
- **不要改** `hiding_manifest.schema.json`，也不要新增字段或类别（类别集合是封闭的：`module` / `pathmask` / `detector` / `data`）。
- **不要**给 manifest 增加 `keybox` / `data` 条目（`includes.keybox` 与 `includes.data` 恒为 false）。
