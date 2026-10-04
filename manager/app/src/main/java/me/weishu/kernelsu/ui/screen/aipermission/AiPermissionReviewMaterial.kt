package me.weishu.kernelsu.ui.screen.aipermission

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
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors

/** Phase 3 permission review, Material flavour. Mirrors AiPermissionReviewPagerMiuix. */
@Composable
fun AiPermissionReviewPagerMaterial(
    uiState: AiPermissionUiState,
    actions: AiPermissionActions,
    resultText: String?,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                title = { Text(stringResource(R.string.ai_permission_title)) },
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
                        text = stringResource(R.string.ai_permission_count, uiState.apps.size),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (uiState.scopeLabelRes != 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.ai_permission_scope_label,
                                stringResource(uiState.scopeLabelRes),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = actions.onRefresh) {
                            Text(stringResource(R.string.ai_permission_refresh))
                        }
                        TextButton(onClick = actions.onOpenConsole, enabled = uiState.aiConfigured) {
                            Text(stringResource(R.string.ai_permission_deep_review))
                        }
                        TextButton(onClick = actions.onOpenConfig) {
                            Text(stringResource(R.string.ai_permission_open_config))
                        }
                    }
                    if (!uiState.aiConfigured) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.ai_permission_not_configured),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (resultText != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            when {
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
                        text = stringResource(R.string.ai_permission_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                uiState.apps.isEmpty() -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_permission_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                else -> {
                    uiState.apps.forEach { app ->
                        Spacer(modifier = Modifier.height(12.dp))
                        AiPermissionRowMaterial(app = app, actions = actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiPermissionRowMaterial(
    app: AiPermissionApp,
    actions: AiPermissionActions,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiPermissionLevelChipMaterial(level = app.level)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = aiPermissionReason(app),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { actions.onRevoke(app) }) {
                Text(stringResource(R.string.ai_permission_revoke))
            }
        }
    }
}

@Composable
private fun AiPermissionLevelChipMaterial(level: AiPermissionLevel) {
    val container = when (level) {
        AiPermissionLevel.HIGH -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when (level) {
        AiPermissionLevel.HIGH -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Text(
        text = aiPermissionLevelLabel(level),
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
