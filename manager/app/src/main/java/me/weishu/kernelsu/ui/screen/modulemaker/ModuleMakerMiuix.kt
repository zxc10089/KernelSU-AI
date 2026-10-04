package me.weishu.kernelsu.ui.screen.modulemaker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.modulemaker.ModulePackage
import me.weishu.kernelsu.data.modulemaker.formatBytes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Phase 6 module maker, Miuix flavour. Mirrors ModuleMakerPagerMaterial. */
@Composable
fun ModuleMakerPagerMiuix(
    uiState: ModuleMakerUiState,
    actions: ModuleMakerActions,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()
    val layoutDirection = LocalLayoutDirection.current
    val backIconModifier = Modifier.graphicsLayer {
        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
    }
    val preview = remember(uiState.draft) { ModulePackage.entries(uiState.draft) }

    Scaffold(
        topBar = {
            TopAppBar(
                color = colorScheme.surface,
                title = stringResource(R.string.module_maker_title),
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            tint = colorScheme.onBackground,
                            modifier = backIconModifier,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
        ) {
            if (uiState.fromAi) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.module_maker_from_ai),
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(stringResource(R.string.module_maker_section_meta))
                    MakerField(stringResource(R.string.module_maker_id), uiState.draft.id, onValueChange = actions.onIdChange)
                    Text(
                        text = stringResource(R.string.module_maker_id_hint),
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MakerField(stringResource(R.string.module_maker_name), uiState.draft.name, onValueChange = actions.onNameChange)
                    MakerField(stringResource(R.string.module_maker_version), uiState.draft.version, onValueChange = actions.onVersionChange)
                    MakerField(stringResource(R.string.module_maker_version_code), uiState.draft.versionCode, onValueChange = actions.onVersionCodeChange)
                    MakerField(stringResource(R.string.module_maker_author), uiState.draft.author, onValueChange = actions.onAuthorChange)
                    MakerField(
                        label = stringResource(R.string.module_maker_description),
                        value = uiState.draft.description,
                        onValueChange = actions.onDescriptionChange,
                        singleLine = false,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(stringResource(R.string.module_maker_section_files))
                    uiState.draft.files.forEachIndexed { index, file ->
                        MakerField(stringResource(R.string.module_maker_file_path), file.path) { value ->
                            actions.onFilePathChange(index, value)
                        }
                        MakerField(
                            label = stringResource(R.string.module_maker_file_content),
                            value = file.content,
                            onValueChange = { value -> actions.onFileContentChange(index, value) },
                            singleLine = false,
                        )
                        TextButton(
                            text = stringResource(R.string.module_maker_remove_file),
                            onClick = { actions.onRemoveFile(index) },
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    TextButton(
                        text = stringResource(R.string.module_maker_add_file),
                        onClick = actions.onAddFile,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    MakerField(
                        label = stringResource(R.string.module_maker_install_script),
                        value = uiState.draft.installScript,
                        onValueChange = actions.onInstallScriptChange,
                        singleLine = false,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(stringResource(R.string.module_maker_section_preview))
                    preview.forEach { entry ->
                        Text(
                            text = stringResource(
                                R.string.module_maker_preview_entry,
                                entry.path,
                                formatBytes(entry.bytes.toLong()),
                            ),
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.module_maker_note),
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.building) {
                    InfiniteProgressIndicator(color = colorScheme.primary)
                }
                TextButton(
                    text = stringResource(R.string.module_maker_build),
                    onClick = actions.onBuild,
                    enabled = !uiState.building,
                )
                TextButton(
                    text = stringResource(R.string.module_maker_install),
                    onClick = actions.onInstall,
                    enabled = !uiState.building && uiState.zipPath.isNotBlank(),
                )
                TextButton(
                    text = stringResource(R.string.module_maker_ai_draft),
                    onClick = actions.onOpenConsole,
                )
            }

            if (uiState.zipPath.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.module_maker_built, uiState.zipPath, uiState.zipSize),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            uiState.errors.forEach { errorRes ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(errorRes),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.error,
                )
            }
            if (uiState.success == false) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.module_maker_install_fail),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.error,
                )
            }
            if (uiState.success == true) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.module_maker_install_ok),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.primary,
                )
            }
            if (uiState.showReboot) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.module_maker_reboot),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.primary,
                )
            }

            if (uiState.output.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionTitle(stringResource(R.string.module_maker_install_output))
                        Text(
                            text = uiState.output.joinToString(separator = NEWLINE),
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

private const val NEWLINE = "\n"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.title4.fontSize,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun MakerField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = singleLine,
    )
    Spacer(modifier = Modifier.height(10.dp))
}

