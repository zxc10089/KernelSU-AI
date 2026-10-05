package me.weishu.kernelsu.ui.screen.aiconfig

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.AiModelListSheetMaterial
import me.weishu.kernelsu.ui.component.material.AiProviderSheetMaterial
import me.weishu.kernelsu.ui.component.material.AiChoiceSheetMaterial
import me.weishu.kernelsu.ui.component.material.AiScopeElevationDialogMaterial
import me.weishu.kernelsu.ui.component.material.AiScopeSheetMaterial
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedSwitchItem
import me.weishu.kernelsu.ui.component.material.SegmentedTextField
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors

/**
 * The three quick templates. Each card writes the two switches below them, so this row is a
 * shortcut and never a second source of truth: [current] is derived from those switches.
 */
@Composable
private fun AiTemplateRowMaterial(
    current: AiPolicyTemplate?,
    onSelect: (AiPolicyTemplate) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AiPolicyTemplate.values().forEach { template ->
            val selected = template == current
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(template) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
                border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(template.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(template.summaryRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun AiConfigPagerMaterial(
    uiState: AiConfigUiState,
    actions: AiConfigActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                title = { Text(stringResource(R.string.ai_config_title)) },
                colors = expressiveTopAppBarColors(),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
        ) {
            // Card 1 - API settings.
            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.ai_section_api),
                content = listOf(
                    {
                        SegmentedListItem(
                            onClick = actions.onOpenProviderSheet,
                            headlineContent = { Text(stringResource(R.string.ai_provider)) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(uiState.provider.labelRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    },
                    {
                        SegmentedTextField(
                            value = uiState.endpoint,
                            onValueChange = actions.onEndpointChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.ai_endpoint),
                            placeholder = {
                                Text(uiState.endpointPlaceholder ?: stringResource(R.string.ai_endpoint_hint))
                            },
                            isError = uiState.endpointInvalid,
                            supportingContent = if (uiState.endpointInvalid) {
                                {
                                    Text(
                                        text = stringResource(R.string.ai_error_endpoint_scheme),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            } else {
                                null
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                        )
                    },
                    {
                        SegmentedTextField(
                            value = uiState.apiKey,
                            onValueChange = actions.onApiKeyChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.ai_api_key),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            ),
                            visualTransformation = if (apiKeyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingContent = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = stringResource(
                                            if (apiKeyVisible) R.string.ai_api_key_hide else R.string.ai_api_key_show
                                        ),
                                    )
                                }
                            },
                        )
                    },
                    {
                        SegmentedTextField(
                            value = uiState.modelName,
                            onValueChange = actions.onModelNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.ai_model_name),
                            placeholder = {
                                Text(uiState.modelPlaceholder ?: stringResource(R.string.ai_model_name_hint))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                            trailingContent = {
                                // Lists the models the configured endpoint offers.
                                IconButton(
                                    onClick = actions.onFetchModels,
                                    enabled = uiState.canFetchModels,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.ai_model_list_fetch),
                                    )
                                }
                            },
                        )
                    },
                    {
                        // How many model turns one message may chain. A request parameter, so
                        // it belongs with the model name rather than with the safety switches.
                        SegmentedListItem(
                            onClick = actions.onOpenRoundsSheet,
                            headlineContent = { Text(stringResource(R.string.ai_max_rounds_title)) },
                            supportingContent = { Text(stringResource(R.string.ai_max_rounds_summary)) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.ai_max_rounds_option,
                                            uiState.maxRounds,
                                            uiState.maxRounds,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    },
                    {
                        // How much text one read may hand back, and how large a file may be read
                        // whole. Token budgets for the file reading tools, not access grants.
                        SegmentedListItem(
                            onClick = actions.onOpenReadLimitSheet,
                            headlineContent = { Text(stringResource(R.string.ai_read_limit_title)) },
                            supportingContent = { Text(stringResource(R.string.ai_read_limit_summary)) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(
                                            R.string.ai_read_limit_option,
                                            uiState.readChunkLimit,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = actions.onOpenFullReadSheet,
                            headlineContent = { Text(stringResource(R.string.ai_full_read_title)) },
                            supportingContent = { Text(stringResource(R.string.ai_full_read_summary)) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(
                                            R.string.ai_full_read_option,
                                            uiState.fullReadThresholdKb,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    },
                ),
            )

            // Entry into the console: the user sets the assistant up on this page, so it links
            // straight to the chat instead of forcing a trip through Home.
            Button(
                onClick = actions.onOpenConsole,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) {
                Text(stringResource(R.string.ai_config_open_console))
            }

            // Card 2 - permissions and execution safety.
            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.ai_section_security),
                content = listOf(
                    {
                        Text(
                            text = stringResource(R.string.ai_template_title),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    {
                        AiTemplateRowMaterial(
                            current = uiState.policyTemplate,
                            onSelect = actions.onTemplateSelected,
                        )
                    },
                    {
                        Text(
                            text = stringResource(R.string.ai_template_footnote),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    },
                    {
                        SegmentedSwitchItem(
                            title = stringResource(R.string.ai_allow_shell_title),
                            summary = stringResource(R.string.ai_allow_shell_summary),
                            checked = uiState.allowShell,
                            onCheckedChange = actions.onAllowShellChange,
                        )
                    },
                    {
                        SegmentedSwitchItem(
                            title = stringResource(R.string.ai_confirm_dangerous_title),
                            summary = stringResource(R.string.ai_confirm_dangerous_summary),
                            // Hard safety floor: always on and not user-toggleable.
                            checked = true,
                            enabled = false,
                            onCheckedChange = {},
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = actions.onOpenScopeSheet,
                            headlineContent = { Text(stringResource(R.string.ai_access_scope_title)) },
                            supportingContent = { Text(stringResource(R.string.ai_access_scope_summary)) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(uiState.accessScope.labelRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    },
                ),
            )

            uiState.restoreScope?.let { previous ->
                // The scope change itself is recorded in the audit file; this is the settings-side
                // way back, so the assistant history stays clean.
                Text(
                    text = stringResource(
                        R.string.ai_access_scope_restore,
                        stringResource(previous.labelRes),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 20.dp, end = 20.dp)
                        .clickable { actions.onRestoreScope() },
                )
            }

            // Worst case cost of one conversation with the settings above: the largest whole
            // read counts once and every round adds its own overhead on top.
            val estimateTokens = AiTokenEstimate.worstCaseTokens(
                uiState.fullReadThresholdKb,
                uiState.maxRounds,
            )
            Text(
                text = stringResource(
                    R.string.ai_token_estimate,
                    AiTokenEstimate.wan(estimateTokens),
                    AiTokenEstimate.kilo(estimateTokens),
                    AiTokenEstimate.yuan(estimateTokens),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            )

            // Reserved slot for the phase-2 request error (401 / timeout) message.
            Text(
                text = uiState.errorMessage ?: stringResource(R.string.ai_error_slot_hint),
                style = MaterialTheme.typography.labelSmall,
                color = if (uiState.errorMessage != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            )
        }
    }

    AiProviderSheetMaterial(
        show = uiState.providerSheetVisible,
        selectedId = uiState.providerId,
        onSelected = actions.onProviderSelected,
        onDismissRequest = actions.onDismissProviderSheet,
    )

    AiModelListSheetMaterial(
        show = uiState.modelListSheetVisible,
        loading = uiState.modelListLoading,
        models = uiState.modelList,
        error = uiState.modelListError,
        currentModel = uiState.modelName,
        onRetry = actions.onFetchModels,
        onSelected = actions.onModelSelected,
        onDismissRequest = actions.onDismissModelList,
    )

    AiScopeSheetMaterial(
        show = uiState.scopeSheetVisible,
        selected = uiState.accessScope,
        onSelected = actions.onScopeSelected,
        onDismissRequest = actions.onDismissScopeSheet,
    )

    AiChoiceSheetMaterial(
        show = uiState.roundsSheetVisible,
        title = stringResource(R.string.ai_max_rounds_sheet_title),
        options = AiRounds.options,
        selected = uiState.maxRounds,
        optionLabel = { pluralStringResource(R.plurals.ai_max_rounds_option, it, it) },
        hint = stringResource(R.string.ai_max_rounds_hint),
        onSelected = actions.onRoundsSelected,
        onDismissRequest = actions.onDismissRoundsSheet,
        customLabel = stringResource(R.string.ai_setting_custom),
        customHint = stringResource(R.string.ai_max_rounds_custom_hint),
        onCustomSelected = actions.onRoundsSelected,
    )

    AiChoiceSheetMaterial(
        show = uiState.readLimitSheetVisible,
        title = stringResource(R.string.ai_read_limit_sheet_title),
        options = AiReadLimits.chunkOptions,
        selected = uiState.readChunkLimit,
        optionLabel = { stringResource(R.string.ai_read_limit_option, it) },
        hint = stringResource(R.string.ai_read_limit_hint),
        customLabel = stringResource(R.string.ai_setting_custom),
        customHint = stringResource(R.string.ai_read_limit_custom_hint),
        onCustomSelected = actions.onReadLimitSelected,
        onSelected = actions.onReadLimitSelected,
        onDismissRequest = actions.onDismissReadLimitSheet,
    )

    AiChoiceSheetMaterial(
        show = uiState.fullReadSheetVisible,
        title = stringResource(R.string.ai_full_read_sheet_title),
        options = AiReadLimits.fullReadOptions,
        selected = uiState.fullReadThresholdKb,
        optionLabel = { stringResource(R.string.ai_full_read_option, it) },
        hint = stringResource(R.string.ai_full_read_hint),
        customLabel = stringResource(R.string.ai_setting_custom),
        customHint = stringResource(R.string.ai_full_read_custom_hint),
        onCustomSelected = actions.onFullReadThresholdSelected,
        onSelected = actions.onFullReadThresholdSelected,
        onDismissRequest = actions.onDismissFullReadSheet,
    )

    AiScopeElevationDialogMaterial(
        target = uiState.pendingScopeElevation,
        onConfirm = actions.onConfirmScopeElevation,
        onDismissRequest = actions.onCancelScopeElevation,
    )
}
