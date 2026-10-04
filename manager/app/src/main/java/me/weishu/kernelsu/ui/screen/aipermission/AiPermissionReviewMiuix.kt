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

/** Phase 3 permission review, Miuix flavour. Mirrors AiPermissionReviewPagerMaterial. */
@Composable
fun AiPermissionReviewPagerMiuix(
    uiState: AiPermissionUiState,
    actions: AiPermissionActions,
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
                title = stringResource(R.string.ai_permission_title),
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
                        text = stringResource(R.string.ai_permission_count, uiState.apps.size),
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                    )
                    if (uiState.scopeLabelRes != 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.ai_permission_scope_label,
                                stringResource(uiState.scopeLabelRes),
                            ),
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.ai_permission_refresh),
                            onClick = actions.onRefresh,
                        )
                        TextButton(
                            text = stringResource(R.string.ai_permission_deep_review),
                            onClick = actions.onOpenConsole,
                            enabled = uiState.aiConfigured,
                        )
                        TextButton(
                            text = stringResource(R.string.ai_permission_open_config),
                            onClick = actions.onOpenConfig,
                        )
                    }
                    if (!uiState.aiConfigured) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.ai_permission_not_configured),
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
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
                        text = stringResource(R.string.ai_permission_error),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                uiState.apps.isEmpty() -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_permission_empty),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                else -> {
                    uiState.apps.forEach { app ->
                        Spacer(modifier = Modifier.height(12.dp))
                        AiPermissionRowMiuix(app = app, actions = actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiPermissionRowMiuix(
    app: AiPermissionApp,
    actions: AiPermissionActions,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiPermissionLevelChipMiuix(level = app.level)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = app.label,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = app.packageName,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = aiPermissionReason(app),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.ai_permission_revoke),
                onClick = { actions.onRevoke(app) },
            )
        }
    }
}

@Composable
private fun AiPermissionLevelChipMiuix(level: AiPermissionLevel) {
    val container = when (level) {
        AiPermissionLevel.HIGH -> colorScheme.error
        else -> colorScheme.secondaryContainer
    }
    val content = when (level) {
        AiPermissionLevel.HIGH -> colorScheme.onError
        else -> colorScheme.onSurface
    }
    Text(
        text = aiPermissionLevelLabel(level),
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun aiPermissionLevelLabel(level: AiPermissionLevel): String = stringResource(
    when (level) {
        AiPermissionLevel.HIGH -> R.string.ai_permission_level_high
        AiPermissionLevel.MEDIUM -> R.string.ai_permission_level_medium
        AiPermissionLevel.LOW -> R.string.ai_permission_level_low
    },
)

@Composable
internal fun aiPermissionReason(app: AiPermissionApp): String {
    val argument = app.reasonArg
    return if (argument == null) {
        stringResource(app.reasonRes)
    } else {
        stringResource(app.reasonRes, argument)
    }
}
