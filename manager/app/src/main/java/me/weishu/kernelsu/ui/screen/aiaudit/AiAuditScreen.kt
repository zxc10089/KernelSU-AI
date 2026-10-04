package me.weishu.kernelsu.ui.screen.aiaudit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.viewmodel.AiAuditViewModel

/**
 * Phase 5 entry point: the recorded actions plus a guarded undo.
 *
 * The confirmation lives here, not in the flavour pages, so both UI modes provably go through the
 * same shared dialog before a reversal is written back to the device.
 */
@Composable
fun AiAuditScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<AiAuditViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingUndo by remember { mutableStateOf<AiAuditItem?>(null) }
    val currentUndo by rememberUpdatedState(pendingUndo)
    val undoLabel = stringResource(R.string.ai_audit_undo)
    val dismissLabel = stringResource(android.R.string.cancel)
    val undoTitle = stringResource(R.string.ai_audit_undo_title)
    val undoMessage = stringResource(R.string.ai_audit_undo_message)
    val pendingTarget = pendingUndo?.let { item -> item.entry.target.ifBlank { stringResource(item.kindRes) } }
    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            currentUndo?.let(viewModel::undo)
            pendingUndo = null
        },
        onDismiss = { pendingUndo = null },
    )
    LaunchedEffect(pendingUndo) {
        val target = pendingTarget ?: return@LaunchedEffect
        confirmDialog.showConfirm(
            title = undoTitle,
            content = undoMessage.format(target),
            confirm = undoLabel,
            dismiss = dismissLabel,
        )
    }

    val actions = AiAuditActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onRefresh = viewModel::refresh,
        onToggleDetail = viewModel::toggleDetail,
        onUndo = { item -> pendingUndo = item },
    )

    val resultText = when (val result = uiState.result) {
        AiAuditResult.Undone -> stringResource(R.string.ai_audit_undo_done)
        is AiAuditResult.Failed -> stringResource(R.string.ai_audit_undo_failed, result.reason)
        AiAuditResult.Unsupported -> stringResource(R.string.ai_audit_undo_unsupported)
        null -> null
    }

    when (LocalUiMode.current) {
        UiMode.Miuix -> AiAuditPagerMiuix(uiState = uiState, actions = actions, resultText = resultText)

        UiMode.Material -> AiAuditPagerMaterial(uiState = uiState, actions = actions, resultText = resultText)
    }
}
