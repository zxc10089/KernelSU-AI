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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAccessScope

private val SheetRowPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)

/**
 * "Choose access scope" bottom sheet, Material flavour. Mirrors AiScopeSheetMiuix.
 */
@Composable
fun AiScopeSheetMaterial(
    show: Boolean,
    selected: AiAccessScope,
    onSelected: (AiAccessScope) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (!show) return

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.ai_access_scope_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            AiAccessScope.options.forEach { option ->
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(option.summaryRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

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
