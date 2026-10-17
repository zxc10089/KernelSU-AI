package me.weishu.kernelsu.ui.viewmodel

import android.os.Build
import android.system.OsConstants
import android.widget.Toast
import androidx.lifecycle.ViewModel
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import java.io.File
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.HidingPackCategory
import me.weishu.kernelsu.data.model.HidingPackEntry
import me.weishu.kernelsu.data.model.HidingPackIds
import me.weishu.kernelsu.data.model.HidingRuntimeMode
import me.weishu.kernelsu.data.repository.HidingPackRepository
import me.weishu.kernelsu.data.repository.HidingPackRepositoryImpl
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.hiding.CleanupPlan
import me.weishu.kernelsu.ui.screen.hiding.HideEnvironmentUiState
import me.weishu.kernelsu.ui.screen.hiding.HidingPackInstallState
import me.weishu.kernelsu.ui.screen.hiding.HidingPackState
import me.weishu.kernelsu.ui.screen.hiding.KeyboxState
import me.weishu.kernelsu.ui.screen.hiding.KeyboxStatus
import me.weishu.kernelsu.ui.screen.hiding.OneKeyHideOptions
import me.weishu.kernelsu.ui.screen.hiding.PathMaskScope
import me.weishu.kernelsu.ui.screen.hiding.PropsOutcome
import me.weishu.kernelsu.ui.screen.hiding.PropsRow
import me.weishu.kernelsu.ui.screen.hiding.SusfsProbe
import me.weishu.kernelsu.ui.screen.hiding.TargetListStatus
import me.weishu.kernelsu.ui.screen.hiding.ZygiskReinforcement
import me.weishu.kernelsu.ui.screen.hiding.branchMatchesKernel
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.getSELinuxStatusRaw
import me.weishu.kernelsu.ui.util.rootAvailable
import org.json.JSONObject
import me.weishu.kernelsu.ui.util.requiresNewKernel

class HideEnvironmentViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl(),
    private val packRepo: HidingPackRepository = HidingPackRepositoryImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(HideEnvironmentUiState())
    val uiState: StateFlow<HideEnvironmentUiState> = _uiState.asStateFlow()

    /**
     * Every install goes through this lock: the root shell is shared and the install contract
     * forbids concurrent installs, so a batch step and a single-row install can never overlap.
     */
    private val installMutex = Mutex()
    private var oneKeyJob: Job? = null
    private var quickJob: Job? = null

    /** 一键隐藏收尾时补 HMA-OSS 配置的结果，决定往步骤日志里追加哪一行（NotApplicable 不记）。 */
    private enum class HmaConfigOutcome { NotApplicable, NotReady, AlreadyConfigured, Synced, Failed }

    private companion object {
        /** HMA-OSS 守护进程运行时目录：名字带随机后缀（hide_my_applist_xxxx），禁止硬编码。 */
        const val HMA_RUNTIME_DIR_GLOB = "/data/misc/hide_my_applist_*"

        /** 随包分发的 HMA-OSS 预置配置（由 _tools\sync-hiding-pack.ps1 复制自资源包 config.json）。 */
        const val HMA_CONFIG_ASSET = "hiding/hma_config.json"

        /**
         * TrickyStore 完整性层。keybox.xml 含明文 EC 私钥：只探测存在性/字节数/sha256 前 8 位，
         * 命令里没有任何读取内容的动作（对照 _tools\hiding-status.ps1 的只读探针）。
         */
        const val KEYBOX_PATH = "/data/adb/tricky_store/keybox.xml"
        const val TARGET_LIST_PATH = "/data/adb/tricky_store/target.txt"

        /** 常驻属性模块的 ksud module id（_tools\hiding-props-module\module.prop）。 */
        const val PROPS_MODULE_ID = "ksu_hide_props"

        /** resetprop 的绝对路径（ksud 多调用符号链接，hiding-lib.ps1 同样用绝对路径）。 */
        const val RESETPROP_PATH = "/data/adb/ksu/bin/resetprop"

        /**
         * PathMask 自己的持久状态：它交给内核的 scope_mode 就来自这里。模块 service.sh 的
         * `seed_config_file` 只在文件缺失/为空时写，所以这个值跨重装存活、每次开机重申。
         * 只在 App 内只读探测：`global` 时模块不查应用名单，本机每个 App 都看不到被遮路径——
         * 而它的目标里 `/system_ext/app/SoterService` 在本机真实存在，于是变成环境异常。
         */
        const val PATHMASK_SCOPE_CONF = "/data/adb/pathmask/scope_mode.conf"

        /** 探针在「目标不存在 / 探测不可用」时回落的哨兵，避免把空输出当成答案。 */
        const val PROBE_SENTINEL = "KSU_HIDE_NA"
        const val PROBE_ABSENT_PREFIX = "ABSENT:"

        /**
         * §4.1 属性洗白表：`NAME=EXPECTED`，来源 `_tools\hiding-props-module\props.txt`（22 条 set）。
         * 只用于比对与展示期望值，App 侧永不写入。
         */
        val PROPS_EXPECTED: List<Pair<String, String>> = listOf(
            "ro.debuggable" to "0",
            "ro.secure" to "1",
            "ro.adb.secure" to "1",
            "ro.build.type" to "user",
            "ro.build.tags" to "release-keys",
            "ro.boot.verifiedbootstate" to "green",
            "vendor.boot.verifiedbootstate" to "green",
            "ro.boot.flash.locked" to "1",
            "ro.boot.vbmeta.device_state" to "locked",
            "vendor.boot.vbmeta.device_state" to "locked",
            "ro.secureboot.lockstate" to "locked",
            "ro.boot.warranty_bit" to "0",
            "ro.oem_unlock_supported" to "0",
            "sys.oem_unlock_allowed" to "0",
            "ro.boot.realme.lockstate" to "1",
            "ro.boot.selinux" to "enforcing",
            "ro.build.selinux" to "1",
            "ro.kernel.qemu" to "0",
            "ro.boot.qemu" to "0",
            "ro.hardware.virtual_device" to "0",
            // 刻意省略报告 §4.1 原表里的 `sys.usb.adb.disabled`（原表为 1）：本机原厂值就是 0，
            // 写它等于偏离原厂值而多留一条痕迹；且该名字在本厂商 USB 栈里的语义未知，若被采信
            // 可能关掉 USB ADB —— 而那是本流水线唯一的控制与恢复通道。隐藏管道只对它只读探查。
            "service.adb.root" to "0",
            "service.adb.tcp.port" to "-1",
        )

        /**
         * §4.1 的删除项（`post-fs-data.sh` 的删除循环）。期望值是「该属性不存在」。
         * 报告里的 `persist.com.luckyzyx.luckytool.*` 是通配模式，resetprop 不接受通配，
         * 只由隐藏管道枚举，App 侧不猜具体名字。
         */
        val PROPS_DELETED: List<String> = listOf(
            "persist.service.adb.enable",
            "persist.service.debuggable",
            "persist.zygote.app_data_isolation",
            "persist.hyperceiler.log.level",
        )

        /** §4.5② 工具缓存（精确包名路径，与 hiding-lib 的 ToolPackages 一致）。 */
        val CLEANUP_TOOL_PACKAGES: List<String> = listOf(
            "bin.mt.plus.canary",
            "com.sevtinge.hyperceiler",
            "com.luckyzyx.luckytool",
        )

        /** §4.5 编排层第 16 步的暂存目录 + 本工具自己的暂存目录。 */
        const val CLEANUP_VIOLET_PATH = "/storage/emulated/0/violet"
        const val CLEANUP_STAGING_PATH = "/data/local/tmp/ksu-hide"
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val selinuxStatus = withContext(Dispatchers.IO) {
                runCatching { getSELinuxStatusRaw() }.getOrDefault("")
            }
            val androidRelease = Build.VERSION.RELEASE.orEmpty()
            val kernelRelease = System.getProperty("os.version").orEmpty()

            // root-only unlock: a self-built APK is never crowned manager by the kernel
            val isFullFeatured = rootAvailable() && (!Natives.isManager || !requiresNewKernel())
            val kernelUapiVersion = Natives.kernelUAPIVersion
            val managerUapiVersion = Natives.managerUAPIVersion
            val isSafeMode = Natives.isSafeMode
            val isLateLoadMode = Natives.isLateLoadMode
            val isLkmMode = repo.isLkmMode()
            // Tri-state, never a bare Boolean: an unanswerable kernel must NOT be treated as GKI.
            // `Natives.isLkmMode` can only answer "yes" from a kernel that really answered, but the
            // GKI branch needs the same proof: without it a device whose kernel side never answered
            // (no KernelSU, uncrowned manager) was labelled "GKI built-in" merely because its
            // release string looks GKI-shaped (D-9). The crowned-kernel check is the same one
            // MainActivity uses before installing anything.
            val kernelAnswered = Natives.isManager && !requiresNewKernel()
            val runtimeMode = when {
                !getKernelVersion().isGKI() -> HidingRuntimeMode.Unknown
                isLkmMode -> HidingRuntimeMode.Lkm
                kernelAnswered -> HidingRuntimeMode.GkiBuiltIn
                else -> HidingRuntimeMode.Unknown
            }

            val selinuxHideStatus = repo.getSelinuxHideStatus()
            val isSelinuxHideEnabled = repo.isSelinuxHideEnabled()
            val kernelUmountStatus = repo.getKernelUmountStatus()
            val isKernelUmountEnabled = repo.isKernelUmountEnabled()
            val suCompatStatus = repo.getSuCompatStatus()
            val isSuEnabled = repo.isSuEnabled()
            val suCompatPersistValue = repo.getSuCompatPersistValue()
            val suCompatMode = if (suCompatPersistValue == 0L) 2 else if (!isSuEnabled) 1 else 0
            val isDefaultUmountModules = repo.isDefaultUmountModules()

            val pack = packRepo.loadManifest().getOrNull()?.takeIf { it.entries.isNotEmpty() }
            // The count on screen and the recommendations must come from the same filtered list.
            val visibleEntries = pack?.entries.orEmpty().filter { it.appliesTo(runtimeMode) }
            val installedIds = if (pack == null) emptySet() else packRepo.installedIds(pack.entries)
            // A module ksud staged in /data/adb/modules_update is present but not live yet, so the
            // row has to read "reboot to take effect" instead of "installed".
            val pendingRebootIds =
                if (pack == null) emptySet() else packRepo.pendingRebootIds(pack.entries)
            val recommendedIds = visibleEntries
                .filter { it.category == HidingPackCategory.PathMask }
                .filter { entry -> entry.branch?.let { branchMatchesKernel(it, kernelRelease) } == true }
                .map { it.id }
                .toSet()

            // Only the manager side is read here, never the pack's own content. The pack always
            // declares `includes.keybox = false`, so the keybox files are outside the pack.
            val integrity = readIntegrityStatus(isFullFeatured, visibleEntries)

            _uiState.update { state ->
                state.copy(
                    isFullFeatured = isFullFeatured,
                    kernelUapiVersion = kernelUapiVersion,
                    managerUapiVersion = managerUapiVersion,
                    isSafeMode = isSafeMode,
                    isLateLoadMode = isLateLoadMode,
                    selinuxStatus = selinuxStatus,
                    androidRelease = androidRelease,
                    kernelRelease = kernelRelease,
                    selinuxHideStatus = selinuxHideStatus,
                    isSelinuxHideEnabled = isSelinuxHideEnabled,
                    kernelUmountStatus = kernelUmountStatus,
                    isKernelUmountEnabled = isKernelUmountEnabled,
                    suCompatStatus = suCompatStatus,
                    suCompatMode = suCompatMode,
                    isSuEnabled = isSuEnabled,
                    isDefaultUmountModules = isDefaultUmountModules,
                    pack = pack,
                    packState = if (pack == null) HidingPackState.Unavailable else HidingPackState.Ready,
                    installStates = pack?.entries.orEmpty().associate { entry ->
                        entry.id to when {
                            entry.id in pendingRebootIds -> HidingPackInstallState.PendingReboot
                            entry.id in installedIds -> HidingPackInstallState.Installed
                            state.installStates[entry.id] == HidingPackInstallState.Installing ->
                                HidingPackInstallState.Installing

                            else -> HidingPackInstallState.NotInstalled
                        }
                    },
                    recommendedIds = recommendedIds,
                    runtimeMode = runtimeMode,
                    keybox = integrity.keybox,
                    targetList = integrity.targetList,
                    susfs = integrity.susfs,
                    pathMaskScope = integrity.pathMaskScope,
                    propsRows = integrity.propsRows,
                    isPropsModuleInstalled = integrity.isPropsModuleInstalled,
                    zygisk = integrity.zygisk,
                    cleanup = state.cleanup.copy(pendingPaths = integrity.cleanupPaths),
                    isResumePending = integrity.isResumePending,
                )
            }
        }
    }

    /** 7 张新状态卡的只读结果；每一格读不到就保持 Unknown / null，绝不用 false 或 0 冒充已知。 */
    private data class IntegrityStatus(
        val keybox: KeyboxStatus,
        val targetList: TargetListStatus,
        val susfs: SusfsProbe,
        val pathMaskScope: PathMaskScope,
        val propsRows: List<PropsRow>,
        val isPropsModuleInstalled: Boolean,
        val zygisk: ZygiskReinforcement,
        val cleanupPaths: List<String>,
        val isResumePending: Boolean,
    )

    /**
     * 只读探测 7 张状态卡的数据源。全程不写入设备、不读取 keybox 内容。
     * [rootUsable] 为 false 时只有 Zygisk 的拒绝列表能回答（在 App 内读包管理器即可判定），
     * 其余一律 Unknown。
     */
    private suspend fun readIntegrityStatus(
        rootUsable: Boolean,
        visibleEntries: List<HidingPackEntry>,
    ): IntegrityStatus = withContext(Dispatchers.IO) {
        val zygiskDenylist = if (!rootUsable) {
            null
        } else {
            // `su -c id` 走 uid=0 才说明拒绝列表生效：root 本身被拒时该行只会回普通 uid。
            runRootCommand(getRootShell(), "su -c id 2>/dev/null | head -n 1")
                ?.let { it.startsWith("uid=0") }
        }
        val zygisk = ZygiskReinforcement(denylistEnforced = zygiskDenylist)

        if (!rootUsable) {
            return@withContext IntegrityStatus(
                keybox = KeyboxStatus(state = KeyboxState.Unknown),
                targetList = TargetListStatus(),
                susfs = SusfsProbe(
                    enabled = null,
                    note = "探测不可用（未取得 root）：状态未知，不猜",
                ),
                pathMaskScope = PathMaskScope.Unknown,
                propsRows = PROPS_EXPECTED.map { (name, expected) ->
                    PropsRow(name = name, expected = expected)
                } + PROPS_DELETED.map { PropsRow(name = it, expected = "") },
                isPropsModuleInstalled = false,
                zygisk = zygisk,
                cleanupPaths = emptyList(),
                isResumePending = false,
            )
        }

        val shell = getRootShell()
        val keybox = readKeyboxStatus(shell)
        val targetList = readTargetListStatus(shell)
        val susfs = readSusfsProbe(shell)
        val pathMaskScope = readPathMaskScope(shell)
        val props = readPropsStatus(shell)
        val cleanupPaths = readCleanupCandidates(shell, visibleEntries)
        val isResumePending = runRootCommand(
            shell,
            "ls -d /data/adb/modules_update/* 2>/dev/null | wc -l",
        )?.trim()?.toIntOrNull()?.let { it > 0 } ?: false

        IntegrityStatus(
            keybox = keybox,
            targetList = targetList,
            susfs = susfs,
            pathMaskScope = pathMaskScope,
            propsRows = props.rows,
            isPropsModuleInstalled = props.moduleInstalled,
            zygisk = zygisk,
            cleanupPaths = cleanupPaths,
            isResumePending = isResumePending,
        )
    }

    /** 一条只读探针：退出码 + 全部输出行。 */
    private data class ProbeResult(val code: Int, val lines: List<String>) {
        val joined: String get() = lines.joinToString("\n")
    }

    private fun probe(shell: Shell, command: String): ProbeResult? = try {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = shell.newJob().add(command).to(out, err).exec()
        ProbeResult(result.code, out.map { it.trim() })
    } catch (_: Throwable) {
        null
    }

    /**
     * keybox 只读探针：`test -s` 存在性 + `wc -c` 字节数 + `sha256sum` 前 8 位。
     * 永远不读内容；命令里没有 cat/head/base64 等任何会打印文件体的动作。
     */
    private fun readKeyboxStatus(shell: Shell): KeyboxStatus {
        val result = probe(
            shell,
            "if test -s $KEYBOX_PATH; then echo \"STATE=present\"; " +
                "echo \"SIZE=\$(wc -c < $KEYBOX_PATH 2>/dev/null || echo 0)\"; " +
                "echo \"SHA=$(sha256sum $KEYBOX_PATH 2>/dev/null | cut -c 1-8)\"; " +
                "else echo \"$PROBE_ABSENT_PREFIX\"; fi",
        ) ?: return KeyboxStatus(state = KeyboxState.Unknown, source = KEYBOX_PATH)
        if (result.code != 0) {
            return KeyboxStatus(state = KeyboxState.Unknown, source = KEYBOX_PATH)
        }
        if (result.joined.contains(PROBE_ABSENT_PREFIX)) {
            return KeyboxStatus(state = KeyboxState.Missing, source = KEYBOX_PATH)
        }
        val size = result.lines.firstOrNull { it.startsWith("SIZE=") }
            ?.removePrefix("SIZE=")?.trim()?.toLongOrNull()
        val sha = result.lines.firstOrNull { it.startsWith("SHA=") }
            ?.removePrefix("SHA=")?.trim().orEmpty()
        val shaOk = sha.length == 8 && sha.all { it in "0123456789abcdefABCDEF" }
        if (!result.joined.contains("STATE=present") || size == null) {
            // 文件在，但连字节数都取不到：存在性与内容都不可断言 ⇒ 校验失败，而不是「已装入」。
            return KeyboxStatus(state = KeyboxState.Invalid, source = KEYBOX_PATH)
        }
        return KeyboxStatus(
            state = if (size > 0L && shaOk) KeyboxState.Installed else KeyboxState.Invalid,
            source = KEYBOX_PATH,
            sizeBytes = size,
            sha256Prefix = if (shaOk) sha.lowercase() else "",
        )
    }

    /** `target.txt`：非空行数即包数量；文件不存在 = 未写入，不拿 0 冒充「已写入 0 条」。 */
    private fun readTargetListStatus(shell: Shell): TargetListStatus {
        val result = probe(
            shell,
            "if test -s $TARGET_LIST_PATH; then echo \"STATE=present\"; " +
                "echo \"LINES=\$(grep -c -v '^[[:space:]]*\$' $TARGET_LIST_PATH 2>/dev/null || echo 0)\"; " +
                "else echo \"$PROBE_ABSENT_PREFIX\"; fi",
        ) ?: return TargetListStatus()
        if (result.code != 0 || result.joined.contains(PROBE_ABSENT_PREFIX)) {
            return TargetListStatus()
        }
        val count = result.lines.firstOrNull { it.startsWith("LINES=") }
            ?.removePrefix("LINES=")?.trim()?.toIntOrNull() ?: return TargetListStatus()
        return TargetListStatus(written = count > 0, packageCount = count)
    }

    /** SUSFS 只读探测：能证明在内核里就回 true，能证明不在就回 false，其余一律 null。 */
    private fun readSusfsProbe(shell: Shell): SusfsProbe {
        val result = probe(
            shell,
            "if test -e /proc/susfs || grep -q -i susfs /proc/kallsyms 2>/dev/null || " +
                "grep -q -i susfs /proc/version 2>/dev/null; then echo DETECTED; else echo NOT_DETECTED; fi",
        )
        if (result == null || result.code != 0) {
            return SusfsProbe(enabled = null, note = "探测不可用：状态未知，不猜")
        }
        // 必须整行相等：探针的两个回答互为子串（"NOT_DETECTED".contains("DETECTED") == true），
        // 用 contains 判定会把「没有 SUSFS」读成「已启用」——失败方向是乐观的，最坏的一种。
        // 先判否定答案，再判肯定答案；两个都没有就保持 Unknown，绝不猜。
        val answers = result.lines.filter { it.isNotEmpty() }
        return when {
            "NOT_DETECTED" in answers ->
                SusfsProbe(enabled = false, note = "未探测到内核侧 SUSFS 痕迹")

            "DETECTED" in answers ->
                SusfsProbe(enabled = true, note = "已探测到内核侧 SUSFS 痕迹")

            else -> SusfsProbe(enabled = null, note = "探测输出无法解析：状态未知，不猜")
        }
    }

    /**
     * PathMask 作用域只读探测。`deny`/`allow` 才是「只对名单里的 App 掩蔽」，`global` 是
     * 「不查名单、每个 App 都掩蔽」——模块自带 WebUI 原文：`Global mode: every app loses sight
     * of the hidden paths; the app list is not consulted.` 读不到、文件不存在、值不认识都回
     * Unknown，绝不用 Scoped 冒充「安全」。
     */
    private fun readPathMaskScope(shell: Shell): PathMaskScope {
        val result = probe(
            shell,
            "if test -s $PATHMASK_SCOPE_CONF; then " +
                "echo \"SCOPE=\$(head -n 1 $PATHMASK_SCOPE_CONF | tr -d '\\r ')\"; " +
                "else echo \"$PROBE_ABSENT_PREFIX\"; fi",
        ) ?: return PathMaskScope.Unknown
        if (result.code != 0 || result.joined.contains(PROBE_ABSENT_PREFIX)) {
            return PathMaskScope.Unknown
        }
        val value = result.lines.firstOrNull { it.startsWith("SCOPE=") }
            ?.removePrefix("SCOPE=")?.trim()?.lowercase()
        return when (value) {
            "global" -> PathMaskScope.Global
            "deny", "allow" -> PathMaskScope.Scoped
            else -> PathMaskScope.Unknown
        }
    }

    /** §4.1 属性表：一次只读 resetprop 查询，逐行比对，App 侧永不写入。 */
    private fun readPropsStatus(shell: Shell): PropsStatus {
        val unknownRows = PROPS_EXPECTED.map { (name, expected) -> PropsRow(name, expected) } +
            PROPS_DELETED.map { PropsRow(it, "") }
        val lookup = PROPS_EXPECTED.map { (name, _) -> name } + PROPS_DELETED
        // resetprop 的转储是 `[name]: [value]`；值里的换行会被转义成字面 `\n`，因此一行一条不会串行。
        // 绝不要用 sed 的 N;$!ba 把整个转储先拼成一行再按行过滤：实测 3480 行的转储会被折叠成
        // 一条，过滤器对任何属性都匹配不上、actual 恒为空，而「读不到」在下面会被渲染成结论。
        // 每个被查名字无条件回一行 `D=`，这样「转储里没有」与「转储根本没回答」不会混为一谈
        // （Android 的 getprop 风格转储对不存在的名字不会输出任何东西）。
        val script = "lookup=\"" + lookup.joinToString(" ") + "\"\n" +
            "( " + RESETPROP_PATH + " 2>/dev/null || resetprop 2>/dev/null ) " +
            "| while IFS= read -r line; do " +
            "name=\${line%%:*}; name=\${name#[}; name=\${name%]}; " +
            "value=\${line#*: }; value=\${value# }; value=\${value#[}; value=\${value%]}; " +
            "case \" \$lookup \" in *\" \$name \"*) echo \"P=\$name=\$value\";; esac; done\n" +
            "for n in \$lookup; do echo \"D=\$n\"; done"
        val result = probe(shell, script)
            ?: return PropsStatus(rows = unknownRows, moduleInstalled = false)
        if (result.code != 0) {
            return PropsStatus(rows = unknownRows, moduleInstalled = false)
        }
        val actual = HashMap<String, String>()
        var dumpAnswered = false
        result.lines.forEach { line ->
            when {
                line.startsWith("P=") -> {
                    dumpAnswered = true
                    val body = line.removePrefix("P=")
                    val eq = body.indexOf('=')
                    if (eq > 0) actual[body.substring(0, eq)] = body.substring(eq + 1)
                }

                line.startsWith("D=") -> dumpAnswered = true
            }
        }
        // 模块是否安装必须先问：只有模块真的在跑，才能把「值已经是目标值」记成是我们做的。
        val moduleList = runRootCommand(shell, "ksud module list 2>/dev/null").orEmpty()
        val moduleInstalled = moduleList.contains("\"id\":\"$PROPS_MODULE_ID\"") ||
            moduleList.contains("\"id\": \"$PROPS_MODULE_ID\"")
        val rows = if (!dumpAnswered) {
            // 属性转储本身没回答（resetprop 缺失/被拒）：所有行的结果必须停在「未知」。
            unknownRows
        } else {
            // 设值项：resetprop 写入后属性依然留在转储里，所以「转储里没有它」只说明它压根不存在，
            // 绝不能读成「已生效」。目标值没落实就是 Pending；落实了也要看是谁落实的。
            PROPS_EXPECTED.map { (name, expected) ->
                val current = actual[name]
                PropsRow(
                    name = name,
                    expected = expected,
                    actual = current.orEmpty(),
                    outcome = when {
                        current == null -> PropsOutcome.Pending
                        current != expected -> PropsOutcome.Pending
                        moduleInstalled -> PropsOutcome.Applied
                        else -> PropsOutcome.Same
                    },
                )
            } + PROPS_DELETED.map { name ->
                val current = actual[name]
                // 删除项：转储里还有 = 尚未清理；没有 = 目标状态已达成。
                PropsRow(
                    name = name,
                    expected = "",
                    actual = current.orEmpty(),
                    outcome = when {
                        current != null -> PropsOutcome.Pending
                        moduleInstalled -> PropsOutcome.Applied
                        else -> PropsOutcome.Same
                    },
                )
            }
        }
        return PropsStatus(rows = rows, moduleInstalled = moduleInstalled)
    }

    private data class PropsStatus(val rows: List<PropsRow>, val moduleInstalled: Boolean)

    /** §4.5 安全子集：只收集「此刻真的存在」的候选路径，不存在的不进清单。 */
    private fun readCleanupCandidates(
        shell: Shell,
        visibleEntries: List<HidingPackEntry>,
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        visibleEntries
            .filter { it.category == HidingPackCategory.Detector }
            .mapNotNull { it.packageName?.takeIf { pkg -> pkg.isNotBlank() } }
            .forEach { candidates += "/storage/emulated/0/Android/data/$it" }
        CLEANUP_TOOL_PACKAGES.forEach { candidates += "/storage/emulated/0/Android/data/$it" }
        candidates += CLEANUP_VIOLET_PATH
        candidates += CLEANUP_STAGING_PATH
        val script = candidates.joinToString("\n") { "test -e \"$it\" && echo \"E=$it\"" }
        val result = probe(shell, script) ?: return emptyList()
        if (result.code != 0) return emptyList()
        return result.lines.filter { it.startsWith("E=") }.map { it.removePrefix("E=") }
    }

    /** keybox 由本地终端 / 自备文件装入：App 内没有文件选择器与私钥处理路径，不伪造成功。 */
    fun installKeybox() {
        showNotImplementedToast(R.string.hide_environment_keybox_title)
    }

    /** target.txt 的原子装入由隐藏管道负责（hiding-onekey.ps1），App 侧不写结构化目标列表。 */
    fun writeTargetList() {
        showNotImplementedToast(R.string.hide_environment_target_title)
    }

    /** 属性洗白必须在启动期完成；App 运行中写入会留下不一致状态，因此只比对不落地。 */
    fun applyPropsOnce() {
        showNotImplementedToast(R.string.hide_environment_props_title)
    }

    fun installPropsModule() {
        showNotImplementedToast(R.string.hide_environment_props_module)
    }

    /** 清痕是可恢复性最低的一步：保留给隐藏管道，App 只展示「此刻存在哪些待清路径」。 */
    fun runCleanup() {
        showNotImplementedToast(R.string.hide_environment_cleanup_title)
    }

    fun resumeOneKey() {
        viewModelScope.launch {
            refresh()
        }
    }

    /** 尚未实现的动作统一只提示卡片标题，绝不假装成功。 */
    private fun showNotImplementedToast(titleRes: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(ksuApp, titleRes, Toast.LENGTH_SHORT).show()
        }
    }

    fun setSelinuxHideEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val status = repo.setSelinuxHideEnabled(enabled)
            repo.execKsudFeatureSave()
            _uiState.update { it.copy(isSelinuxHideEnabled = enabled) }
            when (status) {
                0 -> Unit
                -OsConstants.EAGAIN -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            ksuApp, R.string.settings_selinux_hide_reboot_required,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                else -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            ksuApp, ksuApp.getString(R.string.settings_selinux_hide_failed, status),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    fun setKernelUmountEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setKernelUmountEnabled(enabled)) {
                repo.execKsudFeatureSave()
                _uiState.update { it.copy(isKernelUmountEnabled = enabled) }
            }
        }
    }

    fun setSuCompatMode(mode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            when (mode) {
                0 -> if (repo.setSuEnabled(true)) {
                    repo.execKsudFeatureSave()
                    repo.setSuCompatModePref(0)
                    _uiState.update { it.copy(suCompatMode = 0, isSuEnabled = true) }
                }

                1 -> if (repo.setSuEnabled(true)) {
                    repo.execKsudFeatureSave()
                    if (repo.setSuEnabled(false)) {
                        repo.setSuCompatModePref(0)
                        _uiState.update { it.copy(suCompatMode = 1, isSuEnabled = false) }
                    }
                }

                2 -> if (repo.setSuEnabled(false)) {
                    repo.execKsudFeatureSave()
                    repo.setSuCompatModePref(2)
                    _uiState.update { it.copy(suCompatMode = 2, isSuEnabled = false) }
                }
            }
        }
    }

    fun setDefaultUmountModules(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setDefaultUmountModules(enabled)) {
                _uiState.update { it.copy(isDefaultUmountModules = enabled) }
            }
        }
    }

    fun installPackEntry(entry: HidingPackEntry) {
        val state = _uiState.value
        if (state.installStateOf(entry) == HidingPackInstallState.Installing) return
        // A batch owns the root shell: queueing another install behind it would only confuse the
        // progress the user is watching.
        if (state.isBatchRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            // installOne() takes installMutex itself: locking here as well deadlocked this coroutine
            // against its own frame (Mutex is not reentrant), so a single-row install stayed in
            // "installing" forever and ksud was never invoked.
            val result = installOne(entry)
            if (result.isSuccess) {
                withContext(Dispatchers.Main) {
                    if (entry.isModuleArchive) {
                        Toast.makeText(
                            ksuApp, R.string.hide_environment_pack_reboot_hint, Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ksuApp, R.string.hide_environment_install_failed, Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    fun setOneKeyLsposed(enabled: Boolean) {
        if (enabled && !_uiState.value.isLsposedOptionAvailable) return
        if (_uiState.value.isBatchRunning) return
        _uiState.update { it.copy(oneKeyOptions = it.oneKeyOptions.copy(installLsposed = enabled)) }
    }

    fun setOneKeyBrickRescue(enabled: Boolean) {
        if (_uiState.value.isBatchRunning) return
        _uiState.update { it.copy(oneKeyOptions = it.oneKeyOptions.copy(addBrickRescue = enabled)) }
    }

    /**
     * One-tap hide: the frozen set is built first (base modules + exactly one matching PathMask
     * branch + the optional extras, deduped by moduleId and sorted by `order`), then every step
     * runs one after another and is reported as it finishes.
     */
    fun startOneKeyHide() {
        val state = _uiState.value
        if (state.isBatchRunning || state.packState != HidingPackState.Ready) return
        if (oneKeyJob?.isActive == true || quickJob?.isActive == true) return
        val plan = buildOneKeyPlan(state.visibleEntries, state.recommendedIds, state.oneKeyOptions)
        oneKeyJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isOneKeyRunning = true, oneKeyLog = emptyList())
            }
            if (plan.none { it.category == HidingPackCategory.PathMask }) {
                appendOneKeyLog(ksuApp.getString(R.string.hide_environment_onekey_no_branch))
            }
            // Ask the daemon what is on the device right now instead of trusting a UI snapshot that
            // may be minutes old: an entry already present at the packed versionCode needs no
            // action, so it is skipped. Skipping the metamodule in particular is what keeps ksud's
            // metamodule guard from refusing every later step of the batch.
            val installedNow = packRepo.installedIds(plan)
            val rebootHint = ksuApp.getString(R.string.hide_environment_onekey_reboot_hint)
            var blockedByMetamodule = false
            plan.forEachIndexed { index, entry ->
                when {
                    // Installing the pack's only metamodule drops the "pending update" marker that
                    // makes ksud refuse every later non-metamodule install until a reboot, so those
                    // steps are reported with that cause instead of being burnt against the guard.
                    blockedByMetamodule -> appendOneKeyLog(
                        stepLine(
                            index + 1, plan.size, entry,
                            ksuApp.getString(R.string.hide_environment_step_failed, rebootHint),
                        )
                    )

                    entry.id in installedNow -> appendOneKeyLog(
                        stepLine(
                            index + 1, plan.size, entry,
                            ksuApp.getString(R.string.hide_environment_pack_installed),
                        )
                    )

                    else -> {
                        val step = runInstallStep(index + 1, plan.size, entry)
                        appendOneKeyLog(step.line)
                        if (step.installed && entry.id == HidingPackIds.HYBRID_MOUNT) {
                            blockedByMetamodule = true
                        }
                    }
                }
            }
            // HMA-OSS 的配置不在任何模块条目里，装完是空配置；收尾时按需补上预置配置。
            when (syncHmaOssConfig()) {
                HmaConfigOutcome.Synced ->
                    appendOneKeyLog(ksuApp.getString(R.string.hide_environment_hma_config_synced))

                HmaConfigOutcome.AlreadyConfigured ->
                    appendOneKeyLog(ksuApp.getString(R.string.hide_environment_hma_config_skipped))

                HmaConfigOutcome.Failed ->
                    appendOneKeyLog(ksuApp.getString(R.string.hide_environment_hma_config_failed))

                HmaConfigOutcome.NotReady ->
                    appendOneKeyLog(ksuApp.getString(R.string.hide_environment_hma_config_not_ready))

                HmaConfigOutcome.NotApplicable -> Unit
            }
            appendOneKeyLog(rebootHint)
            _uiState.update { it.copy(isOneKeyRunning = false) }
            // Leave the card agreeing with what the batch just did (and with the daemon).
            refresh()
        }
    }

    fun toggleQuickEntry(id: String) {
        _uiState.update { state ->
            if (state.isBatchRunning) {
                state
            } else {
                state.copy(
                    quickSelectedIds = if (id in state.quickSelectedIds) {
                        state.quickSelectedIds - id
                    } else {
                        state.quickSelectedIds + id
                    },
                )
            }
        }
    }

    /** Quick install: the selected, not-yet-installed detectors, strictly one at a time. */
    fun startQuickInstall() {
        val state = _uiState.value
        if (state.isBatchRunning || state.packState != HidingPackState.Ready) return
        if (oneKeyJob?.isActive == true || quickJob?.isActive == true) return
        val targets = state.quickEntries.filter {
            it.id in state.quickSelectedIds && state.installStateOf(it) != HidingPackInstallState.Installed
        }
        if (targets.isEmpty()) return
        quickJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isQuickRunning = true, quickLog = emptyList()) }
            targets.forEachIndexed { index, entry ->
                appendQuickLog(runInstallStep(index + 1, targets.size, entry).line)
            }
            _uiState.update { it.copy(isQuickRunning = false) }
        }
    }

    /**
     * One executed step: its log line plus whether the install really landed. The batch needs that
     * fact to decide whether the metamodule just closed the door on the remaining steps.
     */
    private data class OneKeyStep(val line: String, val installed: Boolean)

    /**
     * Installs one entry and moves its row to the matching terminal state. The next step can only
     * start once this returns, which is what keeps the batch serial.
     */
    private suspend fun runInstallStep(
        index: Int,
        total: Int,
        entry: HidingPackEntry,
    ): OneKeyStep {
        val result = installOne(entry)
        val cause = result.exceptionOrNull()
        val outcome = if (result.isSuccess) {
            ksuApp.getString(R.string.hide_environment_step_ok)
        } else {
            ksuApp.getString(
                R.string.hide_environment_step_failed,
                cause?.message?.takeIf { it.isNotBlank() } ?: cause?.javaClass?.simpleName.orEmpty(),
            )
        }
        return OneKeyStep(stepLine(index, total, entry, outcome), result.isSuccess)
    }

    /** The frozen step-log line: `第 n/m 步 · <name> · <outcome>`. */
    private fun stepLine(index: Int, total: Int, entry: HidingPackEntry, outcome: String): String =
        ksuApp.getString(R.string.hide_environment_step, index, total, entry.name, outcome)

    private suspend fun installOne(entry: HidingPackEntry): Result<Unit> {
        updateInstallState(entry.id, HidingPackInstallState.Installing)
        val result = installMutex.withLock { packRepo.install(entry) }
        updateInstallState(
            entry.id,
            when {
                !result.isSuccess -> HidingPackInstallState.Failed
                // ksud unpacks modules into /data/adb/modules_update, so the running system has not
                // loaded them yet; a detector is an APK and is live as soon as pm returns.
                entry.isModuleArchive -> HidingPackInstallState.PendingReboot
                else -> HidingPackInstallState.Installed
            },
        )
        return result
    }

    /**
     * 把随包分发的 HMA-OSS 配置补进守护进程的运行时目录。HMA-OSS 的配置不由任何模块条目携带，
     * 装完默认是空配置（「有壳无配置」），这里只补空配置，绝不覆盖用户已有配置、也不生成任何内容。
     *
     * - 运行时目录不存在（守护进程还没跑过）⇒ [HmaConfigOutcome.NotApplicable]，不写日志。
     * - 现存配置读不到 / 不是 JSON ⇒ [HmaConfigOutcome.NotReady]（不猜、不覆盖，留给下一次）。
     * - 现存配置里 templates/scope 有内容 ⇒ [HmaConfigOutcome.AlreadyConfigured]。
     * - 空配置才写：root `cp` + `wc -c` 读回校验，最多 3 次（实测该目录读写会被 SELinux 偶发拒绝）。
     */
    private suspend fun syncHmaOssConfig(): HmaConfigOutcome = withContext(Dispatchers.IO) {
        val shell = getRootShell()
        val dir = runRootCommand(shell, "ls -d $HMA_RUNTIME_DIR_GLOB 2>/dev/null | head -n 1")
            ?.trim()
            .orEmpty()
        if (dir.isEmpty()) return@withContext HmaConfigOutcome.NotApplicable

        val dst = dir.trimEnd('/') + "/config.json"
        val current = runRootCommand(shell, "cat $dst 2>/dev/null")?.trim().orEmpty()
        if (current.isEmpty()) return@withContext HmaConfigOutcome.NotReady
        val alreadyConfigured = try {
            val json = JSONObject(current)
            (json.optJSONObject("templates")?.length() ?: 0) > 0 ||
                (json.optJSONObject("scope")?.length() ?: 0) > 0
        } catch (_: Throwable) {
            // 读到的内容不是我们认识的结构：当作「已有配置」处理，宁可不写也不覆盖。
            true
        }
        if (alreadyConfigured) return@withContext HmaConfigOutcome.AlreadyConfigured

        val payload = try {
            ksuApp.assets.open(HMA_CONFIG_ASSET).use { it.readBytes() }
        } catch (_: Throwable) {
            return@withContext HmaConfigOutcome.Failed
        }

        val tmp = File(ksuApp.cacheDir, "hma_config.json")
        var written = false
        try {
            tmp.writeBytes(payload)
            for (attempt in 1..3) {
                val copied = ShellUtils.fastCmdResult(shell, "cp ${tmp.absolutePath} $dst")
                val size = runRootCommand(shell, "wc -c < $dst 2>/dev/null")?.trim()?.toLongOrNull()
                if (copied && size == payload.size.toLong()) {
                    written = true
                    break
                }
                if (attempt < 3) delay(500)
            }
        } catch (_: Throwable) {
            // 落盘异常按失败记账，不假装成功。
        } finally {
            tmp.delete()
        }
        if (written) HmaConfigOutcome.Synced else HmaConfigOutcome.Failed
    }

    /** 跑一条 root 命令取输出：libsu 的 `fastCmd` 只回首行且失败抛异常，这里统一吞掉异常返回 null。 */
    private fun runRootCommand(shell: Shell, command: String): String? = try {
        ShellUtils.fastCmd(shell, command)
    } catch (_: Throwable) {
        null
    }

    private fun appendOneKeyLog(line: String) {
        _uiState.update { it.copy(oneKeyLog = it.oneKeyLog + line) }
    }

    private fun appendQuickLog(line: String) {
        _uiState.update { it.copy(quickLog = it.quickLog + line) }
    }

    private fun updateInstallState(id: String, state: HidingPackInstallState) {
        _uiState.update { it.copy(installStates = it.installStates + (id to state)) }
    }

    /**
     * The frozen one-tap-hide set, in the frozen order:
     * base modules -> exactly one recommended PathMask branch -> optional LSPosed -> optional
     * brick-rescue module; deduped by moduleId (smallest `order` wins) and sorted by `order`.
     */
    private fun buildOneKeyPlan(
        visibleEntries: List<HidingPackEntry>,
        recommendedIds: Set<String>,
        options: OneKeyHideOptions,
    ): List<HidingPackEntry> {
        val picked = mutableListOf<HidingPackEntry>()
        picked += visibleEntries.filter {
            it.category == HidingPackCategory.Module && it.id in HidingPackIds.oneKeyBase
        }
        // Exactly one variant: the six branches share moduleId and overwrite each other.
        visibleEntries
            .filter { it.category == HidingPackCategory.PathMask && it.id in recommendedIds }
            .minWithOrNull(installOrder)
            ?.let { picked += it }
        if (options.installLsposed && picked.any { it.id == HidingPackIds.ZYGISKSU }) {
            visibleEntries
                .firstOrNull { it.category == HidingPackCategory.Module && it.id == HidingPackIds.ZYGISK_LSPOSED }
                ?.let { picked += it }
        }
        if (options.addBrickRescue) {
            visibleEntries
                .firstOrNull { it.category == HidingPackCategory.Module && it.id == HidingPackIds.BRICK_RESCUE }
                ?.let { picked += it }
        }
        return picked
            .groupBy { it.moduleId ?: it.id }
            .mapNotNull { (_, sameModule) -> sameModule.minWithOrNull(installOrder) }
            .sortedWith(installOrder)
    }

    private val installOrder = compareBy<HidingPackEntry>({ it.order }, { it.id })
}
