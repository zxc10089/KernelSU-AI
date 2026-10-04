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
    Installed,
    Failed,
}

/** The two optional extra modules of the one-tap hide flow. */
@Immutable
data class OneKeyHideOptions(
    val installLsposed: Boolean = false,
    val addBrickRescue: Boolean = false,
)

@Immutable
data class HideEnvironmentUiState(
    val isFullFeatured: Boolean = false,

    // Environment status (read only)
    val kernelUapiVersion: Int = 0,
    val managerUapiVersion: Int = 0,
    val isSafeMode: Boolean = false,
    val isLkmMode: Boolean = false,
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
)
