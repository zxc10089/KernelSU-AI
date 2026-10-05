# KernelSU 环境隐藏分支开发记录

## 0. 文档说明

- 本文件记录 `KernelSU-v330` 工作树在内核层 KernelSU v3.3.0 基线上所做的改造：目标、设计决策、实现要点、已知问题与剩余工作。
- 记录对象是**仓库当前状态**，不是逐日流水。描述架构或行为时以仓库源码为准；涉及实现细节时给出仓库相对路径与函数/常量名，行号会随改动漂移，仅作定位参考。
- 验收方式写成可复现的命令或操作步骤。仓库内无法独立核对的内容不写入，或显式标注「待验证」。
- 相关文档：`docs/开发文档.md`（面向使用者与模块作者）、`docs/ai-console-quickstart.md`（AI 控制台快速上手）、`design/environment-hide-spec.md`（环境隐藏页设计规格）、`design/ai-console-spec.md`（AI 控制台设计规格）、`design/home-ui-review.md`（主页 UI 评审）。

## 1. 项目概览

### 1.1 基线与身份

| 项 | 值 | 位置 |
| --- | --- | --- |
| 上游基线 | KernelSU v3.3.0（GPL-3.0） | 仓库根 |
| applicationId | `me.weishu.kernelsu.hide` | `manager/gradle.properties` 的 `KSU_PACKAGE_NAME` |
| versionCode / versionName | `32601` / `v3.3.0+main` | 同上（`KSU_VERSION_CODE` / `KSU_VERSION_NAME`） |
| 管理器 Activity | `me.weishu.kernelsu.ui.MainActivity` | 未随 applicationId 改动 |
| minSdk / targetSdk / compileSdk | 31 / 37 / 37 | `manager/build.gradle.kts`、`manager/app/build.gradle.kts` |

本分支使用独立 applicationId，因此可以与官方管理器并存安装；Activity 类路径保持上游不变，只有 applicationId 与签名证书不同。这两点加上 daemon 内编译进去的包名，共同构成「设备是否把本分支当作管理器」的身份链（见 3.1 与 3.3）。

### 1.2 功能目标

- 底栏五个页面，顺序为：主页 → 超级用户 → 环境隐藏 → 模块 → 设置。
- 环境隐藏页提供：运行环境状态、隐藏开关、隐藏资源包一键安装、检测器快捷安装，以及按运行模式过滤资源包条目。
- 隐藏资源包随 APK 内置（`manager/app/src/main/assets/hiding/`），无需联网下载。
- 把打过补丁的 LKM 编入 daemon 内置资产，使「直接安装」之后设备仍然认可本分支管理器身份。
- 检测器安装优先走 root 的 `pm install`，失败才回退系统安装器。
- 运行模式过滤为三态；内核无法回答时必须不过滤任何条目（见 4.3）。
- 资源包 manifest 由脚本生成，禁止手改；`includes.keybox` 与 `includes.data` 保持 false。

### 1.3 仓库结构与工具

| 目录 | 内容 |
| --- | --- |
| `kernel/` | 内核模块（C），含 supercall、allowlist、app profile、sucompat、管理器签名判定 |
| `userspace/ksud/` | 用户空间 daemon（Rust），模块管理、boot 打补丁、late load |
| `userspace/meta-overlayfs/` | metamodule 的挂载后端实现（Rust + 脚本） |
| `manager/` | Android 管理器（Kotlin + Compose），含 AI 控制台与环境隐藏页 |
| `website/`、`js/` | 文档站点与模块 WebUI 的 JavaScript 库 |
| `_tools/` | 构建、资源包同步、身份核对脚本 |
| `design/` | 设计规格与 UI 评审记录 |

`_tools/` 现有脚本：`build-ksud.ps1`（daemon 构建与静态检查的唯一入口）、`build-manager.ps1`（管理器构建的唯一入口）、`sync-hiding-pack.ps1`（资源包入包与 manifest 生成的唯一入口）、`verify-identity.ps1`（身份核对）、`hiding_manifest.schema.json`（manifest 冻结契约）、`diff-tree.ps1`、`measure-ui-dump.ps1`、`README.md`。

## 2. 开发历程

改造按主题推进，各阶段之间有明确的依赖关系。

1. **底栏与页面骨架**：新增环境隐藏路由并接入四种导航容器（Miuix/Material 各自的底栏与侧栏），底栏页数由 4 改为 5；同时把「完整界面」的判定从「必须已被内核加冕」放宽为「有 root 即可」，使自建 APK 在加冕之前也能使用完整功能。
2. **资源包与 manifest v2**：确立冻结契约与唯一生成脚本，条目字段、键序、`modes`/`order` 语义随之固定；资源包从压缩包变成随 APK 内置的资产目录。
3. **运行模式过滤**：引入三态运行模式，明确「未知即不过滤」的 fail-safe 规则，并把内核是否应答纳入判定。
4. **身份链**：把补丁 LKM 编入 daemon 资产，配合 daemon 的编译期包名与 APK 签名，形成「安装即为我方管理器」的闭环；增加身份核对脚本。
5. **AI 控制台**：从纯配置层起步，依次补齐提供商与模型列表、访问范围三档、动作管线与安全分级、审计与撤销、计划模式、附件、权限审查 / 模块冲突 / 模块制作等功能，最后做界面收口与全量本地化。
6. **收口**：长输出折叠、链接对比度、输入区结构、危险动作弹窗口径等细节调整。

## 3. 内核与 daemon

### 3.1 管理器加冕机制

内核侧的唯一判定入口是 `kernel/manager/apk_sign.c` 的 `is_manager_apk()`，它调用 `check_v2_signature(path, EXPECTED_SIZE, EXPECTED_HASH)`：

- 期望值来自 `kernel/Kbuild`：`KSU_EXPECTED_SIZE := 0x033b`（827，官方证书 DER 长度）与 `KSU_EXPECTED_HASH := c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6`，通过 `ccflags-y` 导出为 `-DEXPECTED_SIZE` / `-DEXPECTED_HASH`。
- 若同时定义了 `KSU_EXPECTED_SIZE2` 与 `KSU_EXPECTED_HASH2`，`is_manager_apk()` 会再比对第二张证书；只给长度不给哈希会在构建期直接报错。
- 解析 APK Signing Block（magic `APK Sig Block 42`）：v2 块（id `0x7109871a`）必须存在；一旦出现 v3（`0xf05368c0`）或 v3.1（`0x1b93ad61`）直接判失败；遇到 ZIP64 直接放弃判定。
- 比对分两步：先比证书 DER 长度，再把证书哈希转成小写十六进制字符串做 `strcmp`。

对本分支的约束由此确定：release 包必须保持 v2-only 签名；必须使用与补丁 LKM 内置白名单一致的 keystore；证书 DER 长度必须精确匹配。任何一项不符，安装后设备都不认这个管理器（表现为界面显示未安装、底栏消失）。

### 3.2 补丁 LKM 与内置资产

内核期望常量有两条改写路径：

- 路径 A：修改 `kernel/Kbuild` 中的期望值并重新编译内核。代价是每次内核更新都要重新打补丁。
- 路径 B：把补丁直接打进 LKM（`kernelsu.ko`），刷一次引导分区即可。**本分支采用路径 B。**

补丁是对 ko 做等长字节替换：

- 64 字符的期望哈希串替换为本分支证书的 SHA-256。
- `cmp w21, #0x33b` 改为 `cmp w21, #0x2e8`，即期望的证书 DER 长度由 827 改为 744。
- 注意定位方式：反汇编工具输出的偏移是 `.text` 段内偏移，与文件偏移相差段起始地址；直接按反汇编偏移改文件会改错位置。本模块两处改动的文件偏移为：`cmp` 指令 43108（`0xa864`，全文件仅出现一次），证书哈希字面量 112938（`0x1b92a`）。查字节时用 Latin-1 解码后做子串匹配，逐字节拼十六进制串在大文件上不可靠。

内置方式与运行期取用：

- `userspace/ksud/src/assets.rs` 用 `#[derive(RustEmbed)]` 把 `userspace/ksud/bin/<arch>` 整个目录编进二进制（按目标架构与目标系统选择目录：aarch64+android 用 `bin/aarch64`，x86_64 用对应目录，非 Android 目标用 `bin`）。
- 同一文件规定 `ksuinit` 与 `*.ko` 不落盘，只从内存加载，以减少可被扫描的痕迹；`list_supported_kmi()` 枚举可用的 KMI。
- `userspace/ksud/src/late_load.rs` 在 LKM 模式下以 `format!("{kmi}_kernelsu.ko")` 取资产并加载；KMI 缺省时向 `boot_patch::get_current_kmi()` 询问。
- `userspace/ksud/src/boot_patch.rs` 在给 GKI 镜像打补丁时同样按 `{kmi}_kernelsu.ko` 取 ko，并取 `ksuinit` 作为 init 载荷，最终把两者写进 cpio。

当前内置的 ko 是打过补丁的版本：`userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko`（315176 字节，sha256 `eefc53db7e533a2de8c7968bd28adc276b1f7291e95fbdb8e760e62234b89f64`），其中本分支证书哈希位于文件偏移 112938，而官方哈希在整个文件中不存在。这可以用作「内置资产是否已换成我方版本」的判据。
- 该 ko 与 `ksuinit` 随仓库分发（`userspace/ksud/bin/aarch64/`）：上游在该目录用 `**/*.ko`、`**/ksuinit` 忽略 CI 注入的产物，本分支没有 CI，因此对这两个文件加了放行规则。`userspace/ksud/bin/x86_64/` 有 `busybox` 与按上游 ksuinit.yml 的静态链接 recipe 本机构建的 `ksuinit`（无动态依赖）；x86_64 的 `_kernelsu.ko` 由上游 `ddk-lkm.yml` 在 Android DDK 容器里生成，本机没有该容器，因此该架构的 LKM 模式缺少内置资产。

后果：只要刷入或加载这个 ko，管理器身份即为本分支；换 keystore 或换 KMI 都需要重新补丁并重新内置。

### 3.3 ksud 与包名的编译期耦合

- `userspace/ksud/src/defs.rs` 中 `pub const DEFAULT_PACKAGE_NAME: &str = env!("KSU_PACKAGE_NAME");` —— 包名在编译期被写死进 daemon。
- `userspace/ksud/build.rs` 在未设置该变量时回落到上游名 `me.weishu.kernelsu` 并打 `cargo:warning`；它也声明了 `cargo:rerun-if-env-changed=KSU_PACKAGE_NAME`，因此先跑一次未设置变量的构建、再设置变量重建是安全的。
- 若 daemon 用了回落值，运行期会去 force-stop 并拉起官方管理器，并把 boot 备份写到官方数据目录。

由于上游没有任何地方设置该变量（`userspace/ksud/` 下没有 Makefile），它必须由 `_tools/build-ksud.ps1` 显式导出，且必须与 `manager/gradle.properties` 的 `KSU_PACKAGE_NAME` 一致。`_tools/verify-identity.ps1` 把这件事拆成两个独立决定项分别核对：APK 的 applicationId + 签名证书，以及 daemon 二进制内编译进去的包名。

核对 daemon 包名时不要匹配「包名 + Activity」的拼接串：daemon 把包名与 Activity 后缀存为两个独立的格式参数，该字符串在二进制里永远不会出现，这种检查永远无法失败。正确做法是用带排除的 lookahead 正则，把 `me.weishu.kernelsu.hide`（本分支）与 `me.weishu.kernelsu.ui`（Activity 类路径）列为合法余项。

### 3.4 Rust 工作区与依赖重定向

- 根 `Cargo.toml`：workspace 成员为 `userspace/ksud` 与 `userspace/ksuinit`，`resolver = "3"`、edition 2024；release profile 为 `strip = true`、`lto = true`、`opt-level = "z"`、`panic = "abort"`，并单独为 ksud 设置 `codegen-units = 1`。
- 上游依赖所在的 GitHub 组织已改名：`userspace/ksud/Cargo.toml` 与 `userspace/ksuinit/Cargo.toml` 中的 `java-properties`、`adb_client`、`prop-rs-android`（仓库名 `ksu_props`）、`rustix` 都指向 `KernelSU2/*`，`Cargo.lock` 必须与之一致。旧组织名的 git 端点在拉取时返回 401/404，构建脚本头部注释也明确要求在 `Cargo.toml` 与 `Cargo.lock` 两处都使用新组织名。

### 3.5 构建与静态检查

Rust 侧改动后的固定顺序（`userspace/ksud`、`userspace/meta-overlayfs` 同样适用）：

```bash
cargo ndk -t arm64-v8a check
cargo ndk -t arm64-v8a clippy
cargo fmt
```

`_tools/build-ksud.ps1` 的要点与既往故障：

- `-Platform` 默认 31：`userspace/ksud/src/utils.rs` 用到 `__system_property_read_callback`，该 bionic 符号自 API 26 起才存在，而 cargo-ndk 默认按 API 21 构建，会出现 `ld.lld: error: undefined symbol: __system_property_read_callback`。
- clippy 与 check 也必须经 cargo-ndk：直接调用会让 `CC_aarch64-linux-android` 等变量为空，报 `failed to find tool "aarch64-linux-android-clang"`。
- `-Fmt` / `-Clippy` / `-Check` / `-NoVerify` 用于静态检查，只做检查、短路构建，退出码即判定结果。
- 产物内的路径：Rust 会把每个 panic 点的源文件路径（`file!()`）写进二进制，直接构建会把构建机的绝对路径带进 `libksud.so`。脚本固定注入 `RUSTFLAGS=--remap-path-prefix=<工作区>=/workspace --remap-path-prefix=<用户目录>=/home`，产物里只保留 `/workspace/...` 形式。
- 宿主工具链：交叉编译时宿主侧的构建脚本与 proc-macro 仍需要 C 编译器与链接器。脚本用工作区内置的 MinGW-w64 GCC 同时承担 `CC_x86_64_pc_windows_gnu` / `AR_x86_64_pc_windows_gnu` 与 `CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER`；只把 rustup 自带的 gcc 存根当链接器，会以 `ld: cannot find crt2.o` 结束。
- 发布位置只有一个：`manager/app/src/main/jniLibs/<abi>/libksud.so`。不要同时写进 `userspace/ksud/bin/<abi>/`——`bin/x86_64` 正是 x86_64 守护进程的 rust-embed 资产目录（`userspace/ksud/src/assets.rs`），副本留在那里会被下一次 x86_64 构建当成资产嵌进二进制（实测 `.rodata` 由 1.4 MB 涨到 4.2 MB），并在运行期被 `ensure_binaries` 解出一个多余的 `/data/adb/ksud/bin/libksud.so`。

静态检查闸门是 `userspace/ksud/src/main.rs` 首行的 `#![deny(clippy::all, clippy::pedantic)]`。上游沿用的 `derive-new` 在新版 clippy 的 pedantic 下会命中两类告警：`sepolicy.rs` 的 `derive(new)` 展开被判为 `redundant_field_names`，`lkm_image.rs` 的历史断言写法被判为 `assert_is_empty`；两条都落在上游文件里。收口方式是在同一个 crate 级 `allow` 列表里补上这两条 lint 并注明来源，不改写上游实现，`deny` 与其余 `allow` 项保持原样，本分支新增代码仍在闸门内。`_tools/build-ksud.ps1 -Clippy` 只做检查，退出码即结论，当前两个 ABI 均为 0。

## 4. 管理器与界面

### 4.1 底栏与入口

- `manager/app/src/main/java/me/weishu/kernelsu/ui/navigation3/Routes.kt` 定义 `data object HideEnvironment : Route`。
- `MainActivity.kt` 用 `entry<Route.HideEnvironment> { mainScreenEntry() }` 挂载页面，并按位置索引分发（环境隐藏在索引 2）。
- 四种导航容器都需要补上第 3 项：`BottomBarMiuix.kt`、`BottomBarMaterial.kt`、`NavigationRailMiuix.kt`、`NavigationRailMaterial.kt`。
- 完整界面的判定为 `isFullFeatured = rootAvailable() && (!isManager || !Natives.requireNewKernel())`：只要有 root，即使 APK 尚未被内核加冕也放开全部页面；没有 root 时才收窄。导航容器的滚动与徽标也随之受该标志控制。

### 4.2 环境隐藏页结构

页面壳是 `ui/screen/hiding/HideEnvironmentScreen.kt` 的 `HideEnvironmentPager`，按 `LocalUiMode` 分发到 Miuix 与 Material 两套实现；两者共享同一份状态与动作定义：

- `HideEnvironmentUiState.kt` 定义 `HideEnvironmentUiState` 与 `HideEnvironmentActions`。
- `HideEnvironmentMiuix.kt` 与 `HideEnvironmentMaterial.kt` 只做渲染。

UI 状态分四组：

1. 环境状态（只读）：kernel / manager UAPI 版本、安全模式、LKM 模式、late load 模式、SELinux 状态、Android release、内核 release。
2. 隐藏开关：SELinux 隐藏、内核 umount、su compat 模式、默认 umount 模块，各带一个状态字符串与布尔/整数取值。
3. 资源包：`pack`、`packState`（Loading / Ready / Unavailable）、`installStates`、`recommendedIds`、`runtimeMode`。
4. 流程状态：一键隐藏与快捷安装各自的运行中标志、勾选项与日志。

三个派生视图决定了界面行为：

- `visibleEntries`：按当前运行模式过滤后的条目，是屏上计数、推荐集与安装计划的**唯一**来源。
- `quickEntries`：`visibleEntries` 中 category 为 detector 的条目，即快捷安装卡提供的内容。
- `bundleEntries`：资源包卡片渲染的内容 —— 剔除检测器（由快捷安装卡承担），并把多个 PathMask 变体折叠为与当前内核匹配的那一个；若一个都不匹配（内核 release 无法解析，或分支表里没有对应项），则保留全部变体，避免该区域变成死路。

`branchMatchesKernel(branch, kernelRelease)`（定义在 `ui/screen/hiding/HideEnvironmentUiState.kt`）被推荐集与 `bundleEntries` 共用，保证资源包卡片与一键安装计划不会对同一内核给出不同的分支：它取分支名最后一段（`android16-6.12` → `6.12`），要求该段形如 `主版本.次版本`，再与内核 release 中提取出的版本号比较，并用带点号后缀的判定（`actual == expected || actual.startsWith("$expected.")`），因此 `6.12` 不会误配 `6.120`。

### 4.3 运行模式过滤

`HidingRuntimeMode { Lkm, GkiBuiltIn, Unknown }`，其 `packMode` 分别映射到 `HidingPackMode.Lkm`、`HidingPackMode.Gki` 与 `null`。判定逻辑在 `HideEnvironmentViewModel`：

```kotlin
val kernelAnswered = Natives.isManager && !Natives.requireNewKernel()
val runtimeMode = when {
    !getKernelVersion().isGKI() -> HidingRuntimeMode.Unknown
    isLkmMode -> HidingRuntimeMode.Lkm
    kernelAnswered -> HidingRuntimeMode.GkiBuiltIn
    else -> HidingRuntimeMode.Unknown
}
```

条目侧 `HidingPackEntry.appliesTo(runtimeMode)` 的规则是：`modes` 为空即适用；运行模式的 `packMode` 为 `null`（Unknown）也即适用；否则要求 `packMode` 在 `modes` 内。**Unknown 时过滤是 no-op，页面必须把这一状态讲清楚，不能退化成「默认 false 的布尔过滤」** —— 否则只适用 LKM 的条目会被展示给 GKI 用户，或反之。

为什么两条输入都可能是未知：

- `manager/app/src/main/cpp/ksu.cc` 的 `is_lkm_mode()` 只读取 `KSU_GET_INFO_FLAG_LKM` 位，而该位在 `kernel/supercall/dispatch.c` 中由 `#ifdef MODULE` 在编译期恒置位，因此它返回 false 只可能来自 ioctl 失败（此时会回落到 legacy 查询）。
- `getKernelVersion().isGKI()` 只依据 release 字符串回答，内核从未应答时这个判断同样不可靠。

因此 GKI 分支额外要求 `kernelAnswered`（管理器身份成立、且内核 UAPI 不是「需要更新内核」的状态）成立，否则一律按 Unknown 处理。

页面文案：Unknown 时显示「未检测到运行模式，已显示全部内容」；非 Unknown 时显示「已按 %1$s 模式过滤，共隐藏 %2$d 项」，其中 `%2$d` 是**被隐藏**的条目数（`packEntries.size - visibleEntries.size`），不是可见条目数。

### 4.4 资源包与 manifest v2

生成入口是 `_tools/sync-hiding-pack.ps1`，冻结契约是 `_tools/hiding_manifest.schema.json`。脚本每次先清空并重建目标目录，因此是幂等的：`generatedAt` 会变，但条目、`totalBytes` 与各条目哈希不变。

产物布局（`manager/app/src/main/assets/hiding/`）：`manifest.json`、`modules/<id>.zip`、`pathmask/<branch>.zip`、`detectors/<pkg>.apk`（可选）、`data/...`（可选）。

条目字段与键序被生成器断言，两套顺序固定：

- module / pathmask：`id, moduleId, name, version, versionCode, author, description, category, [branch], asset, sizeBytes, sha256, sourceFile, [modes], order`
- detector：`id, name, version, versionCode, author, description, category, packageName, asset, sizeBytes, sha256, sourceFile, [modes], order`

字段语义：

- `modes`：缺省或空数组表示两种运行模式都适用（安全默认）；出现时必须是 `lkm` / `gki` 组成的非空数组。
- `order`：越小越先安装，缺省 100；必须是整数。
- `moduleId`：同一逻辑模块的稳定标识。6 个 PathMask 分支各有不同的 `id`（`pathmask-android12-5.10` … `pathmask-android16-6.12`），但 `moduleId` 都是 `pathmask`，分支名只出现在 `branch` / `asset` 字段；安装后要判断装的是哪个分支，只能读已安装模块 `module.prop` 的 `updateJson` 末段（`<branch>.json`）。
- `totalBytes` 必须等于所有条目 `sizeBytes` 之和。
- `sha256` 为 64 位小写十六进制。

批量安装有两条硬约束：一个批次只允许恰好一个 PathMask 变体（六个分支会互相覆盖）；`hybrid_mount` 必须是**全包 `order` 最小**的条目，值为 5 —— 它的 `module.prop` 声明 `metamodule=1`，是挂载后端，其他模块都经由它安装，因此必须在最前。完整顺序表：`hybrid_mount` 5、`zygisksu` 10、`zygisk_lsposed` 20、`tricky_store` 30、`hma_oss_zygisk` 40、`pathmask-*` 60、`susfs4ksu` 70、`Automatic_brick_rescue` 80、`detector-*` 100。

当前资源包规模：22 个条目，`totalBytes = 129861613`，其中 modules 7（hybrid_mount、zygisksu、zygisk_lsposed、tricky_store、hma_oss_zygisk、susfs4ksu、Automatic_brick_rescue）、pathmask 6（android12-5.10 / android13-5.10 / android13-5.15 / android14-6.1 / android15-6.6 / android16-6.12）、detector 9。`includes.modules`、`includes.pathmask`、`includes.detectors` 为 true，`includes.data` 与 `includes.keybox` 保持 false。

两个默认关闭的开关都有明确理由：

- `-IncludeKeybox` **永远不要开启**：`keybox.xml` 内含明文 EC 私钥，打包分发等同于二次泄露凭据；脚本在开启时会打印告警。
- `-IncludeDetectors` 默认关闭：检测器 APK 体积大，且其中一个为商用闭源应用；确需随包分发时再显式打开。

`hma_config.json` 位于资源包目录但**不是 manifest 条目**（契约只允许 module / pathmask / detector / data 四类 category），管理器按固定资产路径读取，用作 HMA-OSS 的初始配置。

展示顺序与安装顺序是两件不同的事：资源包卡片按 manifest 的数组顺序渲染，`order` 只决定安装顺序。因此修改生成器的条目遍历顺序会直接改变界面顺序。

已知缺口：`modes` 归属表只为 `pathmask`（lkm）与 `susfs4ksu`（gki）提供了依据（分别来自模块自述的 LKM 路径遮罩与「需要打过补丁的内核」），其余模块与全部检测器没有可引用的证据，因此按「两种模式都适用」的缺省处理。

### 4.5 一键隐藏流程

计划构造在 `buildOneKeyPlan(visibleEntries, recommendedIds, options)`：

1. 基础集 = 可见条目中 category 为 module 且 id 属于 `HidingPackIds.oneKeyBase`（`hybrid_mount`、`zygisksu`、`tricky_store`、`hma_oss_zygisk`）。
2. PathMask：从 `visibleEntries` 中取 id 在推荐集内的一条（若有多个取 `order` 最小者）。推荐集为空时在日志中给出「无匹配内核分支」，而不是静默跳过。
3. 勾选「额外安装 LSPosed」且基础集包含 `zygisksu` 时追加 `zygisk_lsposed`；LSPosed 选项在 `zygisksu` 不可见时禁用。
4. 勾选「添加救砖模块」时追加 `Automatic_brick_rescue`。
5. 按 `moduleId`（无则 `id`）去重、保留 `order` 最小者，最后按 `installOrder = compareBy({ order }, { id })` 升序排序。

执行侧：

- 开始前用 `packRepo.installedIds(plan)` **现场向 daemon 查询**已安装状态，不依赖界面快照。
- 所有安装经 `installMutex` 串行执行；批次运行期间单击安装直接返回（批次独占 root shell）。Mutex 不可重入：`installOne` 内部已经取锁，外层不得再锁，否则该协程会永久停在实际未启动安装的状态。
- 安装本包唯一的 metamodule 会留下 pending update 标记，之后所有非 metamodule 安装都会被 ksud 拒绝直到重启。计划里的这类步骤按该原因记账并继续其余可行步骤，而不是反复重试。
- 进度按「第 n/m 步 · 名称 · 成功|失败:<错误>」逐条记录，最后固定提示「需重启后生效」。

### 4.6 检测器快捷安装

- 优先走 root 路径：`pm install -r -t --user 0 <APK 绝对路径>`，退出码为 0 即成功，且不需要「未知来源」权限。
- 失败才回退：用 `FileProvider` 暴露缓存目录内的 APK（`ACTION_VIEW` + APK MIME + 临时读权限 + `FLAG_ACTIVITY_NEW_TASK`），随后以 60 秒超时、1 秒间隔轮询包是否出现；超时即判失败，并把 root 路径的退出码与输出带进错误信息。任何情况下都不 fire-and-forget。
- 包可见性：管理器未声明 `<queries>`，现代 targetSdk 下进程内的包查询会漏掉第三方包，因此额外用 root shell 的 `pm path` / 一次 `pm list packages` 复核。

「已安装」的判定（`HidingPackRepository`）：

- module：`installed != null && (entry.versionCode <= 0 || installed.versionCode == entry.versionCode)`。这里用**相等**而不是「已装版本不小于包内版本」：上游 fork 可能把 `versionCode` 往下重编号。例如当前随包的 tricky_store 声明 104，而它替换的那个版本声明 235/307。`versionCode <= 0` 表示 manifest 没带版本信息，此时只能诚实断言「存在」。
- pathmask：moduleId 存在 + `versionCode` 相等 + `updateJson` 末段等于分支名 + `.json`，三项同时成立。
- detector：包名出现在 root 列出的包名集合中，或进程内查询命中。

### 4.7 环境状态与隐藏开关

页面顶部直接透传内核与 daemon 的能力：SELinux 隐藏、内核 umount、su compat 模式、默认 umount 模块。状态（kernel / manager UAPI 版本、安全模式、LKM、late load、SELinux、Android 与内核 release）来自 daemon 上报，页面刷新时重新读取，不做本地缓存。

### 4.8 HMA-OSS 配置同步

目的：让 HMA-OSS 首次运行即有一份可用配置，但只补空配置，绝不覆盖用户已有配置，也不凭空生成内容。判定顺序：

1. 运行时目录名带随机后缀（`/data/misc/hide_my_applist_*`），先 `ls -d` 取第一个匹配目录；目录不存在 → 不适用，不记日志。
2. 目标配置文件为空 → 未就绪。
3. JSON 中 `templates` 或 `scope` 非空 → 已有配置，不覆盖。
4. JSON 解析异常 → 视为已有配置（宁可不写也不覆盖用户数据）。
5. 确认为空才写入，并用 `wc -c` 读回校验，最多重试 3 次 —— 该目录的读写可能被 SELinux 偶发拒绝；失败如实记账，不假装成功。

### 4.9 构建与签名

**管理器构建的唯一入口是 `_tools/build-manager.ps1`**（参数 `-Task` 默认 `:app:assembleDebug`、`-WorkTree`、`-NoDaemon`、`-Offline`）。不使用 `gradlew` 的原因：gradle-wrapper 需要从 `services.gradle.org` 下载发行包，而 JDK 无法验证其 TLS 链（`PKIX path building failed`），脚本改为直连预置发行包。

脚本自身处理的环境问题（均为既往故障）：

- 同时设置 `ANDROID_HOME` 与 `ANDROID_SDK_ROOT` 为同一路径，避免既有变量压过新值导致 `NDK not configured`。
- 自设 `JAVA_HOME`、`GRADLE_USER_HOME`、`TEMP`、`TMP`，并注入 `-Dorg.gradle.native=false -Djava.io.tmpdir=...`；默认临时目录不可写会让 Gradle 报 `Could not initialize native services`。
- 构建期间把 `$ErrorActionPreference` 设为 `Continue`：Gradle 写到 stderr 的无害警告在 `Stop` 模式下会被 PowerShell 5.1 提升为终止错误并中断调用方；成功与否只以 `EXITCODE` 判定。
- 源码级别为 21，须使用 JDK 21；用更低版本会报「无效的源发行版：21」。
- 若 Kotlin 编译报 `AccessDeniedException ... kotlin-daemon-client-tsmarker*.tmp`，说明 Kotlin 守护进程被环境限制，附加 `-Pkotlin.compiler.execution.strategy=in-process`；在 PowerShell 中该参数必须整体加引号，否则会被拆成两个参数。

构建前置：`manager/app/src/main/jniLibs/arm64-v8a/libksud.so` 必须存在（由 daemon 产物改名而来），缺失会直接构建失败。

产物命名来自 `manager/app/build.gradle.kts`：`<managerName>_<versionName>_<versionCode>-<buildType>.apk`。

签名要求见 3.1；复验用 `apksigner verify --print-certs -v <apk>`，证书 SHA-256 应与补丁后的期望值一致。AGP 打包时会重新 strip jniLibs，因此 APK 内的 `libksud.so` 与 `jniLibs` 里的源文件哈希不同、体积更小，属正常现象，不代表资产被换回官方版本。

体积：内置资源包（当前 129,861,613 字节）会显著增大 APK，这是刻意的取舍。不要把 `.apk` / `.zip` 资产加入 `noCompress` —— AGP 会主动压缩这类资产，禁止压缩反而会明显增大体积。
## 5. AI 控制台

### 5.1 定位与入口

目标是让模型在受控范围内读取设备状态、执行命令、管理模块，并把安装、删除这类高风险动作压缩为「模型提议 + 用户一次点击」。

入口与上下文：

- 主页的 AI 助手卡进入配置页，再进入控制台（空会话）。
- 路由为 `Route.AiConsole(kickoff: String? = null)`；带 `kickoff` 进入时会自动发起对应问题。
- `AiConsoleKickoff` 定义三个固定上下文：`ROOT_REVIEW`（超级用户页与权限审查页：分析持有 root 的应用）、`MODULE_CONFLICT`（模块冲突页）、`MODULE_DRAFT`（模块制作页：先切到计划模式，再发起模块草稿请求）。

### 5.2 提供商、模型与运行参数

- `ui/screen/aiconfig/AiProviderCatalog.kt`：`AiProviderGroup { RECOMMENDED, LOCAL, MORE }`，默认 provider 为 `deepseek`，默认端点为 `https://api.deepseek.com/v1/chat/completions`；同一文件还登记了 OpenAI 兼容、Anthropic、本地推理（如 Ollama、LM Studio）等条目。
- 模型列表：`data/remote/AiModelListClient.kt` 由聊天端点推导 `/models`，按 provider 家族选择鉴权头（Bearer，或 `x-api-key` + `anthropic-version`），再解析返回的模型 id 列表。
- 设置存储：`data/repository/AiSettingsRepositoryImpl.kt` 使用独立的偏好文件 `ai_settings`，键为 provider / endpoint / api_key / model_name 等。API 密钥以明文 XML 存放，与仓库既有做法一致，源码注释已标明该风险。
- 运行参数：轮数 `AiRounds`（默认 50，上限 100，可选档位 1/2/3/5/8/10/30/50/80）、读取上限 `AiReadLimits`（默认分块 128000、整读 2048 KB，上限 256000 / 4096 KB）、消耗估算 `AiTokenEstimate`（约 4 字符 = 1 token，每轮固定开销 2000 token，并按单价换算金额）。安全卡底部即时刷新最坏情况估算，便于用户在放开参数前看到代价。

### 5.3 访问范围

`data/agent/AiAccessScope.kt` 定义三档：`NONE`（默认，fail-closed）、`DATA_ADB`、`ROOT_FS`。派生属性包括 `canReadFiles`、`coversDataAdb`、`coversRoot`；`isElevationFrom(current)` 用 ordinal 比较判断是否为升档；旧的布尔开关通过 `fromLegacyAllowModuleDir` 迁移。

口径：升档必须显式确认（弹窗），降档立即生效并写审计（`origin = user`，不进入「AI 操作历史」；配置页访问范围下方提供「恢复上一档」）。所有文件类动作都要求路径落在当前范围内，范围不足时不是静默失败而是拒绝并记录。

### 5.4 动作管线与安全分级

`data/agent/AiAction.kt` 定义 `AiSafetyTier { SILENT, LIGHT, STRICT }`：SILENT 可无人值守执行，LIGHT 需要轻量确认，STRICT 必须显式确认（红色弹窗）。

动作集合是封闭的：

| 动作 | 说明 |
| --- | --- |
| `ReadFile` / `ReadFileChunk` / `ListDir` / `GetFileMetadata` / `SearchInFile` | 只读类 |
| `RunCommand` / `SafeExec` | 执行命令；两者都经白名单与元字符检查 |
| `RootAppList` | 列出持有 root 的应用 |
| `GrantSu` / `RevokeSu` | root 授权与撤权 |
| `EnableModule` / `DisableModule` / `InstallModule` | 模块启停与安装 |
| `MoveToTrash` | 移入回收站（`/data/adb/.ai_trash`），不是直接删除 |
| `MakeModule` | 由模型撰写载荷并安装模块（见 5.7） |
| `Unknown` | 无法识别的动作 |

判定集中在 `data/agent/AiPathGuard.kt`，原则是**未知一律 STRICT，绝不静默执行**：

- 只读类动作（读文件、列目录、搜文件、取元数据、`pm list`、只读文件命令、设备信息命令）→ SILENT。
- 写入 → `escalate(LIGHT, batchSize)`；同一轮批量动作数达到 `BATCH_STRICT_THRESHOLD = 3` 时升为 STRICT（撤权在小批量时属 LIGHT）。
- 删除类、`MoveToTrash` → 恒 STRICT。
- 命令执行：命中严格命令集合、含 shell 元字符、存在写操作、或不在任何白名单内 → STRICT；`pm` 只放行 `pm list`。
- 路径必须位于当前访问范围内，否则 Deny 并记录原因。
- `Unknown` 动作按 STRICT 处理，保证未知指令永远不会静默执行。

安装与制作的闸门有意不对称：`InstallModule` 只需在批量规则下升级（载荷由用户提供），而 `MakeModule` 恒 STRICT —— 它是唯一由模型撰写载荷的动作，且其中的 `customize.sh` 最终由 ksud 以 root 身份执行。

审计：`data/repository/AiAuditRepositoryImpl.kt` 把动作写入 `filesDir/ai_audit.jsonl`，只保留最近若干条；`data/agent/AiAuditUndo.kt` 为可撤销动作提供撤销，审计条目记录动作类型、目标、结果与是否可撤销，并带 `origin`：AI 执行链写入 `assistant`，用户在设置页的动作写入 `user`；`ui/screen/aiaudit/` 只展示 `assistant` 条目，旧数据里缺 `origin` 的 `SCOPE_CHANGE` 行读取时按 `user` 兜底。

### 5.5 计划模式

- 系统提示在计划模式下要求模型一次性给出完成任务所需的全部动作（只读侦察动作可排在最前），每条写清理由，并明确「在用户批准之前，不得声称任何动作已执行」。
- 客户端把动作渲染为计划卡，提供「一键执行全部」「修改计划」「中止」。
- 只读动作在计划模式下自动放行，写入、删除、root 授权、模块启停仍逐个确认。
- 任一步失败或被拒绝：该步标红，后续步骤转为跳过，计划转为暂停状态，并把原因回传给模型，便于下一轮修订。

### 5.6 附件与图片

- `ui/screen/aiassistant/AiAttachments.kt`：`.log` / `.txt` 直接读取；`.zip` 抽取其中的 `.log` / `.txt`；其他文本类按扩展名处理。上限为单文件 8 MB、文本 64000 字符、压缩包最多 10 条且每条 4000 字符、整体提示不超过 120000 字符；超限抛 `AiAttachmentException`。
- 附件正文以隐藏的用户消息形式随首条消息发给模型，不在界面上回显。
- 图片：`data/remote/AiChatClient.kt` 支持两种载荷形态 —— OpenAI 兼容端点的 `image_url` + data URI，以及 Anthropic 的 `image` + `source.base64`；单图上限 5 MB。

### 5.7 模块制作（make_module）

目标：把「写一个模块」变成对话或计划模式里的一个动作 —— 说清需求，模型给出完整草稿（id、元信息、文件内容、安装脚本），客户端渲染为计划卡，确认后由 ksud 真实安装；同一条链路还能把草稿预填回模块制作页供人工修改。

实现要点：

- 草稿模型与校验 `data/modulemaker/ModuleDraft.kt`：`ModuleDraftValidator` 的 id 规则与 ksud 的模块 id 校验一致（字母开头，其后为字母、数字与 `.` `-` `_`）；上限为文件数 20、单文件 64 KB、总量 128 KB、安装脚本 32 KB，均按 UTF-8 字节数计算。
- 打包 `ModulePackage`：写出 `module.prop` 与 `customize.sh`，产物为 `<id>.zip`，压缩条目时间戳置 0 以保证可复现。
- 安装 `data/modulemaker/ModuleMaker.kt`：草稿先落到缓存目录 `modulemaker/`，再经 `ui/util/KsuCli.kt` 的 `installModuleZip` 调用 `ksud module install <zip>`，成功判据是退出码为 0；安装器输出截断到 6000 字符并在被截断时明确提示。
- 草稿投递 `ModuleDraftStore`：控制台解析出 `MakeModule` 动作后立即把草稿发布给模块制作页，页面顶部提示草稿来自控制台、安装前需核对 id 与文件清单。
- 已知注意点：模块制作页的 ViewModel 会在导航条目重建时被复用，草稿消费必须放在可重入的 `applyDraft(draft)` 中，只写在 `init` 里会丢草稿。

模块制作页本身（`ui/screen/modulemaker/`）由四张卡组成：模块信息、文件、安装脚本、包内文件，配合「生成安装包 / 安装模块 / 让 AI 起草」三个动作。

### 5.8 权限审查、模块冲突与审计撤销

- 权限审查页（`ui/screen/aipermission/`）列出持有 root 的应用，可把结果交给控制台分析（`ROOT_REVIEW`）。
- 模块冲突页（`ui/screen/aiconflict/`，配合 `data/model/ModuleConflict.kt` 与仓库实现）扫描已启用模块并给出冲突结论（`MODULE_CONFLICT`）。
- 审计页（`ui/screen/aiaudit/`）只展示 AI 的动作记录（`origin = assistant`），并对可撤销动作提供撤销入口；用户自己的档位变更不进该页，改由配置页「恢复上一档」处理。

### 5.9 界面与本地化

- Miuix 与 Material 两套实现共享状态与动作，只在渲染层不同。
- 面向用户的文案全部走资源键（`ai_console_*` 等），中英文各一套；模型协议文本与内部提示保持中文，且不直接回显给用户。
- 输入区是单容器结构：附件行 + 自适应高度文本区（最小 44dp，最大 148dp）+ 动作行（模式切换、附件、发送/停止）。文本区独占整宽，避免与按钮争抢宽度造成的观感差异。
- 消息列表跟随流式输出：滚动 key 必须同时包含消息数量与尾部内容长度，否则回复变长时不会继续跟随。
- 长输出默认折叠，摘要判定同时看字符数与行数（约 600 字符或 8 行以内视为短）。链接色按对比度挑选，目标是不低于 4.5:1。
- 危险动作的确认弹窗按「一次显式点击」处理，不再要求输入关键字。
- 清空会话需要二次确认，且控件带可见文字语义，不退回纯图标按钮。

## 6. 已知坑与经验

### 6.1 资源包与 manifest

- 资源包卡片的展示顺序等于 manifest 的数组顺序，`order` 只影响安装顺序（见 4.4）。
- 生成器的键序断言是有意的硬闸门：改动条目字段顺序会让脚本直接失败，而不是生成一份契约外的 manifest。
- PowerShell 函数返回单元素数组会被自动解包成标量：取 `modes` 时必须使用逗号运算符（`return ,$modesTable[$id]`），否则 `@("gki")` 会退化成字符串，违反 schema。
- `$null` 表示「两种模式都适用」，绝不能把 null 写进 JSON。
- `manifest.json` 带 UTF-8 BOM，任何解析前先剥 BOM。
- 解析条目时：`id` 或 `asset` 为空应整条丢弃；`branch` 与 `packageName` 必须先判断 JSON null（`org.json` 会把 null 变成字符串 `"null"`）。

### 6.2 构建工具链

- 不用 `gradlew`（TLS 链无法验证），改用预置 Gradle 发行包。
- `ANDROID_HOME` 与 `ANDROID_SDK_ROOT` 必须同值，否则既有变量会压过新值并导致 `NDK not configured`。
- 临时目录必须可写，否则 Gradle 报 `Could not initialize native services`。
- Gradle 写入 stderr 的警告在 PowerShell `Stop` 模式下会被提升为终止错误，构建脚本必须以退出码而不是异常判定成功。
- 源码级别为 21，必须使用 JDK 21。
- `cargo ndk` 默认平台版本过低会导致 `__system_property_read_callback` 链接失败，需显式指定 `-P 31`；clippy/check 同样要经 cargo-ndk。
- Kotlin 守护进程被限制时用 `-Pkotlin.compiler.execution.strategy=in-process`，且在 PowerShell 中要整体加引号。

### 6.3 身份与签名

- LKM 内置资产与签名 keystore 必须成对匹配；只换其一，安装后设备不认管理器（界面显示未安装、无底栏）。
- 同时带 v3 或 v3.1 签名的 APK 一定不会被判定为管理器。
- daemon 内编译进去的包名与 APK 的 applicationId 是两个独立决定项，必须同时正确。核对 daemon 时不要匹配「包名 + Activity」拼接串，它不会出现在二进制里；用带排除项的 lookahead 正则，把本分支 applicationId 与 Activity 类路径列为合法余项。
- AGP 打包会重新 strip jniLibs，APK 内 `libksud.so` 与源文件哈希、体积都不同，属正常现象。

### 6.4 真机验证

- `uiautomator dump` 在页面过渡、滚动或动画期间会失败且不写出文件，随后读到的是上一次的旧内容，表现为「界面与 XML 完全不符」。取 UI 结构前先删除旧文件，并确认 dump 成功再解析。
- 点击前重新获取当前坐标，不要复用上一步的 bounds；键盘弹出或弹窗出现时，目标元素会整体上移。
- 普通 shell（uid 2000）读不到 `/data/adb`：命令无输出不等于目录为空，需要用 root shell 复核。
- 设备自动灭屏或进入锁屏会让 dump 取到锁屏界面，容易误判目标界面；验证前确认屏幕处于解锁状态。
- `adb shell input text` 无法输入中文，需要改用应用级 locale 或换输入方式。
- 通过 PowerShell 重定向抓取二进制会损坏文件（例如截图应先在设备内落盘再拉取，不要直接管道输出）。

### 6.5 文本编码与脚本

- Windows PowerShell 5.1 按 ANSI 读取无 BOM 的脚本文件，含中文的脚本应保留 UTF-8 BOM。
- 模板类编辑工具写回时可能丢失 BOM，改完含中文的脚本要确认 BOM 仍在且能被解析。
- 使用 PowerShell 的 > 重定向会写出 UTF-16LE，不要用它生成需要按 UTF-8 读取的文本。
- 文档样式：正文与表格单元格里不要用行内代码或行内链接承载语义。某些阅读与入库管线（浏览器复制、知识库切分）会把行内代码、链接的内容抽出来挂到行尾，正文会被读成断句；字面量直接写在句子里，或放进代码块。

## 7. 剩余工作与风险

### 7.1 本轮收口的遗留项

以下五项是上一版列出的未完成项，本轮逐条收口。

1. clippy 闸门已通过。告警共 82 条，全部落在上游文件：`sepolicy.rs` 的 `derive(new)` 展开被判为 `redundant_field_names`（80 条），`lkm_image.rs` 的历史断言写法被判为 `assert_is_empty`（2 条）。处理方式是在 `userspace/ksud/src/main.rs` 已有的 crate 级 `allow` 列表里补上这两条 lint 并注明来源，不改写上游实现；`deny(clippy::all, clippy::pedantic)` 与其余 `allow` 项保持原样。验证命令 `_tools/build-ksud.ps1 -Clippy`，两个 ABI 的 clippy 退出码均为 0。
2. 图片理解已在真机上完成端到端验证。在设备上生成一张内容已知的测试图（深蓝底、左上橙色圆、圆右侧亮绿三角、下方白条内黑色粗体文字 KSU-IMG-7F3A），经控制台的添加附件按钮走系统文件选择器附上，模型回复的文字、形状与配色与测试图完全一致。这一次回复覆盖了附件选择、图片上行与结果渲染三段真机路径。
3. 模块制作与计划模式的界面口径已裁定并在真机上确认。模式开关是输入框上方的两个 chip（对话模式 / 计划模式）；处于计划模式时任何动作都不自动放行，包括只读的查看文件信息，而是先落成一条待确认步骤，界面只给出修改计划与一键执行全部两个出口，执行必须由用户显式触发。模块制作有两处入口：控制台内的 make_module 动作（草稿生成后可在模块制作页打开），以及模块制作页本身，对应路由 `ModuleMaker`。
4. 资源包模式归属的依据已确认，并已写进冻结契约：`_tools/hiding_manifest.schema.json` 的 `modesTable` 逐条记录依据——pathmask 的 module.prop 自述为 LKM 路径遮罩，据此为 `lkm`；susfs4ksu 自述为编译自打过补丁的内核源码，据此为 `gki`；其余模块的 module.prop 未声明运行模式，因此 `modes` 留空表示两种模式都适用，运行时判定见 `HidingPack.appliesTo`。本轮重新核对了资源包内全部 module.prop，与上述依据一致。
5. 设备测试遗留已清理。`/sdcard` 根目录下 672 个界面转储与截图全部删除；`/data/local/tmp` 下的探针脚本与文本转储全部删除。保留的是回滚与恢复资产：`/data/local/tmp` 下的引导镜像备份、预编译内核模块、ksud 备份与官方管理器 APK，以及 `/sdcard` 下的三个 installed 开头的 APK、`hma_config.json` 与 `init_boot_a_now.img`。root 所有的 `/data/local/tmp/ksuout2` 与 `ksutrial` 需要 root 权限才能删除。

真机状态（本轮实测）：安装包为 `me.weishu.kernelsu.hide`，versionCode 32601、versionName v3.3.0+main；底栏五项齐备，环境隐藏页位于第三位，主页有接入 AI 助手入口卡；环境隐藏页显示功能完整与 LKM 模式，内核 UAPI 版本与管理器 UAPI 版本均为 2，内核版本 6.6.77-android15-8，SELinux 强制执行。设备实际系统为 Android 16（sdk 36），更早文档中的 Android 15 说法以本节为准。

编号用例 B1–B13 在仓库内没有定义（历史上只存在于仓库外的材料里），因此不再引用这套编号；本轮按页面与功能逐条验证，结论即本节与第 6.4 节。

### 7.2 风险

- 身份机制脆弱：强绑单一 keystore、单一 KMI（当前只内置 android15-6.6）、强绑单一 slot（只有 `_a` 打了补丁）、必须保持 v2-only 签名、证书 DER 长度必须精确匹配。
- 回滚源只有引导分区镜像备份，且备份位于设备内的 `/data/local/tmp`：清空数据分区或格式化即丢失，更底层的恢复能力未验证。建议尽快把镜像复制到设备外保存。
- 救砖模块的实际效果未在设备上验证，目前只有模块自身开机脚本的自检提示。
- 内置资源包含第三方模块与商用闭源应用，再分发许可需要逐个确认；keybox 不随包分发。
- 应用显示名仍为 KernelSU，与官方管理器同名，只靠不同 applicationId 并存，桌面上两个图标同名。
- 当前访问范围是允许访问根目录加 shell 开启，模型可在不逐条确认的情况下执行命令；批量动作另有阈值升级，但整体仍属高风险配置，非调试场景应下调。
- 一键隐藏是串行链路且需重启后生效；该流程在本分支执行过，但步骤日志完整性与重启后的模块状态仍应在真机上复核一次。

## 8. 验收与自查清单

**Rust（`userspace/ksud`、`userspace/meta-overlayfs`）**

```bash
cargo ndk -t arm64-v8a check
cargo ndk -t arm64-v8a clippy
cargo fmt
```

或使用 `_tools/build-ksud.ps1 -Fmt -Clippy -Check`（只做检查，退出码即结论）。

**管理器**

1. 先准备 `manager/app/src/main/jniLibs/arm64-v8a/libksud.so`。
2. `_tools/build-manager.ps1 -Task :app:assembleRelease`。
3. 若需核对身份：`_tools/verify-identity.ps1 -Apk <apk>`；加 `-SelfTest` 可先验证检查自身既能失败也能通过。

**资源包**

- 重建：`_tools/sync-hiding-pack.ps1`；需要检测器时加 `-IncludeDetectors`；不要开启 `-IncludeKeybox`。
- 重建后确认 `manifest.json` 的 `totalBytes` 等于各条目 `sizeBytes` 之和，且条目数、`order` 与预期一致。

**设备侧**

- 安装后确认底栏存在，环境隐藏页在第三位，且管理器身份被内核接受。
- 打开环境隐藏页，确认运行模式提示与实际内核一致；若内核无法回答，页面应显示全部条目并给出说明。
- 执行一次一键隐藏，确认步骤日志完整、顺序符合 `order` 表，并在重启后复核模块状态。
- 逐个运行内置检测器，按第 9 节口径判读：核心效果看 Su / Magisk / 模块文件类与密钥认证，注入面命中（Hunter、春秋）属已知代价。
- 「重启」按钮：点前确认按钮是否置灰与文案是「重启」还是「软重启」，点后以 `/proc/uptime` 是否归零区分硬重启与用户态软重启。

**文档自查**

- 全文不得出现本机绝对路径、私有目录名与多代理协作痕迹。
- 提到具体实现时给出仓库相对路径与函数/常量名；行号只作参考。
- 无法在仓库内核对的内容不写入，或标注「待验证」。

## 9. 对抗性验收与「重启」定位（真机实测）

### 9.1 环境与安装方式

设备 OPPO PMA110，Android 16（sdk 36），安全补丁 2026-08-01；fork 身份 32601-2（v3.3.0+main，包名 `me.weishu.kernelsu.hide`），内核 6.6.77-android15-8，SELinux 强制执行，LKM 模式下环境隐藏页显示「功能完整」与「已按 LKM 模式过滤，共隐藏 1 项」。生效的隐藏资源包为 Hybrid Mount v6.2.3-rc.2、Zygisk Next v1.4.3、LSPosed v2.2.0、AlwaysStrong v1.0.4、HMA-OSS Zygisk oss-173、PathMask v2.8.2（android15-6.6）；自动神仙救砖模块未启用。

9 个检测器全部经管理器「环境隐藏 → 快捷安装」卡片安装，其日志逐条打印「第 N/M 步 · 名称 · 成功」。对比结论：走系统安装器（`adb install`，shell uid）时，ruru / 春秋 / Hunter 会被 ColorOS 的安装引导拦截（`com.oplus.appdetail/.model.guide.ui.InstallGuideActivity`，中高风险变体没有「继续安装」按钮）；管理器自带的 root 安装路径不弹确认框，可静默装完。

验收手段为 `uiautomator dump` 取文本节点加设备截图人工判读，设备截图不入库。

### 9.2 逐个检测器的结论

| # | 检测器 | 包名 | 版本 | 结论 | 关键观察 |
| --- | --- | --- | --- | --- | --- |
| 1 | momo 真机检测 | `io.github.vvb2060.mahoshojo` | 4.4.1 | 无 root/隐藏命中，残留可疑迹象 | 结论行「没有发现修改，但是存在可疑迹象」；唯一一条可疑痕迹是「已开启调试模式」——由测试本身（主机 adb 驱动）造成；另打印系统 Fingerprint 与安全补丁级别 |
| 2 | ruru 如如检测 | `com.byxiaorun.detector` | V1.1.1 (15) | 3 组「可疑」，无 root/隐藏命中 | 风险分 0.0；环境检测、PM 命令 / PM 混合API / PM 主界面、Libc 与 Syscall 文件检查、Xposed 模块、LSPatch、Magisk 软件、帐户均为「未发现」；「PM 常规Api查询」标可疑但展开后的每一条（Magisk / LSPosed / XPrivacyLua / HMA 等包名）都是 ✓，即隐藏本身有效；另两项可疑为「无障碍服务」与「设置属性」 |
| 3 | Hunter 猎人检测 | `com.zhenxi.hunter` | 6.65 | 判定被 Hook 与修改 | 全屏红字「黑灰产设备,当前程序已经被Hook&修改!」，风险分 0.0；说明 Zygisk / HMA-OSS 对应用进程的注入在自身进程内可见；未命中 Su / Magisk 文件类 |
| 4 | Luna 检测 | `luna.safe.luna` | 1.4.3.5 | 未取得判定（覆盖缺口） | 首屏弹更新提示，取消后停在「等待授权」，随后「超时 / Get Luna Version error」；设备联网正常（ping 8.8.8.8 0% 丢包）、INTERNET 已授权，是其授权/版本服务不可达 |
| 5 | 紫色放大镜 内存检测器 | `io.github.huskydg.memorydetector` | v2.1 | 通过 | 首屏即「Looks fine! Nothing is found.」 |
| 6 | 密钥认证 KeyChain 测试 | `wu.keyChain.test` | — | 通过 | 谷歌硬件根证书可用、**引导加载程序已锁定**、可信执行环境「正常」、密钥认证 2.0.3；证书链为 Google 根签发的 Android Keystore 密钥，说明 AlwaysStrong 的密钥伪装在认证链上成立 |
| 7 | 应用列表检测器 | `com.tsng.applistdetector` | V1.3.2 | 头部标记异常环境，各项未发现 | 标题行「abnormal environment」，随后 pm list packages / pm 常规API / pm 混合API / getPackagesHoldingPermissions / queryIntentActivities / libc / syscall / magisk 随机包名 / xposed 模块全部「未发现」 |
| 8 | 春秋检测 | `com.chunqiunativecheck` | 3.9 (55) | 判定 System compromised | 风险分 0.0；主界面红条「System compromised / 以下检测项存在异常，请核查环境完整性」；可见异常项为 `com.didjdk.adbhelper +7` 与 `Tampered Attestation Key (26)`；该 APK 启动即弹不可关闭的「发现新版本」对话框（无取消/关闭，返回键、点击遮罩、`pm clear`、断网均无效），完整清单未能逐条展开 |
| 9 | MT 管理器 | `bin.mt.plus.canary` | — | 正常启动（人工比对工具） | 文件管理界面可正常浏览 `/sdcard`；该 APK 为资源包内的商用闭源工具，本身不产出自动判定 |

**口径**：隐藏链路的核心效果成立——没有任何检测器报出 Su、Magisk、模块文件或 Syscall/Libc 类命中，密钥认证给出「引导加载程序已锁定」与正常 TEE；但注入面仍可被观测（Hunter 在自身进程内看到 Hook），并且环境属性类检测还有三处「可疑」（ruru 的无障碍服务、设置属性、PM 常规Api查询）与两处篡改类判定（春秋的 Tampered Attestation Key、应用列表检测器的 abnormal environment 头部）。这些是设计取舍的边界，不是安装失败。

### 9.3 「重启」按钮：点击无响应的定位

**代码路径**：环境隐藏页「一键隐藏」卡片右下的按钮在 `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/hiding/HideEnvironmentMiuix.kt:372-391`（Material 版为 `HideEnvironmentMaterial.kt:361`）：文案由 `isSoftRebootPreferred()` 决定显示「重启」还是「软重启」，`onClick` 传入 `soft_reboot` 或空串，`enabled = !uiState.isBatchRunning`。`isSoftRebootPreferred()`（`manager/app/src/main/java/me/weishu/kernelsu/data/repository/SettingsRepositoryImpl.kt:25-27`）在 late-load（免重启加载）模式或设置项 `soft_reboot` 为真时为真。`rememberRebootAction()`（`manager/app/src/main/java/me/weishu/kernelsu/ui/component/rebootlistpopup/RebootListPopup.kt:44-58`）在 late-load 模式且未指定 reason 时先弹确认框，否则直接调用 `reboot(reason)`；`reboot()`（`manager/app/src/main/java/me/weishu/kernelsu/ui/util/KsuCli.kt:501-512`）对 `soft_reboot` 走 `ksud soft-reboot`，其余走 `svc power reboot <reason> || /system/bin/reboot <reason>`。

**真机复现**：页面该卡片有两个可点击容器，「开始隐藏」与「重启」；点击「重启」容器中心后设备真实重启——adb 连接中断，`/proc/uptime` 由 32269 归零再回到 22，`sys.boot_completed` 恢复为 1。当时页面文案是「重启」而非「软重启」，因此未弹确认框，与代码路径一致。

**结论**：按钮本身没有失效，「点击无响应」是状态相关的表现，可复现路径有三条：

1. 一键隐藏批次运行期间 `enabled = false`，点击被静默忽略（卡片同时置灰）。
2. 软重启偏好生效时（late-load 模式或设置开启）执行的是 `ksud soft-reboot`，只重启用户态：内核 uptime 不归零、界面只是短暂闪一下，容易被误判为没反应。
3. late-load 模式下先弹确认框，不点「确定」就不会重启。

**发现的一处真实缺陷**：确认框的 `onConfirm = { reboot() }` 没有携带 `reason`，因此在 late-load 模式下即使偏好软重启，经确认框执行的动作也总是硬重启。修法是把确认框的构造放进返回的 lambda 内以捕获 `reason`，例如 `{ reason -> if (Natives.isLateLoadMode && reason.isEmpty()) showConfirm(onConfirm = { reboot(reason) }) else reboot(reason) }`。本次只定位不改码。

## 附录 A. 关键常量一览

以下取值均来自仓库源码，改动时请同步本节。

### A.1 资源包与安装

| 常量 | 值 | 位置 |
| --- | --- | --- |
| manifest 版本 | `2` | `assets/hiding/manifest.json` |
| 条目数 / `totalBytes` | 22 / `129861613` | 同上 |
| 默认 `order` | `100` | `data/model/HidingPack.kt` 的 `HidingPackEntry.DEFAULT_ORDER` |
| 检视器安装超时 / 轮询间隔 | `60_000L` / `1_000L`（毫秒） | `data/repository/HidingPackRepository.kt` |
| 资源包清单资产 | `hiding/manifest.json` | 同上 |
| APK 解包缓存目录 | `hiding_pack`（位于应用缓存目录） | 同上 |
| HMA 运行时目录匹配 | `/data/misc/hide_my_applist_*` | `ui/viewmodel/HideEnvironmentViewModel.kt` |
| HMA 初始配置资产 | `hiding/hma_config.json` | 同上 |
| 一键隐藏基础集 | `hybrid_mount`、`zygisksu`、`tricky_store`、`hma_oss_zygisk` | `HidingPackIds.oneKeyBase` |

### A.2 AI 控制台

| 常量 | 值 | 位置 |
| --- | --- | --- |
| 默认 provider | `deepseek` | `ui/screen/aiconfig/AiProviderCatalog.kt` |
| 轮数默认 / 上限 | `50` / `100` | `ui/screen/aiconfig/AiRounds.kt` |
| 分块读取默认 / 上限 | `128000` / `256000`（字符） | `ui/screen/aiconfig/AiReadLimits.kt` |
| 整读默认 / 上限 | `2048` / `4096`（KB） | 同上 |
| token 估算 | 4 字符 ≈ 1 token，每轮固定 2000 token | `ui/screen/aiconfig/AiTokenEstimate.kt` |
| HTTP 超时 | 连接 15 s、读写各 120 s、整体 300 s，不使用缓存 | `data/remote/AiHttpClient.kt` |
| 批量升级阈值 | `BATCH_STRICT_THRESHOLD = 3` | `data/agent/AiPathGuard.kt` |
| 回收站根目录 | `/data/adb/.ai_trash` | `data/agent/AiActionExecutor.kt` |
| 审计保留条数 | `2000` | `data/repository/AiAuditRepository.kt`（条目含 `origin`：`assistant` / `user`） |
| 附件：文件 / 图片 / 文本 / 压缩包条目 | 8 MB / 5 MB / 64000 字符 / 10 × 4000 字符 | `ui/screen/aiassistant/AiAttachments.kt` |
| 模块草稿上限 | 20 个文件、单文件 64 KB、合计 128 KB、安装脚本 32 KB | `data/modulemaker/ModuleDraft.kt` |
| 安装器输出截断 | `6000` 字符 | `data/modulemaker/ModuleMaker.kt` |

### A.3 资源字符串约定

- 环境隐藏页：`hide_environment_*`（页面标题、运行模式提示、一键隐藏选项与步骤日志、资源包卡片状态）。
- AI 控制台：`ai_console_*`（标题、清空确认、附件、访问范围行、计划模式日志、轮次与动作日志、各种错误），配置页另有 `ai_config_*` 与 `ai_provider_*`。
- 每个键在中英文资源中各有一份，新增界面文案时应同时补齐。
