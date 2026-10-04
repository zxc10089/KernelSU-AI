package me.weishu.kernelsu.ui.screen.aiassistant

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.aichat.AiAgentLogPanelMaterial
import me.weishu.kernelsu.ui.component.aichat.AiMessageMaterial
import me.weishu.kernelsu.ui.component.aichat.AiModeSwitchMaterial
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import me.weishu.kernelsu.ui.icon.AiSendArrowIcon
import me.weishu.kernelsu.ui.icon.AiStopIcon
import me.weishu.kernelsu.ui.icon.AiUploadFileIcon
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color

/** The AI console, Material flavour. Mirrors AiConsolePagerMiuix. */
@Composable
fun AiConsolePagerMaterial(
    uiState: AiConsoleUiState,
    actions: AiConsoleActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    // A streaming answer grows without changing the message count, so follow the tail length as well;
    // otherwise the newest lines stay below the fold and the composer edge cuts them off.
    val tailLength = uiState.messages.lastOrNull()?.let {
        it.text.length + (it.reasoning?.length ?: 0) + it.actions.size
    } ?: 0
    LaunchedEffect(uiState.messages.size, tailLength) {
        if (uiState.messages.isEmpty() || listState.isScrollInProgress) return@LaunchedEffect
        listState.scrollToItem(uiState.messages.lastIndex, Int.MAX_VALUE / 2)
    }
    val quickPrompts = listOf(
        stringResource(R.string.ai_console_quick_1),
        stringResource(R.string.ai_console_quick_2),
        stringResource(R.string.ai_console_quick_3),
    )
    // The input row must clear the navigation bar and the software keyboard. safeDrawing already
    // unions system bars, display cutout and IME, so its bottom side is the space to reserve.
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                title = { Text(stringResource(R.string.ai_console_title)) },
                actions = {
                    IconButton(onClick = actions.onOpenAudit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Article,
                            contentDescription = stringResource(R.string.ai_audit_title),
                        )
                    }
                },
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
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (uiState.configured) {
                        uiState.model
                    } else {
                        stringResource(R.string.ai_console_unconfigured)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val clearEnabled = uiState.messages.isNotEmpty()
                val clearColor = if (clearEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(
                            enabled = clearEnabled,
                            onClickLabel = stringResource(R.string.ai_console_clear),
                        ) { actions.onClear() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = clearColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.ai_console_clear_short),
                        style = MaterialTheme.typography.labelMedium,
                        color = clearColor,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.ai_console_scope_line,
                    stringResource(uiState.scope.labelRes),
                    stringResource(
                        if (uiState.allowShell) R.string.ai_console_shell_on
                        else R.string.ai_console_shell_off,
                    ),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            AiAgentLogPanelMaterial(
                lines = uiState.agentLog,
                expanded = uiState.logExpanded,
                onToggle = actions.onToggleLog,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (uiState.messages.isEmpty()) {
                    item(key = "ai_console_empty") {
                        Text(
                            text = stringResource(R.string.ai_console_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(items = uiState.messages, key = { it.id }) { message ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        AiMessageMaterial(
                            message = message,
                            plan = uiState.plan,
                            onApprovePlan = actions.onApprovePlan,
                            onRevisePlan = actions.onRevisePlan,
                            onAbortPlan = actions.onAbortPlan,
                            onOpenModuleMaker = actions.onOpenModuleMaker,
                        )
                    }
                }
            }
            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (uiState.messages.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    quickPrompts.forEach { prompt ->
                        TextButton(
                            onClick = { actions.onInputChange(prompt) },
                            modifier = Modifier
                                .height(48.dp)
                                .padding(end = 8.dp),
                        ) {
                            Text(prompt)
                        }
                    }
                    // Trailing space so the last chip can scroll fully clear of the edge.
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
            if (uiState.configured) {
                val pickAttachments = rememberAttachmentPicker(
                    onPicked = actions.onAttachmentsAdded,
                    onError = actions.onAttachmentError,
                )
                // One rounded container holds the whole composer - attachment pills, the text and
                // the action row - instead of a text field flanked by buttons on a bare row.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                ) {
                    if (uiState.attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            uiState.attachments.forEach { attachment ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = attachment.name + " (" + formatSize(attachment.sizeBytes) + ")",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clickable(
                                                onClickLabel = stringResource(R.string.ai_console_remove_attachment),
                                            ) { actions.onRemoveAttachment(attachment.id) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "✕",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = uiState.input,
                        onValueChange = actions.onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        placeholder = { Text(stringResource(R.string.ai_console_input_hint)) },
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        ),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AiAttachButtonMaterial(
                                enabled = !uiState.sending,
                                onClick = pickAttachments,
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(20.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            AiModeSwitchMaterial(
                                planMode = uiState.planMode,
                                onToggle = actions.onTogglePlanMode,
                            )
                        }
                        AiSendButtonMaterial(
                            sending = uiState.sending,
                            enabled = uiState.input.isNotBlank() || uiState.attachments.isNotEmpty(),
                            onSend = actions.onSend,
                            onStop = actions.onStop,
                        )
                    }
                }
            } else {
                TextButton(
                    onClick = actions.onOpenConfig,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Text(stringResource(R.string.ai_console_open_config))
                }
            }
        }
    }
}

/**
 * Secondary action at the left of the composer action row: a bare glyph button, so the only filled
 * circle in the row is the send button. The 48dp box keeps the touch target while the glyph stays at
 * 20dp, matching the reference composer where the left group is quiet and the send circle is loud.
 */
@Composable
private fun AiAttachButtonMaterial(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AiUploadFileIcon,
            contentDescription = stringResource(R.string.ai_console_attach),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Primary action on the right of the action row: a filled circle carrying the hand-drawn arrow, which
 * turns into a stop square while a reply streams. Both states keep the same 40dp circle inside the
 * same 48dp box, so the button neither moves nor resizes when the action changes.
 */
@Composable
private fun AiSendButtonMaterial(
    sending: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val active = sending || enabled
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = active, onClick = { if (sending) onStop() else onSend() }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (sending) AiStopIcon else AiSendArrowIcon,
                contentDescription = stringResource(if (sending) R.string.ai_console_stop else R.string.ai_console_send),
                tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
