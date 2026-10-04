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
| `verify-identity.ps1` | 校验包名 / 版本 / 证书指纹 / 预编译 `libksud.so` 内嵌包名 / 资源包一致性；`-SelfTest` 会跑正反例自检 |
| `measure-ui-dump.ps1` | 把 uiautomator dump 的 XML 换算成 px/dp 尺寸表 |
| `diff-tree.ps1` | 对比两棵目录树的差异 |
| `hiding_manifest.schema.json` | 资源包 manifest 的契约（结构与管理器侧消费语义） |

## 红线

- **不要手改** `manager/app/src/main/assets/hiding/manifest.json`，一律用 `sync-hiding-pack.ps1` 重新生成。
- **不要改** `hiding_manifest.schema.json`，也不要新增字段或类别（类别集合是封闭的：`module` / `pathmask` / `detector` / `data`）。
- **不要**给 manifest 增加 `keybox` / `data` 条目（`includes.keybox` 与 `includes.data` 恒为 false）。