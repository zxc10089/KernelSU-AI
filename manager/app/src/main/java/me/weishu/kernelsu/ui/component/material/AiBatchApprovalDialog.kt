package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.tool.AiRiskLevel
import me.weishu.kernelsu.ui.component.aichat.aiRiskLabel
import me.weishu.kernelsu.ui.component.statustag.StatusTag
import me.weishu.kernelsu.ui.screen.aiassistant.AiBatchApproval
import me.weishu.kernelsu.ui.screen.aiassistant.AiBatchItem

/**
 * One dialog for a whole round of gates, Material flavour. Mirrors AiBatchApprovalDialogMiuix.
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
fun AiBatchApprovalDialogMaterial(
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
    val runnable = batch.selectedCount > 0
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onSelectAll(true) },
                        enabled = !batch.containsFatal,
                    ) {
                        Text(stringResource(R.string.ai_console_batch_select_all))
                    }
                    TextButton(onClick = { onSelectAll(false) }) {
                        Text(stringResource(R.string.ai_console_batch_select_none))
                    }
                }
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.ai_console_batch_message, batch.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (item in batch.items) {
                        AiBatchRowMaterial(
                            item = item,
                            checked = item.actionId in batch.selected,
                            onToggle = onToggle,
                        )
                    }
                }
                if (batch.containsFatal) {
                    Text(
                        text = stringResource(R.string.ai_console_batch_fatal_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = runnable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.ai_console_batch_run, batch.selectedCount))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun AiBatchRowMaterial(item: AiBatchItem, checked: Boolean, onToggle: (Long) -> Unit) {
    val (background, content) = riskColorsMaterial(item.risk)
    var details by remember(item.actionId) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle(item.actionId) })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { details = !details }
                        .padding(vertical = 2.dp),
                )
                if (details) {
                    Text(
                        text = stringResource(R.string.ai_console_batch_raw) + "\n" + item.payload,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusTag(
            label = aiBatchRiskLabelMaterial(item.risk),
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
private fun aiBatchRiskLabelMaterial(risk: AiRiskLevel): String = if (risk == AiRiskLevel.FATAL) {
    aiRiskLabel(risk) + " \u00b7 " + stringResource(R.string.ai_console_batch_fatal_suffix)
} else {
    aiRiskLabel(risk)
}

@Composable
private fun riskColorsMaterial(risk: AiRiskLevel): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (risk) {
        AiRiskLevel.LOW -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        AiRiskLevel.MEDIUM -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        AiRiskLevel.HIGH -> scheme.errorContainer to scheme.onErrorContainer
        AiRiskLevel.FATAL -> scheme.error to scheme.onError
    }
}
