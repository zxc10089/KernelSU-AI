package me.weishu.kernelsu.ui.screen.aiconflict

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.ModuleConflictLevel
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors

/** Phase 4 module conflict scan, Material flavour. Mirrors ModuleConflictPagerMiuix. */
@Composable
fun ModuleConflictPagerMaterial(
    uiState: ModuleConflictUiState,
    actions: ModuleConflictActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                title = { Text(stringResource(R.string.ai_conflict_title)) },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.ai_conflict_scanned, uiState.scanned),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = actions.onRescan) {
                            Text(stringResource(R.string.ai_conflict_rescan))
                        }
                        TextButton(onClick = actions.onOpenConsole, enabled = uiState.aiConfigured) {
                            Text(stringResource(R.string.ai_conflict_ai_explain))
                        }
                        TextButton(onClick = actions.onOpenConfig) {
                            Text(stringResource(R.string.ai_conflict_open_config))
                        }
                    }
                }
            }
            when {
                uiState.scopeMissing -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_conflict_scope_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                uiState.loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.error -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_conflict_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                uiState.conflicts.isEmpty() -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_conflict_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                else -> {
                    uiState.conflicts.forEach { conflict ->
                        Spacer(modifier = Modifier.height(12.dp))
                        ModuleConflictRowMaterial(item = conflict)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleConflictRowMaterial(item: ModuleConflictItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModuleConflictLevelChipMaterial(level = item.level)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(item.typeRes, item.subject),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ai_conflict_modules, item.modulesText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModuleConflictLevelChipMaterial(level: ModuleConflictLevel) {
    val container = when (level) {
        ModuleConflictLevel.HIGH -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when (level) {
        ModuleConflictLevel.HIGH -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Text(
        text = moduleConflictLevelLabel(level),
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
