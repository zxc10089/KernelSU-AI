package me.weishu.kernelsu.ui.screen.aiaudit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors

/** Phase 5 AI action history, Material flavour. Mirrors AiAuditPagerMiuix. */
@Composable
fun AiAuditPagerMaterial(
    uiState: AiAuditUiState,
    actions: AiAuditActions,
    resultText: String?,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                title = { Text(stringResource(R.string.ai_audit_title)) },
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
                        text = stringResource(R.string.ai_audit_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = actions.onRefresh) {
                        Text(stringResource(R.string.ai_audit_refresh))
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
                        text = stringResource(R.string.ai_audit_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                uiState.items.isEmpty() -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_audit_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                else -> {
                    uiState.items.forEach { item ->
                        Spacer(modifier = Modifier.height(12.dp))
                        AiAuditRowMaterial(
                            item = item,
                            expanded = uiState.expanded == item.entry.ts,
                            actions = actions,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiAuditRowMaterial(
    item: AiAuditItem,
    expanded: Boolean,
    actions: AiAuditActions,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.onToggleDetail(item) }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(item.kindRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AiAuditResultChipMaterial(item = item)
            }
            val subtitle = aiAuditSubtitleMaterial(item)
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ai_audit_detail_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = aiAuditDetailMaterial(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.undoable) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { actions.onUndo(item) }) {
                    Text(stringResource(R.string.ai_audit_undo))
                }
            }
        }
    }
}

/** 访问范围变更条目显示本地化后的范围名，其余条目沿用审计原文。 */
@Composable
private fun aiAuditSubtitleMaterial(item: AiAuditItem): String =
    item.scopeChange?.let { stringResource(it.toRes) }
        ?: item.targetRes?.let { stringResource(it) }
        ?: item.entry.target

@Composable
private fun aiAuditDetailMaterial(item: AiAuditItem): String {
    val change = item.scopeChange ?: return item.detail
    val to = stringResource(change.toRes)
    val from = change.fromRes?.let { stringResource(it) } ?: return to
    return from + " → " + to
}

@Composable
private fun AiAuditResultChipMaterial(item: AiAuditItem) {
    val notOk = item.entry.result != AiAuditEntry.RESULT_OK
    Text(
        text = stringResource(item.resultRes),
        style = MaterialTheme.typography.labelMedium,
        color = if (notOk) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(
                if (notOk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
