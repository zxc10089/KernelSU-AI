package me.weishu.kernelsu.ui.screen.aiconfig

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.agent.AiAccessScope

/** Why the model list could not be fetched. Rendered by both flavours of the sheet. */
@Immutable
sealed interface AiModelListError {
    /** The server answered, but not with a 2xx status: 401 for a bad key, 404 for a wrong path. */
    data class HttpStatus(val code: Int) : AiModelListError

    /** No answer at all: DNS, TLS, timeout, ... [detail] is the raw message. */
    data class Network(val detail: String) : AiModelListError

    /** 2xx, but the body has no "data" array. */
    data object Malformed : AiModelListError

    /** The endpoint field is empty or not an http(s) URL. */
    data object InvalidEndpoint : AiModelListError
}

@Immutable
data class AiConfigUiState(
    val providerId: String = AiProviders.DEFAULT_ID,
    val endpoint: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val allowShell: Boolean = false,
    /**
     * Which part of the device file system the assistant may touch. Fail-closed: nothing is
     * reachable until the user raises the scope on purpose.
     */
    val accessScope: AiAccessScope = AiAccessScope.DEFAULT,
    /**
     * Ceiling on how many model turns one message may chain. A cost knob, not a safety gate: the
     * local action guards apply to every round regardless of this value.
     */
    val maxRounds: Int = AiRounds.DEFAULT,
    /** Whether the "max rounds" bottom sheet is currently open. */
    val roundsSheetVisible: Boolean = false,
    /**
     * Characters one file read may hand back to the model. A token budget knob: the guard still
     * caps every read, and a truncated read always tells the model where to continue.
     */
    val readChunkLimit: Int = AiReadLimits.DEFAULT_CHUNK,
    /** Largest file (KB) read_file returns complete before it falls back to a windowed read. */
    val fullReadThresholdKb: Int = AiReadLimits.DEFAULT_FULL_READ_KB,
    /** Whether the "read limit per call" bottom sheet is currently open. */
    val readLimitSheetVisible: Boolean = false,
    /** Whether the "whole file threshold" bottom sheet is currently open. */
    val fullReadSheetVisible: Boolean = false,
    /** Whether the "choose API provider" bottom sheet is currently open. */
    val providerSheetVisible: Boolean = false,
    /** Whether the "choose access scope" bottom sheet is currently open. */
    val scopeSheetVisible: Boolean = false,
    /**
     * Non-null while a scope *elevation* waits for the typed confirmation. Lowering the scope
     * never goes through this: it takes effect immediately.
     */
    val pendingScopeElevation: AiAccessScope? = null,
    /** Whether the model list sheet is currently open. */
    val modelListSheetVisible: Boolean = false,
    val modelListLoading: Boolean = false,
    val modelList: List<String> = emptyList(),
    val modelListError: AiModelListError? = null,
    /**
     * Reserved slot for request failures (HTTP 401, timeout, ...). Phase 1 never sets it: the
     * configuration screen shows the container so a later phase only has to fill this in.
     */
    val errorMessage: String? = null,
) {
    val provider: AiProvider get() = AiProviders.byId(providerId)

    /** Endpoint is only invalid when it is non-empty and not an http(s) URL. */
    val endpointInvalid: Boolean
        get() = endpoint.isNotBlank() &&
            !endpoint.startsWith("http://") &&
            !endpoint.startsWith("https://")

    /** Placeholder for the endpoint field: the provider default, else the generic hint. */
    val endpointPlaceholder: String? get() = provider.endpoint.ifEmpty { null }

    /** Placeholder for the model field: the per-provider suggestion, else the generic hint. */
    val modelPlaceholder: String? get() = provider.modelHint

    /**
     * The model list is a plain GET on the endpoint host, so a usable endpoint is the only
     * requirement. A key is not required up front: local servers (Ollama, LM Studio) need none,
     * and the providers that do need one answer 401, which the sheet reports.
     */
    val canFetchModels: Boolean get() = endpoint.isNotBlank() && !endpointInvalid

    /**
     * The quick template the two permission switches currently spell out, or null when the user
     * built a custom combination: no card is highlighted in that case.
     */
    val policyTemplate: AiPolicyTemplate?
        get() = AiPolicyTemplate.matching(allowShell, accessScope)
}

@Immutable
data class AiConfigActions(
    val onBack: () -> Unit,
    val onOpenProviderSheet: () -> Unit,
    val onDismissProviderSheet: () -> Unit,
    val onProviderSelected: (String) -> Unit,
    val onEndpointChange: (String) -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onModelNameChange: (String) -> Unit,
    val onFetchModels: () -> Unit,
    val onDismissModelList: () -> Unit,
    val onModelSelected: (String) -> Unit,
    val onOpenConsole: () -> Unit,
    val onTemplateSelected: (AiPolicyTemplate) -> Unit,
    val onAllowShellChange: (Boolean) -> Unit,
    val onOpenRoundsSheet: () -> Unit,
    val onDismissRoundsSheet: () -> Unit,
    val onRoundsSelected: (Int) -> Unit,
    val onOpenReadLimitSheet: () -> Unit,
    val onDismissReadLimitSheet: () -> Unit,
    val onReadLimitSelected: (Int) -> Unit,
    val onOpenFullReadSheet: () -> Unit,
    val onDismissFullReadSheet: () -> Unit,
    val onFullReadThresholdSelected: (Int) -> Unit,
    val onOpenScopeSheet: () -> Unit,
    val onDismissScopeSheet: () -> Unit,
    val onScopeSelected: (AiAccessScope) -> Unit,
    val onCancelScopeElevation: () -> Unit,
    val onConfirmScopeElevation: () -> Unit,
)
