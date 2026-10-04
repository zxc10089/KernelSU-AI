package me.weishu.kernelsu.ui.screen.aipermission

import androidx.compose.runtime.Immutable

/** Risk bucket of a root grant, computed locally by the deterministic rules of spec 3.5. */
enum class AiPermissionLevel { HIGH, MEDIUM, LOW }

@Immutable
data class AiPermissionApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val level: AiPermissionLevel,
    val reasonRes: Int,
    val reasonArg: String? = null,
)

/** Outcome of the last revoke attempt; the page turns it into localized copy. */
sealed interface AiPermissionResult {
    data class Revoked(val label: String) : AiPermissionResult
    data class Failed(val reason: String) : AiPermissionResult
}

@Immutable
data class AiPermissionUiState(
    val loading: Boolean = true,
    val apps: List<AiPermissionApp> = emptyList(),
    val scopeLabelRes: Int = 0,
    val error: Boolean = false,
    val aiConfigured: Boolean = false,
    val result: AiPermissionResult? = null,
)

@Immutable
data class AiPermissionActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onRevoke: (AiPermissionApp) -> Unit,
    val onOpenConfig: () -> Unit,
    val onOpenConsole: () -> Unit,
)
