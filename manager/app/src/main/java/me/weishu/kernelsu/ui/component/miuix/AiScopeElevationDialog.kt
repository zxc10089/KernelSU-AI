package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAccessScope
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Confirmation for widening the file access scope, Miuix flavour.
 *
 * Widening is the one setting that turns a chat assistant into something that can read the device
 * file system, so it stays a separate, deliberate step that names the target scope. A plain tap on
 * the confirm button is enough, so the typed keyword gate was removed. Narrowing never reaches
 * this dialog.
 */
@Composable
fun AiScopeElevationDialogMiuix(
    target: AiAccessScope?,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val scope = target ?: return

    OverlayDialog(
        show = true,
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        Text(
            text = stringResource(R.string.ai_access_scope_elevate_title),
            fontSize = MiuixTheme.textStyles.title4.fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.ai_access_scope_elevate_message, stringResource(scope.labelRes)),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        TextButton(
            text = stringResource(R.string.ai_access_scope_confirm_button),
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp)
                .padding(horizontal = 24.dp),
        )
        TextButton(
            text = stringResource(android.R.string.cancel),
            onClick = onDismissRequest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
        )
    }
}
