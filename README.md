# KernelSU-AI · 一键环境隐藏 + 内置 AI 控制台

> 基于 **KernelSU v3.3.0** 的定制分支。两个核心特性：把「环境隐藏」做成管理器底部导航里的**一键安装链路**，并在管理器内**内置 AI 控制台**（用户自带 API Key、访问范围三档、计划模式、操作审计与撤销；操作历史只记 AI 的动作，用户在设置页改档位不写进这张列表）。隐藏资源包与预编译 `ksud` 随仓库分发，克隆即可出包。

| 项 | 值 |
| --- | --- |
| 上游基线 | KernelSU v3.3.0 |
| 管理器包名 | `me.weishu.kernelsu.hide` |
| 版本 | versionCode `32601` · versionName `v3.3.0+main` |
| 系统要求 | Android 12+（minSdk 31 / targetSdk 37 / compileSdk 37） |
| 许可 | GPL-3.0（继承上游，见 [LICENSE](LICENSE)） |
| 上游仓库 | [tiann/KernelSU](https://github.com/tiann/KernelSU) |

## 快速开始

1. **装包**：使用 [Releases](https://github.com/zxc10089/KernelSU-AI/releases) 中的 APK；该页尚未发布时，按「构建与复现」自行出包（发行 APK 属构建产物，不入库）。
2. **环境隐藏**：打开 App → 底部导航「环境隐藏」→ 一键安装（Hybrid Mount → ZygiskSU → LSPosed → AlwaysStrong → HMA-OSS → PathMask）→ 按提示**重启生效**。
3. **AI 控制台**：主页卡片「接入 AI 助手」→ 配置提供商 / 端点 / 密钥 / 模型名 → 选择访问范围 → 开始对话，或使用计划模式。

## 核心特性

### 1. 一键环境隐藏

管理器底部导航第三项。root shell 严格串行（绝不并发），安装顺序由资源包 `manifest.json` 的 `order` 字段决定：

| order | 条目 | 说明 |
| --- | --- | --- |
| 5 | `hybrid_mount` | metamodule，必须最先安装，之后不能改序 |
| 10 | `zygisksu` | |
| 20 | `zygisk_lsposed` | 仅在勾选「额外安装 LSPosed」且 ZygiskSU 在集合内时 |
| 30 | `tricky_store` | 内容为 AlwaysStrong 替代实现，模块 id 保持不变 |
| 40 | `hma_oss_zygisk` | 安装后同步 HMA-OSS 预置配置 |
| 60 | `pathmask-*` | 六个分支变体，按运行内核分支精确命中一个 |
| 70 | `susfs4ksu` | 仅 GKI 模式 |
| 80 | `Automatic_brick_rescue` | 仅在勾选「救砖模块」时 |
| 100 | `detector-*` | 只出现在「快捷安装」卡片，不参与一键链路 |

- 资源包内置在 APK 资产中，首次进入无需联网：`manager/app/src/main/assets/hiding/`（`manifest.json` + `hma_config.json` + modules 7 + pathmask 6 + detectors 9，`data/` 为空）。
- 日志逐条输出「第 n/m 步 · 名称 · 成功/失败」，最后一行固定为 **需重启后生效**。
- 运行模式无法判定时按 fail-closed 处理：不过滤内容，并在页面上说明「未检测到运行模式，已显示全部内容」。
- 设计规格：[design/environment-hide-spec.md](design/environment-hide-spec.md)｜契约 schema：[_tools/hiding_manifest.schema.json](_tools/hiding_manifest.schema.json)

### 2. 内置 AI 控制台

主页卡片「接入 AI 助手」，模型与参数由用户自配（例如 DeepSeek 端点 `https://api.deepseek.com/v1/chat/completions`）。

- 快捷策略模板：严格只读 / 智能助手 / 极客模式
- AI 访问范围三档：禁止访问 / 允许访问 `/data/adb/` / 允许访问根目录 `/`
- 危险动作二次确认：shell、删除、模块安装与卸载弹红色确认窗
- 计划模式：先给出计划，可「修改计划 / 一键执行全部 / 中止」
- `[AI_AGENT]` 客户端日志面板 + 操作历史（只记录 AI 的动作与撤销；用户在设置页改访问范围不计入）

用户手册：[docs/ai-console-quickstart.md](docs/ai-console-quickstart.md)｜设计规格：[design/ai-console-spec.md](design/ai-console-spec.md)

## 与上游的差异

1. **身份收口**：`manager/gradle.properties` 定义 `KSU_PACKAGE_NAME=me.weishu.kernelsu.hide`、`KSU_VERSION_CODE=32601`、`KSU_VERSION_NAME=v3.3.0+main`；`manager/app/build.gradle.kts` 的版本号优先读 Gradle property，再回退 git 推导。
2. **Rust 依赖源**：`Kernel-SU/*` 改为 `KernelSU2/*`（`adb_client`、`java-properties`、`ksu_props`、`rustix`），`Cargo.toml` 与 `Cargo.lock` 同步。
3. **预编译 `libksud.so` 随仓库分发**：`manager/app/src/main/jniLibs/{arm64-v8a,x86_64}/libksud.so`（管理器构建要求 `jniLibs/arm64-v8a/libksud.so` 存在）；上游忽略这两个文件的规则已移除，没有 Rust 工具链也能出包。守护进程只发到 jniLibs 一处——`userspace/ksud/bin/x86_64` 是 x86_64 守护进程的 rust-embed 资产目录（`userspace/ksud/src/assets.rs`），副本放在那里会被下一次 x86_64 构建自己嵌进二进制。补丁后的内核模块与 `ksuinit` 同样随仓库分发：`userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko`、`userspace/ksud/bin/aarch64/ksuinit`（上游在该目录用 `**/*.ko`、`**/ksuinit` 忽略 CI 产物，本分支为这两个文件加了放行规则）。
4. **内置隐藏资源包**（约 130 MB，含 9 个第三方检测器 APK），风险见「合规与风险」。
5. **上游 CI 与 Dependabot 已归档**：`.github/workflows` → `.github/workflows.upstream`、`.github/dependabot.yml` → `.github/dependabot.yml.upstream`（GitHub 只识别原路径，恢复方法与说明见该目录 README）。
6. **新增内容**：`docs/`（开发文档与手册）、`design/`（设计规格）、`_tools/`（构建、校验与资源包同步脚本）。

## 目录导览

```text
KernelSU-AI/
├── manager/          Android 管理器（Kotlin + Compose）；AI 控制台见 ui/screen/ai*、环境隐藏见 ui/screen/hiding/
├── kernel/           内核模块（C）
├── userspace/        ksud / ksuinit（Rust），预编译产物在 ksud/bin/
├── js/ website/ fastlane/ scripts/ uapi/
├── docs/             开发文档、开发日志、AI 控制台手册
├── design/           设计规格与图标
├── _tools/           构建 / 校验 / 资源包同步脚本（维护者工具）
└── .github/workflows.upstream/  已归档的上游 CI
```

## 文档

| 文档 | 用途 |
| --- | --- |
| [docs/开发文档.md](docs/开发文档.md) | 结构化开发文档：目录与架构、构建与验证配方、契约与不变量、已知陷阱、剩余缺口 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 开发记录：按主题记录本分支的实现过程与决策 |
| [docs/ai-console-quickstart.md](docs/ai-console-quickstart.md) | AI 控制台使用指南：配置、访问范围、确认门、计划模式、日志与历史 |
| [design/](design/) | 规格：`ai-console-spec.md`、`environment-hide-spec.md`、`home-ui-review.md` |
| [_tools/README.md](_tools/README.md) | 各脚本用途、前提与红线 |

## 构建与复现

构建脚本假定「工作区根目录」下同时存在本仓库与工具链目录 `.toolchain/`（JDK 21、Gradle 9.7.1、Android SDK）；脚本默认按此布局定位，也可以用 `-Root` 指定别处。

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force

# 1) 内核侧守护进程（产出 libksud.so；无 Rust 工具链时可跳过，仓库已内置预编译产物）
& .\_tools\build-ksud.ps1 -PackageName 'me.weishu.kernelsu.hide'

# 2) 管理器 APK
& .\_tools\build-manager.ps1 -Task ':app:assembleRelease' -NoDaemon

# 3) 重新生成内置资源包（需要一份隐藏环境资源包目录）
& .\_tools\sync-hiding-pack.ps1 -SourcePack <资源包目录> -IncludeDetectors
```

注意事项：

- 本项目的维护环境无法用 `./gradlew.bat` 完成构建（wrapper 下载链的 TLS 校验不通过），因此构建统一走 `_tools/build-manager.ps1`；若你的环境可正常使用 gradlew，直接用也可以。
- `build-ksud.ps1` 的 `-PackageName` 必须等于 `manager/gradle.properties` 里的 `KSU_PACKAGE_NAME`：守护进程在编译期把包名烤进二进制，不一致会导致加冕失败。
- `sync-hiding-pack.ps1` 的 `-IncludeDetectors` 默认关闭（检测器含闭源商业应用）、`-IncludeKeybox` 永不启用（含明文 EC 私钥）、`-IncludeData` 默认 false（契约）。

## 验证状态

- **AI 控制台**：主页入口、模型与参数配置、访问范围、确认弹窗、附件与图片理解、计划模式已在 Android 16 真机上走通；其中图片理解用一张内容已知的测试图做了端到端核对，回复与图上文字、形状、配色一致。仓库不保留设备级截图与哈希留档。
- **构建与身份**：`_tools/build-manager.ps1` 出包与管理器身份核对（包名 / 版本 / 证书）在本分支上执行过。
- **环境隐藏**：安装链路与 HMA-OSS 配置同步已在真机执行过（含一次重启后复核）。
- **对抗性验收（真机实测）**：9 个内置检测器已在 OPPO PMA110（Android 16 / sdk 36）上逐个跑完，环境为 LKM 模式 + Hybrid Mount / Zygisk Next / LSPosed / AlwaysStrong / HMA-OSS / PathMask。核心隐藏效果成立：没有 Su、Magisk、模块文件与 Syscall/Libc 类命中，密钥认证显示**引导加载程序已锁定**、可信执行环境正常。同时暴露出仍可被观测的面：Hunter 判定自身进程「已经被 Hook&修改」，春秋判定 `System compromised`，ruru 把 PM 常规Api查询、无障碍服务、设置属性标为「可疑」，momo 只报「已开启调试模式」，应用列表检测器头部标记 abnormal environment。逐条记录见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) 第 9 节。
- **「重启」按钮**：已在真机复现并定位，点击后 `/proc/uptime` 归零，确为真实重启；结论与可复现的「无响应」路径见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) 第 9 节。
- **静态检查**：ksud 的 clippy 闸门通过；对上游遗留的两条 pedantic 告警在 crate 级 `allow` 中注明来源后放行，收口说明见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) 第 3.5 节。
- **未验证即不写入**：本文件与 `docs/` 中无法在仓库内核对的内容会标注「待验证」，或从文档中删除。

## 已知缺口

- `userspace/ksud/bin/x86_64/` 缺少 `_kernelsu.ko`：上游由 `ddk-lkm.yml` 在 Android DDK 容器里生成，本机没有该容器，因此 x86_64 设备上的 LKM 模式无法从本仓库的资产跑起来（x86_64 的 `ksuinit` 已随仓库分发）。

逐项说明见 [docs/开发文档.md](docs/开发文档.md)。

## 合规与风险

- **许可**：GPL-3.0（继承上游，保留 LICENSE 与版权声明）；分发本分支的二进制时需同时提供对应源码。
- **第三方资源**：`manager/app/src/main/assets/hiding/` 内的模块与检测器 APK 来自第三方（含闭源商业应用，例如 MT 管理器），仅用于环境隐藏功能的兼容性测试与一键安装；版权归各自作者，不适用本项目的 GPL 许可。请自行评估使用与再分发风险。
- **本仓库不包含**：`keybox.xml`（含明文 EC 私钥）、`target.txt`、`assets/hiding/data`。
- Root / 隐藏类工具可能影响设备保修或第三方应用服务条款，请在自有设备与合法用途内使用。

## 上游与致谢

- KernelSU：[tiann/KernelSU](https://github.com/tiann/KernelSU)
- 本分支基于 KernelSU v3.3.0。