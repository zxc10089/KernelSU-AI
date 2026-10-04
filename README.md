# KernelSU-AI · 一键环境隐藏 + 内置 AI 控制台

> 基于 **KernelSU v3.3.0** 的定制分支：把「隐藏环境」做成底部导航里的**一键链路**，并在管理器内**内置 AI 控制台**（自有 API Key、访问范围三档、计划模式、审计与撤销）。资源包与预编译 `ksud` 已随仓库分发，开箱即用。

| 项 | 值 |
| --- | --- |
| 上游基线 | KernelSU v3.3.0（本地基线提交 `3e09466`） |
| 管理器包名 | `me.weishu.kernelsu.hide` |
| 版本 | versionCode `32601` · versionName `v3.3.0+main` |
| 系统要求 | Android 12+（minSdk 31 / targetSdk 37） |
| 许可 | GPL-3.0（继承上游，见 LICENSE） |
| 上游仓库 | https://github.com/tiann/KernelSU |

## 快速开始

1. 安装发行包：使用 Releases 中的 APK，或按「构建与复现」自行出包。
2. 环境隐藏：打开 App → 底部第三项**环境隐藏** → 一键安装（Hybrid Mount → ZygiskSU → LSPosed → AlwaysStrong → HMA-OSS → PathMask）→ 按提示**重启生效**。
3. AI 控制台：主页卡片**接入 AI 助手** → 模型与参数配置（提供商 / 端点 / 密钥 / 模型名）→ 选择访问范围 → 开始对话或使用计划模式。

## 核心特性

### 1. 一键环境隐藏

底部导航第三项，串行执行（root shell 严格串行，绝不并发），顺序固定：

```text
Hybrid Mount(metamodule, 必须最先) -> ZygiskSU -> LSPosed -> AlwaysStrong(tricky_store)
  -> HMA-OSS Zygisk -> PathMask(按当前内核分支精确命中)
可选：LSPosed / 救砖模块
最后一行固定输出：需重启后生效
```

- 资源包内置在 APK 资产里，首次进入无需联网：`manager/app/src/main/assets/hiding/`（`manifest.json` + `hma_config.json` + modules 7 + pathmask 6 + detectors 9）。
- 补丁器负责 HMA 配置同步；检测器只出现在「快捷安装」卡片。
- 设计规格：[design/environment-hide-spec.md](design/environment-hide-spec.md)

### 2. 内置 AI 控制台

主页卡片**接入 AI 助手**，模型与参数自配（实测：DeepSeek 端点 `https://api.deepseek.com/v1/chat/completions`，模型 `deepseek-flash`）。

- 快捷策略模板：严格只读 / 智能助手 / 极客模式
- AI 访问范围三档：禁止访问 / 仅允许 `/data/adb/` / 允许根目录 `/`
- 危险动作二次确认：shell、删除、模块安装与卸载弹**红色确认窗**
- 计划模式：先给计划，可「修改计划 / 一键执行全部 / 中止」
- `[AI_AGENT]` 客户端日志面板 + AI 操作历史（含审计记录与撤销）

用户手册：[docs/ai-console-quickstart.md](docs/ai-console-quickstart.md) ｜ 设计规格：[design/ai-console-spec.md](design/ai-console-spec.md)

## 与上游的差异

1. **包名与版本收口**：`KSU_PACKAGE_NAME=me.weishu.kernelsu.hide`；`versionCode/versionName` 优先取 Gradle property（`KSU_VERSION_CODE` / `KSU_VERSION_NAME`），不再只靠 git 推导。
2. **Rust 依赖源**：`Kernel-SU/*` 改为 `KernelSU2/*`（`adb_client`、`java-properties`、`ksu_props`、`rustix`），原组织端点返回 401/404。
3. **预编译 libksud.so 随仓库分发**：`manager/app/src/main/jniLibs/{arm64-v8a,x86_64}/libksud.so` 与 `userspace/ksud/bin/{arm64-v8a,x86_64}/libksud.so`。上游 `.gitignore` 原本忽略 manager 侧这两个文件，本分支已移除该忽略，便于没有 Rust 工具链的人直接出包。
4. **内置隐藏资源包**（约 130 MB）随仓库分发，含 9 个第三方检测器 APK —— 风险见「合规与风险」。
5. **上游 CI 已归档停用**：`.github/workflows` → `.github/workflows.upstream`（原因与恢复方法见该目录 README）。
6. **新增内容**：`docs/`（三份开发文档）、`design/`（设计规格）、`_tools/`（构建与同步脚本）、`_artifacts/builder/`（交付证据报告）。

## 目录导览

```text
KernelSU-AI/
├── manager/          Android 管理器（Compose / Kotlin）——AI 控制台与环境隐藏在 ui/screen/{ai*,hiding}/
├── kernel/           内核模块（C）
├── userspace/        ksud / ksuinit（Rust）
├── webui/ js/ website/ fastlane/ scripts/ uapi/
├── docs/             开发文档、开发日志、用户手册（见下）
├── design/           设计规格与图标
├── _tools/           构建 / 校验 / 资源包同步脚本（维护机配方）
├── _artifacts/builder/  交付证据报告（Markdown）
└── .github/workflows.upstream/  已归档的上游 CI
```

## 文档

| 文档 | 用途 |
| --- | --- |
| [docs/开发文档.md](docs/开发文档.md) | **结构化开发文档**：文件地图、构建与验证配方、红线与不变量、已知陷阱、剩余缺口、证据索引 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | **权威开发日志**：逐轮状态块与 AI 阶段 0–6.5 全过程 |
| [docs/ai-console-quickstart.md](docs/ai-console-quickstart.md) | AI 控制台普通用户上手（OPPO PMA110 / Android 15 真机流程） |
| [design/](design/) | 规格：`ai-console-spec.md`、`environment-hide-spec.md`、`home-ui-review.md` |
| [_tools/README.md](_tools/README.md) | 各脚本用途、前提与红线 |
| [_artifacts/builder/](_artifacts/builder/) | 体积报告、签名修复验证、R1–R8 验证、HMA 配置同步、R8 重启按钮等证据 |

## 构建与复现

工具链放在工作区（仓库之外的）`.toolchain/`：JDK 21、Gradle 9.7.1、Android SDK、Rust + NDK 目标。完整命令卡见 [docs/开发文档.md](docs/开发文档.md) §3。

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force

# 1) 内核侧守护进程（先出 libksud.so）
& D:/ai/KernelSU/_tools/build-ksud.ps1 -PackageName 'me.weishu.kernelsu.hide'

# 2) 管理器 APK
& D:/ai/KernelSU/_tools/build-manager.ps1 -Task ':app:assembleRelease' -NoDaemon

# 3) 重新生成内置资源包（可选）
& D:/ai/KernelSU/_tools/sync-hiding-pack.ps1 -IncludeDetectors
```

注意事项：

- 直接跑 `./gradlew.bat` 在本机**不可用**（PKIX 证书链构建失败 + 沙箱锁），必须走 `_tools/build-manager.ps1`。
- 管理器构建**要求** `manager/app/src/main/jniLibs/arm64-v8a/libksud.so` 存在（本仓库已内置）。
- `build-ksud.ps1` 的 `-PackageName` 必须等于 `manager/gradle.properties` 里的 `KSU_PACKAGE_NAME`（ksud 编译期会烤入包名）。
- 资源包同步：`-IncludeDetectors` 默认关闭、`-IncludeKeybox` 永不启用、`-IncludeData` 默认 false（契约）。

## 真机证据

- **AI 控制台**：已在 OPPO PMA110 / Android 15 / `v3.3.0+main (32601-2)` 真机验证（详见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) 相关阶段块）。
- **环境隐藏**：R1–R8 的代码级验证与资源包一致性记录见 [_artifacts/builder/evidence/](_artifacts/builder/evidence/)。
- 参考发行包（**未入库**，属于构建产物）：`manager/app/build/outputs/apk/release/KernelSU_v3.3.0+main_32601-release.apk`，120,102,296 字节，sha256 `d9736c40ffaefe46095b3916ed1a92b4abaaac1ff9e8bc1ed94224bf1503cd21`。

## 已知缺口（照实记录）

- 环境隐藏的 **B1–B13 真机验收项尚未执行**（编写时无可用设备，`adb devices -l` 为空）。
- R8 **重启按钮**：3 次点击中有 2 次无响应，未定位（记录见 `_artifacts/builder/evidence/R8-reboot-button.md`）。
- clippy 仍有 **41 条**继承自上游的告警。
- `keybox.xml`、`target.txt`、`assets/hiding/data` 按契约**不随仓库分发**。

逐项说明见 [docs/开发文档.md](docs/开发文档.md) §10。

## 合规与风险

- **许可**：GPL-3.0（继承上游，保留 LICENSE 与版权声明）。分发本分支的二进制时需同时提供对应源码。
- **第三方资源**：`manager/app/src/main/assets/hiding/` 内的模块与检测器 APK 来自第三方（其中含**闭源商业应用**，例如 MT 管理器），仅用于环境隐藏功能的兼容性测试与一键安装；版权归各自作者，**不随本项目的 GPL 许可授权**。请自行评估使用与再分发风险。
- **本仓库不包含**：`keybox.xml`（含明文 EC 私钥）、`target.txt`、`assets/hiding/data`。
- Root / 隐藏类工具可能违反设备保修条款或第三方应用服务条款，请在自有设备与合法用途内使用。

## 关于文档中的路径引用

[docs/开发文档.md](docs/开发文档.md) 附录引用了部分 `_artifacts/` 证据（截图 PNG、dumpsys XML、日志 TXT、构建 APK），合计约 158 MB，**未随仓库分发**；仓库内仅保留了其中的 Markdown 报告。

## 上游与致谢

- KernelSU：https://github.com/tiann/KernelSU
- 本分支基线：KernelSU v3.3.0（本地基线提交 `3e09466`）
