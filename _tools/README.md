# _tools · 维护脚本说明

本目录是开发机上的**构建 / 校验 / 资源包同步**工具，被 [docs/开发文档.md](../docs/开发文档.md) §3 的命令卡直接引用。它们**不是**可移植的构建系统，而是本分支开发环境的复现配方。

## 前提

- Windows + PowerShell 7（pwsh）
- 工作区布局：仓库位于 `<root>/KernelSU-v330/`，工具链位于 `<root>/.toolchain/`（`jdk-21`、`gradle-9.7.1`、`android-sdk`、`gradle-home`、`tmp`）
- 脚本内的根路径按契约硬编码为 `D:/ai/KernelSU`。[docs/开发文档.md](../docs/开发文档.md) §3.1/§3.2 逐行记录的就是这些取值，因此入库时**保持原样、未做参数化**；换机器请先自行改路径。

## 脚本清单

| 脚本 | 用途 |
| --- | --- |
| `build-manager.ps1` | 设置 JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME / TEMP 等后调用 Gradle；用法 `-Task ':app:assembleRelease' -NoDaemon`，结束打印 `EXITCODE=` |
| `build-ksud.ps1` | 用 cargo-ndk 构建 ksud / ksuinit；`-PackageName` 必须等于 `manager/gradle.properties` 的 `KSU_PACKAGE_NAME`；`-Platform` 需 API 26+ |
| `sync-hiding-pack.ps1` | 把隐藏资源包同步进 `assets/hiding` 并**重新生成** `manifest.json`；`-IncludeDetectors` 默认关闭、`-IncludeKeybox` 永不、`-IncludeData` 默认 false |
| `verify-identity.ps1` | 校验包名 / 版本 / AIDL / 资源包一致性（`-Root` 默认 `D:/ai/KernelSU`） |
| `measure-ui-dump.ps1` | 从 uiautomator dump 统计界面尺寸 |
| `diff-tree.ps1` | 对比两棵交付树的差异 |
| `bootstrap.ps1` / `bootstrap2.ps1` / `bootstrap3.ps1` | 历史一次性环境脚手架（存档用，勿在已配置环境重复执行） |
| `hiding_manifest.schema.json` | 资源包 manifest 的**冻结契约** |

## 红线

- **不要手改** `manager/app/src/main/assets/hiding/manifest.json`，一律用 `sync-hiding-pack.ps1` 重新生成。
- **不要改** `hiding_manifest.schema.json`，也不要新增字段或类别（类别集合是封闭的）。
- **不要**给 manifest 增加 `keybox` / `data` 条目（`includes.keybox` 与 `includes.data` 恒为 false）。
