package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import me.weishu.kernelsu.data.remote.AiImage
import me.weishu.kernelsu.ui.screen.aiassistant.AiAttachment
import me.weishu.kernelsu.ui.screen.aiassistant.formatSize
import me.weishu.kernelsu.ui.screen.aiassistant.promptBlock
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiAction
import me.weishu.kernelsu.data.agent.AiActionExecutor
import me.weishu.kernelsu.data.agent.AiActionExecutorImpl
import me.weishu.kernelsu.data.agent.AiActionParser
import me.weishu.kernelsu.data.agent.AiAgentLog
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.agent.AiGuardDecision
import me.weishu.kernelsu.data.agent.AiPathGuard
import me.weishu.kernelsu.data.agent.AiPrompt
import me.weishu.kernelsu.data.agent.AiSafetyTier
import me.weishu.kernelsu.data.modulemaker.ModuleDraft
import me.weishu.kernelsu.data.modulemaker.ModuleDraftStore
import me.weishu.kernelsu.data.remote.AiApiFamily
import me.weishu.kernelsu.data.remote.AiChatClient
import me.weishu.kernelsu.data.remote.AiChatErrorKind
import me.weishu.kernelsu.data.remote.AiChatException
import me.weishu.kernelsu.data.remote.AiChatMessage
import me.weishu.kernelsu.data.repository.AiAuditRepository
import me.weishu.kernelsu.data.repository.AiAuditRepositoryImpl
import me.weishu.kernelsu.data.repository.AiSettingsRepository
import me.weishu.kernelsu.data.repository.AiSettingsRepositoryImpl
import me.weishu.kernelsu.data.repository.DeviceInfoRepository
import me.weishu.kernelsu.data.repository.DeviceInfoRepositoryImpl
import me.weishu.kernelsu.ui.screen.aiconfig.AiProviders
import me.weishu.kernelsu.ui.screen.aiassistant.AiActionItem
import me.weishu.kernelsu.ui.screen.aiassistant.AiActionStatus
import me.weishu.kernelsu.ui.screen.aiassistant.AiConfirmRequest
import me.weishu.kernelsu.ui.screen.aiassistant.AiConsoleMessage
import me.weishu.kernelsu.ui.screen.aiassistant.AiConsoleSession
import me.weishu.kernelsu.ui.screen.aiassistant.AiConsoleUiState
import me.weishu.kernelsu.ui.screen.aiassistant.AiPlanItem
import me.weishu.kernelsu.ui.screen.aiassistant.AiRole

/**
 * Drives the AI console: client side recon, model turns, the two gates and the audit trail.
 *
 * The conversation itself lives in [AiConsoleSession] because the console is reachable from several
 * entry points and must survive navigation; this view model only owns the transient per turn state.
 * The number of model turns one request may chain is the ceiling the user picks on the
 * configuration screen ([me.weishu.kernelsu.ui.screen.aiconfig.AiRounds]), so a model that keeps
 * proposing actions cannot spin.
 */
class AiConsoleViewModel(
    private val repo: AiSettingsRepository = AiSettingsRepositoryImpl(),
    private val audit: AiAuditRepository = AiAuditRepositoryImpl(),
    private val recon: DeviceInfoRepository = DeviceInfoRepositoryImpl(),
    private val executor: AiActionExecutor = AiActionExecutorImpl(audit),
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiConsoleUiState())
    val uiState: StateFlow<AiConsoleUiState> = _uiState.asStateFlow()

    /** Confirmation gates currently waiting for the user, keyed by action id. */
    private val pending = HashMap<Long, CompletableDeferred<Boolean>>()
    private var job: Job? = null
    private var reconSent = false

    /** Model turns already spent on the current request; plan mode suspends the loop between rounds. */
    private var roundsUsed = 0

    init {
        viewModelScope.launch {
            AiConsoleSession.messages.collect { list -> _uiState.update { it.copy(messages = list) } }
        }
        viewModelScope.launch {
            AiAgentLog.lines.collect { lines -> _uiState.update { it.copy(agentLog = lines) } }
        }
        refreshConfig()
    }

    /** Re-reads the configuration, so returning from the settings page is enough to pick it up. */
    fun refreshConfig() {
        _uiState.update {
            it.copy(
                configured = isConfigured(),
                model = repo.modelName,
                scope = repo.accessScope,
                allowShell = repo.allowShell,
            )
        }
    }

    fun setInput(value: String) = _uiState.update { it.copy(input = value) }

    fun toggleLog() = _uiState.update { it.copy(logExpanded = !it.logExpanded) }

    fun clearConversation() {
        job?.cancel()
        job = null
        AiConsoleSession.clear()
        reconSent = false
        AiAgentLog.clear()
        AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_cleared))
        roundsUsed = 0
        _uiState.update {
            it.copy(
                sending = false,
                confirm = null,
                errorMessage = null,
                input = "",
                plan = null,
                attachments = emptyList(),
            )
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_stopped))
        _uiState.update { it.copy(sending = false, confirm = null) }
        AiConsoleSession.replace(
            AiConsoleSession.messages.value.map { if (it.streaming) it.copy(streaming = false) else it },
        )
    }

    /**
     * Opens the console with a prepared request (the superuser page root review). A fresh conversation is
     * sent immediately; an existing one only gets the text in the input box so no turn is replayed.
     */
    fun startKickoff(prompt: String) {
        if (_uiState.value.sending) return
        _uiState.update { it.copy(input = prompt) }
        if (AiConsoleSession.messages.value.isEmpty()) send()
    }

    fun send() {
        if (_uiState.value.sending) return
        val text = _uiState.value.input.trim()
        val attachments = _uiState.value.attachments
        // An attachment-only message is allowed: the files themselves are the request.
        if (text.isEmpty() && attachments.isEmpty()) return
        val config = currentConfig()
        if (config == null) {
            _uiState.update { state -> state.copy(errorMessage = ksuApp.getString(R.string.ai_console_error_unconfigured)) }
            return
        }
        roundsUsed = 0
        _uiState.update { state ->
            state.copy(
                input = "",
                sending = true,
                errorMessage = null,
                plan = null,
                attachments = emptyList(),
            )
        }
        job = viewModelScope.launch { runConversation(config, text, attachments) }
    }

    /** Attachments read by the UI. The same name and size twice is treated as the same file. */
    fun addAttachments(items: List<AiAttachment>) {
        if (items.isEmpty()) return
        val current = _uiState.value.attachments
        val added = items.filterNot { candidate ->
            current.any { it.name == candidate.name && it.sizeBytes == candidate.sizeBytes }
        }
        _uiState.update { it.copy(attachments = current + added, errorMessage = null) }
        added.forEach { attachment ->
            AiAgentLog.info(
                ksuApp.getString(
                    R.string.ai_console_log_attached,
                    attachment.name,
                    formatSize(attachment.sizeBytes),
                ),
            )
        }
    }

    fun removeAttachment(id: Long) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.id == id })
        }
    }

    fun attachmentFailed(message: String) {
        AiAgentLog.error(message)
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun confirm(actionId: Long) {
        _uiState.update { it.copy(confirm = null) }
        pending[actionId]?.complete(true)
    }

    fun cancel(actionId: Long) {
        _uiState.update { it.copy(confirm = null) }
        pending[actionId]?.complete(false)
    }

    // ------------------------------------------------------------------------------------------
    // plan mode
    // ------------------------------------------------------------------------------------------

    fun setPlanMode(enabled: Boolean) {
        if (_uiState.value.planMode == enabled) return
        _uiState.update { it.copy(planMode = enabled) }
        AiAgentLog.info(
            ksuApp.getString(
                if (enabled) R.string.ai_console_log_plan_on else R.string.ai_console_log_plan_off,
            ),
        )
    }

    /**
     * Runs the whole approved batch and hands the results back to the model, which then continues the
     * loop. When a step fails the plan stays paused and the user decides whether to revise or abort.
     */
    fun approvePlan() {
        val plan = _uiState.value.plan ?: return
        if (_uiState.value.sending) return
        val config = currentConfig() ?: return
        _uiState.update {
            it.copy(sending = true, errorMessage = null, plan = it.plan?.copy(running = true, paused = false))
        }
        job = viewModelScope.launch {
            try {
                val items = currentItems(plan.messageId)
                if (items.isEmpty()) {
                    _uiState.update { it.copy(plan = null) }
                    return@launch
                }
                val results = processActions(plan.messageId, items, config, planMode = true)
                appendMessage(
                    AiConsoleMessage(
                        id = AiConsoleSession.nextId(),
                        role = AiRole.USER,
                        text = results,
                        hidden = true,
                    ),
                )
                if (_uiState.value.plan?.paused == true) {
                    AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_plan_paused))
                    return@launch
                }
                _uiState.update { it.copy(plan = null) }
                runRounds(config)
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                _uiState.update { it.copy(sending = false, confirm = null) }
            }
        }
    }

    /** Drops the plan and pre-fills the input box so the user can rewrite it as the next message. */
    fun revisePlan() {
        if (_uiState.value.plan == null || _uiState.value.sending) return
        _uiState.update { it.copy(plan = null, input = ksuApp.getString(R.string.ai_console_plan_revise_input)) }
        AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_plan_revise))
    }

    /** Stops the turn: the steps after the failed one are never run. */
    fun abortPlan() {
        if (_uiState.value.plan == null || _uiState.value.sending) return
        _uiState.update { it.copy(plan = null) }
        AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_plan_abort))
        appendMessage(
            AiConsoleMessage(
                id = AiConsoleSession.nextId(),
                role = AiRole.USER,
                text = ksuApp.getString(R.string.ai_console_plan_abort_result),
                hidden = true,
            ),
        )
    }

    // ------------------------------------------------------------------------------------------
    // turn loop
    // ------------------------------------------------------------------------------------------

    /** Pixels of the attached images, in pick order; empty when the turn carries only text files. */
    private fun List<AiAttachment>.toImages(): List<AiImage> = mapNotNull { attachment ->
        val mediaType = attachment.mediaType
        val base64 = attachment.base64
        if (mediaType == null || base64 == null) null else AiImage(mediaType, base64)
    }

    private suspend fun runConversation(
        config: ConsoleConfig,
        firstUserText: String,
        attachments: List<AiAttachment> = emptyList(),
    ) {
        try {
            val display = firstUserText.ifBlank {
                ksuApp.getString(R.string.ai_console_attachments_only, attachments.size)
            }
            appendMessage(AiConsoleMessage(id = AiConsoleSession.nextId(), role = AiRole.USER, text = display))
            if (attachments.isNotEmpty()) {
                // Hidden turn: the model needs the file contents, the chat should not show them.
                appendMessage(
                    AiConsoleMessage(
                        id = AiConsoleSession.nextId(),
                        role = AiRole.USER,
                        text = promptBlock(attachments),
                        hidden = true,
                        images = attachments.toImages(),
                    ),
                )
            }
            if (!reconSent) loadRecon(config)
            runRounds(config)
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            _uiState.update { it.copy(sending = false, confirm = null) }
        }
    }

    /**
     * The model turn loop. In plan mode it stops right after a round proposes actions: nothing runs
     * until the user approves the batch, and [approvePlan] re-enters this loop with the results.
     */
    private suspend fun runRounds(config: ConsoleConfig) {
        while (roundsUsed < config.maxRounds) {
            roundsUsed += 1
            val round = roundsUsed
            val assistantId = AiConsoleSession.nextId()
                appendMessage(
                    AiConsoleMessage(id = assistantId, role = AiRole.ASSISTANT, text = "", streaming = true),
                )
                val textBuilder = StringBuilder()
                val reasoningBuilder = StringBuilder()
                AiAgentLog.action(
                    ksuApp.getString(
                        R.string.ai_console_log_round,
                        round,
                        buildHistory(config, false).size,
                        config.model,
                    ),
                )
                val result = AiChatClient.stream(
                    family = config.family,
                    endpoint = config.endpoint,
                    apiKey = config.apiKey,
                    model = config.model,
                    systemPrompt = AiPrompt.systemPrompt(
                        config.scope,
                        config.allowShell,
                        _uiState.value.planMode,
                    ),
                    history = buildHistory(config, !reconSent),
                    onDelta = { delta ->
                        delta.reasoning?.let { reasoningBuilder.append(it) }
                        delta.text?.let { textBuilder.append(it) }
                        updateMessage(assistantId) {
                            it.copy(
                                text = textBuilder.toString(),
                                reasoning = reasoningBuilder.toString().ifBlank { null },
                                streaming = true,
                            )
                        }
                    },
                )
                reconSent = true
                val failure = result.exceptionOrNull()
                if (failure != null) {
                    val message = describeFailure(failure)
                    updateMessage(assistantId) { it.copy(text = message, streaming = false) }
                    AiAgentLog.error(message)
                    _uiState.update { it.copy(errorMessage = message) }
                    return
                }
                val reply = result.getOrThrow()
                val raw = reply.text
                val display = stripActions(raw)
                updateMessage(assistantId) {
                    it.copy(
                        text = display,
                        raw = raw,
                        reasoning = reply.reasoning.ifBlank { reasoningBuilder.toString() }.ifBlank { null },
                        streaming = false,
                    )
                }
                if (raw.isBlank()) return
                val actions = AiActionParser.parse(raw)
                // Hand any model-authored module to the module maker page right away: the user can
                // inspect the draft there while still deciding about the confirmation dialog.
                actions.filterIsInstance<AiAction.MakeModule>().forEach {
                    ModuleDraftStore.publish(it.draft)
                }
                if (actions.isEmpty()) {
                    AiAgentLog.result(ksuApp.getString(R.string.ai_console_log_no_actions))
                    return
                }
                AiAgentLog.action(
                    ksuApp.getString(
                        R.string.ai_console_log_actions,
                        actions.size,
                        actions.joinToString(", ") { it.name },
                    ),
                )
                var items = actions.map { action ->
                    AiActionItem(
                        id = AiConsoleSession.nextId(),
                        action = action,
                        tier = null,
                        status = AiActionStatus.PROPOSED,
                    )
                }
                updateMessage(assistantId) { it.copy(actions = items) }
                if (_uiState.value.planMode) {
                    _uiState.update {
                        it.copy(
                            plan = AiPlanItem(
                                messageId = assistantId,
                                summary = display.trim().take(400),
                                stepCount = items.size,
                            ),
                        )
                    }
                    AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_plan_ready, items.size))
                    return
                }
                val results = processActions(assistantId, items, config, planMode = false)
                items = currentItems(assistantId)
                appendMessage(
                    AiConsoleMessage(
                        id = AiConsoleSession.nextId(),
                        role = AiRole.USER,
                        text = results,
                        hidden = true,
                    ),
                )
            }
            AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_max_rounds, config.maxRounds))
    }

    /** Runs every proposed action in order; the results are handed back to the model as one turn. */
    private suspend fun processActions(
        messageId: Long,
        items: List<AiActionItem>,
        config: ConsoleConfig,
        planMode: Boolean,
    ): String {
        val lines = ArrayList<String>()
        var executed = 0
        var paused = false
        for (item in items) {
            if (paused) {
                updateAction(messageId, item.id) { it.copy(status = AiActionStatus.SKIPPED) }
                AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_skip, item.action.name))
                lines.add(ksuApp.getString(R.string.ai_console_step_skipped, item.action.name))
                continue
            }
            val action = item.action
            when (val decision = AiPathGuard.decide(action, config.scope, items.size, config.allowShell)) {
                is AiGuardDecision.Deny -> {
                    val note = denyText(decision.reason)
                    updateAction(messageId, item.id) { it.copy(status = AiActionStatus.DENIED, note = note) }
                    AiAgentLog.denied(ksuApp.getString(R.string.ai_console_log_denied, action.name, note))
                    audit.append(
                        AiAuditEntry(
                            ts = now(),
                            kind = action.name,
                            target = targetOf(action),
                            command = commandOf(action),
                            scope = config.scope.name,
                            result = AiAuditEntry.RESULT_DENIED,
                        ),
                    )
                    lines.add(ksuApp.getString(R.string.ai_console_step_denied, action.name, note))
                    if (planMode) {
                        // A refusal is a failure for the batch: the user has to decide what next.
                        paused = true
                        _uiState.update { state ->
                            state.copy(plan = state.plan?.copy(running = false, paused = true))
                        }
                        AiAgentLog.error(ksuApp.getString(R.string.ai_console_log_plan_denied))
                    }
                }
                is AiGuardDecision.Allow -> {
                    updateAction(messageId, item.id) { it.copy(tier = decision.tier) }
                    // Plan mode pre-approves the read-only part of the batch. Anything that changes
                    // state still goes through the gate, so an approved plan cannot smuggle a write in.
                    val autoApproved = decision.tier == AiSafetyTier.SILENT ||
                        (planMode && isReadOnly(action))
                    if (autoApproved) {
                        if (decision.tier == AiSafetyTier.SILENT) {
                            AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_silent, action.name))
                        } else {
                            AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_plan_auto, action.name))
                        }
                    } else {
                        val approved = awaitUser(messageId, item, decision.tier)
                        if (!approved) {
                            updateAction(messageId, item.id) { it.copy(status = AiActionStatus.CANCELLED) }
                            AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_cancelled, action.name))
                            lines.add(ksuApp.getString(R.string.ai_console_step_cancelled, action.name))
                            continue
                        }
                    }
                    updateAction(messageId, item.id) { it.copy(status = AiActionStatus.RUNNING) }
                    val outcome = executor.execute(action, config.scope)
                    updateAction(messageId, item.id) {
                        it.copy(
                            status = if (outcome.ok) AiActionStatus.DONE else AiActionStatus.FAILED,
                            output = outcome.output,
                        )
                    }
                    AiAgentLog.result(
                        ksuApp.getString(
                            if (outcome.ok) R.string.ai_console_log_done else R.string.ai_console_log_failed,
                            action.name,
                        ),
                    )
                    executed += 1
                    lines.add(
                        ksuApp.getString(
                            if (outcome.ok) R.string.ai_console_step_done else R.string.ai_console_step_failed,
                            action.name,
                            outcome.output,
                        ),
                    )
                    if (!outcome.ok && planMode) {
                        paused = true
                        _uiState.update { it.copy(plan = it.plan?.copy(running = false, paused = true)) }
                        AiAgentLog.error(ksuApp.getString(R.string.ai_console_log_plan_failed))
                    }
                }
            }
        }
        return buildString {
            appendLine("以下是本地客户端对你上面动作的实际执行结果，不是你执行的结果：")
            for (line in lines) appendLine(line)
            if (paused) {
                appendLine("其中一步失败或被拒绝，客户端已暂停计划里的后续步骤，用户会决定调整计划还是中止。")
            }
            if (executed == 0) {
                appendLine("没有任何动作真正执行。请基于当前授权范围给出可执行的下一步，或直接说明做不到及其原因。")
            }
        }
    }

    /** LIGHT and STRICT gates both wait here; the UI answers through [confirm] or [cancel]. */
    private suspend fun awaitUser(messageId: Long, item: AiActionItem, tier: AiSafetyTier): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending[item.id] = deferred
        updateAction(messageId, item.id) { it.copy(status = AiActionStatus.AWAITING_CONFIRM) }
        val labelRes = actionLabelRes(item.action)
        val payload = describePayload(item.action)
        val readOnly = isReadOnly(item.action)
        val titleRes = if (tier == AiSafetyTier.STRICT) {
            R.string.ai_console_confirm_exec_title
        } else {
            R.string.ai_console_confirm_title
        }
        val request = if (tier == AiSafetyTier.STRICT) {
            AiConfirmRequest.Strict(item.id, titleRes, labelRes, payload, readOnly)
        } else {
            AiConfirmRequest.Light(item.id, titleRes, labelRes, payload, readOnly)
        }
        _uiState.update { it.copy(confirm = request) }
        return try {
            deferred.await()
        } finally {
            pending.remove(item.id)
            _uiState.update { it.copy(confirm = null) }
        }
    }

    // ------------------------------------------------------------------------------------------
    // request assembly
    // ------------------------------------------------------------------------------------------

    private fun buildHistory(config: ConsoleConfig, includeRecon: Boolean): List<AiChatMessage> {
        val history = ArrayList<AiChatMessage>()
        for (message in AiConsoleSession.messages.value.takeLast(MAX_HISTORY)) {
            when (message.role) {
                AiRole.USER -> history.add(
                    AiChatMessage(role = "user", content = message.text, images = message.images),
                )
                AiRole.ASSISTANT -> history.add(
                    AiChatMessage(role = "assistant", content = message.raw.ifBlank { message.text }),
                )
            }
        }
        if (includeRecon && reconText.isNotBlank() && history.isNotEmpty() && history[0].role == "user") {
            val first = history[0]
            history[0] = AiChatMessage(
                role = "user",
                content = "以下是客户端在你回答之前已经采集到的设备现状（可信，不需要你再次读取）：\n" +
                    reconText + "\n\n用户请求：\n" + first.content,
                images = first.images,
            )
        }
        return history
    }

    private var reconText: String = ""

    private suspend fun loadRecon(config: ConsoleConfig) {
        if (reconText.isNotBlank()) return
        reconText = runCatching { recon.snapshot(config.scope, config.allowShell).toPromptContext() }
            .getOrDefault("")
        if (reconText.isNotBlank()) AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_recon))
    }

    private fun currentConfig(): ConsoleConfig? {
        val endpoint = repo.endpoint.trim()
        val model = repo.modelName.trim()
        if (endpoint.isEmpty() || model.isEmpty()) return null
        val family = runCatching { AiProviders.byId(repo.providerId).family }
            .getOrDefault(AiApiFamily.OPENAI_COMPATIBLE)
        return ConsoleConfig(
            family = family,
            endpoint = endpoint,
            apiKey = repo.apiKey.trim(),
            model = model,
            scope = repo.accessScope,
            allowShell = repo.allowShell,
            maxRounds = repo.maxRounds,
        )
    }

    private fun isConfigured(): Boolean =
        repo.endpoint.isNotBlank() && repo.modelName.isNotBlank()

    // ------------------------------------------------------------------------------------------
    // session helpers
    // ------------------------------------------------------------------------------------------

    private fun appendMessage(message: AiConsoleMessage) {
        AiConsoleSession.replace(AiConsoleSession.messages.value + message)
    }

    private fun updateMessage(id: Long, transform: (AiConsoleMessage) -> AiConsoleMessage) {
        AiConsoleSession.replace(
            AiConsoleSession.messages.value.map { if (it.id == id) transform(it) else it },
        )
    }

    private fun updateAction(
        messageId: Long,
        actionId: Long,
        transform: (AiActionItem) -> AiActionItem,
    ) {
        updateMessage(messageId) { message ->
            message.copy(actions = message.actions.map { if (it.id == actionId) transform(it) else it })
        }
    }

    private fun currentItems(messageId: Long): List<AiActionItem> =
        AiConsoleSession.messages.value.firstOrNull { it.id == messageId }?.actions ?: emptyList()

    // ------------------------------------------------------------------------------------------
    // presentation helpers
    // ------------------------------------------------------------------------------------------

    /** Read-only proposals: plan mode may run these without a per-action dialog. */
    private fun isReadOnly(action: AiAction): Boolean = when (action) {
        is AiAction.ReadFile,
        is AiAction.GetFileMetadata,
        is AiAction.ReadFileChunk,
        is AiAction.SearchInFile,
        is AiAction.ListDir,
        is AiAction.SafeExec,
        is AiAction.RootAppList,
        -> true
        else -> false
    }

    /** Localised action name, used as the caption of the confirmation payload. */
    private fun actionLabelRes(action: AiAction): Int = when (action) {
        is AiAction.ReadFile -> R.string.ai_console_action_read_file
        is AiAction.GetFileMetadata -> R.string.ai_console_action_get_file_metadata
        is AiAction.ReadFileChunk -> R.string.ai_console_action_read_file_chunk
        is AiAction.SearchInFile -> R.string.ai_console_action_search_in_file
        is AiAction.ListDir -> R.string.ai_console_action_list_dir
        is AiAction.RunCommand -> R.string.ai_console_action_run_command
        is AiAction.SafeExec -> R.string.ai_console_action_safe_exec
        is AiAction.RootAppList -> R.string.ai_console_action_root_app_list
        is AiAction.GrantSu -> R.string.ai_console_action_grant_su
        is AiAction.RevokeSu -> R.string.ai_console_action_revoke_su
        is AiAction.DisableModule -> R.string.ai_console_action_disable_module
        is AiAction.EnableModule -> R.string.ai_console_action_enable_module
        is AiAction.InstallModule -> R.string.ai_console_action_install_module
        is AiAction.MoveToTrash -> R.string.ai_console_action_move_to_trash
        is AiAction.MakeModule -> R.string.ai_console_action_make_module
        is AiAction.Unknown -> R.string.ai_console_action_unknown
    }

    /** Language-neutral payload (path / command / package / module id) shown under the label. */
    private fun describePayload(action: AiAction): String = when (action) {
        is AiAction.ReadFile -> action.path
        is AiAction.GetFileMetadata -> action.path
        is AiAction.ReadFileChunk -> action.path + "\n@" + action.startLine
        is AiAction.SearchInFile -> action.path + "\n" + action.pattern
        is AiAction.ListDir -> action.path
        is AiAction.RunCommand -> action.command
        is AiAction.SafeExec -> action.command
        is AiAction.RootAppList -> ""
        is AiAction.GrantSu -> action.packageName
        is AiAction.RevokeSu -> action.packageName
        is AiAction.DisableModule -> action.id
        is AiAction.EnableModule -> action.id
        is AiAction.InstallModule -> action.path
        is AiAction.MoveToTrash -> action.path
        is AiAction.MakeModule -> modulePayload(action)
        is AiAction.Unknown -> action.raw.take(300)
    }

    /**
     * The module draft is what the user must see before the red dialog: id, file list, and whether
     * ksud will run a model-authored script as root.
     */
    private fun modulePayload(action: AiAction.MakeModule): String {
        val draft = action.draft
        val head = draft.id + " · " + draft.name + " " + draft.version + " (" + draft.versionCode + ")"
        val summary = ksuApp.getString(R.string.ai_console_module_target_files, draft.files.size)
        val paths = draft.files.joinToString("\n") { "- " + it.path }
        val body = if (paths.isBlank()) {
            head + "\n" + summary
        } else {
            head + "\n" + summary + "\n" + paths
        }
        return if (draft.installScript.isBlank()) {
            body
        } else {
            body + "\n" + ksuApp.getString(R.string.ai_console_module_script_warning)
        }
    }

    private fun targetOf(action: AiAction): String = when (action) {
        is AiAction.ReadFile -> action.path
        is AiAction.GetFileMetadata -> action.path
        is AiAction.ReadFileChunk -> action.path + " @ " + action.startLine
        is AiAction.SearchInFile -> action.path + " :: " + action.pattern
        is AiAction.ListDir -> action.path
        is AiAction.RunCommand -> action.command
        is AiAction.SafeExec -> action.command
        is AiAction.RootAppList -> ksuApp.getString(R.string.ai_console_action_root_app_list)
        is AiAction.GrantSu -> action.packageName
        is AiAction.RevokeSu -> action.packageName
        is AiAction.DisableModule -> action.id
        is AiAction.EnableModule -> action.id
        is AiAction.InstallModule -> action.path
        is AiAction.MoveToTrash -> action.path
        is AiAction.MakeModule -> action.draft.id + " · " + action.draft.name
        is AiAction.Unknown -> action.raw.take(300)
    }

    private fun commandOf(action: AiAction): String? = when (action) {
        is AiAction.RunCommand -> action.command
        is AiAction.SafeExec -> action.command
        else -> null
    }

    /** The guard speaks in technical English; the console shows it in the user's language. */
    private fun denyText(reason: String): String = when {
        reason.startsWith("path outside the granted scope") ->
            ksuApp.getString(R.string.ai_console_deny_scope)
        reason.startsWith("command touches a path outside the granted scope") ->
            ksuApp.getString(R.string.ai_console_deny_command_scope)
        reason.startsWith("command reads the file tree") ->
            ksuApp.getString(R.string.ai_console_deny_file_tree)
        reason.startsWith("file access is disabled") ->
            ksuApp.getString(R.string.ai_console_deny_file_access_off)
        reason == "empty path" -> ksuApp.getString(R.string.ai_console_deny_empty_path)
        reason == "empty command" -> ksuApp.getString(R.string.ai_console_deny_empty_command)
        reason == "shell execution is disabled" ->
            ksuApp.getString(R.string.ai_console_deny_shell_off)
        reason.startsWith("command is not in the read-only whitelist") ->
            ksuApp.getString(R.string.ai_console_deny_not_whitelisted)
        reason.startsWith("command uses shell metacharacters") ->
            ksuApp.getString(R.string.ai_console_deny_metacharacters)
        reason == "command too long" -> ksuApp.getString(R.string.ai_console_deny_too_long)
        else -> reason
    }

    private fun describeFailure(t: Throwable): String = when (t) {
        is AiChatException -> when (t.kind) {
            AiChatErrorKind.HTTP ->
                ksuApp.getString(R.string.ai_console_error_http, t.code, describeDetail(t))
            AiChatErrorKind.MALFORMED ->
                ksuApp.getString(R.string.ai_console_error_malformed, describeDetail(t))
            AiChatErrorKind.NETWORK ->
                ksuApp.getString(R.string.ai_console_error_network, describeDetail(t))
        }
        // A transport failure (DNS, TLS, timeout) is thrown by the client itself and never gets
        // the AiChatException wrapping above; without this the raw Java message reaches the user.
        is IOException -> ksuApp.getString(R.string.ai_console_error_network, describeDetail(t.message))
        else -> t.message ?: t.toString()
    }

    private fun describeDetail(t: AiChatException): String = describeDetail(t.detail)

    private fun describeDetail(message: String?): String {
        val detail = message?.trim()?.take(200).orEmpty()
        return if (detail.isEmpty()) "" else "\n" + detail
    }

    private fun stripActions(raw: String): String = AiActionParser.stripActionBlock(raw)

    private fun now(): Long = System.currentTimeMillis()

    private data class ConsoleConfig(
        val family: AiApiFamily,
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val scope: AiAccessScope,
        val allowShell: Boolean,
        val maxRounds: Int,
    )

    companion object {
        /** How many earlier messages are replayed to the model each turn. */
        private const val MAX_HISTORY = 20
    }
}
