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
import me.weishu.kernelsu.ui.screen.hiding.HideEnvironmentUiState
import me.weishu.kernelsu.ui.screen.hiding.HidingPackInstallState
import me.weishu.kernelsu.ui.screen.hiding.HidingPackState
import me.weishu.kernelsu.ui.screen.hiding.OneKeyHideOptions
import me.weishu.kernelsu.ui.screen.hiding.branchMatchesKernel
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.getSELinuxStatusRaw
import me.weishu.kernelsu.ui.util.rootAvailable
import org.json.JSONObject

class HideEnvironmentViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl(),
    private val packRepo: HidingPackRepository = HidingPackRepositoryImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(HideEnvironmentUiState())
    val uiState: StateFlow<HideEnvironmentUiState> = _uiState.asStateFlow()

    /**
     * Every install goes through this lock: the root shell is shared and the frozen contract
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
            val isFullFeatured = rootAvailable() && (!Natives.isManager || !Natives.requireNewKernel())
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
            val kernelAnswered = Natives.isManager && !Natives.requireNewKernel()
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
            val recommendedIds = visibleEntries
                .filter { it.category == HidingPackCategory.PathMask }
                .filter { entry -> entry.branch?.let { branchMatchesKernel(it, kernelRelease) } == true }
                .map { it.id }
                .toSet()

            _uiState.update { state ->
                state.copy(
                    isFullFeatured = isFullFeatured,
                    kernelUapiVersion = kernelUapiVersion,
                    managerUapiVersion = managerUapiVersion,
                    isSafeMode = isSafeMode,
                    isLkmMode = isLkmMode,
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
                            entry.id in installedIds -> HidingPackInstallState.Installed
                            state.installStates[entry.id] == HidingPackInstallState.Installing ->
                                HidingPackInstallState.Installing

                            else -> HidingPackInstallState.NotInstalled
                        }
                    },
                    recommendedIds = recommendedIds,
                    runtimeMode = runtimeMode,
                )
            }
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
            if (result.isSuccess) HidingPackInstallState.Installed else HidingPackInstallState.Failed,
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
