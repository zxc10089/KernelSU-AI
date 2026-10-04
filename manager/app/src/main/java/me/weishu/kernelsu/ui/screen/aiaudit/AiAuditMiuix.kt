package me.weishu.kernelsu.ui.screen.aiaudit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import me.weishu.kernelsu.data.agent.AiAuditEntry
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

/** Phase 5 AI action history, Miuix flavour. Mirrors AiAuditPagerMaterial. */
@Composable
fun AiAuditPagerMiuix(
    uiState: AiAuditUiState,
    actions: AiAuditActions,
    resultText: String?,
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
                title = stringResource(R.string.ai_audit_title),
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
                        text = stringResource(R.string.ai_audit_title),
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        text = stringResource(R.string.ai_audit_refresh),
                        onClick = actions.onRefresh,
                    )
                }
            }
            if (resultText != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = resultText,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.primary,
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
                        InfiniteProgressIndicator(color = colorScheme.primary)
                    }
                }

                uiState.error -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_audit_error),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                uiState.items.isEmpty() -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_audit_empty),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                else -> {
                    uiState.items.forEach { item ->
                        Spacer(modifier = Modifier.height(12.dp))
                        AiAuditRowMiuix(
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

/** 访问范围变更条目显示本地化后的范围名，其余条目沿用审计原文。 */
@Composable
private fun aiAuditSubtitle(item: AiAuditItem): String =
    item.scopeChange?.let { stringResource(it.toRes) }
        ?: item.targetRes?.let { stringResource(it) }
        ?: item.entry.target

@Composable
private fun aiAuditDetail(item: AiAuditItem): String {
    val change = item.scopeChange ?: return item.detail
    val to = stringResource(change.toRes)
    val from = change.fromRes?.let { stringResource(it) } ?: return to
    return from + " → " + to
}

@Composable
private fun AiAuditRowMiuix(
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
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(item.kindRes),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AiAuditResultChipMiuix(item = item)
            }
            val subtitle = aiAuditSubtitle(item)
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ai_audit_detail_title),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = aiAuditDetail(item),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            if (item.undoable) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    text = stringResource(R.string.ai_audit_undo),
                    onClick = { actions.onUndo(item) },
                )
            }
        }
    }
}

@Composable
private fun AiAuditResultChipMiuix(item: AiAuditItem) {
    val notOk = item.entry.result != AiAuditEntry.RESULT_OK
    Text(
        text = stringResource(item.resultRes),
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        color = if (notOk) colorScheme.onError else colorScheme.onSurface,
        modifier = Modifier
            .background(
                if (notOk) colorScheme.error else colorScheme.secondaryContainer,
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
