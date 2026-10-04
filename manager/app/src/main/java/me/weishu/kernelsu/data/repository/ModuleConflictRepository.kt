package me.weishu.kernelsu.data.repository

import me.weishu.kernelsu.data.model.ModuleConflictReport

/**
 * Phase 4: read-only structural scan over the installed modules.
 *
 * The scan never mutates anything and never calls the model; the AI explanation is a separate
 * console kickoff.
 */
interface ModuleConflictRepository {

    /** Every structural conflict between enabled modules, or the failure of the scan itself. */
    suspend fun scan(): Result<ModuleConflictReport>
}
