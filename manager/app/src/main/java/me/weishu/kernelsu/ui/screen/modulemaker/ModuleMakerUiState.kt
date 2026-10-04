package me.weishu.kernelsu.ui.screen.modulemaker

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.modulemaker.ModuleDraft

@Immutable
data class ModuleMakerUiState(
    val draft: ModuleDraft = ModuleDraft(),
    /** True when the current draft came from the AI console and has not been retyped from scratch. */
    val fromAi: Boolean = false,
    val errors: List<Int> = emptyList(),
    val failure: String = "",
    val building: Boolean = false,
    val zipPath: String = "",
    val zipSize: String = "",
    val output: List<String> = emptyList(),
    val success: Boolean? = null,
    val showReboot: Boolean = false,
)

@Immutable
data class ModuleMakerActions(
    val onBack: () -> Unit,
    val onIdChange: (String) -> Unit,
    val onNameChange: (String) -> Unit,
    val onVersionChange: (String) -> Unit,
    val onVersionCodeChange: (String) -> Unit,
    val onAuthorChange: (String) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onAddFile: () -> Unit,
    val onFilePathChange: (Int, String) -> Unit,
    val onFileContentChange: (Int, String) -> Unit,
    val onRemoveFile: (Int) -> Unit,
    val onInstallScriptChange: (String) -> Unit,
    val onBuild: () -> Unit,
    val onInstall: () -> Unit,
    val onOpenConsole: () -> Unit,
)
