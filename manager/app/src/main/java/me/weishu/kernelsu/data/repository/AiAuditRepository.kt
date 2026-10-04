package me.weishu.kernelsu.data.repository

import me.weishu.kernelsu.data.agent.AiAuditEntry

/**
 * Append-only log of everything the assistant changed on the device.
 *
 * The log is the precondition for "undo": an action that is not in here cannot be reversed, so
 * every writer records the previous value before it mutates anything.
 */
interface AiAuditRepository {

    /** Appends one entry and trims the file back to MAX_ENTRIES lines. */
    suspend fun append(entry: AiAuditEntry)

    /** Newest first, at most [limit] entries. Unreadable lines are skipped, not fatal. */
    suspend fun read(limit: Int = MAX_ENTRIES): List<AiAuditEntry>

    companion object {
        const val FILE_NAME = "ai_audit.jsonl"

        /** Hard cap so the file cannot grow forever on a device that is never cleaned up. */
        const val MAX_ENTRIES = 2000
    }
}
