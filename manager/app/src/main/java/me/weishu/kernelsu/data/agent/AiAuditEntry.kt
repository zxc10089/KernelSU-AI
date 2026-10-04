package me.weishu.kernelsu.data.agent

import org.json.JSONObject

/**
 * One recorded assistant-initiated mutation.
 *
 * Only actions are recorded - never the chat transcript and never the API key. The before/after
 * pair carries enough state for the undo executor to put a permission, a module flag or the access
 * scope back to what it was.
 */
data class AiAuditEntry(
    val ts: Long,
    val kind: String,
    val target: String = "",
    val before: String? = null,
    val after: String? = null,
    val command: String? = null,
    val scope: String? = null,
    val result: String = RESULT_OK,
    val undoable: Boolean = false,
) {

    fun toJson(): String = JSONObject().apply {
        put("ts", ts)
        put("kind", kind)
        put("target", target)
        put("result", result)
        put("undoable", undoable)
        before?.let { put("before", it) }
        after?.let { put("after", it) }
        command?.let { put("command", it) }
        scope?.let { put("scope", it) }
    }.toString()

    companion object {
        const val KIND_SCOPE_CHANGE = "SCOPE_CHANGE"

        /** Written by the undo executor itself, so a reversal is visible in the same history. */
        const val KIND_UNDO = "undo"
        const val RESULT_OK = "ok"
        const val RESULT_FAILED = "failed"
        const val RESULT_DENIED = "denied"

        /** Returns null for a line that is not a well formed entry; the caller skips it. */
        fun fromJson(line: String): AiAuditEntry? = runCatching {
            val json = JSONObject(line)
            AiAuditEntry(
                ts = json.optLong("ts"),
                kind = json.optString("kind"),
                target = json.optString("target"),
                before = json.optStringOrNull("before"),
                after = json.optStringOrNull("after"),
                command = json.optStringOrNull("command"),
                scope = json.optStringOrNull("scope"),
                result = json.optString("result", RESULT_OK),
                undoable = json.optBoolean("undoable", false),
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
