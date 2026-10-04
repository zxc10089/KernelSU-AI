package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAccessScope
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private val SheetRowPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/**
 * "Choose access scope" bottom sheet, Miuix flavour.
 *
 * The three options are the whole scope model: forbid everything, /data/adb only, or the whole
 * file system. Picking a wider scope does not apply here - the caller turns it into the typed
 * confirmation dialog first.
 */
@Composable
fun AiScopeSheetMiuix(
    show: Boolean,
    selected: AiAccessScope,
    onSelected: (AiAccessScope) -> Unit,
    onDismissRequest: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.ai_access_scope_sheet_title),
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AiAccessScope.options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(option) }
                        .padding(SheetRowPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(option.labelRes),
                            fontSize = MiuixTheme.textStyles.body1.fontSize,
                        )
                        Text(
                            text = stringResource(option.summaryRes),
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (option == selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colorScheme.primary,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismissRequest,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
