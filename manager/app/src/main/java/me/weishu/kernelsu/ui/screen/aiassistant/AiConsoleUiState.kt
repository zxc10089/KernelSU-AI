package me.weishu.kernelsu.ui.screen.aiassistant

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.agent.AiAction
import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiAgentLog
import me.weishu.kernelsu.data.agent.AiSafetyTier
import me.weishu.kernelsu.data.agent.tool.AiRiskLevel
import me.weishu.kernelsu.data.remote.AiImage

enum class AiRole { USER, ASSISTANT }

enum class AiActionStatus { PROPOSED, DENIED, AWAITING_CONFIRM, RUNNING, DONE, FAILED, CANCELLED, SKIPPED }

@Immutable
data class AiActionItem(
    val id: Long,
    val action: AiAction,
    val tier: AiSafetyTier?,
    val status: AiActionStatus,
    val output: String? = null,
    val note: String? = null,
)

/**
 * A batch of actions waiting for one approval in plan mode. The items themselves stay on the message
 * they were parsed from, so their live status is rendered by the same action cards as usual; this
 * holder only carries the batch state (which message, one line of intent, running or paused).
 */
@Immutable
data class AiPlanItem(
    val messageId: Long,
    val summary: String,
    val stepCount: Int,
    val running: Boolean = false,
    /** True when a step failed and the remaining steps were paused for the user to revise. */
    val paused: Boolean = false,
)

@Immutable
data class AiConsoleMessage(
    val id: Long,
    val role: AiRole,
    /** What is shown in the bubble: the reply with the action JSON stripped out. */
    val text: String,
    /** What the model really said; kept so the next turn can be sent back verbatim. */
    val raw: String = "",
    val reasoning: String? = null,
    val actions: List<AiActionItem> = emptyList(),
    val streaming: Boolean = false,
    /** Hidden messages carry execution results back to the model without filling the chat. */
    val hidden: Boolean = false,
    /** Pixels of the images attached to this turn; empty for every text-only message. */
    val images: List<AiImage> = emptyList(),
)

/**
 * A gate waiting for the user. Light is the shared confirm dialog, strict is the red one.
 *
 * The texts travel as string-resource ids plus a language-neutral payload so the gate is localised:
 * the console also runs on en-US devices, where a Chinese-only safety dialog would defeat informed
 * consent.
 */
sealed interface AiConfirmRequest {
    val actionId: Long
    /** Dialog title ("确认操作" / "Confirm action"). */
    val titleRes: Int
    /** Localised action name, used as the caption of the payload block. */
    val labelRes: Int
    /** Path / command / package name; never localised. */
    val payload: String
    /** True when the action only reads, which selects the milder warning line. */
    val readOnly: Boolean
    data class Light(
        override val actionId: Long,
        override val titleRes: Int,
        override val labelRes: Int,
        override val payload: String,
        override val readOnly: Boolean = true,
    ) : AiConfirmRequest
    data class Strict(
        override val actionId: Long,
        override val titleRes: Int,
        override val labelRes: Int,
        override val payload: String,
        override val readOnly: Boolean = false,
    ) : AiConfirmRequest
}

/**
 * One row of the batch approval dialog: a single pending action with its risk level.
 *
 * The label, subtitle and payload are resolved on the view-model side because the dialog itself has
 * no access to the string table of a specific action, and the risk level travels as an enum so the
 * dialog can both render the tag and decide whether select-all is allowed.
 *
 * [label] is what will happen in business words ("Delete permanently"), [subtitle] names the object
 * it happens to ("Target: /data/adb/modules/foo") and [payload] is the raw argument dump that stays
 * collapsed behind the per-row details toggle.
 */
@Immutable
data class AiBatchItem(
    val actionId: Long,
    val label: String,
    val subtitle: String,
    val payload: String,
    val risk: AiRiskLevel,
)

/**
 * A whole round of pending actions approved in one dialog instead of one dialog per action.
 *
 * [containsFatal] is the one flag every safeguard hangs off: when a fatal action (real delete,
 * forced overwrite) is anywhere in this round, select-all is disabled and nothing is pre-selected,
 * so every irreversible row has to be ticked by hand. Confirmations are no longer gated on a typed
 * keyword (the wording was dropped by product decision; the risk tag says "irreversible" instead).
 */
@Immutable
data class AiBatchApproval(
    val batchId: String,
    val items: List<AiBatchItem>,
    val selected: Set<Long>,
    val containsFatal: Boolean,
) {
    val selectedCount: Int get() = selected.size
    val allSelected: Boolean get() = items.isNotEmpty() && selected.size == items.size
}

@Immutable
data class AiConsoleUiState(
    val configured: Boolean = false,
    val model: String = "",
    val scope: AiAccessScope = AiAccessScope.DEFAULT,
    val allowShell: Boolean = false,
    val messages: List<AiConsoleMessage> = emptyList(),
    val agentLog: List<AiAgentLog.Line> = emptyList(),
    val logExpanded: Boolean = false,
    val input: String = "",
    /** Files attached to the next message; cleared as soon as it is sent. */
    val attachments: List<AiAttachment> = emptyList(),
    val sending: Boolean = false,
    val confirm: AiConfirmRequest? = null,
    val errorMessage: String? = null,
    /** Plan mode batches a whole round of actions behind one approval instead of running them one by one. */
    val planMode: Boolean = false,
    val plan: AiPlanItem? = null,
    /** A whole round of gates shown as one list dialog; set only while the user is deciding. */
    val batch: AiBatchApproval? = null,
    /** How many actions the assistant has queued but not executed yet, for the hint above the input. */
    val pendingQueue: Int = 0,
)

@Immutable
data class AiConsoleActions(
    val onBack: () -> Unit,
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    /** Raises the clear request; the screen confirms it before the conversation is dropped. */
    val onClear: () -> Unit,
    val onToggleLog: () -> Unit,
    val onConfirm: (Long) -> Unit,
    val onCancel: (Long) -> Unit,
    val onOpenConfig: () -> Unit,
    val onOpenAudit: () -> Unit,
    /** Opens the module maker page, which shows the draft the last make_module carried. */
    val onOpenModuleMaker: () -> Unit,
    val onTogglePlanMode: (Boolean) -> Unit,
    val onApprovePlan: () -> Unit,
    val onRevisePlan: () -> Unit,
    val onAbortPlan: () -> Unit,
    val onAttachmentsAdded: (List<AiAttachment>) -> Unit,
    val onRemoveAttachment: (Long) -> Unit,
    val onAttachmentError: (String) -> Unit,
    /** Ticks or unticks one row of the batch dialog. */
    val onBatchToggle: (Long) -> Unit,
    /** Selects or clears every row; the dialog disables it when the round contains a fatal action. */
    val onBatchSelectAll: (Boolean) -> Unit,
    /** Approves the ticked rows; an irreversible row must have been ticked by hand first. */
    val onBatchConfirm: () -> Unit,
    /** Throws the whole round away: the cancel button, never a tap outside the dialog. */
    val onBatchCancel: () -> Unit,
    /** Only hides the dialog; the round stays queued and the hint above the input brings it back. */
    val onBatchHide: () -> Unit,
    /** Brings the pending queue back on screen from the hint above the input box. */
    val onOpenQueue: () -> Unit,
    /**
     * Runs one finished-for-bad-reasons row again through the same approval gate. Only offered on
     * outcomes that left the device untouched, so a retry can never repeat a completed write.
     */
    val onRetryAction: (Long) -> Unit,
)
