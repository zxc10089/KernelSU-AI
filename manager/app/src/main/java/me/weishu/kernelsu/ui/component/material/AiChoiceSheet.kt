package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R

private val SheetRowPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)

/**
 * Generic single choice sheet for a numeric setting, Material flavour. Mirrors AiChoiceSheetMiuix,
 * including the optional custom row that lets a value outside [options] be typed in by hand.
 */
@Composable
fun AiChoiceSheetMaterial(
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
    if (!show) return

    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    // A reopened sheet must never show the previous draft, so the state resets on close.
    LaunchedEffect(show) {
        if (!show) {
            customOpen = false
            customText = ""
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(option) }
                        .padding(SheetRowPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = option == selected,
                        onClick = { onSelected(option) },
                    )
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (customLabel != null && onCustomSelected != null) {
                if (customOpen) {
                    val typed = customText.trim().toIntOrNull()
                    Column(modifier = Modifier.padding(SheetRowPadding)) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(customHint ?: customLabel) },
                            isError = customText.isNotBlank() && typed == null,
                            supportingText = {
                                if (customText.isNotBlank() && typed == null) {
                                    Text(stringResource(R.string.ai_setting_custom_invalid))
                                }
                            },
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { typed?.let(onCustomSelected) },
                                enabled = typed != null,
                            ) {
                                Text(stringResource(R.string.ai_setting_custom_apply))
                            }
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
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected !in options) {
                            Text(
                                text = optionLabel(selected),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}
