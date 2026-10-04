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
            runCatching { repository.read(READ_LIMIT) }.fold(
                onSuccess = { entries ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = false,
                            items = entries.map { entry -> toItem(entry) },
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

    fun undo(item: AiAuditItem) {
        if (!undo.supports(item.entry)) {
            _uiState.update { it.copy(result = AiAuditResult.Unsupported) }
            return
        }
        viewModelScope.launch {
            val outcome = undo.undo(item.entry)
            outcome.fold(
                onSuccess = { _uiState.update { state -> state.copy(result = AiAuditResult.Undone) } },
                onFailure = { failure ->
                    _uiState.update { state ->
                        state.copy(result = AiAuditResult.Failed(failure.message.orEmpty()))
                    }
                },
            )
            refresh()
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
