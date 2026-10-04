package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.aichat.TerminalBackground
import me.weishu.kernelsu.ui.component.aichat.TerminalForeground
import me.weishu.kernelsu.ui.screen.aiassistant.AiConfirmRequest
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * The red gate for STRICT operations (see design/ai-console-spec.md section 6).
 *
 * This dialog is deliberately the only place where a root command can be released: the confirming
 * button carries the error colour, the payload is shown in a terminal block, and the warning text
 * is not dismissible content but part of the contract in the design brief.
 */
@Composable
fun AiExecConfirmDialogMiuix(
    request: AiConfirmRequest.Strict?,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val pending = request ?: return
    // User decision 2026-10-04 (m04766): the STRICT gate is a single explicit tap. The payload in
    // the terminal block, the red warning and the error-coloured button carry the weight; no
    // keyword typing is required any more, here or in the access-scope elevation dialog.
    OverlayDialog(
        show = true,
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        Text(
            text = "\u26a0\ufe0f " + stringResource(pending.titleRes),
            fontSize = MiuixTheme.textStyles.title4.fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.ai_console_confirm_exec_message),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .heightIn(max = 200.dp)
                .background(TerminalBackground, RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(
                text = (stringResource(pending.labelRes) + "\n" + pending.payload).trim(),
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
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
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(16.dp))
            TextButton(
                text = stringResource(R.string.ai_console_confirm_exec_button),
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    color = colorScheme.error,
                    textColor = colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
