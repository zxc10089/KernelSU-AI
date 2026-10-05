package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.remote.AiHttpStatusException
import me.weishu.kernelsu.data.remote.AiModelListClient
import me.weishu.kernelsu.data.remote.AiResponseFormatException
import me.weishu.kernelsu.data.repository.AiAuditRepository
import me.weishu.kernelsu.data.repository.AiAuditRepositoryImpl
import me.weishu.kernelsu.data.repository.AiSettingsRepository
import me.weishu.kernelsu.data.repository.AiSettingsRepositoryImpl
import me.weishu.kernelsu.ui.screen.aiconfig.AiConfigUiState
import me.weishu.kernelsu.ui.screen.aiconfig.AiModelListError
import me.weishu.kernelsu.ui.screen.aiconfig.AiPolicyTemplate
import me.weishu.kernelsu.ui.screen.aiconfig.AiProvider
import me.weishu.kernelsu.ui.screen.aiconfig.AiProviders
import me.weishu.kernelsu.ui.screen.aiconfig.AiReadLimits
import me.weishu.kernelsu.ui.screen.aiconfig.AiRounds

class AiConfigViewModel(
    private val repo: AiSettingsRepository = AiSettingsRepositoryImpl(),
    private val audit: AiAuditRepository = AiAuditRepositoryImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiConfigUiState())
    val uiState: StateFlow<AiConfigUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update {
            it.copy(
                providerId = repo.providerId,
                endpoint = repo.endpoint,
                apiKey = repo.apiKey,
                modelName = repo.modelName,
                allowShell = repo.allowShell,
                accessScope = repo.accessScope,
                maxRounds = repo.maxRounds,
                readChunkLimit = repo.readChunkLimit,
                fullReadThresholdKb = repo.fullReadThresholdKb,
            )
        }
        loadRestorableScope()
    }

    fun openProviderSheet() = _uiState.update { it.copy(providerSheetVisible = true) }

    fun dismissProviderSheet() = _uiState.update { it.copy(providerSheetVisible = false) }

    /**
     * Switching the provider also switches the endpoint: every catalogue entry that documents one
     * overwrites the field. The endpoint-less "OpenAI compatible" template keeps what the user
     * typed, because there is nothing to replace it with.
     */
    fun selectProvider(id: String) {
        val provider: AiProvider = AiProviders.byId(id)
        val endpoint = if (provider.hasEndpointDefault) provider.endpoint else repo.endpoint
        repo.providerId = id
        repo.endpoint = endpoint
        _uiState.update {
            it.copy(
                providerId = id,
                endpoint = endpoint,
                providerSheetVisible = false,
                // A list that was fetched from the previous provider must not survive the switch.
                modelList = emptyList(),
                modelListError = null,
                modelListSheetVisible = false,
            )
        }
    }

    fun updateEndpoint(value: String) {
        repo.endpoint = value
        _uiState.update { it.copy(endpoint = value) }
    }

    fun updateApiKey(value: String) {
        repo.apiKey = value
        _uiState.update { it.copy(apiKey = value) }
    }

    fun updateModelName(value: String) {
        repo.modelName = value
        _uiState.update { it.copy(modelName = value) }
    }

    fun setAllowShell(value: Boolean) {
        repo.allowShell = value
        _uiState.update { it.copy(allowShell = value) }
    }

    /**
     * One tap writes the preset's two fields. Command execution flips straight away; the file
     * scope goes through [selectScope], so widening it keeps the typed confirmation.
     */
    fun applyTemplate(template: AiPolicyTemplate) {
        repo.allowShell = template.allowShell
        _uiState.update { it.copy(allowShell = template.allowShell) }
        selectScope(template.scope)
    }

    // -- Max rounds ----------------------------------------------------------------------------

    fun openRoundsSheet() = _uiState.update { it.copy(roundsSheetVisible = true) }

    fun dismissRoundsSheet() = _uiState.update { it.copy(roundsSheetVisible = false) }

    /**
     * Picking a ceiling writes it straight through: it removes the risk of an endless loop, it
     * never widens file or command access, so it needs no typed confirmation.
     */
    fun setMaxRounds(value: Int) {
        val rounds = AiRounds.sanitize(value)
        repo.maxRounds = rounds
        _uiState.update { it.copy(maxRounds = rounds, roundsSheetVisible = false) }
    }

    // -- Read limits ---------------------------------------------------------------------------

    fun openReadLimitSheet() = _uiState.update { it.copy(readLimitSheetVisible = true) }

    fun dismissReadLimitSheet() = _uiState.update { it.copy(readLimitSheetVisible = false) }

    /**
     * How many characters one read may hand back. It only shrinks or grows the window, it never
     * widens file access, so it takes effect immediately without a typed confirmation.
     */
    fun setReadChunkLimit(value: Int) {
        val limit = AiReadLimits.sanitizeChunk(value)
        repo.readChunkLimit = limit
        _uiState.update { it.copy(readChunkLimit = limit, readLimitSheetVisible = false) }
    }

    fun openFullReadSheet() = _uiState.update { it.copy(fullReadSheetVisible = true) }

    fun dismissFullReadSheet() = _uiState.update { it.copy(fullReadSheetVisible = false) }

    /** Largest file read_file returns whole; above it the model gets one window plus a start_line. */
    fun setFullReadThresholdKb(value: Int) {
        val kb = AiReadLimits.sanitizeFullRead(value)
        repo.fullReadThresholdKb = kb
        _uiState.update { it.copy(fullReadThresholdKb = kb, fullReadSheetVisible = false) }
    }

    // -- Model list ----------------------------------------------------------------------------

    fun fetchModels() {
        val current = _uiState.value
        if (!current.canFetchModels) {
            _uiState.update {
                it.copy(
                    modelListSheetVisible = true,
                    modelListLoading = false,
                    modelList = emptyList(),
                    modelListError = AiModelListError.InvalidEndpoint,
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                modelListSheetVisible = true,
                modelListLoading = true,
                modelList = emptyList(),
                modelListError = null,
            )
        }
        val family = current.provider.family
        val endpoint = current.endpoint
        val apiKey = current.apiKey
        viewModelScope.launch {
            val result = AiModelListClient.fetch(family = family, endpoint = endpoint, apiKey = apiKey)
            // A result that lands after the user closed the sheet must not reopen it.
            _uiState.update { state ->
                if (!state.modelListSheetVisible) {
                    state
                } else {
                    state.copy(
                        modelListLoading = false,
                        modelList = result.getOrDefault(emptyList()),
                        modelListError = result.exceptionOrNull()?.toModelListError(),
                    )
                }
            }
        }
    }

    fun dismissModelList() = _uiState.update { it.copy(modelListSheetVisible = false) }

    fun selectModel(name: String) {
        repo.modelName = name
        _uiState.update { it.copy(modelName = name, modelListSheetVisible = false) }
    }

    private fun Throwable.toModelListError(): AiModelListError = when (this) {
        is AiHttpStatusException -> AiModelListError.HttpStatus(code)
        is AiResponseFormatException -> AiModelListError.Malformed
        is IllegalArgumentException -> AiModelListError.InvalidEndpoint
        else -> AiModelListError.Network(message ?: this::class.java.simpleName)
    }

    // -- Access scope --------------------------------------------------------------------------

    fun openScopeSheet() = _uiState.update { it.copy(scopeSheetVisible = true) }

    fun dismissScopeSheet() = _uiState.update { it.copy(scopeSheetVisible = false) }

    /**
     * Widening the scope is the sensitive direction, so it parks the request in
     * [AiConfigUiState.pendingScopeElevation] until the user types the confirmation keyword.
     * Narrowing it applies immediately.
     */
    fun selectScope(scope: AiAccessScope) {
        if (scope.isElevationFrom(repo.accessScope)) {
            _uiState.update { it.copy(scopeSheetVisible = false, pendingScopeElevation = scope) }
            return
        }
        applyScope(scope)
    }

    fun cancelScopeElevation() = _uiState.update { it.copy(pendingScopeElevation = null) }

    fun confirmScopeElevation() {
        val target = _uiState.value.pendingScopeElevation ?: return
        applyScope(target)
    }

    private fun applyScope(scope: AiAccessScope) {
        val before = repo.accessScope
        _uiState.update { it.copy(scopeSheetVisible = false, pendingScopeElevation = null) }
        if (before == scope) return
        repo.accessScope = scope
        _uiState.update { it.copy(accessScope = scope) }
        // Every widening or narrowing is recorded, so the previous value can be restored later.
        // It is written with ORIGIN_USER: the assistant history page is not the place for the
        // user's own settings change, and this screen offers its own "restore previous" action.
        viewModelScope.launch {
            audit.append(
                AiAuditEntry(
                    ts = System.currentTimeMillis(),
                    kind = AiAuditEntry.KIND_SCOPE_CHANGE,
                    target = scope.name,
                    before = before.name,
                    after = scope.name,
                    scope = scope.name,
                    undoable = true,
                    origin = AiAuditEntry.ORIGIN_USER,
                )
            )
            loadRestorableScope()
        }
    }

    /**
     * Puts the scope back to what it was before the last recorded change. The restore goes through
     * [selectScope], so widening access still needs the typed confirmation; it writes its own
     * record, which makes the restore the new "previous value".
     */
    fun restorePreviousScope() {
        val target = _uiState.value.restoreScope ?: return
        selectScope(target)
    }

    /**
     * Reads the previous scope out of the audit file. Kept apart from [refresh] because it touches
     * the file system; the state lands when the read finishes.
     */
    private fun loadRestorableScope() {
        viewModelScope.launch {
            val entry = audit.latest(AiAuditEntry.KIND_SCOPE_CHANGE, AiAuditEntry.ORIGIN_USER)
            val previous = entry?.before?.let { AiAccessScope.fromKey(it) }
            _uiState.update { state ->
                state.copy(restoreScope = previous?.takeIf { it != state.accessScope })
            }
        }
    }
}
