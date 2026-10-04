package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.aichat.TerminalBackground
import me.weishu.kernelsu.ui.component.aichat.TerminalForeground
import me.weishu.kernelsu.ui.screen.aiassistant.AiConfirmRequest

/**
 * The red gate for STRICT operations, Material flavour. Mirrors AiExecConfirmDialogMiuix.
 */
@Composable
fun AiExecConfirmDialogMaterial(
    request: AiConfirmRequest.Strict?,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val pending = request ?: return
    // The STRICT gate is a single explicit tap (see the Miuix flavour for the full rationale).
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "\u26a0\ufe0f " + stringResource(pending.titleRes),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.ai_console_confirm_exec_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(max = 200.dp)
                        .background(TerminalBackground, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = (stringResource(pending.labelRes) + "\n" + pending.payload).trim(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        color = TerminalForeground,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Text(
                    text = stringResource(
                        if (pending.readOnly) {
                            R.string.ai_console_confirm_exec_warning_readonly
                        } else {
                            R.string.ai_console_confirm_exec_warning
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.ai_console_confirm_exec_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
