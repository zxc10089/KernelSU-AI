package me.weishu.kernelsu.ui.component.aichat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
) {
    if (message.hidden) return
    when (message.role) {
        AiRole.USER -> AiUserBubbleMaterial(message.text)
        AiRole.ASSISTANT -> Column(modifier = Modifier.fillMaxWidth()) {
            message.reasoning?.takeIf { it.isNotBlank() }?.let { AiReasoningCardMaterial(it) }
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
                )
            } else {
                message.actions.forEach { item ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AiActionCardMaterial(item, onOpenModuleMaker)
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
fun AiReasoningCardMaterial(reasoning: String) {
    var expanded by remember(reasoning) { mutableStateOf(false) }
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
                    .clickable { expanded = !expanded },
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
fun AiActionCardMaterial(item: AiActionItem, onOpenModuleMaker: () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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
                        text = "\u26a0\ufe0f " + friendly,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item.output?.takeIf { it.isNotBlank() }?.let { output ->
                Spacer(modifier = Modifier.height(8.dp))
                AiOutputSectionMaterial(output)
            }
        }
    }
}

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
                .clickable { expanded = !expanded },
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
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "[AI_AGENT] " + stringResource(R.string.ai_console_agent_log),
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
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
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
                if (lines.isEmpty()) {
                    Text(
                        text = "-",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TerminalForeground.copy(alpha = 0.5f),
                    )
                }
                lines.forEach { line ->
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
                AiActionCardMaterial(item, onOpenModuleMaker)
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
