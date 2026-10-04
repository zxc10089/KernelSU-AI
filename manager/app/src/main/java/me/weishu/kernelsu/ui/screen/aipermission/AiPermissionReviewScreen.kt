package me.weishu.kernelsu.ui.screen.aipermission

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
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.aiassistant.AiConsoleKickoff
import me.weishu.kernelsu.ui.viewmodel.AiPermissionReviewViewModel

/**
 * Phase 3 entry point: local risk labels plus a guarded revoke.
 *
 * The revoke confirmation lives here, not in the flavour pages, so both UI modes provably go
 * through the same shared dialog before the executor touches a grant.
 */
@Composable
fun AiPermissionReviewScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<AiPermissionReviewViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingRevoke by remember { mutableStateOf<AiPermissionApp?>(null) }
    val currentRevoke by rememberUpdatedState(pendingRevoke)
    val confirmLabel = stringResource(R.string.ai_permission_revoke)
    val dismissLabel = stringResource(android.R.string.cancel)
    val revokeTitle = stringResource(R.string.ai_permission_revoke_title)
    val revokeMessage = stringResource(R.string.ai_permission_revoke_message)
    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            currentRevoke?.let(viewModel::revoke)
            pendingRevoke = null
        },
        onDismiss = { pendingRevoke = null },
    )
    LaunchedEffect(pendingRevoke) {
        val app = pendingRevoke ?: return@LaunchedEffect
        confirmDialog.showConfirm(
            title = revokeTitle,
            content = revokeMessage.format(app.label),
            confirm = confirmLabel,
            dismiss = dismissLabel,
        )
    }

    val actions = AiPermissionActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onRefresh = viewModel::refresh,
        onRevoke = { app -> pendingRevoke = app },
        onOpenConfig = { navigator.push(Route.AiConfig) },
        onOpenConsole = { navigator.push(Route.AiConsole(kickoff = AiConsoleKickoff.ROOT_REVIEW)) },
    )

    val resultText = when (val result = uiState.result) {
        is AiPermissionResult.Revoked -> stringResource(R.string.ai_permission_revoked, result.label)
        is AiPermissionResult.Failed -> stringResource(R.string.ai_permission_revoke_failed, result.reason)
        null -> null
    }

    when (LocalUiMode.current) {
        UiMode.Miuix -> AiPermissionReviewPagerMiuix(
            uiState = uiState,
            actions = actions,
            resultText = resultText,
        )

        UiMode.Material -> AiPermissionReviewPagerMaterial(
            uiState = uiState,
            actions = actions,
            resultText = resultText,
        )
    }
}
