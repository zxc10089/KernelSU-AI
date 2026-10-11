package me.weishu.kernelsu.ui.component.aichat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAction
import me.weishu.kernelsu.data.agent.AiAgentLog
import me.weishu.kernelsu.data.agent.AiSafetyTier
import me.weishu.kernelsu.ui.screen.aiassistant.AiActionItem
import me.weishu.kernelsu.ui.screen.aiassistant.AiActionStatus
import me.weishu.kernelsu.ui.screen.aiassistant.AiConsoleMessage
import me.weishu.kernelsu.ui.screen.aiassistant.AiPlanItem
import me.weishu.kernelsu.ui.screen.aiassistant.AiRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One conversation turn, Material flavour. Mirrors AiMessageMiuix. */
@Composable
fun AiMessageMaterial(
    message: AiConsoleMessage,
    plan: AiPlanItem? = null,
    onApprovePlan: () -> Unit = {},
    onRevisePlan: () -> Unit = {},
    onAbortPlan: () -> Unit = {},
    onOpenModuleMaker: () -> Unit = {},
    onRetryAction: (Long) -> Unit = {},
) {
    if (message.hidden) return
    when (message.role) {
        AiRole.USER -> AiUserBubbleMaterial(message.text)
        AiRole.ASSISTANT -> Column(modifier = Modifier.fillMaxWidth()) {
            message.reasoning?.takeIf { it.isNotBlank() }?.let { AiReasoningCardMaterial(it, message.id) }
            if (message.text.isNotBlank()) {
                Text(
                    text = aiRichText(message.text, codeBackground = MaterialTheme.colorScheme.surfaceVariant),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            if (message.streaming) AiThinkingMaterial()
            val activePlan = plan?.takeIf { it.messageId == message.id }
            if (activePlan != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AiPlanCardMaterial(
                    plan = activePlan,
                    items = message.actions,
                    onApprove = onApprovePlan,
                    onRevise = onRevisePlan,
                    onAbort = onAbortPlan,
                    onOpenModuleMaker = onOpenModuleMaker,
                    onRetryAction = onRetryAction,
                )
            } else {
                message.actions.forEach { item ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AiActionCardMaterial(item, onOpenModuleMaker, onRetryAction)
                }
            }
        }
    }
}

@Composable
fun AiUserBubbleMaterial(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
fun AiThinkingMaterial() {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.width(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ai_console_thinking),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AiReasoningCardMaterial(reasoning: String, messageId: Long) {
    // Keyed on the message, never on the streaming text: `reasoning` changes on every chunk while
    // the model is still thinking, so a text key recreated this state again and again and the card
    // snapped back to collapsed immediately after a tap. Collapsing only hides the text below — the
    // stream keeps arriving and the ViewModel keeps appending to `reasoning`.
    var expanded by remember(messageId) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        onClickLabel = stringResource(
                            if (expanded) R.string.ai_console_log_collapse else R.string.ai_console_log_expand,
                        ),
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ai_console_reasoning),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (expanded) R.string.ai_console_log_collapse
                        else R.string.ai_console_log_expand,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = aiLinkColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = aiLinkColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
fun AiActionCardMaterial(
    item: AiActionItem,
    onOpenModuleMaker: () -> Unit = {},
    onRetry: (Long) -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // The verdict stripe runs the full height of the card, so the row measures its content first.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            AiResultStripe(item.status)
            Column(modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = aiActionTitle(item.action),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    aiTierLabel(item.tier)?.let { label ->
                        AiChipMaterial(text = label, danger = item.tier == AiSafetyTier.STRICT)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    AiChipMaterial(text = aiStatusLabel(item.status), danger = false)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = aiActionTarget(item.action),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.action is AiAction.MakeModule) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = onOpenModuleMaker) {
                        Text(stringResource(R.string.ai_console_module_open_editor))
                    }
                }
                item.action.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.status == AiActionStatus.DENIED || item.status == AiActionStatus.CANCELLED) {
                    item.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (item.status == AiActionStatus.FAILED) {
                    aiFriendlyError(item.output)?.let { friendly ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = friendly,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item.output?.takeIf { it.isNotBlank() }?.let { output ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AiOutputSectionMaterial(output)
                }
                AiResultFooterMaterial(item, onRetry)
            }
        }
    }
}

/**
 * The copy / retry line under one result. Copy is offered whenever there is something to take away;
 * retry only for the outcomes that left the device untouched (failed, refused, cancelled, skipped),
 * because re-running a write that already succeeded is a footgun rather than a convenience.
 */
@Composable
private fun AiResultFooterMaterial(item: AiActionItem, onRetry: (Long) -> Unit) {
    val output = item.output?.takeIf { it.isNotBlank() }
    val note = item.note?.takeIf { it.isNotBlank() }
    // A refusal carries no output but does carry the reason, and that is exactly what gets shared.
    val copyable = output != null || note != null
    val retryable = item.status == AiActionStatus.FAILED || item.status == AiActionStatus.DENIED ||
        item.status == AiActionStatus.CANCELLED || item.status == AiActionStatus.SKIPPED
    if (!copyable && !retryable) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val title = aiActionTitle(item.action)
    val target = aiActionTarget(item.action)
    val status = aiStatusLabel(item.status)
    // The label flips to "copied" for a moment as the only visible proof the tap landed.
    var copied by remember(item.id, item.output, item.note) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MS)
            copied = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (copyable) {
            // Filled capsule: the primary action sits one step above the card surface. The card itself is
            // surfaceVariant, and in this dynamic scheme surfaceContainerHighest resolves to that very same
            // colour (measured on a real device: both #23252E), which would erase the fill. A low-alpha
            // primary tint stays visible in any scheme, and the label/icon keep onSurface so the action
            // never competes with the status rail.
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClickLabel = stringResource(R.string.ai_console_result_copy)) {
                        val text = aiResultCopyText(title, target, status, note, output)
                        // setClipEntry suspends, so the label only flips once the clipboard really holds it.
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(CLIP_LABEL, text)))
                            copied = true
                        }
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The icon swaps with the label, so "copied" is never signalled by colour alone.
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (copied) R.string.ai_console_result_copied else R.string.ai_console_result_copy,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (retryable) {
            // Outlined capsule: restarts an authorisation gate, so it must read as the secondary action.
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = stringResource(R.string.ai_console_result_retry)) {
                        onRetry(item.id)
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ai_console_result_retry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Long enough that the confirmation is still on screen when the user looks back at the card.
private const val COPIED_LABEL_MS = 4000L
private const val CLIP_LABEL = "kernelsu-ai"
private const val AGENT_LOG_TAG = "AI_AGENT"

/**
 * A captured result stays one line tall; short results open themselves because hiding them would
 * only add a tap. Long dumps (a whole script, a package list) wait for the user to ask.
 */
@Composable
fun AiOutputSectionMaterial(output: String) {
    var expanded by remember(output) { mutableStateOf(aiOutputAutoExpanded(output)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.ai_console_log_collapse else R.string.ai_console_log_expand,
                    ),
                ) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = aiOutputSummary(output),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) {
                    stringResource(R.string.ai_console_log_collapse)
                } else {
                    stringResource(R.string.ai_console_log_expand)
                },
                style = MaterialTheme.typography.labelSmall,
                color = aiLinkColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = aiLinkColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            AiTerminalCardMaterial(output)
        }
    }
}

@Composable
private fun AiChipMaterial(text: String, danger: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (danger) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (danger) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun AiTerminalCardMaterial(output: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(TerminalBackground, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            text = output.trim(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = TerminalForeground,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
fun AiAgentLogPanelMaterial(
    lines: List<AiAgentLog.Line>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Reading a failure out of dozens of lines is the common case, so the panel can hide the noise and
    // hand the whole thing to the clipboard for a bug report.
    var errorsOnly by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val errorLines = remember(lines) {
        lines.filter {
            it.level == AiAgentLog.Level.DENIED || it.level == AiAgentLog.Level.ERROR
        }
    }
    val shown = if (errorsOnly) errorLines else lines
    val logTitle = stringResource(R.string.ai_console_agent_log)
    val logText = remember(lines, logTitle, formatter) {
        buildString {
            appendLine("[$AGENT_LOG_TAG] $logTitle")
            lines.forEach { line ->
                appendLine(formatter.format(Date(line.ts)) + "  " + line.level + "  " + line.message)
            }
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MS)
            copied = false
        }
    }
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.ai_console_log_collapse else R.string.ai_console_log_expand,
                    ),
                ) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "[$AGENT_LOG_TAG] " + logTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (expanded) {
                AgentLogActionMaterial(
                    label = stringResource(
                        if (errorsOnly) {
                            R.string.ai_console_log_all
                        } else {
                            R.string.ai_console_log_errors_only
                        },
                    ),
                    enabled = errorLines.isNotEmpty(),
                    onClick = { errorsOnly = !errorsOnly },
                )
                AgentLogActionMaterial(
                    label = stringResource(
                        if (copied) R.string.ai_console_log_copied else R.string.ai_console_log_copy,
                    ),
                    enabled = lines.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText(AGENT_LOG_TAG, logText)),
                            )
                            copied = true
                        }
                    },
                )
            }
            Text(
                text = stringResource(
                    if (expanded) R.string.ai_console_log_collapse
                    else R.string.ai_console_log_expand,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = aiLinkColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = aiLinkColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.height(if (expanded) 4.dp else 0.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (expanded) 420.dp else 0.dp)
                .background(TerminalBackground, RoundedCornerShape(8.dp))
                .padding(if (expanded) 8.dp else 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                if (shown.isEmpty()) {
                    Text(
                        text = "-",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TerminalForeground.copy(alpha = 0.5f),
                    )
                }
                shown.forEach { line ->
                    Text(
                        text = formatter.format(Date(line.ts)) + "  " + line.message,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (line.level) {
                            AiAgentLog.Level.DENIED, AiAgentLog.Level.ERROR -> MaterialTheme.colorScheme.error
                            AiAgentLog.Level.RESULT -> TerminalForeground
                            AiAgentLog.Level.ACTION -> TerminalAccent
                            AiAgentLog.Level.INFO -> TerminalForeground.copy(alpha = 0.72f)
                        },
                    )
                }
            }
        }
    }
}

/** One text action in the log header; dimmed when it would do nothing. */
@Composable
private fun AgentLogActionMaterial(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled) {
            aiLinkColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
    )
}

/**
 * The batch approval card of plan mode: the whole plan in one place with an explicit decision.
 *
 * A step that changes state keeps its own gate, so approving a plan never approves a write on its
 * own; the read-only steps are the ones this approval covers.
 */
@Composable
fun AiPlanCardMaterial(
    plan: AiPlanItem,
    items: List<AiActionItem>,
    onApprove: () -> Unit,
    onRevise: () -> Unit,
    onAbort: () -> Unit,
    onOpenModuleMaker: () -> Unit = {},
    onRetryAction: (Long) -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ai_console_plan_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AiChipMaterial(
                    text = when {
                        plan.paused -> stringResource(R.string.ai_console_plan_paused)
                        plan.running -> stringResource(R.string.ai_console_plan_running)
                        else -> stringResource(R.string.ai_console_plan_waiting)
                    },
                    danger = plan.paused,
                )
            }
            if (plan.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = plan.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.ai_console_plan_hint, plan.stepCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.forEachIndexed { index, item ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = (index + 1).toString() + ".",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                AiActionCardMaterial(item, onOpenModuleMaker, onRetryAction)
            }
            if (plan.paused || !plan.running) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onRevise) {
                        Text(stringResource(R.string.ai_console_plan_revise))
                    }
                    if (plan.paused) {
                        TextButton(onClick = onAbort) {
                            Text(stringResource(R.string.ai_console_plan_abort))
                        }
                    } else {
                        TextButton(onClick = onApprove, enabled = items.isNotEmpty()) {
                            Text(stringResource(R.string.ai_console_plan_approve))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Conversation / plan mode switch, drawn as the two pills the reference composer puts on the left of
 * its action row: the selected mode is filled, the other one is outlined.
 */
@Composable
fun AiModeSwitchMaterial(planMode: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AiModeChipMaterial(
            text = stringResource(R.string.ai_console_mode_chat),
            selected = !planMode,
            onClick = { onToggle(false) },
        )
        AiModeChipMaterial(
            text = stringResource(R.string.ai_console_mode_plan),
            selected = planMode,
            onClick = { onToggle(true) },
        )
    }
}

@Composable
private fun AiModeChipMaterial(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    // The pill itself stays small; the touch target around it is a full height row.
    Box(
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    shape = shape,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
