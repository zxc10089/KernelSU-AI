# 与上游（tiann/KernelSU）的一致性审计与构建条件

> 2026-10-05 首版；**2026-10-16 已实际执行对齐**：用 `git replace --graft` 把导入提交 `3e09466` 接到上游 v3.3.0（`932014ab`），再以真 merge 合入上游 **v3.3.1**（`0c0fdb96`），得到合并提交 `4d824711` 并推送 `origin/master`。做法、冲突面与验证见第 8 节。
> 用户要求：除了我们制作的内容，其余尽可能与原版一致；没有条件就创造条件。
> 对照对象：上游 tag `v3.3.0` = `932014ab`、`v3.3.1` = `0c0fdb96`、`main` = `200818ca`（本次已 fetch 进本仓库，可直接 `git diff refs/tags/v3.3.1 master` 复算）。首版审计用的 `_src/**` 快照仅作历史存档；第 3–7 节保留首版措辞，第 1、8 节为准。

## 1. 结论

**2026-10-16 合并后的现状（权威；`git diff refs/tags/v3.3.1 master` = 257 项，A=196 / M=55 / D=6）**

- `kernel/`：与上游 v3.3.1 只差 14 项，除 `kernel/Kbuild` 的身份默认值外全部是导入伪影——`D` 4 项：`AGENTS.md`、`kernel/.vscode/{c_cpp_properties.json,generate_compdb.py,tasks.json}`（未随快照带入）与 `kernel/include/uapi`（符号链接被实体化）；`A` 6 项：`kernel/include/uapi/{app_profile,feature,ksu,selinux,sulog,supercall}.h`；`M` 4 项：`kernel/Kbuild`、`kernel/build-all.sh`、`kernel/build-all-x64.sh`、`kernel/setup.sh`。
- `kernel/manager/apk_sign.c`：与上游 v3.3.1 **逐字节一致**（`git diff refs/tags/v3.3.1 master -- kernel/manager/apk_sign.c` 为空）——首版记的 6 行分叉随 v3.3.1 把 `ksu_filp_open_nonotify` 带入发布线而自然消解。
- `userspace/`：`A` 18 项（16 个 ko + 2 个 ksuinit）、`M` 5 项（`ksud/bin/.gitignore`、`bin/{aarch64,x86_64}/busybox`、`ksud/build.rs`、`ksud/src/main.rs`）；`ksud/Cargo.toml` 已直接采用上游 v3.3.1 的版本，不再是差异项。
- `website/`：`M` 10 项，全部是 `pt_BR/**` 的导入伪影（换行规范化与同步）。
- `.github/`：`A` 1 项（我方触发器 `build-lkm-fork.yml`），其余与上游 v3.3.1 一致；`dependabot.yml` 与 12 个工作流都在**原路径**，首版「归档为 `*.upstream`」的说法已作废（README 与 `docs/开发文档.md` 已同步更正）。
- `manager/`：`A` 140 + `M` 29 + `D` 1，全部是我们自己的内容（AI 控制台、环境隐藏资源包、模块制作、AI 审计、身份接线、首页适配）；29 个 `M` 都是与上游同路径的接线/适配文件。
- 其余：`_tools/**` `A` 21、`design/**` `A` 4、`docs/**` `A` 4；`scripts/` `M` 3（`ksubot.py`、`prepare-ddk-x64.sh`、`setup_cargo_config.py`）；`docs/README_IW.md`、`docs/README_PL.md`、`manager/gradle.properties`、`manager/build.gradle.kts` 等为 `M`。
- 相对导入基线 `3e09466`：479 文件（+53816 / −4181）。

## 2. 有意保留的分叉（属于「我们制作的内容」）

| 位置 | 内容 | 依据 |
| --- | --- | --- |
| `manager/**`（116 新增 + 31 差异） | AI 控制台、环境隐藏、模块制作、AI 审计，以及包名/版本等身份接线 | 本分支的产品内容 |
| `kernel/manager/apk_sign.c` | **已不再是分叉**：上游 v3.3.1 把 main 那套更严的 `check_v2_signature`（未知签块直接判失败）带进了发布线，2026-10-16 的合并自动收敛 | 用户 2026-10-05 指令「把原版那套更严的合并回来」；合并后与 v3.3.1 逐字节一致 |
| `userspace/ksud/bin/{aarch64,x86_64}/{android12-5.10,android13-5.10,android13-5.15,android14-5.15,android14-6.1,android15-6.6,android16-6.12,android17-6.18}_kernelsu.ko`、两个 `ksuinit` | 上游 8 个 KMI × 2 个 ABI 的全部 ko 随仓库分发（上游用 `.gitignore` 忽略 CI 产物） | 让没有工具链的源码快照也能直接出包；矩阵来源 run `37312303026`，逐项哈希见第 7 节 |
| `userspace/ksud/bin/.gitignore` | 放开上述全部资产的忽略规则（`**/*.ko`、`**/ksuinit` 仍忽略，再逐个放行矩阵里的 16 个 ko 与 2 个 ksuinit） | 同上 |
| `userspace/ksud/build.rs` | `cargo:rerun-if-env-changed=KSU_PACKAGE_NAME` + 未设置时 `cargo:warning` | 保证 daemon 包名不会静默回落 |
| `userspace/ksud/src/main.rs` | crate 级 clippy allow（`redundant_field_names`、`assert_is_empty`），注释声明只覆盖上游文件 | 本机较新 clippy 对上游代码的既有告警；v3.3.1 起上游自己也放行 `redundant_field_names`，本分支只多 `assert_is_empty` |
| `_tools/**`、`docs/**`、`design/**` | 构建/验收工具与文档 | 本分支的工程支撑 |
| `README.md` | 本分支说明 | 本分支的对外说明 |

## 3. 与上游 main 的对齐（不是分叉）

- `userspace/ksud/Cargo.toml`、`userspace/ksuinit/Cargo.toml` 的 git 依赖组织 `Kernel-SU` → `KernelSU2`：上游 main 已在用 `KernelSU2`，我们只是提前对齐。2026-10-16 合并后 `userspace/ksud/Cargo.toml` 直接采用上游 v3.3.1 的版本（`bindgen = "0.73"`、`prop-rs-android` rev `ddb6ee7294467f7f25bad2118e9e24eee104144b`），首版钉的 `6f5723105d8d4cacad31d83d343defbf032c7b33`（v3.3.0 版本）已不再使用。
- `kernel/manager/apk_sign.c`：首版与上游 main 只差 6 行（4 增 2 删）——main 用 `ksu_filp_open_nonotify(path, O_RDONLY | O_NOATIME)`，当时本分支只能用 `filp_open(path, O_RDONLY, 0)` 加 `fp->f_mode |= FMODE_NONOTIFY`，因为 `ksu_filp_open_nonotify` 只存在于 main 的 `kernel/include/util.h`（v3.3.0 树里 0 命中）。**v3.3.1 把它带进了发布线**，因此合并后该文件与 v3.3.1 逐字节一致（现在的 `kernel/manager/apk_sign.c:158` 就是 `ksu_filp_open_nonotify(path, O_RDONLY | O_NOATIME)`），这条分叉已关闭。

## 4. 包名匹配的一个细节（为什么可以默认开启 `KSU_MANAGER_PACKAGE`）

v3.3.1 起包名校验搬出了 `apk_sign.c`：`kernel/manager/apk_sign.c` 的 `is_manager_apk()`（:326）现在只做签名校验 `check_v2_signature(path, EXPECTED_SIZE, EXPECTED_HASH)`；真正的包名比对在 `kernel/manager/throne_tracker.c`——`is_expected_manager()`（:84）做 `strncmp(pkg, KSU_PACKAGE_NAME, sizeof(KSU_PACKAGE_NAME)) == 0`，:41/:302 用同一常量做遍历与比对，:76 有 `_Static_assert(sizeof(KSU_PACKAGE_NAME) < KSU_MAX_PACKAGE_NAME)`，:344 把它写进日志；`kernel/core/init.c:95` 也在启动横幅里打印包名，`kernel/supercall/dispatch.c:57/88` 用 `KSU_IS_PR_BUILD` 区分 PR 构建。

APK 目录到包名的提取在 `get_pkg_from_apk_dir_path()`（`kernel/manager/apk_sign.c:301`）：先 `strrchr(path, '/')`（:307）取最后一个斜杠，再从那里 `strchr(last_slash, '-')`（:311）取**第一个**连字符，二者之间即包名（:315-321，含 `KSU_MAX_PACKAGE_NAME` 边界检查）。因此 APK 目录后缀里带连字符（实测 `me.weishu.kernelsu.hide-VNZu0x-zfCWWNVYgj3Dvhw==`）不会污染包名——包名本身不含连字符。

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
KSU_IS_PR_BUILD ?= 0
ifeq ($(KSU_IS_PR_BUILD),0)
KSU_PACKAGE_NAME ?= me.weishu.kernelsu.hide
else
KSU_PACKAGE_NAME ?= me.weishu.kernelsu.hide.pr
endif
KSU_EXPECTED_SIZE ?= 0x342
KSU_EXPECTED_HASH ?= ca40afa835e460be5dcfd0743296d95c3275b9e51673de8b6fd0a64665a83eec
```

2026-10-16 起这里是「上游 v3.3.1 的开关形状 + 本项目的值」：上游把 `KSU_MANAGER_PACKAGE` 更名为 `KSU_PACKAGE_NAME`、新增 `KSU_IS_PR_BUILD`、把 `:=` 改成 `?=`，并删掉了副签名者 `EXPECTED_SIZE2/HASH2` 块。合并后的内核 C 代码只引用新名字（`kernel/core/init.c:95`、`kernel/manager/throne_tracker.c:41/76/84/302/344`、`kernel/supercall/dispatch.c:57/88`，`KSU_MANAGER_PACKAGE` 已无任何引用），所以必须用新名。上游自己的默认值是 `me.weishu.kernelsu` / `0x033b` / `c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6`，PR 构建用 `.pr` 后缀（上游 `me.weishu.kernelsu.pr12` 一类）；本分支不发布 PR 构建，恒为 `KSU_IS_PR_BUILD=0`。

这与上游给自己写死官方身份的做法一致，只是值换成本项目的：上游 CI 因此无需任何改动即可构建本分支的 ko。用 `make KSU_PACKAGE_NAME=… KSU_EXPECTED_SIZE=… KSU_EXPECTED_HASH=…` 仍可覆盖。

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

- 源码产物已上线：DDK CI（`build-lkm-fork.yml`，run `37305609190`，KMI `android15-6.6`）产出的 ko 经 `_tools/adopt-lkm-asset.ps1 -Verify` 收编，`userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko` = **315,280 字节 / sha256 `105ec969f2697459e9f8a9f14d643fc1ba58dbf3660200d08c877bca10b3e374`**，含 `me.weishu.kernelsu.hide`（路径 A；首发批次带 `-DKSU_VERSION=16`，同日已由 run `37324382993` 的重建产物替换，见下条）。回退产物归档在 `_artifacts/builder/evidence/android15-6.6_kernelsu-pathB-bytepatch.ko`。
- `libksud.so`（arm64-v8a / x86_64）与 release APK 已按新资产重建；`_tools/verify-identity.ps1 -RequireSourceBuild` 把「资产必须源自源码构建」变成可执行闸门（路径 B 直接 FAIL）。`_tools/verify-init-boot.py` 为引导镜像提供独立复核（自带解析器，不共用 producer 代码）。
- **全矩阵已上线（2026-10-05）**：`build-lkm.yml` 的 8-KMI 矩阵由 `build-lkm-fork.yml` 触发（首轮 run `37312303026`，master `85d4955`，8 个矩阵任务 + publish 全绿），16 个产物逐个经 `_tools/adopt-lkm-asset.ps1 -Abi <abi> -Kmi <kmi>` 校验身份后收编进 `userspace/ksud/bin/`，`docs/DEVELOPMENT.md` 第 447 行记录的「单一 KMI」脆弱性随之关闭（slot/keystore/v2-only 三条仍成立）。
- **身份闸门修掉两个真坑**：DER 长度的比较指令寄存器是编译器选择——aarch64 多数 KMI 是 `cmp w21,#834`，android12-5.10 编成 `cmp w8,#834`；x86_64 的立即数位置随寻址方式变（`cmpl $834, %r12d` 与 `cmpl $834, 8(%rsp)`）。旧工具按字节硬匹配 `bfee0c71`/`0xA864`，会把 android12-5.10 误判失败、也可能误判通过。现在 `_tools/identity-lib.ps1` 以 `(w & 0x7F80001F) == 0x7100001F` 收集全部 wzr 比较再比对长度，x86_64 改用 llvm-objdump 反汇编；`patch-init-boot.py` 的 `--signer-size` 也改为「按 `--from-size` 唯一命中」定位并保留原寄存器编码。

- **驱动版本随 pin 重建（2026-10-05）**：首轮产物「证书与包名都正确，但 `-DKSU_VERSION=16`」，管理器首页因此报「驱动版本 16 过低」。`kernel/Kbuild` 改为按「显式 `KSU_VERSION_CODE` > `manager/gradle.properties` > git 计数 > 16」取版本后，同一工作流重跑（run `37324382993` @ `993a080c`，8/8 矩阵 + publish 全绿），16 个产物逐个用 `_tools/identity-lib.ps1` 的 `Find-KsuVersion` 从反汇编读出 `version=32601`，再由 `_tools/adopt-lkm-asset.ps1` 整体替换；下表为新产物的哈希。

| ABI | KMI | 字节 | sha256 |
| --- | --- | ---: | --- |
| aarch64 | android12-5.10 | 350160 | `edc3fa011bf44a3300421e5cd892d8ff7b70d31868e5ed80a6e178f102141b9f` |
| aarch64 | android13-5.10 | 346176 | `fb62b51f4bbf5cdcf6d466a53867e7c93c0109e670d4e4d26218eef97039023f` |
| aarch64 | android13-5.15 | 374256 | `d49add3b64591ca152166f41d706724defdab02a7103d946b32add3c2d117575` |
| aarch64 | android14-5.15 | 469976 | `6503a50e8b8a4c9455b9d2c0d53e512da1f371c6941c8837293736bf3a06c568` |
| aarch64 | android14-6.1 | 386896 | `24b4a9e8383da19ffbeeafcbd9fdd7aeffcb9ba977dd4975f79ed569de208480` |
| aarch64 | android15-6.6 | 315280 | `105ec969f2697459e9f8a9f14d643fc1ba58dbf3660200d08c877bca10b3e374` |
| aarch64 | android16-6.12 | 386600 | `ef03735e73cd4be0cdc7f6c54d53af285363f47c9d7eb5eeb2df1d1aa6c87230` |
| aarch64 | android17-6.18 | 357280 | `851e2e66fad9601abdf30b129468891e9cef2fb7acd695d7226b1b618f34f553` |
| x86_64 | android12-5.10 | 204152 | `b8fc8854d40f55b04f50df28070cf908f4d1ffac95888bbbc130c616a5d3699f` |
| x86_64 | android13-5.10 | 206448 | `1320471a0fca765cbed2fe663eb190abd0fd13f34848989fed5bd8c629532643` |
| x86_64 | android13-5.15 | 205232 | `f1c08297fdab1db4888dc9cb3c142e6742ff8f992534e011d93db8d37222c99d` |
| x86_64 | android14-5.15 | 223912 | `1feb1ada1da507cddbf9ce6ce9a504ef90ae35e76a807d90f3b66b2c9999b202` |
| x86_64 | android14-6.1 | 237056 | `0da68e4f7935fa96b66d31cda2108d9fff2112aaac8f5c472c6fbee197c2bc7b` |
| x86_64 | android15-6.6 | 318896 | `4f98a80ef08ec7e8ebadfe41c703b48972d78aff7123bb0c6f840ef17a4bdcce` |
| x86_64 | android16-6.12 | 341680 | `46002448c3da6b653ac566a64db6cde81962b61365ce1553ea2a125ac56a52cd` |
| x86_64 | android17-6.18 | 409792 | `81b659fe31fb5a1bb20a6d086bcc5ca080ccf71a41048472a5dfab46365a711f` |

表内 16 个文件的 sha256 与 `ci/lkm` 上的产物一致（`git blob` 亦逐个核对），`_tools/verify-identity.ps1 -RequireSourceBuild` 对全部 16 个报 PASS；每个文件反汇编读出的驱动版本均为 32601（`_tools/identity-lib.ps1` 的 `Find-KsuVersion` 按指令形状定位常量，不依赖符号表）。

## 8. 2026-10-16：把历史接到上游并合入 v3.3.1（已执行并推送）

起点：导入提交 `3e09466`（`chore: import KernelSU v3.3.0 baseline`）是**无父的 root 提交**，与上游没有任何共同祖先，直接 `git merge` 需要 `--allow-unrelated-histories`，会把 224 个自有改动文件全算成冲突。

1. **接历史**：`git replace --graft 3e09466f6816e551b4eec880a4463eb30c001e30 932014ab5b2c9b74a3d11e2ec4d17dd10fc9442e`（把导入提交的父改成上游 v3.3.0）。选 v3.3.0 作 graft 父是逐个算过 `v3.3.0..upstream/main` 全部 82 个提交对 `3e09466` 的文件差异数后定的：v3.3.0 最小（39，全是导入伪影），次小 `435ab65e8f` 是 54，再往后从 83 起。graft 后 `merge-base master v3.3.1` 精确等于 `932014ab`，冲突面从 224 收敛到 16。
2. **合入 v3.3.1**：`git merge-tree --write-tree master v3.3.1` 得到合并树 `74344790b8550e039f789864921af80567b5504f` 与 **16 个冲突**（相对 285 项变更）；逐个解决后用 plumbing 建树提交：`read-tree <mergeTree>` → 解冲突 → `add -u` → `write-tree` = `00b333fe915ae9a8b3542a14ab2b48d1f0b6cc08` → `commit-tree 00b333fe -p ea64134892509d010b9364f79f51d68f499d7d6b -p 0c0fdb960178fa12d80d6cbcfdcb6ad8be6a865d` = **`4d824711fddbe50edcf50f1930ee43b74da9ec79`** → `update-ref refs/heads/master 4d824711`，最后 `read-tree HEAD` 把真实 index 同步成同一棵树（否则 `status` 会显示数百项「已修改」）。
   > 为什么不用 `git merge`：本机文件系统上 lock→目标的 rename 退化成 copy+delete，而 delete 是静默空操作，同一把锁在**一次调用内**被取两次必然失败，`git merge` 恰好需要两次 `index.lock`；因此改用上述 plumbing 组合（过程见 `_logs/lockprobe-merge*.log`）。
3. **推送**：`git push origin master` 因凭据 helper 也被交给 `sh.exe` 执行而不可用，改用**进程内 `Authorization: Basic base64(x-access-token:<token>)`** 请求头完成（`_dl/push-p7-header.ps1`），结果 `35095011..4d824711 master -> master`；随后用 `Move-Item` 把 `refs/replace/<3e09466>` 移出仓库（删除在本机是空操作），`for-each-ref refs/replace` 现为空。

验证：`rev-list --parents -n1 master` = `4d824711 ea641348 0c0fdb96`（双亲）；`merge-base --is-ancestor refs/tags/v3.3.1 master` exit=0（v3.3.1 已是本分支祖先）；`status --porcelain` 为空；`diff --shortstat refs/tags/v3.3.1 master` = 257 files changed, 44669 insertions(+), 1670 deletions(-)。

16 个冲突文件与解法：`Cargo.lock`、`userspace/ksud/Cargo.toml` 取上游 v3.3.1 原样；`scripts/ksubot.py`、`website/docs/pt_BR/guide/installation.md` 取上游内容并把 CRLF 规范化为 LF（这两个「全文件差异」其实是导入时换行规范化留下的伪影）；`userspace/ksud/src/main.rs` 取上游结构 + 保留 `assert_is_empty` allow；`kernel/Kbuild` 取上游开关形状 + 换回本项目身份默认值（第 6 节）；4 个 `ui/component/bottombar/*.kt` 保留本分支的 root-only 解锁逻辑（上游改用 `Natives.isFullFeatured()`，会让自编译 APK 丢掉完整特性）；`ui/MainActivity.kt`、`res/values-zh-rHK/strings.xml`、`ui/screen/module/ModuleMaterial.kt`、`ui/screen/superuser/SuperUserMaterial.kt` 逐 hunk 取并集；首页 `HomeMaterial.kt`/`HomeMiuix.kt`/`HomeScreen.kt`/`HomeUiState.kt`/`HomeViewModel.kt` 整体对齐上游 v3.3.1 后补回本分支的「接入 AI 助手」卡片（`actions.onOpenAiConfig`，字符串 `home_ai_assistant_title` / `home_ai_assistant_summary`）。

未完成（另立任务）：16 个 `userspace/ksud/bin/**/*_kernelsu.ko`、两个 `libksud.so` 与 release APK 仍是按 **v3.3.0 / 32601** 从源码构建的产物，而源码已对齐 v3.3.1（上游 v3.3.1 官方 versionCode 是 **32680**）；借 DDK CI 重建后才能把 `manager/gradle.properties` 的 `KSU_VERSION_CODE` 提上去，在那之前 `_tools/verify-identity.ps1 -RequireSourceBuild` 仍按 32601 校验。
