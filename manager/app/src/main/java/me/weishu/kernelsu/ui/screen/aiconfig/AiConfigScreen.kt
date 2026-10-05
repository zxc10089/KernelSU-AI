package me.weishu.kernelsu.ui.screen.aiconfig

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.AiConfigViewModel

/**
 * "AI model and parameters" screen.
 *
 * Follows the settings screen pattern: a pager composable that owns the view model and dispatches
 * to the Miuix / Material implementations. The screen stores parameters, lists the models the
 * configured endpoint offers, and gates the file access scope - it contains no command execution
 * path.
 */
@Composable
fun AiConfigScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<AiConfigViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val actions = AiConfigActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenProviderSheet = viewModel::openProviderSheet,
        onDismissProviderSheet = viewModel::dismissProviderSheet,
        onProviderSelected = viewModel::selectProvider,
        onEndpointChange = viewModel::updateEndpoint,
        onApiKeyChange = viewModel::updateApiKey,
        onModelNameChange = viewModel::updateModelName,
        onFetchModels = viewModel::fetchModels,
        onDismissModelList = viewModel::dismissModelList,
        onModelSelected = viewModel::selectModel,
        onOpenConsole = dropUnlessResumed { navigator.push(Route.AiConsole()) },
        onTemplateSelected = viewModel::applyTemplate,
        onAllowShellChange = viewModel::setAllowShell,
        onOpenRoundsSheet = viewModel::openRoundsSheet,
        onDismissRoundsSheet = viewModel::dismissRoundsSheet,
        onRoundsSelected = viewModel::setMaxRounds,
        onOpenReadLimitSheet = viewModel::openReadLimitSheet,
        onDismissReadLimitSheet = viewModel::dismissReadLimitSheet,
        onReadLimitSelected = viewModel::setReadChunkLimit,
        onOpenFullReadSheet = viewModel::openFullReadSheet,
        onDismissFullReadSheet = viewModel::dismissFullReadSheet,
        onFullReadThresholdSelected = viewModel::setFullReadThresholdKb,
        onOpenScopeSheet = viewModel::openScopeSheet,
        onDismissScopeSheet = viewModel::dismissScopeSheet,
        onScopeSelected = viewModel::selectScope,
        onCancelScopeElevation = viewModel::cancelScopeElevation,
        onConfirmScopeElevation = viewModel::confirmScopeElevation,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> AiConfigPagerMiuix(
            uiState = uiState,
            actions = actions,
        )

        UiMode.Material -> AiConfigPagerMaterial(
            uiState = uiState,
            actions = actions,
        )
    }
}
