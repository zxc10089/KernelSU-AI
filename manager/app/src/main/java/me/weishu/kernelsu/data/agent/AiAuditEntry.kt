package me.weishu.kernelsu.data.agent

import org.json.JSONObject

/**
 * One recorded mutation.
 *
 * Only actions are recorded - never the chat transcript and never the API key. The before/after
 * pair carries enough state for the undo executor to put a permission, a module flag or the access
 * scope back to what it was.
 *
 * Almost every entry is an assistant action. A change the user makes on a settings screen is
 * recorded too, so it can be restored, but it is marked [ORIGIN_USER] and the action history page
 * filters it out: that page is the assistant's trail, not the user's.
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
    /** Who initiated it: [ORIGIN_ASSISTANT] for the execution chain, [ORIGIN_USER] for settings. */
    val origin: String = ORIGIN_ASSISTANT,
) {

    fun toJson(): String = JSONObject().apply {
        put("ts", ts)
        put("kind", kind)
        put("target", target)
        put("result", result)
        put("undoable", undoable)
        put("origin", origin)
        before?.let { put("before", it) }
        after?.let { put("after", it) }
        command?.let { put("command", it) }
        scope?.let { put("scope", it) }
    }.toString()

    companion object {
        const val KIND_SCOPE_CHANGE = "SCOPE_CHANGE"

        /** Written by the assistant's execution chain. */
        const val ORIGIN_ASSISTANT = "assistant"

        /** Written by a settings screen on the user's behalf; it is not an assistant action. */
        const val ORIGIN_USER = "user"

        /** Written by the undo executor itself, so a reversal is visible in the same history. */
        const val KIND_UNDO = "undo"
        const val RESULT_OK = "ok"
        const val RESULT_FAILED = "failed"
        const val RESULT_DENIED = "denied"

        /** Returns null for a line that is not a well formed entry; the caller skips it. */
        fun fromJson(line: String): AiAuditEntry? = runCatching {
            val json = JSONObject(line)
            val kind = json.optString("kind")
            AiAuditEntry(
                ts = json.optLong("ts"),
                kind = kind,
                target = json.optString("target"),
                before = json.optStringOrNull("before"),
                after = json.optStringOrNull("after"),
                command = json.optStringOrNull("command"),
                scope = json.optStringOrNull("scope"),
                result = json.optString("result", RESULT_OK),
                undoable = json.optBoolean("undoable", false),
                // Rows written before the field existed carry no origin. Every scope change of
                // that era came from the settings screen, so it must read back as a user action.
                origin = json.optStringOrNull("origin")
                    ?: if (kind == KIND_SCOPE_CHANGE) ORIGIN_USER else ORIGIN_ASSISTANT,
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
