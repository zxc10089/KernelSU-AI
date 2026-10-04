package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable

/** How risky one conflict is; the conflict page shows this as a coloured tag. */
enum class ModuleConflictLevel { HIGH, MEDIUM }

/** The structural reasons two enabled modules cannot safely coexist. */
enum class ModuleConflictType {
    FILE_OVERLAP,
    DUPLICATE_ID,
    PROP_DUPLICATE,
    DIR_ID_MISMATCH,
    MISSING_PROP,
}

@Immutable
data class ModuleConflict(
    val level: ModuleConflictLevel,
    val type: ModuleConflictType,
    val subject: String,
    val modules: List<String>,
)

@Immutable
data class ModuleConflictReport(
    val scannedModules: Int,
    val conflicts: List<ModuleConflict>,
)
