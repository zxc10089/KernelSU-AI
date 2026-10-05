# 镜像补丁取不到 root 的根因与修复（预置 `ksu_manager_appid`）

适用：本分支（KernelSU-AI，包名 `me.weishu.kernelsu.hide`）在设备上刷入自编译 init_boot 后，
管理器仍显示「未安装」、无法授权 root 的问题。2026-10-05 在 OPPO PMA110（ColorOS 16，
内核 6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k，bootloader 已解锁）上定位并修复。

## 1. 现象

- 镜像（init_boot_a）确实是本分支的补丁镜像：ramdisk 内含 `ksuinit`（打包为 `/init`）、`init.real`、`kernelsu.ko`，
  ko 的签名常量与已安装 APK 的 v2 证书**逐字节匹配**（size `0x2e8`=744，sha256 `f9af24bd…63d0e`）。
- LKM 已加载且驱动可用：`ksud debug info` 返回 `version: 32601, lkm: true, late_load: false`。
- 但管理器没有被「加冕」：管理器 UI 显示「未安装」，且从 shell（uid 2000）调用
  `debug info` 得到 `flags: 0x1`（仅 LKM），缺少 `MANAGER` 位。

## 2. 根因

`kernel/manager/apk_sign.c: is_manager_apk()` 的判定是「包名（编译期，可选）+ v2 签名证书 size/hash」。
本分支内置的 ko 有**两个致命属性**：

1. 白名单证书是 **公开的 AOSP debug 证书**（`f9af24bd…63d0e`，任何用 Android 默认 debug key 签名的 APK 都满足），
   而不是项目自己的私有 release key（原版为 `0x033b` / `c371061b…d2d6`）。
2. 构建时**没有定义 `KSU_MANAGER_PACKAGE`**（ko 内不存在 `me.weishu` 字符串），于是包名校验被编译掉，
   只剩证书比对。

因此 `search_manager()` 遍历 `/data/app` 时，**第一个匹配证书的 APK 就赢得加冕**。实测设备上共有两个
匹配的应用：`me.weishu.kernelsu.hide` 与调试版 `moe.nb4a.debug`（uid 10523）；readdir 顺序稳定，
所以冠位稳定地落在 `moe.nb4a.debug` 上：

```
run-as moe.nb4a.debug …/ksu-test debug info   -> flags: 0x3   (LKM|MANAGER)
adb shell       /data/local/tmp/ksud-fork debug info -> flags: 0x1   (LKM only)
```

注意：`kernel/manager/manager_identity.h: is_manager()` 比较的是 `ksu_manager_appid == current_uid().val % 100000`，
与包名无关；`kernel/supercall/perm.c: allowed_for_su()` = `is_manager() || ksu_is_allow_uid_for_current(…)`。
所以「冠位被别人拿走」的后果就是：**管理器应用（即使已装好、签名正确）永远拿不到 root 授权**。

## 3. 修复

把 `ksu_manager_appid` 在 ko 的 `.data` 里预置为管理器应用的 uid（本设备 2026-10-05 卸载重装后是 `10627`；重装前是 `10626`），
这样模块一加载冠位就是它。原理与副作用：

- 定义在 `kernel/manager/throne_tracker.c:15`（原值 `KSU_INVALID_APPID`=`0xffffffff`），
  ELF 里可定位为符号 `ksu_manager_appid`（section `.data`，value `0xb0`，文件偏移 `0x1d520`）。
- 预置值**持久有效**：`track_throne()` 只在「当前 appid 无效」或「该 uid 不在 packages.list 中」时才重新搜索
  （`throne_tracker.c:315-333`）。预置 uid 在 packages.list 中一直存在，所以后续装包事件不会把冠位抢回去。
- 对多用户克隆同样成立：`1010627 % 100000 == 10627`。
- appid 与设备/安装绑定：**卸载重装管理器会换 uid，必须重新打补丁**（实测：`pm install -r` 保持 10626；
  卸载后重装就变成 10627）。改 appid 前先读一次：`adb shell pm list packages -U -3 | grep kernelsu`
  （输出 `package:me.weishu.kernelsu.hide uid:10627,1010627`），或 `adb shell stat -c %u /data/data/me.weishu.kernelsu.hide`。
  `adb shell pm list packages -U -3 | grep kernelsu`（输出 `uid:10627,1010627`）或
  `adb shell stat -c %u /data/data/me.weishu.kernelsu.hide` 读取，改 appid 前先确认这一个值。

### 3.1 一条命令打补丁

```powershell
# 以「当前正在运行的那个 init_boot 镜像」为基准，预置 appid 后输出新镜像
python KernelSU-v330\_tools\patch-init-boot.py \
  --base  <当前镜像.img> \
  --out   <新镜像.img> \
  --manager-appid 10627        # 必须等于设备上管理器实际 uid % 100000
```

工具是纯标准库实现（无需 lz4/magiskboot）：解出 boot v4 头 → 解 LZ4-legacy ramdisk（或未压缩 cpio）→
按 ELF 符号定位 `ksu_manager_appid` → 改 4 字节 → 以「单块全字面量 LZ4」重新编码 → 回填 ramdisk_size →
补齐到原镜像大小 → 重新解析/解码头做自校验（只允许 `ramdisk_size` 4 字节变化）。输出应可复现：

```
verify      : ramdisk_size=5656983 cpio=5634876 total diff=4
verify      : ko sha256=5d78e567f3c0334373502e19cdd94c9d5f623676231d027ee334c7010db04574
verify      : header byte diffs ['0xc', '0xd', '0xe']
verify      : PASS
```

已有产物（本设备，基准 = 当前在跑的 `_tmp\device-pull\now_init_boot_a.img`，sha256 `76ee9de4…d255`）：

| 产物 | sha256 | 内含 |
| --- | --- | --- |
| `_tmp\init_boot_a_projectkey_appid10627.img`（**推荐**，设备副本 `/data/local/tmp/…`） | `4450ea229d0032339f35b6108579ab52c592979f79c3169fae01faad604403f3` | 项目证书 ca40af…/834 + appid 10627 |
| `_tmp\init_boot_a_projectkey.img` | `26651779a3b17120eb4975cdb2259c7ec871af492b73c4bc4aecf04137c2bfc1` | 只换项目证书 |

两个镜像的 ko 都可用 `_tmp\verify_image_ko.py <img> <appid> <cert-sha256> <der-size>` 复核；更早的 appid-only 镜像
`out\init_boot_a_ksu-appid10626.img`（sha256 `4e61a259…`）已被第一个产物取代（它的 appid 已过期）。

### 3.2 顺带修内置资产（`--ko-in` 模式）

`userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko` 是被打进 `libksud.so` 的内置资产，
**同样是未预置的**：当用户走 app 内「修补/安装」流程（`ksud boot-patch` 注入内置 ko）时会重新引入该缺陷。
重新构建 libksud 前先补一次：

```powershell
python KernelSU-v330\_tools\patch-init-boot.py \
  --ko-in  KernelSU-v330\userspace\ksud\bin\aarch64\android15-6.6_kernelsu.ko \
  --ko-out <输出.ko> --signer-sha256 ca40afa835e460be5dcfd0743296d95c3275b9e51673de8b6fd0a64665a83eec --signer-size 834
```

注意：内置资产里**只换证书**、不要把 appid 预置进去——资产随发行版分发到别的设备，而 appid 是每台设备
每个安装实例特有的。内置资产当前已是项目证书版（上游 8 个 KMI × 2 个 ABI 共 16 个文件全部来自源码构建，
逐项 sha256 见 `docs/upstream-alignment.md` 第 7 节；这里提到的 `d537e0d7…c9178` 是被替换掉的那份路径 B 产物），
`_tools/verify-identity.ps1` 第 4 节会逐个校验。

### 3.3 刷入与回滚

事前把**当前正在运行的镜像**留在设备上作为回滚镜像（本设备：`/data/local/tmp/init_boot_a.img`，
sha256 `76ee9de4…d255`，已确认是当前运行的那一份）。两条路径任选：

- fastboot（推荐，会校验分区大小）：`adb reboot bootloader` → 确认当前槽位（本设备 `ro.boot.slot_suffix=_a`）→
  `fastboot flash init_boot_a <新镜像>`（若 bootloader 只暴露 `init_boot`，用 `fastboot flash init_boot <新镜像>`）→
  `fastboot reboot`。
- 已有 root 时直接写分区（无需 fastboot）：`dd if=/data/local/tmp/init_boot_a_projectkey_appid10627.img of=/dev/block/by-name/init_boot_a`
  （本设备 `init_boot_a -> /dev/block/sde29`）。写分区有断电风险，写前确认目标分区号。

回滚：把 `/data/local/tmp/init_boot_a.img` 用同样方式写回。

> 本次交付刷的是 `_tmp\init_boot_a_projectkey_appid10627.img`（sha256 `4450ea22…403f3`，设备副本
> `/data/local/tmp/init_boot_a_projectkey_appid10627.img`）：项目私钥证书让别的 app 抢不走冠位，预置 appid 10627
> 让冠位在模块加载时立刻成立、不依赖 fsnotify/zygote 触发。只想换证书、保留运行时搜索行为的话，
> 用 `_tmp\init_boot_a_projectkey.img`（设备副本 `/data/local/tmp/init_boot_a_projectkey.img`）。

### 3.4 验证

1. 重启后管理器 UI 应显示「工作中/已安装」，不再是「未安装」。
2. `adb shell /data/local/tmp/ksud-fork debug info` 的 `flags` 应含 MANAGER 位（`0x3`）。
3. 管理器内授权任意应用后可用 `su`；或先验证冠位：
   ```sh
   run-as me.weishu.kernelsu.hide id    # 若 APK 未 debuggable 则改用管理器内操作
   ```
4. 保留一份新镜像的 sha256 与 `dd`/fastboot 日志作为证据。

## 4. 项目级修法（2026-10-05 已落地）

预置 appid 只是「运行时逃生口」，根治要靠两个编译期事实（原版 tiann/KernelSU 的做法）：

1. **项目私有 release key**：`_artifacts/builder/keystore/kernelsu-ai-release.jks`（alias `kernelsu-ai`，RSA 2048，
   DN `CN=KernelSU-AI, OU=KernelSU-AI, O=zxc10089, C=CN`）；导出证书 `kernelsu-ai-release.der` = **834 字节（0x342）**，
   证书 SHA-256 **`ca40afa835e460be5dcfd0743296d95c3275b9e51673de8b6fd0a64665a83eec`**。
   公开事实写在 `_tools/project-signer.json`；私钥与口令只在 `_artifacts/builder/keystore/keystore.properties`（不入库）。
2. **ko 常量改用这张证书**：`KSU_EXPECTED_SIZE=0x342`、`KSU_EXPECTED_HASH=ca40af…`，并定义
   `KSU_MANAGER_PACKAGE=me.weishu.kernelsu.hide`（`kernel/Kbuild:130-157` 会转成 ccflag，让同证书的其他包名也匹配不上）。

交付有两条路径，**源码构建是正式路径**：

**路径 A（正式，DDK CI）**：`kernel/Kbuild` 现在把三项身份写成默认值（`0x342` / `ca40af…` / `me.weishu.kernelsu.hide`），与上游给自己写死官方身份的做法一致，因此上游 DDK 工作流无需改动即可构建出正确的 ko：

- 全矩阵：Actions → `Build LKM for KernelSU` → Run workflow（`build-lkm.yml` → `ddk-lkm.yml`，容器 `ghcr.io/ylarod/ddk-min:<kmi>-<ddk_release>`）；
- 源码构建走 CI 矩阵：`.github/workflows/build-lkm-fork.yml`（push `kernel/**` 或该工作流自身变更时自动触发）调用上游 `build-lkm.yml` 的 8 个 KMI，产物名 `{aarch64,x86_64}-<kmi>-lkm`，整批提交到 `ci/lkm` 分支便于取回。

**路径 A 已落地（2026-10-05）**：`.github/workflows/build-lkm-fork.yml` 触发的 DDK CI（run `37305609190`，`Build kernelsu.ko for android15-6.6` 全绿）产出源码构建的 ko，经 `_tools/adopt-lkm-asset.ps1 -Ko <built.ko> -Verify` 收编为内置资产：

- `userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko`：**315,280 字节**，sha256 `00bcca544e8115c9fea3e70d39ed29316e93b887468970a04cb7af2918cc3040`，含 `me.weishu.kernelsu.hide` 字符串（= `KSU_MANAGER_PACKAGE` 已编译进内核模块，包名校验生效）；同一 CI 产物在 `ci/lkm` 分支留档。
- 被替换的路径 B 产物归档到 `_artifacts/builder/evidence/android15-6.6_kernelsu-pathB-bytepatch.ko`（sha256 `d537e0d7…c9178`）。
- vermagic 四种 ko 副本完全一致（`6.6.127-4k-g46a034eca005-dirty SMP preempt mod_unload modversions aarch64`），所以源码构建与设备上已在跑的回退产物一样可加载。

**全矩阵已补齐（2026-10-05）**：run `37312303026`（master `85d4955`）把上游 8 个 KMI × 2 个 ABI 全跑绿，16 个产物逐个经 `_tools/adopt-lkm-asset.ps1 -Abi <abi> -Kmi <kmi>` 校验身份后收编进 `userspace/ksud/bin/`；本机设备是 android15-6.6，它的那一份与上面 315,280 字节的产物逐字节相同。逐项字节数与 sha256 见 `docs/upstream-alignment.md` 第 7 节。

**路径 B（回退，本机无 DDK 容器时的应急方式）**：等长字节补丁，与路径 A 在「证书哈希 + DER 长度」两项上等价，但 `KSU_MANAGER_PACKAGE` 的包名校验仍是编译掉的——只靠「私钥证书唯一」达到同等效果：

- 校验闸门同时是两条路径的判据：路径 A 的产物含 `me.weishu.kernelsu.hide` 字符串（只有真正定义 `KSU_MANAGER_PACKAGE` 才会出现），路径 B 的产物没有；`_tools/verify-identity.ps1 -RequireSourceBuild` 把包名这一项作为闸门（缺字符串即 FAIL），不带该开关时只报 `provenance=path A/path B` 的 WARN。

**镜像产物（源码 ko + 冠位预置）**：`_tmp/init_boot_a_sourceko_appid10627.img`（8,388,608 字节，sha256 `4fbe9ef40eb0c38a52954527010f9085a07e7236cce26b6c3114e0a5d230f844`），构建方式：

```powershell
python _tools/patch-init-boot.py --base <当前已刷镜像> --out <out.img> `
    --replace-ko userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko --manager-appid <uid%100000>
```

ko 大小变了（315176 -> 315280），工具会重排 newc 归档而不是原地覆盖。独立复核（不共用 producer 代码）：`_tools/verify-init-boot.py <img> --expect-appid 10627 --require-source-build` -> 7 项全 PASS；对旧的路径 B 镜像同参数 2 项 FAIL（产地 + 冠位），证明判据有效。

## 5. 注意事项（踩过的坑）

- **不要** `rmmod kernelsu`：本内核会在卸载时崩溃并自动重启（实测 `up 0 min, load average: 16.59`）。
  运行期换 ko 不可行，只能走镜像。
- `CONFIG_KSU_DEBUG` 的 sysfs 通道（`/sys/module/kernelsu/parameters/…`）在本分支不可用：
  `kernel/core/init.c:183-186` 在非 debug 下 `kobject_del()` 掉了 `/sys/module/kernelsu`。
  也没有任何 ioctl 可以设置 appid（只有 `KSU_IOCTL_GET_MANAGER_APPID`，且限 manager/root）。
- 路径 B 产物的冠位判定不看包名、只看证书；因此**任何**用同一 key 签名的应用都会竞争（2026-10-05 的 `moe.nb4a.debug` 事件）。
  路径 A 产物额外校验包名，必须同时是 `me.weishu.kernelsu.hide`；交付/测试时不要把同 key 的 APK 与管理器装在同一设备上。
- `ksud boot-patch` 注入的是 ksud 二进制内嵌的 ko；若用未预置的 ksud 去打补丁，会覆盖掉本次修复。
  用本工具或预置好的镜像。
- 设备内 `/data/adb/ksu/bin/ksud` 缺失时，内核 rc 里的 post-fs-data/services/boot-completed 钩子不会执行；
  这不影响本修复（本修复在模块加载时就生效）。