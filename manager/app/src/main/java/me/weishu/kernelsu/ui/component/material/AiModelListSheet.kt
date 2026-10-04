package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.screen.aiconfig.AiModelListError

private val SheetRowPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/** Keeps the sheet below the screen height on a 400x890dp layout. */
private val SheetListMaxHeight = 460.dp

/**
 * "Choose model" bottom sheet, Material flavour. Mirrors AiModelListSheetMiuix.
 */
@Composable
fun AiModelListSheetMaterial(
    show: Boolean,
    loading: Boolean,
    models: List<String>,
    error: AiModelListError?,
    currentModel: String,
    onRetry: () -> Unit,
    onSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (!show) return

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.ai_model_list_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                error != null -> Text(
                    text = describeModelListError(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )

                models.isEmpty() -> Text(
                    text = stringResource(R.string.ai_model_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = SheetListMaxHeight),
                ) {
                    items(models, key = { it }) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelected(model) }
                                .padding(SheetRowPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (model == currentModel) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (error != null && !loading) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.ai_model_list_retry))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}

/** Maps a transport failure onto the localised message shown inside the sheet. */
@Composable
private fun describeModelListError(error: AiModelListError): String = when (error) {
    is AiModelListError.HttpStatus -> stringResource(R.string.ai_model_list_error_http, error.code)
    is AiModelListError.Network -> stringResource(R.string.ai_model_list_error_network, error.detail)
    AiModelListError.Malformed -> stringResource(R.string.ai_model_list_error_malformed)
    AiModelListError.InvalidEndpoint -> stringResource(R.string.ai_model_list_error_endpoint)
}
