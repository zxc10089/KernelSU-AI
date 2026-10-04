package me.weishu.kernelsu.ui.screen.aiconflict

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.model.ModuleConflictLevel

@Immutable
data class ModuleConflictItem(
    val level: ModuleConflictLevel,
    val typeRes: Int,
    val subject: String,
    val modulesText: String,
)

@Immutable
data class ModuleConflictUiState(
    val loading: Boolean = true,
    val scopeMissing: Boolean = false,
    val scanned: Int = 0,
    val conflicts: List<ModuleConflictItem> = emptyList(),
    val error: Boolean = false,
    val errorMessage: String = "",
    val aiConfigured: Boolean = false,
)

@Immutable
data class ModuleConflictActions(
    val onBack: () -> Unit,
    val onRescan: () -> Unit,
    val onOpenConfig: () -> Unit,
    val onOpenConsole: () -> Unit,
)
