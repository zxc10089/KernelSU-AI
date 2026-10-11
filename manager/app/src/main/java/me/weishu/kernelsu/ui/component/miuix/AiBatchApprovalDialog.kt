package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.tool.AiRiskLevel
import me.weishu.kernelsu.ui.component.aichat.aiRiskLabel
import me.weishu.kernelsu.ui.component.statustag.StatusTag
import me.weishu.kernelsu.ui.screen.aiassistant.AiBatchApproval
import me.weishu.kernelsu.ui.screen.aiassistant.AiBatchItem
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * One dialog for a whole round of gates, Miuix flavour. Mirrors AiBatchApprovalDialogMaterial.
 *
 * The round is approved as a list instead of one dialog per action. Every row leads with the business
 * wording of what will happen ("Delete permanently") and the object it happens to, while the raw
 * argument dump hides behind a per-row details toggle. A round that contains a fatal action starts
 * with nothing ticked and cannot be swept up by select-all, so each irreversible row has to be ticked
 * by hand; the tag additionally says "irreversible".
 *
 * [onDismissRequest] and [onCancel] are deliberately different: tapping outside or pressing back
 * only hides the dialog and leaves the round queued, so the waiting hint above the input box can
 * bring the very same list back. Only the cancel button throws the round away.
 */
@Composable
fun AiBatchApprovalDialogMiuix(
    approval: AiBatchApproval?,
    onToggle: (Long) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit,
) {
    val batch = approval ?: return
    val title = if (batch.items.size == 1) {
        batch.items.first().label
    } else {
        stringResource(R.string.ai_console_batch_title)
    }
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismissRequest,
        content = {
            val runnable = batch.selectedCount > 0
            Column {
                Text(
                    text = stringResource(R.string.ai_console_batch_message, batch.items.size),
                    color = colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ai_console_batch_select_all),
                        color = if (batch.containsFatal) {
                            colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
                        } else {
                            colorScheme.primary
                        },
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable(enabled = !batch.containsFatal) { onSelectAll(true) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        text = stringResource(R.string.ai_console_batch_select_none),
                        color = colorScheme.primary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { onSelectAll(false) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (item in batch.items) {
                        AiBatchRowMiuix(
                            item = item,
                            checked = item.actionId in batch.selected,
                            onToggle = onToggle,
                        )
                    }
                }
                if (batch.containsFatal) {
                    Text(
                        text = stringResource(R.string.ai_console_batch_fatal_note),
                        color = colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.ai_console_batch_run, batch.selectedCount),
                        onClick = { if (runnable) onConfirm() },
                        modifier = Modifier.weight(1f),
                        colors = if (runnable) {
                            ButtonDefaults.textButtonColors(
                                color = colorScheme.error,
                                textColor = colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.textButtonColors(
                                color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f),
                                textColor = colorScheme.onSurfaceVariantSummary,
                            )
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun AiBatchRowMiuix(item: AiBatchItem, checked: Boolean, onToggle: (Long) -> Unit) {
    val (background, content) = riskColorsMiuix(item.risk)
    var details by remember(item.actionId) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatchCheckbox(checked = checked, onCheckedChange = { onToggle(item.actionId) })
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = item.label,
                color = colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    color = colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.payload.isNotBlank()) {
                Text(
                    text = stringResource(
                        if (details) R.string.ai_console_batch_details_hide
                        else R.string.ai_console_batch_details,
                    ),
                    color = colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { details = !details }
                        .padding(vertical = 2.dp),
                )
                if (details) {
                    Text(
                        text = stringResource(R.string.ai_console_batch_raw) + "\n" + item.payload,
                        color = colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusTag(
            label = aiBatchRiskLabel(item.risk),
            backgroundColor = background,
            contentColor = content,
        )
    }
}

/**
 * The risk tag, with the "irreversible" suffix on fatal rows: the wording says what the tag means
 * instead of relying on the colour alone.
 */
@Composable
internal fun aiBatchRiskLabel(risk: AiRiskLevel): String = if (risk == AiRiskLevel.FATAL) {
    aiRiskLabel(risk) + " \u00b7 " + stringResource(R.string.ai_console_batch_fatal_suffix)
} else {
    aiRiskLabel(risk)
}

/**
 * A bare checkbox. Miuix only ships the full-row CheckboxPreference, so this draws the same shape the
 * AI selection rows already use: filled with the primary colour when ticked, outlined when not.
 */
@Composable
private fun BatchCheckbox(checked: Boolean, onCheckedChange: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(if (checked) colorScheme.primary else Color.Transparent, shape)
            .border(
                width = 1.dp,
                color = if (checked) Color.Transparent else colorScheme.outline.copy(alpha = 0.6f),
                shape = shape,
            )
            .clickable { onCheckedChange() },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(text = "\u2713", color = colorScheme.onPrimary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun riskColorsMiuix(risk: AiRiskLevel): Pair<Color, Color> = when (risk) {
    AiRiskLevel.LOW -> colorScheme.surfaceContainerHigh to colorScheme.onSurfaceVariantSummary
    AiRiskLevel.MEDIUM -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
    AiRiskLevel.HIGH -> colorScheme.errorContainer to colorScheme.onErrorContainer
    AiRiskLevel.FATAL -> colorScheme.error to colorScheme.onError
}
