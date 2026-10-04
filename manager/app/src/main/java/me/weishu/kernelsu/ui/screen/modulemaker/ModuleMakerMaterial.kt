package me.weishu.kernelsu.ui.screen.modulemaker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.modulemaker.ModulePackage
import me.weishu.kernelsu.data.modulemaker.formatBytes
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors

/** Phase 6 module maker, Material flavour. Mirrors ModuleMakerPagerMiuix. */
@Composable
fun ModuleMakerPagerMaterial(
    uiState: ModuleMakerUiState,
    actions: ModuleMakerActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    val preview = remember(uiState.draft) { ModulePackage.entries(uiState.draft) }

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                title = { Text(stringResource(R.string.module_maker_title)) },
                colors = expressiveTopAppBarColors(),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(bottomInsets)
                .verticalScroll(rememberScrollState())
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            if (uiState.fromAi) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.module_maker_from_ai),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        TextButton(onClick = { actions.onRemoveFile(index) }) {
                            Text(stringResource(R.string.module_maker_remove_file))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    TextButton(onClick = actions.onAddFile) {
                        Text(stringResource(R.string.module_maker_add_file))
                    }
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.module_maker_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                }
                TextButton(onClick = actions.onBuild, enabled = !uiState.building) {
                    Text(stringResource(R.string.module_maker_build))
                }
                TextButton(
                    onClick = actions.onInstall,
                    enabled = !uiState.building && uiState.zipPath.isNotBlank(),
                ) {
                    Text(stringResource(R.string.module_maker_install))
                }
                TextButton(onClick = actions.onOpenConsole) {
                    Text(stringResource(R.string.module_maker_ai_draft))
                }
            }

            if (uiState.zipPath.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.module_maker_built, uiState.zipPath, uiState.zipSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            uiState.errors.forEach { errorRes ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(errorRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (uiState.success == false) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.module_maker_install_fail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (uiState.success == true) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.module_maker_install_ok),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (uiState.showReboot) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.module_maker_reboot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (uiState.output.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionTitle(stringResource(R.string.module_maker_install_output))
                        Text(
                            text = uiState.output.joinToString(separator = NEWLINE),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
    )
    Spacer(modifier = Modifier.height(10.dp))
}
