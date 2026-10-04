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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One conversation turn, Miuix flavour. Hidden messages never reach the chat list. */
@Composable
fun AiMessageMiuix(
    message: AiConsoleMessage,
    plan: AiPlanItem? = null,
    onApprovePlan: () -> Unit = {},
    onRevisePlan: () -> Unit = {},
    onAbortPlan: () -> Unit = {},
    onOpenModuleMaker: () -> Unit = {},
) {
    if (message.hidden) return
    when (message.role) {
        AiRole.USER -> AiUserBubbleMiuix(message.text)
        AiRole.ASSISTANT -> Column(modifier = Modifier.fillMaxWidth()) {
            message.reasoning?.takeIf { it.isNotBlank() }?.let { AiReasoningCardMiuix(it) }
            if (message.text.isNotBlank()) {
                Text(
                    text = aiRichText(message.text, codeBackground = colorScheme.surfaceContainerHigh),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            if (message.streaming) AiThinkingMiuix()
            val activePlan = plan?.takeIf { it.messageId == message.id }
            if (activePlan != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AiPlanCardMiuix(
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
                    AiActionCardMiuix(item, onOpenModuleMaker)
                }
            }
        }
    }
}

@Composable
fun AiUserBubbleMiuix(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colorScheme.secondaryContainer),
        ) {
            Text(
                text = text,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
fun AiThinkingMiuix() {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfiniteProgressIndicator(color = colorScheme.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ai_console_thinking),
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** Collapsed reasoning block; a reasoning model can stream thousands of characters. */
@Composable
fun AiReasoningCardMiuix(reasoning: String) {
    var expanded by remember(reasoning) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.surfaceContainerHigh),
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
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) {
                        stringResource(R.string.ai_console_log_collapse)
                    } else {
                        stringResource(R.string.ai_console_log_expand)
                    },
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = aiLinkColor(colorScheme.primary, colorScheme.surfaceContainerHigh),
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = reasoning,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** One proposed or executed operation: tier, status, target and captured output. */
@Composable
fun AiActionCardMiuix(item: AiActionItem, onOpenModuleMaker: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = aiActionTitle(item.action),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                aiTierLabel(item.tier)?.let { label ->
                    AiChipMiuix(text = label, danger = item.tier == AiSafetyTier.STRICT)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                AiChipMiuix(text = aiStatusLabel(item.status), danger = false)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = aiActionTarget(item.action),
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurfaceVariantSummary,
            )
            if (item.action is AiAction.MakeModule) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    text = stringResource(R.string.ai_console_module_open_editor),
                    onClick = onOpenModuleMaker,
                )
            }
            item.action.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = reason,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            if (item.status == AiActionStatus.DENIED || item.status == AiActionStatus.CANCELLED) {
                item.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = note,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = colorScheme.error,
                    )
                }
            }
            if (item.status == AiActionStatus.FAILED) {
                aiFriendlyError(item.output)?.let { friendly ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "\u26a0\ufe0f " + friendly,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = colorScheme.error,
                    )
                }
            }
            item.output?.takeIf { it.isNotBlank() }?.let { output ->
                Spacer(modifier = Modifier.height(8.dp))
                AiOutputSectionMiuix(output)
            }
        }
    }
}

/**
 * A captured result stays one line tall; short results open themselves because hiding them would
 * only add a tap. Long dumps (a whole script, a package list) wait for the user to ask.
 */
@Composable
fun AiOutputSectionMiuix(output: String) {
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
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) {
                    stringResource(R.string.ai_console_log_collapse)
                } else {
                    stringResource(R.string.ai_console_log_expand)
                },
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = aiLinkColor(colorScheme.primary, colorScheme.surfaceContainerHigh),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            AiTerminalCardMiuix(output)
        }
    }
}

@Composable
private fun AiChipMiuix(text: String, danger: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (danger) colorScheme.errorContainer else colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = if (danger) colorScheme.onErrorContainer else colorScheme.onSurface,
        )
    }
}

/** Read only terminal echo of a command result. */
@Composable
fun AiTerminalCardMiuix(output: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(TerminalBackground, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            text = output.trim(),
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            fontFamily = FontFamily.Monospace,
            color = TerminalForeground,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

/** Live [AI_AGENT] tail: what the client is doing right now. */
@Composable
fun AiAgentLogPanelMiuix(
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
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) {
                    stringResource(R.string.ai_console_log_collapse)
                } else {
                    stringResource(R.string.ai_console_log_expand)
                },
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = aiLinkColor(colorScheme.primary, colorScheme.surfaceContainerHigh),
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
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        fontFamily = FontFamily.Monospace,
                        color = TerminalForeground.copy(alpha = 0.5f),
                    )
                }
                lines.forEach { line ->
                    Text(
                        text = formatter.format(Date(line.ts)) + "  " + line.message,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        fontFamily = FontFamily.Monospace,
                        color = when (line.level) {
                            AiAgentLog.Level.DENIED, AiAgentLog.Level.ERROR -> colorScheme.error
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
fun AiPlanCardMiuix(
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
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AiChipMiuix(
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
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.ai_console_plan_hint, plan.stepCount),
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
            items.forEachIndexed { index, item ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = (index + 1).toString() + ".",
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                AiActionCardMiuix(item, onOpenModuleMaker)
            }
            if (plan.paused || !plan.running) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        text = stringResource(R.string.ai_console_plan_revise),
                        onClick = onRevise,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (plan.paused) {
                        TextButton(
                            text = stringResource(R.string.ai_console_plan_abort),
                            onClick = onAbort,
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.ai_console_plan_approve),
                            onClick = onApprove,
                            enabled = items.isNotEmpty(),
                        )
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
fun AiModeSwitchMiuix(planMode: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AiModeChipMiuix(
            text = stringResource(R.string.ai_console_mode_chat),
            selected = !planMode,
            onClick = { onToggle(false) },
        )
        AiModeChipMiuix(
            text = stringResource(R.string.ai_console_mode_plan),
            selected = planMode,
            onClick = { onToggle(true) },
        )
    }
}

@Composable
private fun AiModeChipMiuix(text: String, selected: Boolean, onClick: () -> Unit) {
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
                .background(if (selected) colorScheme.secondaryContainer else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (selected) Color.Transparent else colorScheme.outline.copy(alpha = 0.6f),
                    shape = shape,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
