package me.weishu.kernelsu.data.agent

import androidx.annotation.StringRes
import me.weishu.kernelsu.R

/**
 * Which part of the device file system the assistant may touch.
 *
 * The ordering of the constants is the permission ordering: a switch to a higher ordinal widens
 * access and therefore has to pass the strict confirmation gate, a switch to a lower one applies
 * immediately.
 *
 * [options] is the user-facing selector: only [DATA_ADB] and [ROOT_FS] can be picked. [NONE] stays
 * in the enum as the fail-closed value (unknown stored key, UI default before the repository
 * answers, undo of an old audit entry); a device that already stored NONE keeps it until the user
 * picks one of the two options.
 *
 * Note that granting and revoking root for an app does not go through the file system
 * (Natives.setAppProfile), so it stays available in every scope.
 */
enum class AiAccessScope(
    @get:StringRes val labelRes: Int,
    @get:StringRes val summaryRes: Int,
) {
    /**
     * No file access at all. Chat and app permission review still work.
     *
     * Not offered by [options] any more: kept as the fail-closed value only.
     */
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
        /**
         * Fail-closed value used when a stored key cannot be understood and as the UI default
         * before the repository answers. It is no longer offered by [options].
         */
        val DEFAULT: AiAccessScope = NONE

        /** Scope a device starts with when the user has never chosen one. */
        val STARTUP_DEFAULT: AiAccessScope = DATA_ADB

        /** Display order of the selector: /data/adb first, then the whole root. */
        val options: List<AiAccessScope> = listOf(DATA_ADB, ROOT_FS)

        fun fromKey(key: String?): AiAccessScope = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
