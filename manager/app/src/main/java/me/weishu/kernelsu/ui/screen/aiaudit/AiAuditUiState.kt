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

/**
 * 一个批次：用户在同一个列表弹窗里确认后，助手执行时写下的那组记录。
 *
 * [items] 与历史列表里的是同一批行，所以每条成员自带类型、详情和单条撤销能力；[undoableCount]
 * 是其中真的能反向执行的条数，页面用它在批次行上如实显示「几项可撤销」，而不是把整批都说成可撤销。
 */
@Immutable
data class AiAuditBatch(
    val batchId: String,
    val time: String,
    val items: List<AiAuditItem>,
    val undoableCount: Int,
)

@Immutable
data class AiAuditUiState(
    val loading: Boolean = true,
    val items: List<AiAuditItem> = emptyList(),
    val batches: List<AiAuditBatch> = emptyList(),
    val error: Boolean = false,
    val result: AiAuditResult? = null,
    val expanded: Long? = null,
    val expandedBatch: String? = null,
)

sealed interface AiAuditResult {
    /** [count] 条已还原；其中 [failed] 条没能还原，必须如实告诉用户而不是只说「已撤销」。 */
    data class Undone(val count: Int, val failed: Int = 0) : AiAuditResult

    data class Failed(val reason: String) : AiAuditResult

    data object Unsupported : AiAuditResult
}

@Immutable
data class AiAuditActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onToggleDetail: (AiAuditItem) -> Unit,
    val onUndo: (AiAuditItem) -> Unit,
    val onToggleBatchDetail: (AiAuditBatch) -> Unit,
    val onUndoBatch: (AiAuditBatch) -> Unit,
)
