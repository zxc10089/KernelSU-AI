# 镜像补丁取不到 root 的根因与修复（预置 \`ksu_manager_appid\`）

适用：本分支（KernelSU-AI，包名 \`me.weishu.kernelsu.hide\`）在设备上刷入自编译 init_boot 后，
管理器仍显示「未安装」、无法授权 root 的问题。2026-10-05 在 OPPO PMA110（ColorOS 16，
内核 6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k，bootloader 已解锁）上定位并修复。

## 1. 现象

- 镜像（init_boot_a）确实是本分支的补丁镜像：ramdisk 内含 \`ksuinit\`（打包为 \`/init\`）、\`init.real\`、\`kernelsu.ko\`，
  ko 的签名常量与已安装 APK 的 v2 证书**逐字节匹配**（size \`0x2e8\`=744，sha256 \`f9af24bd…63d0e\`）。
- LKM 已加载且驱动可用：\`ksud debug info\` 返回 \`version: 32601, lkm: true, late_load: false\`。
- 但管理器没有被「加冕」：管理器 UI 显示「未安装」，且从 shell（uid 2000）调用
  \`debug info\` 得到 \`flags: 0x1\`（仅 LKM），缺少 \`MANAGER\` 位。

## 2. 根因

\`kernel/manager/apk_sign.c: is_manager_apk()\` 的判定是「包名（编译期，可选）+ v2 签名证书 size/hash」。
本分支内置的 ko 有**两个致命属性**：

1. 白名单证书是 **公开的 AOSP debug 证书**（\`f9af24bd…63d0e\`，任何用 Android 默认 debug key 签名的 APK 都满足），
   而不是项目自己的私有 release key（原版为 \`0x033b\` / \`c371061b…d2d6\`）。
2. 构建时**没有定义 \`KSU_MANAGER_PACKAGE\`**（ko 内不存在 \`me.weishu\` 字符串），于是包名校验被编译掉，
   只剩证书比对。

因此 \`search_manager()\` 遍历 \`/data/app\` 时，**第一个匹配证书的 APK 就赢得加冕**。实测设备上共有两个
匹配的应用：\`me.weishu.kernelsu.hide\` 与调试版 \`moe.nb4a.debug\`（uid 10523）；readdir 顺序稳定，
所以冠位稳定地落在 \`moe.nb4a.debug\` 上：

\`\`\`
run-as moe.nb4a.debug …/ksu-test debug info   -> flags: 0x3   (LKM|MANAGER)
adb shell       /data/local/tmp/ksud-fork debug info -> flags: 0x1   (LKM only)
\`\`\`

注意：\`kernel/manager/manager_identity.h: is_manager()\` 比较的是 \`ksu_manager_appid == current_uid().val % 100000\`，
与包名无关；\`kernel/supercall/perm.c: allowed_for_su()\` = \`is_manager() || ksu_is_allow_uid_for_current(…)\`。
所以「冠位被别人拿走」的后果就是：**管理器应用（即使已装好、签名正确）永远拿不到 root 授权**。

## 3. 修复

把 \`ksu_manager_appid\` 在 ko 的 \`.data\` 里预置为管理器应用的 uid（本设备 \`10626\`），
这样模块一加载冠位就是它。原理与副作用：

- 定义在 \`kernel/manager/throne_tracker.c:15\`（原值 \`KSU_INVALID_APPID\`=\`0xffffffff\`），
  ELF 里可定位为符号 \`ksu_manager_appid\`（section \`.data\`，value \`0xb0\`，文件偏移 \`0x1d520\`）。
- 预置值**持久有效**：\`track_throne()\` 只在「当前 appid 无效」或「该 uid 不在 packages.list 中」时才重新搜索
  （\`throne_tracker.c:315-333\`）。预置 uid 在 packages.list 中一直存在，所以后续装包事件不会把冠位抢回去。
- 对多用户克隆同样成立：\`1010626 % 100000 == 10626\`。
- appid 与设备/安装绑定：**卸载重装管理器会换 uid，需要重新补丁**。uid 用
  \`adb shell stat -c %u /data/data/me.weishu.kernelsu.hide\` 读取。

### 3.1 一条命令打补丁

\`\`\`powershell
# 以「当前正在运行的那个 init_boot 镜像」为基准，预置 appid 后输出新镜像
python KernelSU-v330\\_tools\\patch-init-boot.py \\
  --base  <当前镜像.img> \\
  --out   <新镜像.img> \\
  --manager-appid 10626
\`\`\`

工具是纯标准库实现（无需 lz4/magiskboot）：解出 boot v4 头 → 解 LZ4-legacy ramdisk（或未压缩 cpio）→
按 ELF 符号定位 \`ksu_manager_appid\` → 改 4 字节 → 以「单块全字面量 LZ4」重新编码 → 回填 ramdisk_size →
补齐到原镜像大小 → 重新解析/解码头做自校验（只允许 \`ramdisk_size\` 4 字节变化）。输出应可复现：

\`\`\`
verify      : ramdisk_size=5656983 cpio=5634876 total diff=4
verify      : ko sha256=5d78e567f3c0334373502e19cdd94c9d5f623676231d027ee334c7010db04574
verify      : header byte diffs ['0xc', '0xd', '0xe']
verify      : PASS
\`\`\`

已有产物（本设备当前的补丁镜像 + 预置 ko）：
\`out\\init_boot_a_ksu-appid10626.img\`，8,388,608 B，sha256 \`4e61a259cc8b194401536327e62ff6834f238ed84efb1709cbf592945c0f02d9\`；
预置 ko \`_tmp\\kernelsu_appid10626.ko\`，sha256 \`5d78e567f3c0334373502e19cdd94c9d5f623676231d027ee334c7010db04574\`。

### 3.2 顺带修内置资产（\`--ko-in\` 模式）

\`userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko\` 是被打进 \`libksud.so\` 的内置资产，
**同样是未预置的**：当用户走 app 内「修补/安装」流程（\`ksud boot-patch\` 注入内置 ko）时会重新引入该缺陷。
重新构建 libksud 前先补一次：

\`\`\`powershell
python KernelSU-v330\\_tools\\patch-init-boot.py \\
  --ko-in  KernelSU-v330\\userspace\\ksud\\bin\\aarch64\\android15-6.6_kernelsu.ko \\
  --ko-out <输出.ko> --manager-appid 10626
\`\`\`

### 3.3 刷入与回滚

事前把**当前正在运行的镜像**留在设备上作为回滚镜像（本设备：\`/data/local/tmp/init_boot_a.img\`，
sha256 \`76ee9de4…d255\`，已确认是当前运行的那一份）。两条路径任选：

- fastboot（推荐，会校验分区大小）：\`adb reboot bootloader\` → 确认当前槽位（本设备 \`ro.boot.slot_suffix=_a\`）→
  \`fastboot flash init_boot_a <新镜像>\`（若 bootloader 只暴露 \`init_boot\`，用 \`fastboot flash init_boot <新镜像>\`）→
  \`fastboot reboot\`。
- 已有 root 时直接写分区（无需 fastboot）：\`dd if=/data/local/tmp/init_boot_a_ksu_appid10626.img of=/dev/block/by-name/init_boot_a\`
  （本设备 \`init_boot_a -> /dev/block/sde29\`）。写分区有断电风险，写前确认目标分区号。

回滚：把 \`/data/local/tmp/init_boot_a.img\` 用同样方式写回。

### 3.4 验证

1. 重启后管理器 UI 应显示「工作中/已安装」，不再是「未安装」。
2. \`adb shell /data/local/tmp/ksud-fork debug info\` 的 \`flags\` 应含 MANAGER 位（\`0x3\`）。
3. 管理器内授权任意应用后可用 \`su\`；或先验证冠位：
   \`\`\`sh
   run-as me.weishu.kernelsu.hide id    # 若 APK 未 debuggable 则改用管理器内操作
   \`\`\`
4. 保留一份新镜像的 sha256 与 \`dd\`/fastboot 日志作为证据。

## 4. 更彻底的项目级修法（尚未落地）

预置 appid 是「运行时逃生口」，根治要靠两个编译期事实（原版 tiann/KernelSU 的做法）：

1. 编译 ko 时定义 \`KSU_MANAGER_PACKAGE=me.weishu.kernelsu.hide\`（\`kernel/Kbuild:133-159\` 会转成 ccflag），
   让同证书的其他包名不可能匹配；
2. 换成项目私有 release key 并同步改 \`KSU_EXPECTED_SIZE\`/\`KSU_EXPECTED_HASH\`（原版默认 \`0x033b\`/\`c371061b…\`），
   并保证 release APK 保持 v2-only 且证书 DER 长度与常量精确一致。

两者都需要 DDK 重新编译 ko，本机没有该工具链，故本次以 4 字节预置交付。

## 5. 注意事项（踩过的坑）

- **不要** \`rmmod kernelsu\`：本内核会在卸载时崩溃并自动重启（实测 \`up 0 min, load average: 16.59\`）。
  运行期换 ko 不可行，只能走镜像。
- \`CONFIG_KSU_DEBUG\` 的 sysfs 通道（\`/sys/module/kernelsu/parameters/…\`）在本分支不可用：
  \`kernel/core/init.c:183-186\` 在非 debug 下 \`kobject_del()\` 掉了 \`/sys/module/kernelsu\`。
  也没有任何 ioctl 可以设置 appid（只有 \`KSU_IOCTL_GET_MANAGER_APPID\`，且限 manager/root）。
- 冠位判定不看包名，只看证书（本分支）；因此**任何**用同一 debug key 签名的应用都会竞争。
  交付/测试时不要把这种 APK 与管理器一起装在同一设备上。
- \`ksud boot-patch\` 注入的是 ksud 二进制内嵌的 ko；若用未预置的 ksud 去打补丁，会覆盖掉本次修复。
  用本工具或预置好的镜像。
- 设备内 \`/data/adb/ksu/bin/ksud\` 缺失时，内核 rc 里的 post-fs-data/services/boot-completed 钩子不会执行；
  这不影响本修复（本修复在模块加载时就生效）。
