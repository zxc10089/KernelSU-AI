package me.weishu.kernelsu.data.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.repository.AiAuditRepository
import me.weishu.kernelsu.data.repository.AiAuditRepositoryImpl
import me.weishu.kernelsu.data.repository.AiSettingsRepository
import me.weishu.kernelsu.data.repository.AiSettingsRepositoryImpl
import me.weishu.kernelsu.ui.util.getRootShell

/** What one batch reversal actually restored; the audit page reports both numbers. */
data class AiUndoSummary(
    val undone: Int,
    val failed: Int,
    val firstError: String? = null,
)

/**
 * Phase 5: reverses an entry that is already in the audit log (spec 3.7), one at a time or as the
 * whole batch the user confirmed in one dialog.
 *
 * Only state the log captured before the mutation is replayed, so an entry whose before/after pair
 * is missing, whose kind is unknown or whose result was not ok is refused instead of guessed. The
 * reversal is appended as a new entry, which keeps the history honest when the undo itself fails.
 *
 * The covered kinds are the ones with a deterministic reverse: an su/module switch, a file moved to
 * the trash, a scope change, a directory a tool created, a file a tool wrote (from the backup taken
 * before an overwrite, or by deleting the file it created while the digest still matches), a file a
 * tool moved, a module backup archive, a scheduled/boot script whose previous bytes were recorded,
 * and an app's root policy or freeze state. Everyone else - a forced delete, cleared app data, an
 * uninstalled app, an extracted archive - stays un-undoable and says so, because guessing a reverse
 * for a destructive change is worse than admitting there is none.
 */
class AiAuditUndo(
    private val executor: AiActionExecutor = AiActionExecutorImpl(),
    private val settings: AiSettingsRepository = AiSettingsRepositoryImpl(),
    private val audit: AiAuditRepository = AiAuditRepositoryImpl(),
) {

    /** Whether [entry] carries enough recorded state to be reversed. */
    fun supports(entry: AiAuditEntry): Boolean = plan(entry) != null

    suspend fun undo(entry: AiAuditEntry): Result<String> = reverse(entry, batchId = null)

    /**
     * Reverses every reversible entry of one batch and reports how much of it worked.
     *
     * A batch is what the user confirmed in one list dialog, so "one tap undo" has to reverse the
     * whole thing: the rows are replayed in the order they were recorded, a row that cannot be
     * reversed is skipped instead of aborting the rest, and the reversals are appended under a fresh
     * undo batch id so the audit page can show the reversal as its own row instead of hiding it
     * inside the batch it just undid. Partial success is reported as success with a count, because
     * half a batch restored is a result the user has to see - not a plain failure.
     */
    suspend fun undoBatch(entries: List<AiAuditEntry>): Result<AiUndoSummary> {
        val reversible = entries.filter { supports(it) }
        if (reversible.isEmpty()) return Result.failure(IllegalStateException(UNSUPPORTED))
        val undoBatchId = UNDO_BATCH_PREFIX + System.currentTimeMillis()
        var undone = 0
        var failed = 0
        var firstError: String? = null
        for (entry in reversible) {
            reverse(entry, undoBatchId).fold(
                onSuccess = { undone += 1 },
                onFailure = { failure ->
                    failed += 1
                    if (firstError == null) firstError = failure.message.orEmpty()
                },
            )
        }
        if (undone == 0) return Result.failure(IllegalStateException(firstError ?: UNSUPPORTED))
        return Result.success(AiUndoSummary(undone = undone, failed = failed, firstError = firstError))
    }

    /** One reversal; [batchId] groups the rows this call appends into the reversal's own batch. */
    private suspend fun reverse(entry: AiAuditEntry, batchId: String?): Result<String> {
        val plan = plan(entry) ?: return Result.failure(IllegalStateException(UNSUPPORTED))
        val outcome: Result<String> = when (plan) {
            is UndoPlan.Action -> runAction(plan.action)

            is UndoPlan.Move -> move(plan.from, plan.to)

            is UndoPlan.Scope -> runCatching {
                settings.accessScope = plan.scope
                plan.scope.name
            }

            is UndoPlan.Shell -> shell(plan.command)
        }
        audit.append(
            AiAuditEntry(
                ts = System.currentTimeMillis(),
                kind = AiAuditEntry.KIND_UNDO,
                target = entry.target,
                before = entry.after,
                after = entry.before,
                command = entry.command,
                scope = settings.accessScope.name,
                result = if (outcome.isSuccess) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                undoable = false,
                batchId = batchId,
            ),
        )
        return outcome
    }

    private fun plan(entry: AiAuditEntry): UndoPlan? {
        if (entry.result != AiAuditEntry.RESULT_OK) return null
        return when (entry.kind) {
            AiActionExecutorImpl.KIND_SU -> when {
                entry.target.isEmpty() -> null
                entry.before == STATE_ALLOW -> UndoPlan.Action(AiAction.GrantSu(entry.target))
                else -> UndoPlan.Action(AiAction.RevokeSu(entry.target))
            }

            AiActionExecutorImpl.KIND_MODULE -> when {
                entry.target.isEmpty() -> null
                entry.before == STATE_ENABLED -> UndoPlan.Action(AiAction.EnableModule(entry.target))
                else -> UndoPlan.Action(AiAction.DisableModule(entry.target))
            }

            AiActionExecutorImpl.KIND_TRASH -> {
                val from = entry.after
                val to = entry.before
                if (from.isNullOrEmpty() || to.isNullOrEmpty()) null else planMove(from, to)
            }

            AiAuditEntry.KIND_SCOPE_CHANGE -> {
                val previous = entry.before
                if (previous.isNullOrEmpty()) null else UndoPlan.Scope(AiAccessScope.fromKey(previous))
            }

            AiAuditEntry.KIND_MKDIR -> planMkdir(entry)

            AiAuditEntry.KIND_FILE_WRITE -> planFileWrite(entry)

            AiAuditEntry.KIND_FILE_MOVE -> planFileMove(entry)

            AiAuditEntry.KIND_MODULE_BACKUP -> planModuleBackup(entry)

            AiAuditEntry.KIND_SCHEDULE_WRITE -> planScheduleWrite(entry)

            AiAuditEntry.KIND_ROOT_POLICY -> planRootPolicy(entry)

            AiAuditEntry.KIND_APP_STATE -> planAppState(entry)

            else -> null
        }
    }

    /**
     * create_directory is only reversed when it created the directory, and then with `rmdir`, so a
     * directory that has since been filled is reported as a failure instead of being deleted.
     */
    private fun planMkdir(entry: AiAuditEntry): UndoPlan? {
        if (entry.before != STATE_ABSENT || entry.after != STATE_CREATED) return null
        val path = quote(entry.target) ?: return null
        return UndoPlan.Shell("rmdir $path")
    }

    /**
     * write_file is reversed from what the write itself recorded: the backup taken before an
     * overwrite is copied back, and a file that was absent is removed - but only while its digest is
     * still the one this write produced, so a file the user has since edited is left alone. An
     * overwrite without a backup has nothing to restore and stays un-undoable.
     */
    private fun planFileWrite(entry: AiAuditEntry): UndoPlan? {
        val target = quote(entry.target) ?: return null
        val backup = entry.command
        if (!backup.isNullOrBlank()) {
            val source = quote(backup) ?: return null
            return UndoPlan.Shell("test -f $source && cp -f $source $target")
        }
        val digest = entry.after
        if (entry.before == STATE_ABSENT && digest != null && DIGEST.matches(digest)) {
            return UndoPlan.Shell(
                "test \"$(md5sum $target 2>/dev/null | cut -d' ' -f1)\" = '$digest' && rm -f $target",
            )
        }
        return null
    }

    private fun planFileMove(entry: AiAuditEntry): UndoPlan? {
        val from = entry.after
        val to = entry.before
        if (from.isNullOrEmpty() || to.isNullOrEmpty()) return null
        return planMove(from, to)
    }

    /** A module backup leaves one new archive behind; its reversal removes exactly that file. */
    private fun planModuleBackup(entry: AiAuditEntry): UndoPlan? {
        val archive = entry.after
        if (archive.isNullOrBlank()) return null
        val path = quote(archive) ?: return null
        return UndoPlan.Shell("rm -f $path")
    }

    /**
     * A schedule or boot-script write records the previous file as base64, or `absent` when the file
     * did not exist. The reversal either puts those bytes back - restoring the executable bit when
     * the write set one - or deletes the file the write created.
     */
    private fun planScheduleWrite(entry: AiAuditEntry): UndoPlan? {
        val target = quote(entry.target) ?: return null
        val previous = entry.before
        if (previous.isNullOrEmpty() || previous == STATE_ABSENT) return UndoPlan.Shell("rm -f $target")
        if (!BASE64_TEXT.matches(previous)) return null
        val mode = if (entry.command?.contains(CHMOD_MARK) == true) " && chmod 755 $target" else ""
        return UndoPlan.Shell("printf '%s' '$previous' | base64 -d > $target$mode")
    }

    /**
     * set_root_policy is replayed through the tool the model used, with the policy recorded before
     * the change (an app with no stored profile is the "ask next time" state). A custom template id
     * cannot be replayed by that tool, so such an entry stays un-undoable rather than being silently
     * downgraded to a plain allow.
     */
    private fun planRootPolicy(entry: AiAuditEntry): UndoPlan? {
        if (entry.target.isEmpty()) return null
        val policy = when (entry.before) {
            STATE_ABSENT, STATE_PROMPT -> STATE_PROMPT
            STATE_DENY -> STATE_DENY
            STATE_ALLOW -> STATE_ALLOW
            else -> return null
        }
        return UndoPlan.Action(
            AiAction.Tool(TOOL_ROOT_POLICY, mapOf(PARAM_PACKAGE to entry.target, PARAM_POLICY to policy)),
        )
    }

    /** freeze/unfreeze is replayed through the tool that owns the opposite transition. */
    private fun planAppState(entry: AiAuditEntry): UndoPlan? {
        if (entry.target.isEmpty()) return null
        val tool = when (entry.before) {
            STATE_ENABLED -> TOOL_UNFREEZE
            STATE_DISABLED -> TOOL_FREEZE
            else -> return null
        }
        return UndoPlan.Action(AiAction.Tool(tool, mapOf(PARAM_PACKAGE to entry.target)))
    }

    private fun planMove(from: String, to: String): UndoPlan? {
        if (quote(from) == null || quote(to) == null || quote(parentOf(to)) == null) return null
        return UndoPlan.Move(from, to)
    }

    private suspend fun runAction(action: AiAction): Result<String> = runCatching {
        executor.execute(action, settings.accessScope)
    }.fold(
        onSuccess = { result ->
            if (result.ok) {
                Result.success(result.output)
            } else {
                Result.failure(IllegalStateException(result.output))
            }
        },
        onFailure = { failure -> Result.failure(failure) },
    )

    private suspend fun move(from: String, to: String): Result<String> {
        val source = quote(from) ?: return Result.failure(IllegalStateException(QUOTE_FAILED))
        val destination = quote(to) ?: return Result.failure(IllegalStateException(QUOTE_FAILED))
        val parent = quote(parentOf(to)) ?: return Result.failure(IllegalStateException(QUOTE_FAILED))
        return shell("mkdir -p $parent && mv $source $destination")
    }

    private suspend fun shell(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val io = getRootShell().newJob()
                .add(command)
                .to(ArrayList(), null)
                .exec()
            if (io.code == 0) {
                Result.success(command)
            } else {
                Result.failure(
                    IllegalStateException(
                        io.err.joinToString(NEWLINE).ifBlank { io.out.joinToString(NEWLINE) },
                    ),
                )
            }
        }.getOrElse { Result.failure(it) }
    }

    private fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        return if (trimmed.contains('/')) trimmed.substringBeforeLast('/') else trimmed
    }

    /** Single quoted shell argument, or null when the value cannot be quoted safely. */
    private fun quote(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(39.toChar()) || trimmed.contains(10.toChar()) || trimmed.contains(13.toChar())) {
            return null
        }
        return 39.toChar() + trimmed + 39.toChar()
    }

    private sealed interface UndoPlan {
        data class Action(val action: AiAction) : UndoPlan

        data class Move(val from: String, val to: String) : UndoPlan

        data class Scope(val scope: AiAccessScope) : UndoPlan

        /** A command built only out of quoted, recorded values; see the plan builders above. */
        data class Shell(val command: String) : UndoPlan
    }

    private companion object {
        const val UNSUPPORTED = "该记录不支持撤销"
        const val QUOTE_FAILED = "记录中的路径无法安全引用，已跳过"
        const val STATE_ALLOW = "allow"
        const val STATE_ENABLED = "enabled"
        const val STATE_DISABLED = "disabled"
        const val STATE_ABSENT = "absent"
        const val STATE_CREATED = "created"
        const val STATE_DENY = "deny"
        const val STATE_PROMPT = "prompt"
        const val TOOL_ROOT_POLICY = "set_root_policy"
        const val TOOL_FREEZE = "freeze_app"
        const val TOOL_UNFREEZE = "unfreeze_app"
        const val PARAM_PACKAGE = "package_name"
        const val PARAM_POLICY = "policy"
        const val CHMOD_MARK = "chmod 755"
        const val NEWLINE = "\n"

        /** Groups the rows that one batch reversal appends, so they read as one reversal. */
        const val UNDO_BATCH_PREFIX = "undo-"

        val DIGEST = Regex("^[0-9a-fA-F]{32}$")
        val BASE64_TEXT = Regex("^[A-Za-z0-9+/=]+$")
    }
}
