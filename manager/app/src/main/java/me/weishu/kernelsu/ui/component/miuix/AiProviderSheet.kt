package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Row padding inside the provider sheet. Horizontal 20dp follows ui/component/miuix/DropdownItem.kt,
 * vertical 16dp matches the default insideMargin of the Miuix preferences used elsewhere.
 */
private val SheetRowPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/** Keeps the sheet below the screen height on a 400x890dp layout. */
private val SheetListMaxHeight = 460.dp

/**
 * "Choose API provider" bottom sheet, Miuix flavour.
 *
 * Uses [OverlayBottomSheet]: miuix-kmp 0.9.3 has no `SuperBottomSheet` (that class does not exist
 * in the artifact), and OverlayBottomSheet is the only bottom sheet the library ships.
 * Search is local state only - there is no network in phase 1.
 */
@Composable
fun AiProviderSheetMiuix(
    show: Boolean,
    selectedId: String,
    onSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(show) {
        if (!show) query = ""
    }

    val context = LocalContext.current
    // Labels are resolved outside the layout tree because the filter needs the localized text and
    // stringResource cannot be called from a plain (non-composable) lambda.
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

    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.ai_provider_sheet_title),
        onDismissRequest = onDismissRequest,
    ) {
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = stringResource(R.string.ai_provider_search_hint),
            useLabelAsPlaceholder = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariantActions,
                )
            },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.ai_provider_empty),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
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
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onDismissRequest,
                text = stringResource(android.R.string.cancel),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
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
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
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
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (provider.recommended) {
            Text(
                text = stringResource(R.string.ai_provider_badge_recommended),
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colorScheme.primary,
            )
        }
    }
}
