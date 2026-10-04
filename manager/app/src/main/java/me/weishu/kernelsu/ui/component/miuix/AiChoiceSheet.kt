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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private val SheetRowPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/**
 * Generic single choice sheet for a numeric setting, Miuix flavour.
 *
 * Used by the numeric settings, where the option list and the label text differ but the
 * interaction is the same: tap a row, sheet closes, value is stored. When [customLabel] and
 * [onCustomSelected] are given the sheet also offers a custom row that reveals a number field, so
 * a value the picker never listed can still be typed in by hand.
 */
@Composable
fun AiChoiceSheetMiuix(
    show: Boolean,
    title: String,
    options: List<Int>,
    selected: Int,
    optionLabel: @Composable (Int) -> String,
    hint: String,
    onSelected: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    customLabel: String? = null,
    customHint: String? = null,
    onCustomSelected: ((Int) -> Unit)? = null,
) {
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    // A reopened sheet must never show the previous draft, so the state resets on close.
    LaunchedEffect(show) {
        if (!show) {
            customOpen = false
            customText = ""
        }
    }

    OverlayBottomSheet(
        show = show,
        title = title,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(option) }
                        .padding(SheetRowPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = optionLabel(option),
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        modifier = Modifier.weight(1f),
                    )
                    if (option == selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colorScheme.primary,
                        )
                    }
                }
            }

            if (customLabel != null && onCustomSelected != null) {
                if (customOpen) {
                    val typed = customText.trim().toIntOrNull()
                    Column(modifier = Modifier.padding(SheetRowPadding)) {
                        TextField(
                            value = customText,
                            onValueChange = { customText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = customHint ?: customLabel,
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (customText.isNotBlank() && typed == null) {
                                    stringResource(R.string.ai_setting_custom_invalid)
                                } else {
                                    ""
                                },
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = stringResource(R.string.ai_setting_custom_apply),
                                onClick = { typed?.let(onCustomSelected) },
                                enabled = typed != null,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { customOpen = true }
                            .padding(SheetRowPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = customLabel,
                            fontSize = MiuixTheme.textStyles.body1.fontSize,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected !in options) {
                            Text(
                                text = optionLabel(selected),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            Text(
                text = hint,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )

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
