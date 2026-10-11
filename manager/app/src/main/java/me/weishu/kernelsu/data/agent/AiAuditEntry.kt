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
    /**
     * The approval batch this entry ran in, when the user accepted more than one pending action at
     * once. The action history page groups by it so a batch can be undone with one tap.
     */
    val batchId: String? = null,
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
        batchId?.let { put("batchId", it) }
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

        // ---- kinds written by the structured tool handlers --------------------------------------

        /** `freeze_app` / `unfreeze_app`: before/after are "enabled" / "disabled". */
        const val KIND_APP_STATE = "app_state"

        /** `set_root_policy`: before/after are "allow" / "deny" / "prompt". */
        const val KIND_ROOT_POLICY = "root_policy"

        /** `write_file`: before is the old md5 (or "absent"), after the new md5. */
        const val KIND_FILE_WRITE = "file_write"

        /** `move_file`: before is the source path, after the destination. */
        const val KIND_FILE_MOVE = "file_move"

        /** `delete_file` with `force`: irreversible, so it is never undoable. */
        const val KIND_FILE_DELETE = "file_delete"

        /** `create_directory`: before/after are "absent" / "created". */
        const val KIND_MKDIR = "mkdir"

        /** `backup_module`: after is the archive path the undo has to remove. */
        const val KIND_MODULE_BACKUP = "module_backup"

        /** `restore_module`: before/after are the archive path and the module directory. */
        const val KIND_MODULE_RESTORE = "module_restore"

        /** `schedule_task` / `cancel_task` / `set_boot_script`: the file the tool rewrote. */
        const val KIND_SCHEDULE_WRITE = "schedule_write"

        /** `extract_archive`: after is the directory the archive was unpacked into. */
        const val KIND_ARCHIVE_EXTRACT = "archive_extract"

        /**
         * `import_module_from_sdcard`: before is the zip the user picked under /sdcard, after is the
         * copy staged in /data/adb/AI/staging that ksud installed from.
         */
        const val KIND_MODULE_IMPORT = "module_import"

        /**
         * `uninstall_app` and `clear_app_data`. Neither can be undone by this app - a reinstall needs
         * the original apk and cleared data is gone - so the entry records what happened instead of
         * offering a reverse.
         */
        const val KIND_APP_DESTRUCTIVE = "app_destructive"

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
                batchId = json.optStringOrNull("batchId"),
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
