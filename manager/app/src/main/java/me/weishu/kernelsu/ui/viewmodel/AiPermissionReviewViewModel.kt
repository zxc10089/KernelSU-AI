package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAction
import me.weishu.kernelsu.data.agent.AiActionExecutor
import me.weishu.kernelsu.data.agent.AiActionExecutorImpl
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.data.repository.AiSettingsRepository
import me.weishu.kernelsu.data.repository.AiSettingsRepositoryImpl
import me.weishu.kernelsu.data.repository.SuperUserRepository
import me.weishu.kernelsu.data.repository.SuperUserRepositoryImpl
import me.weishu.kernelsu.ui.screen.aipermission.AiPermissionApp
import me.weishu.kernelsu.ui.screen.aipermission.AiPermissionLevel
import me.weishu.kernelsu.ui.screen.aipermission.AiPermissionResult
import me.weishu.kernelsu.ui.screen.aipermission.AiPermissionUiState

/**
 * Phase 3: AI permission review.
 *
 * The risk labels are deterministic local rules (spec 3.5) so the page works offline and every
 * verdict can be recomputed from the profile fields. Revoking reuses the executor, which already
 * guards system apps and writes the audit entry the audit page undoes later.
 */
class AiPermissionReviewViewModel(
    private val superUser: SuperUserRepository = SuperUserRepositoryImpl(),
    private val settings: AiSettingsRepository = AiSettingsRepositoryImpl(),
    private val executor: AiActionExecutor = AiActionExecutorImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiPermissionUiState())
    val uiState: StateFlow<AiPermissionUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val configured = settings.endpoint.isNotBlank() && settings.modelName.isNotBlank()
            _uiState.update { it.copy(loading = true, error = false) }
            val result = superUser.getAppList()
            result.fold(
                onSuccess = { (apps, _) ->
                    val granted = apps
                        .filter { it.allowSu }
                        .sortedWith(compareBy({ levelOf(it).ordinal }, { it.label.lowercase() }))
                    _uiState.update { state ->
                        state.copy(
                            loading = false,
                            apps = granted.map(::toItem),
                            scopeLabelRes = settings.accessScope.labelRes,
                            aiConfigured = configured,
                            error = false,
                        )
                    }
                },
                onFailure = {
                    _uiState.update { state ->
                        state.copy(loading = false, error = true, aiConfigured = configured)
                    }
                },
            )
        }
    }

    fun revoke(app: AiPermissionApp) {
        viewModelScope.launch {
            val outcome = runCatching { executor.execute(AiAction.RevokeSu(app.packageName), settings.accessScope) }
            val execution = outcome.getOrNull()
            val result = if (execution != null && execution.ok) {
                AiPermissionResult.Revoked(app.label)
            } else {
                val reason = execution?.output ?: outcome.exceptionOrNull()?.message.orEmpty()
                AiPermissionResult.Failed(reason)
            }
            _uiState.update { it.copy(result = result) }
            if (execution != null && execution.ok) refresh()
        }
    }

    private fun toItem(app: AppInfo): AiPermissionApp {
        val reason = reasonOf(app)
        return AiPermissionApp(
            packageName = app.packageName,
            label = app.label,
            uid = app.uid,
            level = levelOf(app),
            reasonRes = reason.first,
            reasonArg = reason.second,
        )
    }

    private fun levelOf(app: AppInfo): AiPermissionLevel {
        val profile = app.profile ?: return AiPermissionLevel.LOW
        if (app.uid < SYSTEM_UID_FLOOR || profile.capabilities.isNotEmpty() || profile.groups.isNotEmpty()) {
            return AiPermissionLevel.HIGH
        }
        if (!profile.rootUseDefault || profile.rootTemplate != null || !profile.umountModules || app.label.isBlank()) {
            return AiPermissionLevel.MEDIUM
        }
        return AiPermissionLevel.LOW
    }

    private fun reasonOf(app: AppInfo): Pair<Int, String?> {
        val profile = app.profile ?: return R.string.ai_permission_reason_default to null
        return when {
            app.uid < SYSTEM_UID_FLOOR -> R.string.ai_permission_reason_system to app.uid.toString()
            profile.capabilities.isNotEmpty() -> R.string.ai_permission_reason_capabilities to null
            profile.groups.isNotEmpty() -> R.string.ai_permission_reason_groups to null
            !profile.rootUseDefault -> R.string.ai_permission_reason_root_custom to null
            profile.rootTemplate != null -> R.string.ai_permission_reason_template to null
            !profile.umountModules -> R.string.ai_permission_reason_umount to null
            app.label.isBlank() -> R.string.ai_permission_reason_nolabel to null
            else -> R.string.ai_permission_reason_default to null
        }
    }

    private companion object {
        /** Below this uid Android treats the app as system or privileged (spec 3.5). */
        const val SYSTEM_UID_FLOOR = 10000
    }
}
