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
| `verify-identity.ps1` | 校验四件事：`manager/gradle.properties` 的包名、预编译 `libksud.so` 内嵌包名、APK 的 applicationId 与签名证书、内置 LKM 资产的证书哈希与 DER 长度；第 4 项还报出资产产地（路径 A ＝ 编译进了 `KSU_MANAGER_PACKAGE`，路径 B ＝ 只换了证书常量），`-RequireSourceBuild` 把路径 B 直接判失败；`-SelfTest` 会跑正反例自检 |
| `measure-ui-dump.ps1` | 把 uiautomator dump 的 XML 换算成 px/dp 尺寸表 |
| `diff-tree.ps1` | 对比两棵目录树的差异 |
| `patch-init-boot.py` | 对引导镜像或裸 ko 做常量替换 / 资产替换（`--base/--out` 或 `--ko-in/--ko-out`），可顺带 `--manager-appid` 预置冠位；`--replace-ko` 允许 ko 大小变化，此时会重排整个 newc 归档（成员大小一变，后面每个 entry 都会移位，原地覆盖会破坏归档）；写出后自查 cpio 往返与成员顺序，`--dry-run` 只报告不改动 |
| `verify-init-boot.py` | 独立复核引导镜像（不共用 producer 的解析代码：自带 boot 头解析、LZ4-legacy 解码、newc 遍历、ELF `.symtab` 查找）：ko 是否 ELF64 aarch64、项目证书哈希是否恰好一处、官方与 debug 哈希是否都已消失、`cmp w21,#imm` 是否等于项目证书 DER 长度、产地是路径 A 还是 B、`--expect-appid` 冠位预置；`--require-source-build` 时路径 B 直接失败，`--dump-ko` 导出内置 ko |
| `adopt-lkm-asset.ps1` | 把 CI 从源码构建出来的 ko 收编为内置资产：先证明它是项目身份（证书哈希 / DER 长度 / 无官方与 debug 哈希），再报出它是否带 `KSU_MANAGER_PACKAGE` 包名校验（路径 A 有、路径 B 没有）；`-DryRun` 只校验，`-AllowPatchedAsset` 才接受路径 B |
| `project-signer.json` | 项目签名身份的公开事实（证书 SHA-256、DER 长度等）；私钥与口令在 `.artifacts/builder/keystore/keystore.properties`，不入库 |
| `hiding_manifest.schema.json` | 资源包 manifest 的契约（结构与管理器侧消费语义） |

## 红线

- **不要手改** `manager/app/src/main/assets/hiding/manifest.json`，一律用 `sync-hiding-pack.ps1` 重新生成。
- **不要改** `hiding_manifest.schema.json`，也不要新增字段或类别（类别集合是封闭的：`module` / `pathmask` / `detector` / `data`）。
- **不要**给 manifest 增加 `keybox` / `data` 条目（`includes.keybox` 与 `includes.data` 恒为 false）。