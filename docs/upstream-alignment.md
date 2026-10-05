# 与上游（tiann/KernelSU）的一致性审计与构建条件

> 2026-10-05。用户要求：除了我们制作的内容，其余尽可能与原版一致；没有条件就创造条件。
> 对照对象：`_src/v3.3.0/KernelSU-3.3.0`（本分支的导入基线，commit `3e09466`）与 `_src/mainfresh/KernelSU-main`（上游 main 快照）。

## 1. 结论

- `kernel/`：除 `kernel/manager/apk_sign.c` 外与上游 v3.3.0 逐字节一致（83/83 文件，仅 1 个不同）。
- `userspace/`：5 个文件有差异（`ksud/Cargo.toml`、`ksuinit/Cargo.toml`、`ksud/build.rs`、`ksud/src/main.rs`、`ksud/bin/.gitignore`），另有 19 个我方预编译资产（8 个 KMI × 2 个 ABI 的 ko 加 2 个 ksuinit）。
- `website/`：与上游完全一致（156/156）。
- `.github/`：已由「归档为 `*.upstream`」恢复为上游原样，12 个工作流 + `dependabot.yml` 与上游 v3.3.0 逐字节一致（SHA-256 逐个核对）；只多一个我方触发器 `build-lkm-fork.yml`。
- `manager/`：116 个新增 + 31 个差异，全部是我们自己的内容（AI 控制台、环境隐藏资源包、身份工具），无上游文件被误改。
- 仓库根：`README.md`（我方）与 `Cargo.lock`（依赖组织改名导致）与上游不同，其余一致。

## 2. 有意保留的分叉（属于「我们制作的内容」）

| 位置 | 内容 | 依据 |
| --- | --- | --- |
| `manager/**`（116 新增 + 31 差异） | AI 控制台、环境隐藏、模块制作、AI 审计，以及包名/版本等身份接线 | 本分支的产品内容 |
| `kernel/manager/apk_sign.c` | 换成上游 main 更严的 `check_v2_signature`（未知签块直接判失败） | 用户 2026-10-05 指令「把原版那套更严的合并回来」 |
| `userspace/ksud/bin/{aarch64,x86_64}/{android12-5.10,android13-5.10,android13-5.15,android14-5.15,android14-6.1,android15-6.6,android16-6.12,android17-6.18}_kernelsu.ko`、两个 `ksuinit` | 上游 8 个 KMI × 2 个 ABI 的全部 ko 随仓库分发（上游用 `.gitignore` 忽略 CI 产物） | 让没有工具链的源码快照也能直接出包；矩阵来源 run `37312303026`，逐项哈希见第 7 节 |
| `userspace/ksud/bin/.gitignore` | 放开上述全部资产的忽略规则（`**/*.ko`、`**/ksuinit` 仍忽略，再逐个放行矩阵里的 16 个 ko 与 2 个 ksuinit） | 同上 |
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
2. `.github/workflows/build-lkm-fork.yml` 调用上游 `build-lkm.yml` 跑完整 8-KMI 矩阵（每个 KMI 各出 aarch64 与 x86_64 两个产物），并把整批提交到 `ci/lkm` 的 `ci-artifacts/<abi>/<kmi>_kernelsu.ko`，便于没有 Actions 权限的客户端取回。
3. 身份不需要任何参数：`kernel/Kbuild` 的默认值就是项目身份（见下节），上游工作流里的 `make` 直接产出正确身份。

取回与收编（本机没有可写 GitHub 凭证时，前两步需要在能推送的环境执行）：

```bash
# 触发：推送 master（含 kernel/** 或该工作流自身变更）会自动跑 build-lkm-fork.yml；
# 或在 Actions -> Build fork LKM (upstream KMI matrix) -> Run workflow 手动触发
git push origin master

# 产物：Actions 页的 8 个 aarch64-<kmi>-lkm 与 8 个 x86_64-<kmi>-lkm；
# publish 任务把整批提交到 ci/lkm，路径为 ci-artifacts/<abi>/<kmi>_kernelsu.ko
git fetch origin ci/lkm
git show ci/lkm:ci-artifacts/aarch64/android15-6.6_kernelsu.ko > /tmp/built.ko
```

```powershell
# 收编：-Abi 与 -Kmi 决定落点 userspace\ksud\bin\<abi>\<kmi>_kernelsu.ko，矩阵逐个收编
foreach ($abi in @('aarch64','x86_64')) {
  foreach ($kmi in @('android12-5.10','android13-5.10','android13-5.15','android14-5.15',
                     'android14-6.1','android15-6.6','android16-6.12','android17-6.18')) {
    powershell -NoProfile -ExecutionPolicy Bypass -File _tools\adopt-lkm-asset.ps1 `
      -Ko (Join-Path $built $abi)\$kmi`_kernelsu.ko -Abi $abi -Kmi $kmi -DryRun
  }
}
# 之后重建 libksud.so（先 cargo clean -p ksud --release，再跑 _tools\build-ksud.ps1）
# 与管理器 APK，并重新打引导镜像
```

`adopt-lkm-asset.ps1` 会报出候选模块是路径 A（含编译进去的包名校验）还是路径 B（只有证书匹配），因此「资产是否与源码一致」从一句描述变成了可执行的判据。

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

同一文件里驱动版本也收口到 `manager/gradle.properties`（2026-10-05 新增，见 `docs/DEVELOPMENT.md` 3.2）：

```make
ifndef KSU_VERSION_CODE
KSU_VERSION_PROPS := $(wildcard $(MDIR)../manager/gradle.properties)
KSU_VERSION_CODE := $(shell sed -n 's/^KSU_VERSION_CODE=\([0-9][0-9]*\).*/\1/p' "$(KSU_VERSION_PROPS)" 2>/dev/null)
KSU_VERSION_CODE := $(patsubst KSU_VERSION_CODE=%,%,$(filter KSU_VERSION_CODE=%,$(file <$(KSU_VERSION_PROPS))))
endif
```

第二行是 `sed` 不可用时的回退（GNU make 自带 `$(file <…)`），因此上游 CI 里「shallow checkout + 写死 safe.directory」导致的 git 判定失败不再影响版本：产物里的 `-DKSU_VERSION` 与 APK/管理器版本恒等。

## 7. 收口结果（2026-10-05 已完成）

- 源码产物已上线：DDK CI（`build-lkm-fork.yml`，run `37305609190`，KMI `android15-6.6`）产出的 ko 经 `_tools/adopt-lkm-asset.ps1 -Verify` 收编，`userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko` = **315,280 字节 / sha256 `00bcca544e8115c9fea3e70d39ed29316e93b887468970a04cb7af2918cc3040`**，含 `me.weishu.kernelsu.hide`（路径 A）。回退产物归档在 `_artifacts/builder/evidence/android15-6.6_kernelsu-pathB-bytepatch.ko`。
- `libksud.so`（arm64-v8a / x86_64）与 release APK 已按新资产重建；`_tools/verify-identity.ps1 -RequireSourceBuild` 把「资产必须源自源码构建」变成可执行闸门（路径 B 直接 FAIL）。`_tools/verify-init-boot.py` 为引导镜像提供独立复核（自带解析器，不共用 producer 代码）。
- **全矩阵已上线（2026-10-05）**：`build-lkm.yml` 的 8-KMI 矩阵由 `build-lkm-fork.yml` 触发（run `37312303026`，master `85d4955`，8 个矩阵任务 + publish 全绿），16 个产物逐个经 `_tools/adopt-lkm-asset.ps1 -Abi <abi> -Kmi <kmi>` 校验身份后收编进 `userspace/ksud/bin/`，`docs/DEVELOPMENT.md` 第 447 行记录的「单一 KMI」脆弱性随之关闭（slot/keystore/v2-only 三条仍成立）。
- **身份闸门修掉两个真坑**：DER 长度的比较指令寄存器是编译器选择——aarch64 多数 KMI 是 `cmp w21,#834`，android12-5.10 编成 `cmp w8,#834`；x86_64 的立即数位置随寻址方式变（`cmpl $834, %r12d` 与 `cmpl $834, 8(%rsp)`）。旧工具按字节硬匹配 `bfee0c71`/`0xA864`，会把 android12-5.10 误判失败、也可能误判通过。现在 `_tools/identity-lib.ps1` 以 `(w & 0x7F80001F) == 0x7100001F` 收集全部 wzr 比较再比对长度，x86_64 改用 llvm-objdump 反汇编；`patch-init-boot.py` 的 `--signer-size` 也改为「按 `--from-size` 唯一命中」定位并保留原寄存器编码。

| ABI | KMI | 字节 | sha256 |
| --- | --- | ---: | --- |
| aarch64 | android12-5.10 | 350160 | `cdc3dd5a70046896fcd2e7f4bb5c7105789193312081e4da085e9417384ef296` |
| aarch64 | android13-5.10 | 346176 | `2a3e88de09281901f0fdd34984199d43adcca3b827352e384ef3feff75c34dd3` |
| aarch64 | android13-5.15 | 374256 | `5774e5837a56d49307e4d1016158e3f90b905507af4172e78aec556cab9d2573` |
| aarch64 | android14-5.15 | 469976 | `89b4af077b4c11a62be50f3650df4672e2fcf828d7493969307fbf168a050ab0` |
| aarch64 | android14-6.1 | 386896 | `a54e1fb9db18170fcf088daf83160da8c62c63a68117d68e3a401e7f44333b8e` |
| aarch64 | android15-6.6 | 315280 | `00bcca544e8115c9fea3e70d39ed29316e93b887468970a04cb7af2918cc3040` |
| aarch64 | android16-6.12 | 386600 | `b37651ac4d20af494315caba863b68a4eaa9e9229b78a9de412723cfffa61fa8` |
| aarch64 | android17-6.18 | 357280 | `5e2705fb895b736225bbd70f0c858983768b1eabe95d7d6dcff3260a109c5054` |
| x86_64 | android12-5.10 | 204152 | `3cdcf8b91d90e5905695130420138b618a01b4cf9e2fd29d6a6853d55df43151` |
| x86_64 | android13-5.10 | 206448 | `28657fe859c1f0890e1a67ef7fb4cb9e9cbc95e39a5b67568af0c7bc10eb11f8` |
| x86_64 | android13-5.15 | 205232 | `b5886e564c003eeeb2b03abe2f6b0a1faed46975a9a838bbbee3450825cdf544` |
| x86_64 | android14-5.15 | 223912 | `0bfbeadaff07c814a54e6ca7e3d0882ef0be0f944b769de371c252bead4e0e83` |
| x86_64 | android14-6.1 | 237056 | `875b97ee905702e3d46b384cd1bc871e3bc52d06a2f632831045facf6ad7b07b` |
| x86_64 | android15-6.6 | 318896 | `6ad59a410a38a79275c669a0e114e25a11b17f7ba85167a245d5ca102079f33e` |
| x86_64 | android16-6.12 | 341680 | `6238983f2e12e2c71329e19cb33d54f542d48016f9f01ebf6d73ebb4981d25fd` |
| x86_64 | android17-6.18 | 409792 | `2b3e086a033d10b72d870e4693c481dcb05d5a35afdc5f59b34f4578212ba5e7` |

表内 16 个文件的 sha256 与 `ci/lkm` 上的产物一致（`git blob` 亦逐个核对），`_tools/verify-identity.ps1 -RequireSourceBuild` 对全部 16 个报 PASS。
