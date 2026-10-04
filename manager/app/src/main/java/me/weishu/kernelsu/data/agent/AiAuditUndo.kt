package me.weishu.kernelsu.data.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.repository.AiAuditRepository
import me.weishu.kernelsu.data.repository.AiAuditRepositoryImpl
import me.weishu.kernelsu.data.repository.AiSettingsRepository
import me.weishu.kernelsu.data.repository.AiSettingsRepositoryImpl
import me.weishu.kernelsu.ui.util.getRootShell

/**
 * Phase 5: reverses one entry that is already in the audit log (spec 3.7).
 *
 * Only state the log captured before the mutation is replayed, so an entry whose before/after pair
 * is missing, whose kind is unknown or whose result was not ok is refused instead of guessed. The
 * reversal is appended as a new entry, which keeps the history honest when the undo itself fails.
 */
class AiAuditUndo(
    private val executor: AiActionExecutor = AiActionExecutorImpl(),
    private val settings: AiSettingsRepository = AiSettingsRepositoryImpl(),
    private val audit: AiAuditRepository = AiAuditRepositoryImpl(),
) {

    /** Whether [entry] carries enough recorded state to be reversed. */
    fun supports(entry: AiAuditEntry): Boolean = plan(entry) != null

    suspend fun undo(entry: AiAuditEntry): Result<String> {
        val plan = plan(entry) ?: return Result.failure(IllegalStateException(UNSUPPORTED))
        val outcome: Result<String> = when (plan) {
            is UndoPlan.Action -> runAction(plan.action)

            is UndoPlan.Move -> move(plan.from, plan.to)

            is UndoPlan.Scope -> runCatching {
                settings.accessScope = plan.scope
                plan.scope.name
            }
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
                if (from.isNullOrEmpty() || to.isNullOrEmpty()) null else UndoPlan.Move(from, to)
            }

            AiAuditEntry.KIND_SCOPE_CHANGE -> {
                val previous = entry.before
                if (previous.isNullOrEmpty()) null else UndoPlan.Scope(AiAccessScope.fromKey(previous))
            }

            else -> null
        }
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

    private suspend fun move(from: String, to: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val io = getRootShell().newJob()
                .add("mkdir -p " + parentOf(to) + " && mv " + from + " " + to)
                .to(ArrayList(), null)
                .exec()
            if (io.code == 0) {
                Result.success(to)
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

    private sealed interface UndoPlan {
        data class Action(val action: AiAction) : UndoPlan

        data class Move(val from: String, val to: String) : UndoPlan

        data class Scope(val scope: AiAccessScope) : UndoPlan
    }

    private companion object {
        const val UNSUPPORTED = "该记录不支持撤销"
        const val STATE_ALLOW = "allow"
        const val STATE_ENABLED = "enabled"
        const val NEWLINE = "\n"
    }
}
