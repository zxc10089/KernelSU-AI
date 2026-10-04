package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.ModuleConflict
import me.weishu.kernelsu.data.model.ModuleConflictType
import me.weishu.kernelsu.data.repository.AiSettingsRepository
import me.weishu.kernelsu.data.repository.AiSettingsRepositoryImpl
import me.weishu.kernelsu.data.repository.ModuleConflictRepository
import me.weishu.kernelsu.data.repository.ModuleConflictRepositoryImpl
import me.weishu.kernelsu.ui.screen.aiconflict.ModuleConflictItem
import me.weishu.kernelsu.ui.screen.aiconflict.ModuleConflictUiState

class ModuleConflictViewModel(
    private val moduleConflicts: ModuleConflictRepository = ModuleConflictRepositoryImpl(),
    private val settings: AiSettingsRepository = AiSettingsRepositoryImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModuleConflictUiState())
    val uiState: StateFlow<ModuleConflictUiState> = _uiState.asStateFlow()

    init {
        rescan()
    }

    fun rescan() {
        if (!settings.accessScope.coversDataAdb) {
            _uiState.update {
                it.copy(
                    loading = false,
                    scopeMissing = true,
                    error = false,
                    aiConfigured = configured(),
                    conflicts = emptyList(),
                )
            }
            return
        }
        _uiState.update {
            it.copy(loading = true, scopeMissing = false, error = false, aiConfigured = configured())
        }
        viewModelScope.launch {
            moduleConflicts.scan().fold(
                onSuccess = { report ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = false,
                            scanned = report.scannedModules,
                            conflicts = report.conflicts.map { conflict -> toItem(conflict) },
                            aiConfigured = configured(),
                        )
                    }
                },
                onFailure = { failure ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = true,
                            errorMessage = failure.message.orEmpty(),
                        )
                    }
                },
            )
        }
    }

    private fun configured(): Boolean =
        settings.endpoint.isNotBlank() && settings.modelName.isNotBlank()

    private fun toItem(conflict: ModuleConflict): ModuleConflictItem = ModuleConflictItem(
        level = conflict.level,
        typeRes = typeRes(conflict.type),
        subject = conflict.subject,
        modulesText = conflict.modules.joinToString(", "),
    )

    private fun typeRes(type: ModuleConflictType): Int = when (type) {
        ModuleConflictType.FILE_OVERLAP -> R.string.ai_conflict_type_file_overlap
        ModuleConflictType.DUPLICATE_ID -> R.string.ai_conflict_type_duplicate_id
        ModuleConflictType.PROP_DUPLICATE -> R.string.ai_conflict_type_prop_duplicate
        ModuleConflictType.DIR_ID_MISMATCH -> R.string.ai_conflict_type_dir_id_mismatch
        ModuleConflictType.MISSING_PROP -> R.string.ai_conflict_type_missing_prop
    }
}
