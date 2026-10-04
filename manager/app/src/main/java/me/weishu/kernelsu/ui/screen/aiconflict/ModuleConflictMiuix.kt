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
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.ModuleConflictLevel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Phase 4 module conflict scan, Miuix flavour. Mirrors ModuleConflictPagerMaterial. */
@Composable
fun ModuleConflictPagerMiuix(
    uiState: ModuleConflictUiState,
    actions: ModuleConflictActions,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()
    val layoutDirection = LocalLayoutDirection.current
    val backIconModifier = Modifier.graphicsLayer {
        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                color = colorScheme.surface,
                title = stringResource(R.string.ai_conflict_title),
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.ai_conflict_scanned, uiState.scanned),
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.ai_conflict_rescan),
                            onClick = actions.onRescan,
                        )
                        TextButton(
                            text = stringResource(R.string.ai_conflict_ai_explain),
                            onClick = actions.onOpenConsole,
                            enabled = uiState.aiConfigured,
                        )
                        TextButton(
                            text = stringResource(R.string.ai_conflict_open_config),
                            onClick = actions.onOpenConfig,
                        )
                    }
                }
            }
            when {
                uiState.scopeMissing -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_conflict_scope_required),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
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
                        InfiniteProgressIndicator(color = colorScheme.primary)
                    }
                }

                uiState.error -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_conflict_error),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                uiState.conflicts.isEmpty() -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_conflict_empty),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                else -> {
                    uiState.conflicts.forEach { conflict ->
                        Spacer(modifier = Modifier.height(12.dp))
                        ModuleConflictRowMiuix(item = conflict)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleConflictRowMiuix(item: ModuleConflictItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModuleConflictLevelChipMiuix(level = item.level)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(item.typeRes, item.subject),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ai_conflict_modules, item.modulesText),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ModuleConflictLevelChipMiuix(level: ModuleConflictLevel) {
    val container = when (level) {
        ModuleConflictLevel.HIGH -> colorScheme.error
        else -> colorScheme.secondaryContainer
    }
    val content = when (level) {
        ModuleConflictLevel.HIGH -> colorScheme.onError
        else -> colorScheme.onSurface
    }
    Text(
        text = moduleConflictLevelLabel(level),
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun moduleConflictLevelLabel(level: ModuleConflictLevel): String = stringResource(
    when (level) {
        ModuleConflictLevel.HIGH -> R.string.ai_conflict_level_high
        ModuleConflictLevel.MEDIUM -> R.string.ai_conflict_level_medium
    },
)
