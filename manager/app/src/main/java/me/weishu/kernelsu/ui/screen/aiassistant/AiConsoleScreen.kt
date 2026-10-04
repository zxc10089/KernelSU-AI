package me.weishu.kernelsu.ui.screen.aiassistant

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
import me.weishu.kernelsu.ui.component.material.AiExecConfirmDialogMaterial
import me.weishu.kernelsu.ui.component.miuix.AiExecConfirmDialogMiuix
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.AiConsoleViewModel

/**
 * The AI console: conversation, action cards and the two execution gates (design brief section 4).
 *
 * The gates are wired here rather than inside the flavour composables so that both UI modes provably
 * share one implementation: LIGHT reuses the app wide confirm dialog, STRICT always gets the red one.
 */
@Composable
fun AiConsoleScreen(kickoff: String? = null) {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<AiConsoleViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Clearing throws the conversation away and cancels a run that is still answering, so the
    // button only raises a request here; the confirmation below performs the actual clear.
    var clearRequested by remember { mutableStateOf(false) }

    val actions = AiConsoleActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onInputChange = viewModel::setInput,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onClear = { clearRequested = true },
        onToggleLog = viewModel::toggleLog,
        onConfirm = viewModel::confirm,
        onCancel = viewModel::cancel,
        onOpenConfig = { navigator.push(Route.AiConfig) },
        onOpenAudit = { navigator.push(Route.AiAudit) },
        onOpenModuleMaker = { navigator.push(Route.ModuleMaker) },
        onTogglePlanMode = viewModel::setPlanMode,
        onApprovePlan = viewModel::approvePlan,
        onRevisePlan = viewModel::revisePlan,
        onAbortPlan = viewModel::abortPlan,
        onAttachmentsAdded = viewModel::addAttachments,
        onRemoveAttachment = viewModel::removeAttachment,
        onAttachmentError = viewModel::attachmentFailed,
    )

    // rememberConfirmDialog keeps its callbacks for the lifetime of the composition, so the pending
    // request has to be read through rememberUpdatedState or the dialog would answer a stale action.
    val pending by rememberUpdatedState(uiState.confirm)
    val confirmLabel = stringResource(R.string.ai_console_confirm_exec_button)
    val cancelLabel = stringResource(android.R.string.cancel)
    val confirmDialog = rememberConfirmDialog(
        onConfirm = { pending?.let { viewModel.confirm(it.actionId) } },
        onDismiss = { pending?.let { viewModel.cancel(it.actionId) } },
    )
    val lightRequest = uiState.confirm as? AiConfirmRequest.Light
    val lightTitle = if (lightRequest != null) stringResource(lightRequest.titleRes) else null
    val lightContent = if (lightRequest != null) {
        stringResource(lightRequest.labelRes) + "\n" + lightRequest.payload
    } else {
        null
    }
    LaunchedEffect(uiState.confirm) {
        if (lightTitle != null && lightContent != null) {
            confirmDialog.showConfirm(
                title = lightTitle,
                content = lightContent,
                confirm = confirmLabel,
                dismiss = cancelLabel,
            )
        }
    }

    // Clearing is destructive and stops an answering run, so it gets its own confirmation instead
    // of clearing straight from the header button.
    val clearTitle = stringResource(R.string.ai_console_clear_confirm_title)
    val clearMessage = stringResource(R.string.ai_console_clear_confirm_message)
    val clearLabel = stringResource(R.string.ai_console_clear)
    val clearDialog = rememberConfirmDialog(
        onConfirm = {
            clearRequested = false
            viewModel.clearConversation()
        },
        onDismiss = { clearRequested = false },
    )
    LaunchedEffect(clearRequested) {
        if (clearRequested) {
            clearDialog.showConfirm(
                title = clearTitle,
                content = clearMessage,
                confirm = clearLabel,
                dismiss = cancelLabel,
            )
        }
    }

    // The superuser page opens the console with its root review request; a fresh conversation runs it
    // straight away and an existing one only gets it in the input box (see startKickoff).
    val kickoffPrompt = when (kickoff) {
        AiConsoleKickoff.ROOT_REVIEW -> stringResource(R.string.ai_root_review_prompt)
        AiConsoleKickoff.MODULE_CONFLICT -> stringResource(R.string.ai_conflict_prompt)
        AiConsoleKickoff.MODULE_DRAFT -> stringResource(R.string.ai_module_draft_prompt)
        else -> null
    }
    LaunchedEffect(kickoffPrompt) {
        if (kickoffPrompt != null) {
            // The module draft has to be approved as one batch, so the entry point asks for plan mode
            // and then sends the request (user asked for exactly this pairing).
            if (kickoff == AiConsoleKickoff.MODULE_DRAFT) viewModel.setPlanMode(true)
            viewModel.startKickoff(kickoffPrompt)
        }
    }

    when (LocalUiMode.current) {
        UiMode.Miuix -> {
            AiConsolePagerMiuix(uiState = uiState, actions = actions)
            AiExecConfirmDialogMiuix(
                request = uiState.confirm as? AiConfirmRequest.Strict,
                onConfirm = { pending?.let { viewModel.confirm(it.actionId) } },
                onDismissRequest = { pending?.let { viewModel.cancel(it.actionId) } },
            )
        }

        UiMode.Material -> {
            AiConsolePagerMaterial(uiState = uiState, actions = actions)
            AiExecConfirmDialogMaterial(
                request = uiState.confirm as? AiConfirmRequest.Strict,
                onConfirm = { pending?.let { viewModel.confirm(it.actionId) } },
                onDismissRequest = { pending?.let { viewModel.cancel(it.actionId) } },
            )
        }
    }
}
