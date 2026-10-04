package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.modulemaker.ModuleDraft
import me.weishu.kernelsu.data.modulemaker.ModuleDraftError
import me.weishu.kernelsu.data.modulemaker.ModuleDraftValidator
import me.weishu.kernelsu.data.modulemaker.ModuleMaker
import me.weishu.kernelsu.data.modulemaker.ModuleMakerFile
import me.weishu.kernelsu.ui.screen.modulemaker.ModuleMakerUiState

/**
 * Phase 6 module maker: the form collects a draft, [ModuleMaker] turns it into a plain module zip in
 * cacheDir and ksud installs it, which is what the real module installer does. The page never
 * touches /data/adb itself, and build + install is shared with the AI console's make_module action so
 * the two paths cannot drift.
 */
class ModuleMakerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ModuleMakerUiState())
    val uiState: StateFlow<ModuleMakerUiState> = _uiState.asStateFlow()

    /**
     * Applies a draft the AI console just proposed, so the page opens pre-filled and labels the
     * form as AI-generated. Called by the screen on entry, which also covers re-entering the page
     * while this ViewModel instance is still alive (a reused ViewModel never runs [init] again).
     */
    fun applyDraft(draft: ModuleDraft) {
        _uiState.update {
            it.copy(
                draft = draft,
                fromAi = true,
                errors = emptyList(),
                failure = "",
                zipPath = "",
                zipSize = "",
                output = emptyList(),
                success = null,
                showReboot = false,
            )
        }
    }

    fun setId(value: String) = updateDraft { it.copy(id = value.take(64)) }

    fun setName(value: String) = updateDraft { it.copy(name = value.take(64)) }

    fun setVersion(value: String) = updateDraft { it.copy(version = value.take(32)) }

    fun setVersionCode(value: String) = updateDraft { it.copy(versionCode = value.filter { c -> c.isDigit() }.take(9)) }

    fun setAuthor(value: String) = updateDraft { it.copy(author = value.take(64)) }

    fun setDescription(value: String) = updateDraft { it.copy(description = value.take(400)) }

    fun setInstallScript(value: String) = updateDraft { it.copy(installScript = value) }

    fun addFile() = updateDraft { it.copy(files = it.files + ModuleMakerFile()) }

    fun removeFile(index: Int) = updateDraft { draft ->
        if (index !in draft.files.indices) {
            draft
        } else {
            draft.copy(files = draft.files.filterIndexed { i, _ -> i != index })
        }
    }

    fun setFilePath(index: Int, value: String) = updateFile(index) { it.copy(path = value) }

    fun setFileContent(index: Int, value: String) = updateFile(index) { it.copy(content = value) }

    fun build() {
        if (_uiState.value.building) return
        val draft = _uiState.value.draft
        val errors = ModuleDraftValidator.validate(draft)
        if (errors.isNotEmpty()) {
            _uiState.update { state ->
                state.copy(
                    errors = errors.map { error -> errorRes(error) },
                    failure = "",
                    output = emptyList(),
                    success = null,
                    showReboot = false,
                )
            }
            return
        }
        _uiState.update { it.copy(building = true, errors = emptyList(), failure = "", success = null) }
        viewModelScope.launch {
            val result = ModuleMaker.buildAndInstall(draft, install = false)
            _uiState.update { state ->
                state.copy(
                    building = false,
                    errors = if (result.ok) emptyList() else listOf(R.string.module_maker_error_build),
                    failure = if (result.ok) "" else result.message,
                    zipPath = if (result.ok) result.zipPath else state.zipPath,
                    zipSize = if (result.ok) result.sizeText else state.zipSize,
                    output = emptyList(),
                    success = null,
                    showReboot = false,
                )
            }
        }
    }

    fun install() {
        if (_uiState.value.building) return
        if (_uiState.value.zipPath.isBlank()) {
            _uiState.update { it.copy(errors = listOf(R.string.module_maker_need_build), failure = "") }
            return
        }
        val draft = _uiState.value.draft
        _uiState.update {
            it.copy(building = true, errors = emptyList(), failure = "", output = emptyList(), success = null)
        }
        viewModelScope.launch {
            val result = ModuleMaker.buildAndInstall(draft, install = true)
            _uiState.update { state ->
                state.copy(
                    building = false,
                    zipPath = result.zipPath.ifBlank { state.zipPath },
                    zipSize = result.sizeText.ifBlank { state.zipSize },
                    success = result.ok,
                    output = if (result.output.isBlank()) emptyList() else result.output.split("\n"),
                    showReboot = result.ok && result.showReboot,
                    failure = if (result.ok) "" else result.message,
                )
            }
        }
    }

    private fun updateFile(index: Int, block: (ModuleMakerFile) -> ModuleMakerFile) = updateDraft { draft ->
        if (index !in draft.files.indices) {
            draft
        } else {
            draft.copy(files = draft.files.mapIndexed { i, file -> if (i == index) block(file) else file })
        }
    }

    private fun updateDraft(block: (ModuleDraft) -> ModuleDraft) {
        _uiState.update {
            it.copy(
                draft = block(it.draft),
                errors = emptyList(),
                failure = "",
                zipPath = "",
                zipSize = "",
                output = emptyList(),
                success = null,
                showReboot = false,
            )
        }
    }

    private fun errorRes(error: ModuleDraftError): Int = when (error) {
        ModuleDraftError.InvalidId -> R.string.module_maker_error_id
        ModuleDraftError.MissingName -> R.string.module_maker_error_name
        ModuleDraftError.MissingVersion -> R.string.module_maker_error_version
        ModuleDraftError.InvalidVersionCode -> R.string.module_maker_error_version_code
        is ModuleDraftError.InvalidFilePath -> R.string.module_maker_error_file_path
        is ModuleDraftError.DuplicateFilePath -> R.string.module_maker_error_file_duplicate
        is ModuleDraftError.TooManyFiles -> R.string.module_maker_error_too_many_files
        is ModuleDraftError.FileTooLarge -> R.string.module_maker_error_file_too_large
        is ModuleDraftError.TotalTooLarge -> R.string.module_maker_error_total_too_large
    }
}