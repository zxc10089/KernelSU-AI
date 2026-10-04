package me.weishu.kernelsu.ui.screen.aiassistant

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.agent.AiAction
import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiAgentLog
import me.weishu.kernelsu.data.agent.AiSafetyTier
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
)
