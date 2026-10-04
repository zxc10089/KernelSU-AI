package me.weishu.kernelsu.ui.component.miuix

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.screen.aiconfig.AiModelListError
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Row padding inside the sheet, matching ui/component/miuix/AiProviderSheet.kt. */
private val SheetRowPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/** Keeps the sheet below the screen height on a 400x890dp layout. */
private val SheetListMaxHeight = 460.dp

/**
 * "Choose model" bottom sheet, Miuix flavour.
 *
 * The list is a snapshot of one GET /models call: it is not re-fetched while the sheet is open,
 * and [onRetry] is the only way to run the request again. Selecting a row writes the model name
 * back into the text field through the caller.
 */
@Composable
fun AiModelListSheetMiuix(
    show: Boolean,
    loading: Boolean,
    models: List<String>,
    error: AiModelListError?,
    currentModel: String,
    onRetry: () -> Unit,
    onSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.ai_model_list_title),
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(color = colorScheme.onBackground)
                }

                error != null -> Text(
                    text = describeModelListError(error),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )

                models.isEmpty() -> Text(
                    text = stringResource(R.string.ai_model_list_empty),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
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
                                fontSize = MiuixTheme.textStyles.body1.fontSize,
                                modifier = Modifier.weight(1f),
                            )
                            if (model == currentModel) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (error != null && !loading) {
                    TextButton(
                        text = stringResource(R.string.ai_model_list_retry),
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismissRequest,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
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
