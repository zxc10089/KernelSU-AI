package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.screen.aiconfig.AiProvider
import me.weishu.kernelsu.ui.screen.aiconfig.AiProviderGroup
import me.weishu.kernelsu.ui.screen.aiconfig.AiProviders

private val SheetRowPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/** Keeps the sheet below the screen height on a 400x890dp layout. */
private val SheetListMaxHeight = 460.dp

/**
 * "Choose API provider" bottom sheet, Material flavour.
 *
 * Material3's [ModalBottomSheet] already supplies the large top corners and the scrim, so nothing
 * is re-implemented here. Search is local state only - there is no network in phase 1.
 */
@Composable
fun AiProviderSheetMaterial(
    show: Boolean,
    selectedId: String,
    onSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (!show) return

    // The composable is only present while the sheet is open, so this state is disposed on
    // dismissal and starts empty every time the sheet is reopened.
    var query by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current
    val entries = remember(context) {
        AiProviders.all.map { it to context.getString(it.labelRes) }
    }
    val keyword = query.trim().lowercase()
    val filtered = if (keyword.isEmpty()) {
        entries
    } else {
        entries.filter { (provider, label) ->
            label.lowercase().contains(keyword) || provider.id.contains(keyword)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        content = {
            Column {
                Text(
                    text = stringResource(R.string.ai_provider_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    placeholder = { Text(stringResource(R.string.ai_provider_search_hint)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                    },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))

                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ai_provider_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(SheetRowPadding),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = SheetListMaxHeight),
                    ) {
                        AiProviders.groupOrder.forEach { group ->
                            val groupItems = filtered.filter { it.first.group == group }
                            if (groupItems.isEmpty()) return@forEach
                            item(key = "header_" + group.name) {
                                ProviderGroupHeader(group = group)
                            }
                            items(groupItems, key = { it.first.id }) { (provider, label) ->
                                ProviderRow(
                                    label = label,
                                    provider = provider,
                                    selected = provider.id == selectedId,
                                    onClick = { onSelected(provider.id) },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        },
    )
}

@Composable
private fun ProviderGroupHeader(group: AiProviderGroup) {
    Text(
        text = stringResource(
            when (group) {
                AiProviderGroup.RECOMMENDED -> R.string.ai_provider_group_recommended
                AiProviderGroup.LOCAL -> R.string.ai_provider_group_local
                AiProviderGroup.MORE -> R.string.ai_provider_group_more
            }
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ProviderRow(
    label: String,
    provider: AiProvider,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(SheetRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (provider.recommended) {
            Text(
                text = stringResource(R.string.ai_provider_badge_recommended),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
