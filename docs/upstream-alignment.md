# 与上游（tiann/KernelSU）的一致性审计与构建条件

> 2026-10-05。用户要求：除了我们制作的内容，其余尽可能与原版一致；没有条件就创造条件。
> 对照对象：`_src/v3.3.0/KernelSU-3.3.0`（本分支的导入基线，commit `3e09466`）与 `_src/mainfresh/KernelSU-main`（上游 main 快照）。

## 1. 结论

- `kernel/`：除 `kernel/manager/apk_sign.c` 外与上游 v3.3.0 逐字节一致（83/83 文件，仅 1 个不同）。
- `userspace/`：5 个文件有差异（`ksud/Cargo.toml`、`ksuinit/Cargo.toml`、`ksud/build.rs`、`ksud/src/main.rs`、`ksud/bin/.gitignore`），另有 3 个我方预编译资产。
- `website/`：与上游完全一致（156/156）。
- `.github/`：已由「归档为 `*.upstream`」恢复为上游原样，12 个工作流 + `dependabot.yml` 与上游 v3.3.0 逐字节一致（SHA-256 逐个核对）；只多一个我方触发器 `build-lkm-fork.yml`。
- `manager/`：116 个新增 + 31 个差异，全部是我们自己的内容（AI 控制台、环境隐藏资源包、身份工具），无上游文件被误改。
- 仓库根：`README.md`（我方）与 `Cargo.lock`（依赖组织改名导致）与上游不同，其余一致。

## 2. 有意保留的分叉（属于「我们制作的内容」）

| 位置 | 内容 | 依据 |
| --- | --- | --- |
| `manager/**`（116 新增 + 31 差异） | AI 控制台、环境隐藏、模块制作、AI 审计，以及包名/版本等身份接线 | 本分支的产品内容 |
| `kernel/manager/apk_sign.c` | 换成上游 main 更严的 `check_v2_signature`（未知签块直接判失败） | 用户 2026-10-05 指令「把原版那套更严的合并回来」 |
| `userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko`、`aarch64/ksuinit`、`x86_64/ksuinit` | 随仓库分发的预编译资产（上游用 `.gitignore` 忽略 CI 产物） | 让没有工具链的源码快照也能直接出包 |
| `userspace/ksud/bin/.gitignore` | 放开上述三个资产的忽略规则 | 同上 |
| `userspace/ksud/build.rs` | `cargo:rerun-if-env-changed=KSU_PACKAGE_NAME` + 未设置时 `cargo:warning` | 保证 daemon 包名不会静默回落 |
| `userspace/ksud/src/main.rs` | 两个 crate 级 clippy allow（`redundant_field_names`、`assert_is_empty`），注释声明只覆盖上游文件 | 本机较新 clippy 对上游代码的既有告警 |
| `_tools/**`、`docs/**`、`design/**` | 构建/验收工具与文档 | 本分支的工程支撑 |
| `README.md` | 本分支说明 | 本分支的对外说明 |

## 3. 与上游 main 的对齐（不是分叉）

- `userspace/ksud/Cargo.toml`、`userspace/ksuinit/Cargo.toml` 的 git 依赖组织 `Kernel-SU` → `KernelSU2`：上游 main 已经在用 `KernelSU2`，我们只是提前对齐；`prop-rs` 我们钉在 `6f5723105d8d4cacad31d83d343defbf032c7b33`（v3.3.0 的版本），main 是 `ddb6ee7294467f7f25bad2118e9e24eee104144b`。
- `kernel/manager/apk_sign.c`：与上游 main 只差 6 行（4 增 2 删）——main 用 `ksu_filp_open_nonotify(path, O_RDONLY | O_NOATIME)`，本分支用 `filp_open(path, O_RDONLY, 0)` 加 `fp->f_mode |= FMODE_NONOTIFY`。原因：`ksu_filp_open_nonotify` 只存在于 main（`kernel/include/util.h:63` 的 static inline，被 `kernel/manager/apk_sign.c:158`、`kernel/manager/throne_tracker.c:195` 使用），在 v3.3.0 树里 0 命中；直接移植要把 main 的 `util.h`（ksyscall/arch.h 等一大组头）一并搬过来，不属于「尽量一致」的范畴。

## 4. 包名匹配的一个细节（为什么可以默认开启 `KSU_MANAGER_PACKAGE`）

`kernel/manager/apk_sign.c` 的 `get_pkg_from_apk_path()`（:302）在 `/data/app/~~<b64>==/<pkg>-<b64>==/base.apk` 上取**第一个**连字符之前的部分作为包名（:326 `strchr(second_last_slash, '-')`），因此 APK 目录后缀里带连字符（实测 `me.weishu.kernelsu.hide-VNZu0x-zfCWWNVYgj3Dvhw==`）不会影响 `strncmp(pkg, KSU_MANAGER_PACKAGE, …)`（:351）——包名本身不含连字符。

## 5. 创建缺失的条件：从源码构建 ko

本机没有可用的 Linux/DDK 条件（无管理员权限装 WSL、无 Docker、无 `make`/`bash`，只有 Windows 侧 clang 与 NDK），因此「从源码构建 ko」这条上游路径只能借 CI：

1. `.github/workflows/` 已恢复为上游原样，因此上游 `Build LKM for KernelSU`（`build-lkm.yml` → `ddk-lkm.yml`，DDK 容器 `ghcr.io/ylarod/ddk-min:<kmi>-<ddk_release>`）可直接手动触发出全矩阵产物。
2. `.github/workflows/build-lkm-fork.yml` 只构建 `android15-6.6` 一个 KMI，并把产物提交到 `ci/lkm` 分支，便于没有 Actions 权限的客户端取回。
3. 身份不需要任何参数：`kernel/Kbuild` 的默认值就是项目身份（见下节），上游工作流里的 `make` 直接产出正确身份。

## 6. `kernel/Kbuild` 的身份默认值（本次新增）

```make
ifndef KSU_EXPECTED_SIZE
KSU_EXPECTED_SIZE := 0x342
endif
ifndef KSU_EXPECTED_HASH
KSU_EXPECTED_HASH := ca40afa835e460be5dcfd0743296d95c3275b9e51673de8b6fd0a64665a83eec
endif
ifndef KSU_MANAGER_PACKAGE
KSU_MANAGER_PACKAGE := me.weishu.kernelsu.hide
endif
```

这与上游给自己写死官方身份（`0x033b` / `c371061b…`）的做法一致，只是值换成本项目的：上游 CI 因此无需任何改动即可构建本分支的 ko。用 `make KSU_EXPECTED_SIZE=… KSU_EXPECTED_HASH=… KSU_MANAGER_PACKAGE=…` 仍可覆盖。

## 7. 尚未完成的收口

- 仓库里当前分发的 `userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko`（sha256 `d537e0d76969a7dab712926b292dc66e23f6ba915067e615486d829a738c9178`）仍是「上游 v3.3.0 产物 + 两处常量等长替换」的回退交付：证书哈希与 DER 长度正确，但 `KSU_MANAGER_PACKAGE` 的包名校验没编进二进制。
- 用第 5 节的工作流构建出真实产物后，应替换该资产、重建 `libksud.so` 与管理器 APK，并把 `me.weishu.kernelsu.hide` 字符串纳入 `_tools/verify-identity.ps1` 第 4 节的资产闸门（两条路径共有特征之外的这项只有源码构建才有）。
