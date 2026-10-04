package me.weishu.kernelsu.ui.screen.aiconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.miuix.AiModelListSheetMiuix
import me.weishu.kernelsu.ui.component.miuix.AiProviderSheetMiuix
import me.weishu.kernelsu.ui.component.miuix.AiChoiceSheetMiuix
import me.weishu.kernelsu.ui.component.miuix.AiScopeElevationDialogMiuix
import me.weishu.kernelsu.ui.component.miuix.AiScopeSheetMiuix
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Content padding of a card whose children are laid out by hand. 16dp is BasicComponent's default
 * insideMargin, so hand-built cards line up with the preference-driven ones.
 */
private val CardContentPadding = 16.dp

/** Space above a field label, echoing the gap BasicComponent leaves between two stacked rows. */
private val FieldLabelTopPadding = 12.dp

/** Space below a field label. */
private val FieldLabelBottomPadding = 4.dp

/**
 * The three quick templates. Each card writes the two switches below it, so this row is a
 * shortcut and never a second source of truth: [current] is derived from those switches.
 */
@Composable
private fun AiTemplateRowMiuix(
    current: AiPolicyTemplate?,
    onSelect: (AiPolicyTemplate) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AiPolicyTemplate.values().forEach { template ->
            val selected = template == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) colorScheme.secondaryContainer else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (selected) Color.Transparent else colorScheme.onSurfaceVariantSummary,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(template) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(template.labelRes),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(template.summaryRes),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
fun AiConfigPagerMiuix(
    uiState: AiConfigUiState,
    actions: AiConfigActions,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }

    val layoutDirection = LocalLayoutDirection.current
    val backIconModifier = Modifier.graphicsLayer {
        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.ai_config_title),
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(
                                modifier = backIconModifier,
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                // Card 1 - API settings.
                item(key = "ai_api_card") {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(CardContentPadding)) {
                            ArrowPreference(
                                title = stringResource(R.string.ai_provider),
                                endActions = {
                                    Text(
                                        text = stringResource(uiState.provider.labelRes),
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                                onClick = actions.onOpenProviderSheet,
                            )

                            FieldLabel(stringResource(R.string.ai_endpoint))
                            TextField(
                                value = uiState.endpoint,
                                onValueChange = actions.onEndpointChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.ai_endpoint_hint),
                                useLabelAsPlaceholder = true,
                                colors = TextFieldDefaults.textFieldColors(
                                    borderColor = if (uiState.endpointInvalid) colorScheme.error else colorScheme.primary,
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next,
                                ),
                                singleLine = true,
                            )
                            if (uiState.endpointInvalid) {
                                ErrorText(stringResource(R.string.ai_error_endpoint_scheme))
                            }

                            FieldLabel(stringResource(R.string.ai_api_key))
                            TextField(
                                value = uiState.apiKey,
                                onValueChange = actions.onApiKeyChange,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Next,
                                ),
                                singleLine = true,
                                visualTransformation = if (apiKeyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { apiKeyVisible = !apiKeyVisible },
                                    ) {
                                        Icon(
                                            imageVector = if (apiKeyVisible) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = stringResource(
                                                if (apiKeyVisible) R.string.ai_api_key_hide else R.string.ai_api_key_show
                                            ),
                                            tint = colorScheme.onSurfaceVariantActions,
                                        )
                                    }
                                },
                            )

                            FieldLabel(stringResource(R.string.ai_model_name))
                            TextField(
                                value = uiState.modelName,
                                onValueChange = actions.onModelNameChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = uiState.modelPlaceholder
                                    ?: stringResource(R.string.ai_model_name_hint),
                                useLabelAsPlaceholder = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Done,
                                ),
                                singleLine = true,
                            )
                            // How many model turns one message may chain. This is a request
                            // parameter, so it lives beside the model name instead of with the
                            // safety switches, where it would read like an access grant.
                            ArrowPreference(
                                title = stringResource(R.string.ai_max_rounds_title),
                                summary = stringResource(R.string.ai_max_rounds_summary),
                                endActions = {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.ai_max_rounds_option,
                                            uiState.maxRounds,
                                            uiState.maxRounds,
                                        ),
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                                onClick = actions.onOpenRoundsSheet,
                            )
                            // How much text one read may hand back, and how large a file may be
                            // read whole. Both are token budgets for the file reading tools, so
                            // they sit beside the model name with the other request parameters.
                            ArrowPreference(
                                title = stringResource(R.string.ai_read_limit_title),
                                summary = stringResource(R.string.ai_read_limit_summary),
                                endActions = {
                                    Text(
                                        text = stringResource(
                                            R.string.ai_read_limit_option,
                                            uiState.readChunkLimit,
                                        ),
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                                onClick = actions.onOpenReadLimitSheet,
                            )
                            ArrowPreference(
                                title = stringResource(R.string.ai_full_read_title),
                                summary = stringResource(R.string.ai_full_read_summary),
                                endActions = {
                                    Text(
                                        text = stringResource(
                                            R.string.ai_full_read_option,
                                            uiState.fullReadThresholdKb,
                                        ),
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                                onClick = actions.onOpenFullReadSheet,
                            )
                            // Lists the models the configured endpoint offers, so the name does
                            // not have to be typed from memory.
                            TextButton(
                                text = stringResource(R.string.ai_model_list_fetch),
                                onClick = actions.onFetchModels,
                                enabled = uiState.canFetchModels,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                            // Entry into the console: the user sets the assistant up on this page, so
                            // it links straight to the chat instead of forcing a trip through Home.
                            TextButton(
                                text = stringResource(R.string.ai_config_open_console),
                                onClick = actions.onOpenConsole,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        }
                    }
                }

                // Card 2 - permissions and execution safety.
                item(key = "ai_security_card") {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(CardContentPadding)) {
                            // Quick templates sit above the switches they write, so the
                            // shortcut and the fine-grained controls stay one screen.
                            Text(
                                text = stringResource(R.string.ai_template_title),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            AiTemplateRowMiuix(
                                current = uiState.policyTemplate,
                                onSelect = actions.onTemplateSelected,
                            )
                            Text(
                                text = stringResource(R.string.ai_template_footnote),
                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                            )
                            SwitchPreference(
                                title = stringResource(R.string.ai_allow_shell_title),
                                summary = stringResource(R.string.ai_allow_shell_summary),
                                checked = uiState.allowShell,
                                onCheckedChange = actions.onAllowShellChange,
                            )
                            SwitchPreference(
                                title = stringResource(R.string.ai_confirm_dangerous_title),
                                summary = stringResource(R.string.ai_confirm_dangerous_summary),
                                // Hard safety floor: always on and not user-toggleable.
                                checked = true,
                                onCheckedChange = {},
                                enabled = false,
                            )
                            ArrowPreference(
                                title = stringResource(R.string.ai_access_scope_title),
                                summary = stringResource(R.string.ai_access_scope_summary),
                                endActions = {
                                    Text(
                                        text = stringResource(uiState.accessScope.labelRes),
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                                onClick = actions.onOpenScopeSheet,
                            )
                        }
                    }
                }

                // Worst case cost of one conversation with the settings above: the largest whole
                // read counts once and every round adds its own overhead on top.
                item(key = "ai_token_estimate") {
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
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }

                // Reserved slot for the phase-2 request error (401 / timeout) message.
                item(key = "ai_error_slot") {
                    Text(
                        text = uiState.errorMessage ?: stringResource(R.string.ai_error_slot_hint),
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = if (uiState.errorMessage != null) {
                            colorScheme.error
                        } else {
                            colorScheme.onSurfaceVariantSummary
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    AiProviderSheetMiuix(
        show = uiState.providerSheetVisible,
        selectedId = uiState.providerId,
        onSelected = actions.onProviderSelected,
        onDismissRequest = actions.onDismissProviderSheet,
    )

    AiModelListSheetMiuix(
        show = uiState.modelListSheetVisible,
        loading = uiState.modelListLoading,
        models = uiState.modelList,
        error = uiState.modelListError,
        currentModel = uiState.modelName,
        onRetry = actions.onFetchModels,
        onSelected = actions.onModelSelected,
        onDismissRequest = actions.onDismissModelList,
    )

    AiScopeSheetMiuix(
        show = uiState.scopeSheetVisible,
        selected = uiState.accessScope,
        onSelected = actions.onScopeSelected,
        onDismissRequest = actions.onDismissScopeSheet,
    )

    AiChoiceSheetMiuix(
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

    AiChoiceSheetMiuix(
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

    AiChoiceSheetMiuix(
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

    AiScopeElevationDialogMiuix(
        target = uiState.pendingScopeElevation,
        onConfirm = actions.onConfirmScopeElevation,
        onDismissRequest = actions.onCancelScopeElevation,
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(
            top = FieldLabelTopPadding,
            bottom = FieldLabelBottomPadding,
        ),
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        color = colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}
