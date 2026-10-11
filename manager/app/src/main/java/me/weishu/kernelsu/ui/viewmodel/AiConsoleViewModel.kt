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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.aichat.AiToolLabels
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiAction
import me.weishu.kernelsu.data.agent.AiActionExecutor
import me.weishu.kernelsu.data.agent.AiActionExecutorImpl
import me.weishu.kernelsu.data.agent.AiActionParser
import me.weishu.kernelsu.data.agent.AiAgentLog
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.agent.AiErrorCode
import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import me.weishu.kernelsu.data.agent.AiGuardDecision
import me.weishu.kernelsu.data.agent.AiOutcomeStatus
import me.weishu.kernelsu.data.agent.AiPathGuard
import me.weishu.kernelsu.data.agent.AiPrompt
import me.weishu.kernelsu.data.agent.AiSafetyTier
import me.weishu.kernelsu.data.agent.aiResultJson
import me.weishu.kernelsu.data.agent.tool.AiRiskLevel
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
import me.weishu.kernelsu.ui.screen.aiassistant.AiBatchApproval
import me.weishu.kernelsu.ui.screen.aiassistant.AiBatchItem
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

    /** A whole round of gates shown as one dialog; completed with the ids the user ticked. */
    private var batchGate: CompletableDeferred<Set<Long>>? = null

    /** The round on screen, kept so the queue hint can bring it back. */
    private var batchSnapshot: AiBatchApproval? = null

    /**
     * Batch id of the round being executed, so every audit entry that round writes can be grouped
     * and reversed together. Null while no confirmed batch is running.
     */
    private var roundBatchId: String? = null
    private var job: Job? = null
    private var reconSent = false

    /**
     * Set once the console has asked the model to resend an unreadable action payload, so a model
     * that keeps answering with broken JSON cannot spend the whole round budget on retries.
     */
    private var repairSent = false

    /**
     * Where one action runs. Deliberately NOT a child of the round: an action that blocks inside a
     * synchronous call (a shell command that never returns, a root service call that hangs) cannot be
     * interrupted, so the round must be able to walk away from it and settle the card instead of
     * waiting forever. The abandoned call finishes on this scope's thread, off the console's path.
     */
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Model turns already spent on the current request; plan mode suspends the loop between rounds. */
    private var roundsUsed = 0

    init {
        viewModelScope.launch {
            AiConsoleSession.messages.collect { list -> _uiState.update { it.copy(messages = list).withQueueCount() } }
        }
        viewModelScope.launch {
            AiAgentLog.lines.collect { lines -> _uiState.update { it.copy(agentLog = lines) } }
        }
        refreshConfig()
    }

    override fun onCleared() {
        // An action that overran its deadline is still parked on this scope; cancelling it here keeps
        // a destroyed view model from holding a reference to the console.
        actionScope.cancel()
        super.onCleared()
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
        // Release every gate that is still parked. Their coroutines sit inside `awaitUser` / the
        // batch dialog, and a tap that arrives after the screen was cleared would otherwise answer
        // a conversation that no longer exists; completing them lets the round unwind cleanly.
        pending.values.forEach { it.complete(false) }
        pending.clear()
        batchGate?.complete(emptySet())
        batchGate = null
        batchSnapshot = null
        // The batch id groups audit entries for reversal; a new conversation must not inherit it.
        roundBatchId = null
        AiConsoleSession.clear()
        reconSent = false
        // The recon block is a cached read of the device: keeping it would leak the previous
        // turn's tool context into the next conversation (refreshRecon() returns early while it
        // is non-blank, so the new conversation would see the stale snapshot).
        reconText = ""
        AiAgentLog.clear()
        AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_cleared))
        roundsUsed = 0
        repairSent = false
        _uiState.update {
            it.copy(
                sending = false,
                confirm = null,
                errorMessage = null,
                input = "",
                plan = null,
                // Plan mode is a per-conversation mode marker: a cleared conversation starts in the
                // normal (non-plan) mode instead of inheriting the previous one.
                planMode = false,
                batch = null,
                pendingQueue = 0,
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
        repairSent = false
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

    /**
     * Runs one finished-for-bad-reasons row again, through the very same gate as its first attempt:
     * the guard re-decides, an irreversible action still asks for its confirmation, and the card is
     * reset so the fresh outcome replaces the old one instead of piling up next to it.
     *
     * Only outcomes that left the device untouched are retryable — a write that already succeeded has
     * no retry button, because running it twice is a footgun rather than a convenience. The model is
     * not asked to re-plan: this repeats exactly the operation the user pointed at.
     */
    fun retryAction(actionId: Long) {
        if (_uiState.value.sending || _uiState.value.confirm != null || batchSnapshot != null) return
        val config = currentConfig() ?: return
        val messageId = AiConsoleSession.messages.value
            .firstOrNull { message -> message.actions.any { it.id == actionId } }
            ?.id ?: return
        val item = currentItems(messageId).firstOrNull { it.id == actionId } ?: return
        if (!isRetryable(item.status)) return
        val fresh = item.copy(status = AiActionStatus.PROPOSED, tier = null, output = null, note = null)
        updateAction(messageId, actionId) { fresh }
        _uiState.update { it.copy(sending = true, errorMessage = null) }
        AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_retry, item.action.name))
        job = viewModelScope.launch {
            try {
                // A single row, so the batch dialog never opens; a strict action still gets its dialog.
                processActions(messageId, listOf(fresh), config, planMode = false)
            } finally {
                _uiState.update { it.copy(sending = false, confirm = null) }
            }
        }
    }

    /** Running it again is only offered for the outcomes that changed nothing on the device. */
    private fun isRetryable(status: AiActionStatus): Boolean = when (status) {
        AiActionStatus.FAILED,
        AiActionStatus.DENIED,
        AiActionStatus.CANCELLED,
        AiActionStatus.SKIPPED,
        -> true
        else -> false
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
    // batch approval
    // ------------------------------------------------------------------------------------------

    /** Ticks or unticks one row of the batch dialog. */
    fun toggleBatch(actionId: Long) {
        _uiState.update { state ->
            val batch = state.batch ?: return@update state
            val selected = if (actionId in batch.selected) {
                batch.selected - actionId
            } else {
                batch.selected + actionId
            }
            state.copy(batch = batch.copy(selected = selected))
        }
    }

    /**
     * Selects or clears every row. Selecting everything is refused while the round contains a fatal
     * action: a destructive step must be ticked one by one, never swept up by a single tap.
     */
    fun selectAllBatch(selected: Boolean) {
        _uiState.update { state ->
            val batch = state.batch ?: return@update state
            if (selected && batch.containsFatal) return@update state
            val ids = if (selected) batch.items.map { it.actionId }.toSet() else emptySet()
            state.copy(batch = batch.copy(selected = ids))
        }
    }

    /**
     * Approves the ticked rows.
     *
     * A fatal round is still protected by the dialog itself (nothing pre-selected, select-all off), so
     * every irreversible row had to be ticked by hand before this is reachable; the typed keyword gate
     * was removed by product decision and is deliberately not checked here any more.
     */
    fun confirmBatch() {
        val batch = _uiState.value.batch ?: return
        val approved = batch.selected
        releaseBatch()
        batchGate?.complete(approved)
    }

    /** The cancel button: throws the whole round away and answers the gate with nothing approved. */
    fun cancelBatch() {
        releaseBatch()
        batchGate?.complete(emptySet())
    }

    /**
     * Only hides the dialog, for a tap outside it or the back gesture.
     *
     * The round is not thrown away: every row stays AWAITING_CONFIRM, [batchSnapshot] is kept, and
     * the gate keeps waiting, so the hint above the input box can reopen the very same list. Without
     * this split the snapshot died with the dialog and the hint could never bring anything back.
     */
    fun hideBatch() {
        _uiState.update { it.copy(batch = null) }
    }

    /**
     * Brings the round back from the queue hint.
     *
     * [batchSnapshot] holds a batch whose dialog was closed without an answer, and a single-action
     * gate keeps its own request on screen, so those two reopen themselves. Anything else that still
     * counts as waiting is a step the round has not reached yet: say so instead of logging a line
     * that claims an approval is pending.
     */
    fun openQueue() {
        val snapshot = batchSnapshot
        if (snapshot != null && _uiState.value.batch == null) {
            _uiState.update { it.copy(batch = snapshot) }
        } else if (_uiState.value.pendingQueue > 0) {
            AiAgentLog.info(
                ksuApp.getString(R.string.ai_console_log_queue_busy, _uiState.value.pendingQueue),
            )
        }
    }

    private fun releaseBatch() {
        batchSnapshot = null
        _uiState.update { it.copy(batch = null) }
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
                val parsed = AiActionParser.parseDetailed(raw)
                val actions = parsed.actions
                val display = stripActions(raw)
                val reasoning = reply.reasoning.ifBlank { reasoningBuilder.toString() }
                // A card with nothing on it is a dead end: the user cannot tell whether the model
                // declined, broke its own format, or was cut off mid-answer. Every empty outcome says
                // which one it was, and an action-shaped miss is retried below instead of dropped.
                val visible = when {
                    display.isNotBlank() || actions.isNotEmpty() -> display
                    reasoning.isNotBlank() -> ksuApp.getString(R.string.ai_console_notice_reasoning_only)
                    else -> ksuApp.getString(R.string.ai_console_notice_no_text)
                }
                updateMessage(assistantId) {
                    it.copy(
                        text = visible,
                        raw = raw,
                        reasoning = reasoning.ifBlank { null },
                        streaming = false,
                    )
                }
                if (raw.isBlank()) {
                    AiAgentLog.result(ksuApp.getString(R.string.ai_console_log_no_actions))
                    return
                }
                // Hand any model-authored module to the module maker page right away: the user can
                // inspect the draft there while still deciding about the confirmation dialog.
                actions.filterIsInstance<AiAction.MakeModule>().forEach {
                    ModuleDraftStore.publish(it.draft)
                }
                if (actions.isEmpty()) {
                    if (parsed.actionish && !repairSent && roundsUsed < config.maxRounds) {
                        // The reply carried something shaped like an action payload and no action
                        // survived parsing. Asking once more, for the same thing in one flat shape, is
                        // the difference between "the AI only talks" and a working turn. Bounded, so a
                        // model stuck on a broken shape cannot spend the whole round budget.
                        AiAgentLog.error(ksuApp.getString(R.string.ai_console_log_actions_unparsed))
                        repairSent = true
                        appendMessage(
                            AiConsoleMessage(
                                id = AiConsoleSession.nextId(),
                                role = AiRole.USER,
                                text = ksuApp.getString(R.string.ai_console_repair_request),
                                hidden = true,
                            ),
                        )
                        continue
                    }
                    AiAgentLog.result(
                        ksuApp.getString(
                            // Only an action-shaped payload that survived no parser counts as a
                            // failed read. A prose answer that merely shows some JSON is a normal
                            // end of turn, and must not be logged as a broken action.
                            if (parsed.actionish) {
                                R.string.ai_console_log_actions_unparsed
                            } else {
                                R.string.ai_console_log_no_actions
                            },
                        ),
                    )
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

    /**
     * Runs one action with a deadline, on a scope the round can walk away from.
     *
     * [AiActionExecutor.execute] works through blocking calls (a root shell, a binder call), so it
     * cannot be cancelled once it is stuck. Parking it on [actionScope] and awaiting it with a
     * timeout means a stuck action settles as a visible failure instead of leaving its card on
     * "running" while the round waits for a result that never comes. Exceptions take the same route,
     * so every path through here ends in exactly one settled outcome.
     */
    private suspend fun runAction(action: AiAction, scope: AiAccessScope): AiExecutionOutcome {
        // Stamped with the batch the user confirmed, so the audit page can group this round and
        // reverse it in one tap instead of walking entry by entry.
        val batchId = roundBatchId
        val running = actionScope.async {
            runCatching { executor.withBatch(batchId) { executor.execute(action, scope) } }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
                    AiExecutionOutcome(
                        ok = false,
                        output = ksuApp.getString(R.string.ai_console_step_crashed, action.name),
                        status = AiOutcomeStatus.FAILED,
                        error = AiErrorCode.EXECUTION_FAILED,
                        message = t.message ?: t::class.java.simpleName,
                    )
                }
        }
        return withTimeoutOrNull(ACTION_TIMEOUT_MS) { running.await() }
            ?: AiExecutionOutcome(
                ok = false,
                output = ksuApp.getString(
                    R.string.ai_console_step_timeout,
                    action.name,
                    ACTION_TIMEOUT_MS / 1000,
                ),
                status = AiOutcomeStatus.FAILED,
                error = AiErrorCode.TIMEOUT,
                message = ksuApp.getString(R.string.ai_console_step_timeout_reason, ACTION_TIMEOUT_MS / 1000),
            )
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
        // A round of two or more gates is decided in one list dialog before anything runs.
        roundBatchId = null
        val roundGates = collectBatchGates(messageId, items, config, planMode)
        for (item in items) {
            if (paused) {
                updateAction(messageId, item.id) { it.copy(status = AiActionStatus.SKIPPED) }
                AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_skip, item.action.name))
                val skipped = ksuApp.getString(R.string.ai_console_step_skipped, item.action.name)
                lines.add(skipped)
                // The model reads the payload back, so a step that never ran has to say so: otherwise an
                // aborted plan looks like a half executed one.
                lines.add(
                    aiResultJson(
                        tool = item.action.name,
                        ok = false,
                        status = AiOutcomeStatus.SKIPPED,
                        error = AiErrorCode.PLAN_PAUSED,
                        message = skipped,
                    ),
                )
                continue
            }
            val action = item.action
            when (val decision = AiPathGuard.decide(action, config.scope, items.size, config.allowShell)) {
                is AiGuardDecision.Deny -> {
                    // The refusal reaches the action card, the audit log and the model's own payload, so
                    // it is phrased for a person and carries the advice on what to try instead.
                    val note = denyText(decision.reason) + " " +
                        ksuApp.getString(R.string.ai_console_deny_fix_hint)
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
                    // "blocked", not "failed" and not silence: the model is required to tell the user it
                    // was stopped by the safety policy and which switch would lift it.
                    lines.add(
                        aiResultJson(
                            tool = action.name,
                            ok = false,
                            status = AiOutcomeStatus.BLOCKED,
                            error = denyCode(decision.reason),
                            message = note,
                        ),
                    )
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
                        val approved = roundGates?.get(item.id)
                            ?: awaitUser(messageId, item, decision.tier)
                        if (!approved) {
                            updateAction(messageId, item.id) { it.copy(status = AiActionStatus.CANCELLED) }
                            AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_cancelled, action.name))
                            val cancelled = ksuApp.getString(R.string.ai_console_step_cancelled, action.name)
                            lines.add(cancelled)
                            // A declined gate is a result too: without this line the missing output reads
                            // as "still deciding", and the model fills the gap with a story.
                            lines.add(
                                aiResultJson(
                                    tool = action.name,
                                    ok = false,
                                    status = AiOutcomeStatus.CANCELLED,
                                    error = AiErrorCode.USER_DECLINED,
                                    message = cancelled,
                                ),
                            )
                            continue
                        }
                    }
                    updateAction(messageId, item.id) { it.copy(status = AiActionStatus.RUNNING) }
                    // A tool may sit in a blocking call until the deadline, and while it does the input
                    // box stays disabled: the panel says which step is holding the round instead of
                    // going silent until the result lands.
                    AiAgentLog.action(
                        ksuApp.getString(
                            R.string.ai_console_log_running,
                            action.name,
                            ACTION_TIMEOUT_MS / 1000,
                        ),
                    )
                    val outcome = runAction(action, config.scope)
                    if (outcome.error == AiErrorCode.TIMEOUT) {
                        AiAgentLog.error(
                            ksuApp.getString(
                                R.string.ai_console_log_timeout,
                                action.name,
                                ACTION_TIMEOUT_MS / 1000,
                            ),
                        )
                    }
                    // The card is read by a human, so it gets the same plain wording as the
                    // line handed back to the model (`package_not_found` becomes a sentence).
                    val humanized = humanizeOutput(action, outcome.output)
                    updateAction(messageId, item.id) {
                        it.copy(
                            status = if (outcome.ok) AiActionStatus.DONE else AiActionStatus.FAILED,
                            output = humanized,
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
                            humanized,
                        ),
                    )
                    if (action !is AiAction.Tool) {
                        // A structured tool already answered with its own envelope (carried inside
                        // `humanized`); the hand-built actions answer with plain text, so the same
                        // envelope is added here and no result stays prose only.
                        lines.add(
                            aiResultJson(
                                tool = action.name,
                                ok = outcome.ok,
                                status = outcome.status,
                                error = outcome.error
                                    ?: if (outcome.ok) null else AiErrorCode.EXECUTION_FAILED,
                                message = outcome.message ?: humanized,
                            ),
                        )
                    }
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
            appendLine(
                "每行 JSON 信封是唯一权威结果：status 为 blocked（被安全策略拦截，动作没有执行）、" +
                    "cancelled（用户取消）、failed（执行了但失败）、skipped（计划暂停，没有执行）。" +
                    "凡是 status 不是 ok，你必须如实告诉用户这一步没有成功，原样说明 message 里的原因与解除办法；" +
                    "禁止声称工具或命令不存在，禁止编造「设备没有这个文件」，也禁止换工具或路径绕过同一次拦截。",
            )
            for (line in lines) appendLine(line)
            if (paused) {
                appendLine("其中一步失败或被拒绝，客户端已暂停计划里的后续步骤，用户会决定调整计划还是中止。")
            }
            if (executed == 0) {
                appendLine("没有任何动作真正执行。请基于当前授权范围给出可执行的下一步，或直接说明做不到及其原因。")
            }
        }
    }

    /**
     * Pre-computes the gates of a whole round so two or more confirmations arrive as one list dialog.
     *
     * Returns null when the round decides itself (nothing to confirm) or holds a single gate, in
     * which case [processActions] keeps the per-action dialog. The returned map answers every gated id
     * of the round, so a ticked row runs and an unticked one is cancelled without a second look.
     */
    private suspend fun collectBatchGates(
        messageId: Long,
        items: List<AiActionItem>,
        config: ConsoleConfig,
        planMode: Boolean,
    ): Map<Long, Boolean>? {
        val rows = ArrayList<Pair<AiActionItem, AiRiskLevel>>()
        for (item in items) {
            val decision = AiPathGuard.decide(item.action, config.scope, items.size, config.allowShell)
            if (decision !is AiGuardDecision.Allow) continue
            val autoApproved = decision.tier == AiSafetyTier.SILENT ||
                (planMode && isReadOnly(item.action))
            if (autoApproved) continue
            rows.add(item to riskFor(item.action, decision.tier))
        }
        if (rows.size < 2) return null
        val containsFatal = rows.any { it.second == AiRiskLevel.FATAL }
        // One id for the dialog and for the audit entries the confirmed rows will write.
        val batchId = "batch-" + now()
        roundBatchId = batchId
        val batch = AiBatchApproval(
            batchId = batchId,
            items = rows.map { (item, risk) ->
                val target = targetOf(item.action)
                AiBatchItem(
                    actionId = item.id,
                    label = actionLabel(item.action),
                    subtitle = if (target.isBlank()) {
                        ""
                    } else {
                        ksuApp.getString(R.string.ai_console_batch_target, target.take(120))
                    },
                    payload = describePayload(item.action),
                    risk = risk,
                )
            },
            // A destructive round starts empty: the user has to pick the fatal rows deliberately.
            selected = if (containsFatal) emptySet() else rows.map { it.first.id }.toSet(),
            containsFatal = containsFatal,
        )
        val gate = CompletableDeferred<Set<Long>>()
        batchGate = gate
        batchSnapshot = batch
        rows.forEach { (item, _) ->
            updateAction(messageId, item.id) { it.copy(status = AiActionStatus.AWAITING_CONFIRM) }
        }
        AiAgentLog.info(ksuApp.getString(R.string.ai_console_log_batch_gate, batch.items.size))
        _uiState.update { it.copy(batch = batch) }
        val approved = try {
            gate.await()
        } finally {
            batchGate = null
            releaseBatch()
        }
        return rows.associate { (item, _) -> item.id to (item.id in approved) }
    }

    /** The risk shown next to a row; a fatal tool is the only thing that lifts a round to FATAL. */
    private fun riskFor(action: AiAction, tier: AiSafetyTier): AiRiskLevel {
        if (action is AiAction.Tool && AiPathGuard.riskOf(action) == AiRiskLevel.FATAL) {
            return AiRiskLevel.FATAL
        }
        return when (tier) {
            AiSafetyTier.SILENT -> AiRiskLevel.LOW
            AiSafetyTier.LIGHT -> AiRiskLevel.MEDIUM
            AiSafetyTier.STRICT -> AiRiskLevel.HIGH
        }
    }

    /**
     * Keeps the hint above the input box in step with the actions still waiting for a decision.
     *
     * Only [AiActionStatus.AWAITING_CONFIRM] counts. A PROPOSED step is one the round has not reached
     * yet - it is queued behind the action currently running, or behind the plan approval - so there is
     * nothing the user can do about it. Counting those made the bar promise an approval that did not
     * exist: tapping it could only log a line, which is what produced the repeated "no pending
     * approval" spam while the bar kept offering one.
     */
    private fun AiConsoleUiState.withQueueCount(): AiConsoleUiState {
        val waiting = messages.sumOf { message ->
            message.actions.count { it.status == AiActionStatus.AWAITING_CONFIRM }
        }
        return if (waiting == pendingQueue) this else copy(pendingQueue = waiting)
    }

    /** LIGHT and STRICT gates both wait here; the UI answers through [confirm] or [cancel]. */
    private suspend fun awaitUser(messageId: Long, item: AiActionItem, tier: AiSafetyTier): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending[item.id] = deferred
        updateAction(messageId, item.id) { it.copy(status = AiActionStatus.AWAITING_CONFIRM) }
        // The gate caption is the business wording, so a single-action approval reads "Delete
        // permanently" rather than the generic "Device tool" it used to show for every tool call.
        val labelRes = actionBusinessLabelRes(item.action)
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
                AiRole.ASSISTANT -> {
                    // The streaming placeholder of the round being prepared is an empty assistant
                    // message. Sending it as the last turn of the request is malformed for the model:
                    // it reads as "your previous answer was nothing", and the shape it answers with
                    // becomes unreliable. Empty turns are dropped instead.
                    val content = message.raw.ifBlank { message.text }
                    if (content.isBlank()) continue
                    history.add(AiChatMessage(role = "assistant", content = content))
                }
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

    /** Localised protocol name of a non-tool action; tool rows go through [actionLabel] instead. */
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
        is AiAction.Tool -> R.string.ai_console_action_tool
        is AiAction.Unknown -> R.string.ai_console_action_unknown
    }

    /**
     * What the user is about to approve, in business words instead of the protocol name: a tool row
     * reads "Delete permanently", not "delete_file". Non-tool actions keep their existing label, and a
     * tool the table does not know yet falls back to its protocol name, so a new tool can never render
     * as a blank row.
     */
    private fun actionLabel(action: AiAction): String =
        if (action is AiAction.Tool) {
            AiToolLabels[action.toolName]?.let { ksuApp.getString(it) } ?: action.toolName
        } else {
            ksuApp.getString(actionLabelRes(action))
        }

    /**
     * The same business wording as a string-resource id, for the gates that carry localised text as an
     * id rather than a string (the single-action confirm dialog builds its caption from one).
     */
    private fun actionBusinessLabelRes(action: AiAction): Int =
        if (action is AiAction.Tool) {
            AiToolLabels[action.toolName] ?: actionLabelRes(action)
        } else {
            actionLabelRes(action)
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
        is AiAction.Tool -> toolPayload(action)
        is AiAction.Unknown -> action.raw.take(300)
    }

    /**
     * Tool name followed by its arguments, one per line. The arguments are stable-sorted so the
     * approval dialog shows the same text for the same call no matter how the model ordered them.
     */
    private fun toolPayload(action: AiAction.Tool): String = buildString {
        append(action.toolName)
        action.args.entries
            .filter { it.value != null }
            .sortedBy { it.key }
            .forEach { append('\n').append(it.key).append('=').append(it.value) }
    }.take(600)

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
        is AiAction.Tool -> toolTargetOf(action)
        is AiAction.Unknown -> action.raw.take(300)
    }

    /**
     * The object a tool action touches, used as the approval row's "目标". Never the protocol tool
     * id: a row reading `目标：clear_app_data` tells the user nothing, while `目标：com.example.app` does.
     */
    private fun toolTargetOf(action: AiAction.Tool): String {
        for (key in TOOL_TARGET_KEYS) {
            val value = action.args[key]?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) return value.take(160)
        }
        return ""
    }

    private fun commandOf(action: AiAction): String? = when (action) {
        is AiAction.RunCommand -> action.command
        is AiAction.SafeExec -> action.command
        else -> null
    }

    /**
     * The stable code for a guard refusal.
     *
     * [denyText] turns the same reason into the sentence a person reads; this keeps the machine
     * readable half next to it, so the model can tell a scope problem (raise the range) apart from an
     * invalid request (fix the arguments) without parsing prose.
     */
    private fun denyCode(reason: String): String = when {
        reason.startsWith("path outside the granted scope") -> AiErrorCode.PATH_OUTSIDE_SCOPE
        reason.startsWith("command touches a path outside the granted scope") -> AiErrorCode.PATH_OUTSIDE_SCOPE
        reason.startsWith("command reads the file tree") -> AiErrorCode.PATH_OUTSIDE_SCOPE
        reason.startsWith("file access is disabled") -> AiErrorCode.FILE_ACCESS_DISABLED
        reason == "shell execution is disabled" -> AiErrorCode.SHELL_DISABLED
        reason.startsWith("unknown tool") -> AiErrorCode.UNKNOWN_TOOL
        reason.startsWith("该工具需要更高的访问范围") -> AiErrorCode.SCOPE_TOO_NARROW
        reason.contains("超出当前授权范围") -> AiErrorCode.SCOPE_TOO_NARROW
        reason.startsWith("empty ") ||
            reason.startsWith("command too long") ||
            reason.startsWith("command is not in the read-only whitelist") ||
            reason.startsWith("command uses shell metacharacters") ||
            reason.startsWith("search pattern") -> AiErrorCode.INVALID_REQUEST
        reason.startsWith("缺少必填参数") ||
            reason.startsWith("未知参数") ||
            reason.startsWith("参数 ") -> AiErrorCode.VALIDATION_FAILED
        else -> AiErrorCode.SECURITY_DENIED
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
        else -> friendlyDeny(reason)
    }

    /**
     * The validator rejects with short technical fragments ("参数 limit 大于上限 500"); the console
     * shows the same refusal in plain words, with the parameter it is about spelled out. Anything the
     * patterns below do not match still says it was blocked and keeps the original text, so a rule
     * added later degrades to understandable instead of silent.
     */
    private fun friendlyDeny(reason: String): String {
        val required = REQUIRED_ARG.find(reason)
        if (required != null) {
            return ksuApp.getString(R.string.ai_console_deny_arg_required, required.groupValues[1])
        }
        val unknown = UNKNOWN_ARG.find(reason)
        if (unknown != null) {
            return ksuApp.getString(R.string.ai_console_deny_arg_unknown, unknown.groupValues[1])
        }
        val shellTool = SHELL_TOOL.find(reason)
        if (shellTool != null) {
            return ksuApp.getString(R.string.ai_console_deny_shell_tool, shellTool.groupValues[1])
        }
        val issue = ARG_ISSUE.find(reason)
        if (issue != null) {
            val name = issue.groupValues[1]
            val why = issue.groupValues[2].trim()
            return when {
                why == "格式不合法" -> ksuApp.getString(R.string.ai_console_deny_arg_format, name)
                why.startsWith("超过长度上限") ->
                    ksuApp.getString(R.string.ai_console_deny_arg_length, name, why.substringAfter(' ').trim())
                why.startsWith("大于上限") || why.startsWith("小于下限") ->
                    ksuApp.getString(R.string.ai_console_deny_arg_range, name, why.substringAfter(' ').trim())
                why.startsWith("必须位于") || why.contains("/data/adb") ->
                    ksuApp.getString(R.string.ai_console_deny_arg_data_adb, name)
                why.startsWith("必须是规范的绝对路径") ->
                    ksuApp.getString(R.string.ai_console_deny_arg_format, name)
                why == "必须是整数" || why == "必须是 true 或 false" ->
                    ksuApp.getString(R.string.ai_console_deny_arg_type, name, why.removePrefix("必须是"))
                why.startsWith("必须是") ->
                    ksuApp.getString(R.string.ai_console_deny_arg_enum, name, why.removePrefix("必须是"))
                why.startsWith("超出当前授权范围") ->
                    ksuApp.getString(R.string.ai_console_deny_arg_out_of_scope, name)
                else -> blockedWithDetail(reason)
            }
        }
        return blockedWithDetail(reason)
    }

    /**
     * The refusal a tool failure is shown with: the user reads a sentence, and the raw JSON the tool
     * produced stays underneath because the model needs it to correct itself.
     */
    private fun humanizeOutput(action: AiAction, output: String): String {
        if (action !is AiAction.Tool) return output
        if (!output.contains("\"error\":\"package_not_found\"")) return output
        val pkg = action.args["package_name"]?.toString().orEmpty()
        return ksuApp.getString(R.string.ai_console_error_package_missing, pkg) + "\n" + output
    }

    private fun blockedWithDetail(reason: String): String =
        ksuApp.getString(R.string.ai_console_deny_generic) + "（" + reason + "）"

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

        /**
         * How long one action may take before the console gives up on it. Generous on purpose: a big
         * log read is slow but finite, while a shell command that never returns is not, and the card
         * must not stay on "running" for either of them.
         */
        private const val ACTION_TIMEOUT_MS = 60_000L

        /** The fragments [friendlyDeny] rewrites; anything unmatched degrades to a generic line. */
        private val REQUIRED_ARG = Regex("^缺少必填参数：(.*)$")
        private val UNKNOWN_ARG = Regex("^未知参数：(.*)$")
        private val SHELL_TOOL = Regex("^当前会话未开启 shell 权限，工具 (.+) 不可用$")
        private val ARG_ISSUE = Regex("^参数 (\\S+) (.*)$")

        /**
         * Argument keys that name the object of a tool call, most specific first. [toolTargetOf] takes
         * the first one that carries a value, so the approval row describes the target instead of the tool.
         */
        private val TOOL_TARGET_KEYS = listOf(
            "path", "backup_path", "archive_path", "script_path", "old_path", "new_path",
            "package_name", "package", "module", "module_id", "name", "target", "file",
            "source", "query", "pattern",
        )
    }
}
