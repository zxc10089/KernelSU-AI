package me.weishu.kernelsu.ui.screen.aiaudit

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.agent.AiAuditEntry

@Immutable
data class AiAuditItem(
    val entry: AiAuditEntry,
    val kindRes: Int,
    val resultRes: Int,
    val time: String,
    val detail: String,
    val undoable: Boolean,
    val scopeChange: AiScopeChange? = null,
    val targetRes: Int? = null,
)

/** 访问范围变更的本地化结果：fromRes 为 null 表示变更前没有范围（NONE）。 */
@Immutable
data class AiScopeChange(val fromRes: Int?, val toRes: Int)

@Immutable
data class AiAuditUiState(
    val loading: Boolean = true,
    val items: List<AiAuditItem> = emptyList(),
    val error: Boolean = false,
    val result: AiAuditResult? = null,
    val expanded: Long? = null,
)

sealed interface AiAuditResult {
    data object Undone : AiAuditResult

    data class Failed(val reason: String) : AiAuditResult

    data object Unsupported : AiAuditResult
}

@Immutable
data class AiAuditActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onToggleDetail: (AiAuditItem) -> Unit,
    val onUndo: (AiAuditItem) -> Unit,
)
