package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAccessScope

/**
 * Confirmation for widening the file access scope, Material flavour. Mirrors AiScopeElevationDialogMiuix:
 * a deliberate separate step that names the target scope, released by a single tap (m04766).
 */
@Composable
fun AiScopeElevationDialogMaterial(
    target: AiAccessScope?,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val scope = target ?: return

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(R.string.ai_access_scope_elevate_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.ai_access_scope_elevate_message,
                        stringResource(scope.labelRes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ai_access_scope_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
