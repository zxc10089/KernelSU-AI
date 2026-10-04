package me.weishu.kernelsu.ui.screen.modulemaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.data.modulemaker.ModuleDraftStore
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.aiassistant.AiConsoleKickoff
import me.weishu.kernelsu.ui.viewmodel.ModuleMakerViewModel

/**
 * Phase 6 entry point: a local form that builds a plain module zip and installs it through ksud.
 *
 * Nothing here writes to /data/adb directly; the AI button only hands the drafting request to the
 * console, which runs the same command gates as any other conversation.
 */
@Composable
fun ModuleMakerScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<ModuleMakerViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // A draft the AI proposed may be waiting when this entry opens; it is consumed exactly once so
    // re-visiting the page later starts from an empty form again.
    LaunchedEffect(Unit) {
        ModuleDraftStore.take()?.let(viewModel::applyDraft)
    }

    val actions = ModuleMakerActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onIdChange = viewModel::setId,
        onNameChange = viewModel::setName,
        onVersionChange = viewModel::setVersion,
        onVersionCodeChange = viewModel::setVersionCode,
        onAuthorChange = viewModel::setAuthor,
        onDescriptionChange = viewModel::setDescription,
        onAddFile = viewModel::addFile,
        onFilePathChange = viewModel::setFilePath,
        onFileContentChange = viewModel::setFileContent,
        onRemoveFile = viewModel::removeFile,
        onInstallScriptChange = viewModel::setInstallScript,
        onBuild = viewModel::build,
        onInstall = viewModel::install,
        onOpenConsole = { navigator.push(Route.AiConsole(kickoff = AiConsoleKickoff.MODULE_DRAFT)) },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> ModuleMakerPagerMiuix(uiState = uiState, actions = actions)

        UiMode.Material -> ModuleMakerPagerMaterial(uiState = uiState, actions = actions)
    }
}
