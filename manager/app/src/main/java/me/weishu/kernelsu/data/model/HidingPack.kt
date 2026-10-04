package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable

/**
 * Category of an entry inside the built-in hiding resource pack.
 * Mirrors the frozen manifest schema: "module" / "pathmask" / "detector" / "data".
 */
enum class HidingPackCategory(val value: String) {
    Module("module"),
    PathMask("pathmask"),
    Detector("detector"),
    Data("data"),
    ;

    companion object {
        fun fromValue(value: String): HidingPackCategory {
            return entries.firstOrNull { it.value == value } ?: Data
        }
    }
}

/**
 * One value of the frozen manifest `modes` array. An entry whose [HidingPackEntry.modes] is
 * empty is valid on every runtime mode.
 */
enum class HidingPackMode(val value: String) {
    Lkm("lkm"),
    Gki("gki"),
    ;

    companion object {
        fun fromValue(value: String): HidingPackMode? =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
    }
}

/**
 * What the running kernel is, i.e. what the pack entries are filtered against:
 * `!isGKI()` -> [Unknown], else [Lkm] when `Natives.isLkmMode`, else [GkiBuiltIn].
 *
 * [Unknown] means the kernel cannot answer, so filtering MUST be a no-op and the page MUST say
 * so. Never collapse this into a bare Boolean defaulting to false: that would offer GKI-only
 * entries to LKM users.
 */
enum class HidingRuntimeMode {
    Lkm,
    GkiBuiltIn,
    Unknown,
    ;

    /** The pack mode this runtime accepts; null for [Unknown], i.e. "accept everything". */
    val packMode: HidingPackMode?
        get() = when (this) {
            Lkm -> HidingPackMode.Lkm
            GkiBuiltIn -> HidingPackMode.Gki
            Unknown -> null
        }

    /** Substituted into the frozen "已按 %1$s 模式过滤" literal. */
    val label: String
        get() = when (this) {
            Lkm -> "LKM"
            GkiBuiltIn -> "GKI"
            Unknown -> ""
        }
}

/**
 * Ids the frozen one-tap-hide contract names, kept in one place so view model, repository and UI
 * cannot drift apart.
 */
object HidingPackIds {
    const val ZYGISKSU = "zygisksu"
    const val ZYGISK_LSPOSED = "zygisk_lsposed"
    const val BRICK_RESCUE = "Automatic_brick_rescue"

    /**
     * Mount backend (module.prop declares `metamodule=1`). The contract puts it first: every other
     * module is installed *through* it, so it must never be reordered behind them (`order = 5`).
     */
    const val HYBRID_MOUNT = "hybrid_mount"

    /**
     * Base set of the one-tap hide flow: visible `module` entries whose id is in here.
     * `Violet` was removed together with its pack entry.
     */
    val oneKeyBase: Set<String> = setOf(HYBRID_MOUNT, ZYGISKSU, "tricky_store", "hma_oss_zygisk")
}

@Immutable
data class HidingPackEntry(
    val id: String,
    /**
     * Real ksud module id, i.e. the `/data/adb/modules/<moduleId>` directory name. It differs
     * from [id] for the PathMask branches (all six declare `id=pathmask`); null when the
     * manifest does not carry one.
     */
    val moduleId: String?,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val category: HidingPackCategory,
    val branch: String?,
    val packageName: String?,
    val asset: String,
    val sizeBytes: Long,
    val sha256: String,
    val sourceFile: String,
    /**
     * Manifest `modes`: runtime modes this entry may be offered in. Empty — also when the key is
     * absent or is not an array — means "both modes are fine".
     */
    val modes: Set<HidingPackMode> = emptySet(),
    /** Manifest `order`: smaller installs first. [DEFAULT_ORDER] when absent or not a number. */
    val order: Int = DEFAULT_ORDER,
) {
    /** Module archives go through ksud; detectors go through the package installer. */
    val isModuleArchive: Boolean
        get() = category == HidingPackCategory.Module || category == HidingPackCategory.PathMask

    /** Entries of category "data" carry no install action in the frozen schema. */
    val isInstallable: Boolean
        get() = category != HidingPackCategory.Data

    /**
     * Whether this entry may be shown/installed on [runtimeMode]. An entry with no declared mode
     * applies everywhere, and [HidingRuntimeMode.Unknown] always keeps everything.
     */
    fun appliesTo(runtimeMode: HidingRuntimeMode): Boolean {
        if (modes.isEmpty()) return true
        val own = runtimeMode.packMode ?: return true
        return own in modes
    }

    companion object {
        const val DEFAULT_ORDER = 100
    }
}

@Immutable
data class HidingPack(
    val version: Int,
    val generatedAt: String,
    val sourcePack: String,
    val includesModules: Boolean,
    val includesPathMask: Boolean,
    val includesDetectors: Boolean,
    val includesData: Boolean,
    val includesKeybox: Boolean,
    val totalBytes: Long,
    val entries: List<HidingPackEntry>,
)
