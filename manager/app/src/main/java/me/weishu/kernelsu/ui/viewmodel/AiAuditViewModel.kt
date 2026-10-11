package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiActionExecutorImpl
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.agent.AiAuditUndo
import me.weishu.kernelsu.data.repository.AiAuditRepository
import me.weishu.kernelsu.data.repository.AiAuditRepositoryImpl
import me.weishu.kernelsu.ui.screen.aiaudit.AiAuditBatch
import me.weishu.kernelsu.ui.screen.aiaudit.AiAuditItem
import me.weishu.kernelsu.ui.screen.aiaudit.AiAuditResult
import me.weishu.kernelsu.ui.screen.aiaudit.AiAuditUiState
import me.weishu.kernelsu.ui.screen.aiaudit.AiScopeChange

class AiAuditViewModel(
    private val repository: AiAuditRepository = AiAuditRepositoryImpl(),
    private val undo: AiAuditUndo = AiAuditUndo(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiAuditUiState())
    val uiState: StateFlow<AiAuditUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            // The page is the assistant's trail: a settings change the user made on their own
            // screens is recorded in the same file but is not an assistant action, so it is
            // dropped here. The filter runs after the read on purpose - the limit counts file
            // rows, and filtering inside the repository would let user rows eat the window.
            runCatching {
                repository.read(READ_LIMIT).filter { it.origin == AiAuditEntry.ORIGIN_ASSISTANT }
            }.fold(
                onSuccess = { entries ->
                    val items = entries.map { entry -> toItem(entry) }
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = false,
                            items = items,
                            batches = toBatches(items),
                        )
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(loading = false, error = true) }
                },
            )
        }
    }

    fun toggleDetail(item: AiAuditItem) {
        _uiState.update {
            it.copy(expanded = if (it.expanded == item.entry.ts) null else item.entry.ts)
        }
    }

    fun toggleBatchDetail(batch: AiAuditBatch) {
        _uiState.update {
            it.copy(expandedBatch = if (it.expandedBatch == batch.batchId) null else batch.batchId)
        }
    }

    fun undo(item: AiAuditItem) {
        if (!undo.supports(item.entry)) {
            _uiState.update { it.copy(result = AiAuditResult.Unsupported) }
            return
        }
        viewModelScope.launch {
            val outcome = undo.undo(item.entry)
            outcome.fold(
                onSuccess = {
                    _uiState.update { state -> state.copy(result = AiAuditResult.Undone(count = 1)) }
                },
                onFailure = { failure ->
                    _uiState.update { state ->
                        state.copy(result = AiAuditResult.Failed(failure.message.orEmpty()))
                    }
                },
            )
            refresh()
        }
    }

    /**
     * Reverses one batch with a single tap.
     *
     * The row is only offered when at least one member can be reversed, and the summary keeps a
     * partial reversal visible: "已撤销 3 项，2 项失败" is the honest report, not a bare "已撤销".
     */
    fun undoBatch(batch: AiAuditBatch) {
        val entries = batch.items.map { it.entry }
        if (entries.none { undo.supports(it) }) {
            _uiState.update { it.copy(result = AiAuditResult.Unsupported) }
            return
        }
        viewModelScope.launch {
            undo.undoBatch(entries).fold(
                onSuccess = { summary ->
                    _uiState.update { state ->
                        state.copy(
                            result = AiAuditResult.Undone(count = summary.undone, failed = summary.failed),
                        )
                    }
                },
                onFailure = { failure ->
                    _uiState.update { state ->
                        state.copy(result = AiAuditResult.Failed(failure.message.orEmpty()))
                    }
                },
            )
            refresh()
        }
    }

    /** 把同一批次号的记录聚合成一行；单条动作没有批次号，只留在完整历史里。 */
    private fun toBatches(items: List<AiAuditItem>): List<AiAuditBatch> {
        val groups = LinkedHashMap<String, MutableList<AiAuditItem>>()
        for (item in items) {
            val id = item.entry.batchId ?: continue
            groups.getOrPut(id) { ArrayList() }.add(item)
        }
        return groups.entries.map { (id, members) ->
            AiAuditBatch(
                batchId = id,
                time = timeFormat.format(Date(members.maxOf { it.entry.ts })),
                items = members,
                undoableCount = members.count { it.undoable },
            )
        }
    }

    private fun toItem(entry: AiAuditEntry): AiAuditItem = AiAuditItem(
        entry = entry,
        kindRes = kindRes(entry.kind),
        resultRes = resultRes(entry.result),
        time = timeFormat.format(Date(entry.ts)),
        detail = detail(entry),
        undoable = entry.undoable && entry.result == AiAuditEntry.RESULT_OK && undo.supports(entry),
        scopeChange = scopeChange(entry),
        targetRes = scopeRes(entry.target),
    )

    private fun scopeChange(entry: AiAuditEntry): AiScopeChange? {
        if (entry.kind != AiAuditEntry.KIND_SCOPE_CHANGE) return null
        return AiScopeChange(fromRes = scopeRes(entry.before), toRes = scopeRes(entry.after) ?: return null)
    }

    private fun scopeRes(name: String?): Int? = when (name) {
        AiAccessScope.NONE.name -> R.string.ai_access_scope_none
        AiAccessScope.DATA_ADB.name -> R.string.ai_access_scope_data_adb
        AiAccessScope.ROOT_FS.name -> R.string.ai_access_scope_root
        else -> null
    }

    private fun detail(entry: AiAuditEntry): String {
        val parts = ArrayList<String>()
        entry.target.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        entry.command?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (entry.before != null || entry.after != null) {
            parts.add((entry.before ?: EMPTY) + " -> " + (entry.after ?: EMPTY))
        }
        entry.scope?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        return parts.joinToString(NEWLINE)
    }

    private fun kindRes(kind: String): Int = when (kind) {
        AiActionExecutorImpl.KIND_SU -> R.string.ai_audit_kind_su
        AiActionExecutorImpl.KIND_MODULE -> R.string.ai_audit_kind_module
        AiActionExecutorImpl.KIND_TRASH -> R.string.ai_audit_kind_trash
        AiActionExecutorImpl.KIND_INSTALL -> R.string.ai_audit_kind_install
        AiActionExecutorImpl.KIND_MAKE_MODULE -> R.string.ai_audit_kind_make_module
        AiActionExecutorImpl.KIND_COMMAND -> R.string.ai_audit_kind_command
        AiActionExecutorImpl.KIND_SAFE_EXEC -> R.string.ai_audit_kind_safe_exec
        AiAuditEntry.KIND_SCOPE_CHANGE -> R.string.ai_audit_kind_scope
        AiAuditEntry.KIND_UNDO -> R.string.ai_audit_kind_undo
        AiAuditEntry.KIND_APP_STATE -> R.string.ai_audit_kind_app_state
        AiAuditEntry.KIND_ROOT_POLICY -> R.string.ai_audit_kind_root_policy
        AiAuditEntry.KIND_FILE_WRITE -> R.string.ai_audit_kind_file_write
        AiAuditEntry.KIND_FILE_MOVE -> R.string.ai_audit_kind_file_move
        AiAuditEntry.KIND_FILE_DELETE -> R.string.ai_audit_kind_file_delete
        AiAuditEntry.KIND_MKDIR -> R.string.ai_audit_kind_mkdir
        AiAuditEntry.KIND_MODULE_BACKUP -> R.string.ai_audit_kind_module_backup
        AiAuditEntry.KIND_MODULE_RESTORE -> R.string.ai_audit_kind_module_restore
        AiAuditEntry.KIND_SCHEDULE_WRITE -> R.string.ai_audit_kind_schedule_write
        AiAuditEntry.KIND_ARCHIVE_EXTRACT -> R.string.ai_audit_kind_archive_extract
        AiAuditEntry.KIND_APP_DESTRUCTIVE -> R.string.ai_audit_kind_app_destructive
        else -> R.string.ai_audit_kind_other
    }

    private fun resultRes(result: String): Int = when (result) {
        AiAuditEntry.RESULT_FAILED -> R.string.ai_audit_result_failed
        AiAuditEntry.RESULT_DENIED -> R.string.ai_audit_result_denied
        else -> R.string.ai_audit_result_ok
    }

    private companion object {
        const val READ_LIMIT = 200
        const val EMPTY = "-"
        const val NEWLINE = "\n"
    }
}
