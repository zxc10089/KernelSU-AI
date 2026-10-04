package me.weishu.kernelsu.ui.screen.aiconflict

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.aiassistant.AiConsoleKickoff
import me.weishu.kernelsu.ui.viewmodel.ModuleConflictViewModel

/**
 * Phase 4 entry point: a local read-only scan over the installed modules.
 *
 * The page itself never mutates a module; the AI button only hands the question to the console,
 * which runs the same command gates as any other conversation.
 */
@Composable
fun ModuleConflictScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<ModuleConflictViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val actions = ModuleConflictActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onRescan = viewModel::rescan,
        onOpenConfig = { navigator.push(Route.AiConfig) },
        onOpenConsole = { navigator.push(Route.AiConsole(kickoff = AiConsoleKickoff.MODULE_CONFLICT)) },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> ModuleConflictPagerMiuix(uiState = uiState, actions = actions)

        UiMode.Material -> ModuleConflictPagerMaterial(uiState = uiState, actions = actions)
    }
}
