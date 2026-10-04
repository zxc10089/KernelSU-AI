package me.weishu.kernelsu.data.agent

import androidx.annotation.StringRes
import me.weishu.kernelsu.R

/**
 * Which part of the device file system the assistant may touch.
 *
 * The ordering of the constants is the permission ordering: a switch to a higher ordinal widens
 * access and therefore has to pass the strict confirmation gate, a switch to a lower one applies
 * immediately. NONE is the fail-closed default.
 *
 * Note that granting and revoking root for an app does not go through the file system
 * (Natives.setAppProfile), so it stays available in every scope.
 */
enum class AiAccessScope(
    @get:StringRes val labelRes: Int,
    @get:StringRes val summaryRes: Int,
) {
    /** No file access at all. Chat and app permission review still work. */
    NONE(R.string.ai_access_scope_none, R.string.ai_access_scope_none_summary),

    /** Read and write under /data/adb (module directories, ksud state, the AI trash). */
    DATA_ADB(R.string.ai_access_scope_data_adb, R.string.ai_access_scope_data_adb_summary),

    /** Read any path; every write still requires a confirmation. */
    ROOT_FS(R.string.ai_access_scope_root, R.string.ai_access_scope_root_summary),
    ;

    /** True when the assistant is allowed to read files at all. */
    val canReadFiles: Boolean get() = this != NONE

    /** True when /data/adb is inside the scope. */
    val coversDataAdb: Boolean get() = this != NONE

    /** True when every path is inside the scope. */
    val coversRoot: Boolean get() = this == ROOT_FS

    /** True when switching away from [current] to this value widens access. */
    fun isElevationFrom(current: AiAccessScope): Boolean = ordinal > current.ordinal

    companion object {
        /** Fail-closed: a fresh install cannot read anything until the user says so. */
        val DEFAULT: AiAccessScope = NONE

        /** Display order of the selector. */
        val options: List<AiAccessScope> = listOf(NONE, DATA_ADB, ROOT_FS)

        fun fromKey(key: String?): AiAccessScope = entries.firstOrNull { it.name == key } ?: DEFAULT

        /**
         * One-time migration from the phase-1 boolean allow_module_dir: the module directory lives
         * under /data/adb, so true maps onto the /data/adb scope.
         */
        fun fromLegacyAllowModuleDir(allow: Boolean): AiAccessScope =
            if (allow) DATA_ADB else DEFAULT
    }
}
