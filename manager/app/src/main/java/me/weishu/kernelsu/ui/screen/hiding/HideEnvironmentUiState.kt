package me.weishu.kernelsu.ui.screen.hiding

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.model.HidingPack
import me.weishu.kernelsu.data.model.HidingPackCategory
import me.weishu.kernelsu.data.model.HidingPackEntry
import me.weishu.kernelsu.data.model.HidingPackIds
import me.weishu.kernelsu.data.model.HidingRuntimeMode

private val KERNEL_VERSION_REGEX = Regex("""\d+\.\d+(?:\.\d+)?""")
private val PATH_MASK_BRANCH_REGEX = Regex("""\d+\.\d+""")

/**
 * A pathmask branch looks like "android16-6.12"; the running kernel release looks like
 * "6.12.69-android16-...". Compare only the version segment so 6.12 never matches 6.120.
 *
 * Shared by the recommendation set (view model) and by [HideEnvironmentUiState.bundleEntries] so
 * the pack card and the one-tap plan can never disagree about which branch belongs to this kernel.
 */
internal fun branchMatchesKernel(branch: String, kernelRelease: String): Boolean {
    val expected = branch.substringAfterLast('-').trim()
    if (!PATH_MASK_BRANCH_REGEX.matches(expected)) return false
    val actual = KERNEL_VERSION_REGEX.find(kernelRelease)?.value ?: return false
    return actual == expected || actual.startsWith("$expected.")
}

enum class HidingPackState {
    Loading,
    Ready,
    Unavailable,
}

enum class HidingPackInstallState {
    NotInstalled,
    Installing,

    /**
     * A module archive sits in `/data/adb/modules_update` and only becomes active after a reboot,
     * so the row must not claim "installed" while the running system has not loaded it yet.
     */
    PendingReboot,
    Installed,
    Failed,
}

/** The two optional extra modules of the one-tap hide flow. */
@Immutable
data class OneKeyHideOptions(
    val installLsposed: Boolean = false,
    val addBrickRescue: Boolean = false,
)

/**
 * Integrity layer of TrickyStore, read from `/data/adb/tricky_store/keybox.xml`.
 *
 * [Unknown] is the only honest answer while root is unavailable — it must never be collapsed into
 * [Missing], because "not readable" and "not installed" lead to different user actions.
 */
enum class KeyboxState {
    Unknown,
    Missing,
    Installed,
    Invalid,
}

/**
 * Read-only summary of the keybox file. The manager probes existence, byte size and the first eight
 * hex digits of the SHA-256 only; the file body (a plaintext EC private key) is never read, logged
 * or rendered.
 */
@Immutable
data class KeyboxStatus(
    val state: KeyboxState = KeyboxState.Unknown,
    val source: String = "",
    val sizeBytes: Long = 0L,
    val sha256Prefix: String = "",
    val time: String = "",
)

/** `/data/adb/tricky_store/target.txt`: whether it holds a non-empty list, and how many packages. */
@Immutable
data class TargetListStatus(
    val written: Boolean = false,
    val packageCount: Int = 0,
)

/**
 * SUSFS probe result. [enabled] is deliberately nullable: `null` means "not detected", which is not
 * the same claim as `false` ("detected and disabled").
 */
@Immutable
data class SusfsProbe(
    val enabled: Boolean? = null,
    val note: String = "",
)

/**
 * Outcome of one property-laundering row. [Unknown] means the probe could not answer;
 * [Pending] means the probe did answer and the target value is not in place yet (the property
 * is absent, or the device still holds its original value). [Applied] is reserved for rows
 * where the laundering module is actually installed, so a row can never read as "we applied
 * this" without that evidence.
 */
enum class PropsOutcome {
    Unknown,
    Same,
    Applied,
    Pending,
    Failed,
}

/**
 * One system property of the §4.1 laundering table: [name] is `resetprop`-written to [expected], and
 * [actual] is what the device reports. The row is compared, never written, from the manager.
 */
@Immutable
data class PropsRow(
    val name: String,
    val expected: String,
    val actual: String = "",
    val outcome: PropsOutcome = PropsOutcome.Unknown,
)

/** Zygisk hardening flags. Each flag is tri-state; `null` must render as an explicit "unknown". */
@Immutable
data class ZygiskReinforcement(
    val denylistEnforced: Boolean? = null,
    val memoryIsolation: Boolean? = null,
    val linkerBlocked: Boolean? = null,
)

/** Safe-subset trace cleanup: the paths that exist right now, plus the run log. */
@Immutable
data class CleanupPlan(
    val pendingPaths: List<String> = emptyList(),
    val log: List<String> = emptyList(),
    val isRunning: Boolean = false,
)

@Immutable
data class HideEnvironmentUiState(
    val isFullFeatured: Boolean = false,

    // Environment status (read only)
    val kernelUapiVersion: Int = 0,
    val managerUapiVersion: Int = 0,
    val isSafeMode: Boolean = false,
    val isLateLoadMode: Boolean = false,
    val selinuxStatus: String = "",
    val androidRelease: String = "",
    val kernelRelease: String = "",

    // Hiding switches
    val selinuxHideStatus: String = "",
    val isSelinuxHideEnabled: Boolean = false,
    val kernelUmountStatus: String = "",
    val isKernelUmountEnabled: Boolean = false,
    val suCompatStatus: String = "",
    val suCompatMode: Int = 0,
    val isSuEnabled: Boolean = false,
    val isDefaultUmountModules: Boolean = false,

    // Hiding resource pack
    val pack: HidingPack? = null,
    val packState: HidingPackState = HidingPackState.Loading,
    val installStates: Map<String, HidingPackInstallState> = emptyMap(),
    val recommendedIds: Set<String> = emptySet(),
    /** Tri-state kernel mode the pack is filtered against; [HidingRuntimeMode.Unknown] filters nothing. */
    val runtimeMode: HidingRuntimeMode = HidingRuntimeMode.Unknown,

    // One-tap hide
    val isOneKeyRunning: Boolean = false,
    val oneKeyOptions: OneKeyHideOptions = OneKeyHideOptions(),
    val oneKeyLog: List<String> = emptyList(),

    // Quick install (detectors)
    val isQuickRunning: Boolean = false,
    val quickSelectedIds: Set<String> = emptySet(),
    val quickLog: List<String> = emptyList(),

    // Integrity layer (TrickyStore keybox / target list). Read only, contents never touched.
    val keybox: KeyboxStatus = KeyboxStatus(),
    val targetList: TargetListStatus = TargetListStatus(),

    // SUSFS probe (read only)
    val susfs: SusfsProbe = SusfsProbe(),

    // Property laundering (§4.1). Rows are compared; nothing is written from the app.
    val propsRows: List<PropsRow> = emptyList(),
    val isPropsRunning: Boolean = false,
    val isPropsModuleInstalled: Boolean = false,

    // Zygisk hardening (read only, tri-state per flag)
    val zygisk: ZygiskReinforcement = ZygiskReinforcement(),

    // Trace cleanup (safe subset)
    val cleanup: CleanupPlan = CleanupPlan(),

    /** True while the boot-time one-key plan is still pending, i.e. the resume card is shown. */
    val isResumePending: Boolean = false,
) {
    /** Every entry of the pack, no matter whether it applies to this runtime mode. */
    val packEntries: List<HidingPackEntry>
        get() = pack?.entries.orEmpty()

    /**
     * What the page is allowed to show and count. Filtering is a no-op on
     * [HidingRuntimeMode.Unknown], and the count shown on screen must come from this same list.
     */
    val visibleEntries: List<HidingPackEntry>
        get() = packEntries.filter { it.appliesTo(runtimeMode) }

    /** Visible entries of category `detector`, i.e. what the quick-install card offers. */
    val quickEntries: List<HidingPackEntry>
        get() = visibleEntries.filter { it.category == HidingPackCategory.Detector }

    /**
     * What the "hiding resource pack" card renders:
     *  * the detectors are gone (they are offered by the quick-install card only);
     *  * the PathMask variants are collapsed to the one that matches the running kernel.
     *
     * If no variant matches — unparsable or unknown kernel release — every variant stays visible:
     * an unanswerable kernel must never turn the section into a dead end. The six variants all stay
     * in the pack either way, other KMI devices need them.
     */
    val bundleEntries: List<HidingPackEntry>
        get() {
            val candidates = visibleEntries.filter { it.category != HidingPackCategory.Detector }
            val pathMask = candidates.filter { it.category == HidingPackCategory.PathMask }
            val matched = pathMask
                .filter { entry -> entry.branch?.let { branchMatchesKernel(it, kernelRelease) } == true }
                .map { it.id }
                .toSet()
            val shownPathMask = if (matched.isEmpty()) pathMask.map { it.id }.toSet() else matched
            return candidates.filter {
                it.category != HidingPackCategory.PathMask || it.id in shownPathMask
            }
        }

    /**
     * True when at least one PathMask variant declares this kernel's branch.
     *
     * [bundleEntries] deliberately falls back to showing every variant when nothing matches, so the
     * page needs this flag to explain why six lookalike archives appeared instead of one.
     */
    val pathMaskMatched: Boolean
        get() = visibleEntries.any {
            it.category == HidingPackCategory.PathMask &&
                it.branch?.let { branch -> branchMatchesKernel(branch, kernelRelease) } == true
        }

    /** The extra LSPosed module is only meaningful while Zygisk (zygisksu) is in the base set. */
    val isLsposedOptionAvailable: Boolean
        get() = visibleEntries.any {
            it.category == HidingPackCategory.Module && it.id == HidingPackIds.ZYGISKSU
        }

    /** A batch run disables the start buttons and the option checkboxes; installs are serialized. */
    val isBatchRunning: Boolean
        get() = isOneKeyRunning || isQuickRunning

    /** Value substituted into the frozen "已按 %1$s 模式过滤" literal. */
    val runtimeModeLabel: String
        get() = runtimeMode.label

    fun installStateOf(entry: HidingPackEntry): HidingPackInstallState {
        return installStates[entry.id] ?: HidingPackInstallState.NotInstalled
    }

    fun isRecommended(entry: HidingPackEntry): Boolean = entry.id in recommendedIds
}

@Immutable
data class HideEnvironmentActions(
    val onRefresh: () -> Unit,
    val onSetSelinuxHideEnabled: (Boolean) -> Unit,
    val onSetKernelUmountEnabled: (Boolean) -> Unit,
    val onSetSuCompatMode: (Int) -> Unit,
    val onSetDefaultUmountModules: (Boolean) -> Unit,
    val onInstallPackEntry: (HidingPackEntry) -> Unit,
    val onSetOneKeyLsposed: (Boolean) -> Unit,
    val onSetOneKeyBrickRescue: (Boolean) -> Unit,
    val onStartOneKeyHide: () -> Unit,
    val onToggleQuickEntry: (String) -> Unit,
    val onStartQuickInstall: () -> Unit,

    /**
     * Install the keybox from a user-picked local file. The manager cannot do this on its own: the
     * keybox holds a plaintext EC private key and must come from the local terminal / a user file,
     * so the button stays disabled until such a picker exists.
     */
    val onInstallKeybox: () -> Unit = {},
    val onWriteTargetList: () -> Unit = {},
    val onApplyPropsOnce: () -> Unit = {},
    val onInstallPropsModule: () -> Unit = {},
    val onRunCleanup: () -> Unit = {},
    val onResumeOneKey: () -> Unit = {},
)
