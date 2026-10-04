package me.weishu.kernelsu.ui.screen.aiassistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.aichat.AiAgentLogPanelMiuix
import me.weishu.kernelsu.ui.component.aichat.AiMessageMiuix
import me.weishu.kernelsu.ui.component.aichat.AiModeSwitchMiuix
import me.weishu.kernelsu.ui.icon.AiSendArrowIcon
import me.weishu.kernelsu.ui.icon.AiStopIcon
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import me.weishu.kernelsu.ui.icon.AiUploadFileIcon

/**
 * The AI console, Miuix flavour (design brief section 4).
 *
 * The console only proposes: every action card is read only here, the gates live in the view model
 * and, for STRICT operations, in the red dialog. This composable never runs a command by itself.
 */
@Composable
fun AiConsolePagerMiuix(
    uiState: AiConsoleUiState,
    actions: AiConsoleActions,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val layoutDirection = LocalLayoutDirection.current
    val backIconModifier = Modifier.graphicsLayer {
        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
    }
    val listState = rememberLazyListState()
    // A streaming answer grows without changing the message count, so follow the tail length as well;
    // otherwise the newest lines stay below the fold and the composer edge cuts them off.
    val tailLength = uiState.messages.lastOrNull()?.let {
        it.text.length + (it.reasoning?.length ?: 0) + it.actions.size
    } ?: 0
    LaunchedEffect(uiState.messages.size, tailLength) {
        if (uiState.messages.isEmpty() || listState.isScrollInProgress) return@LaunchedEffect
        // A large offset is clamped to the end of the content, pinning the newest line above the
        // composer without snapping the last message's top to the top of the list.
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

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.ai_console_title),
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
                    actions = {
                        IconButton(onClick = actions.onOpenAudit) {
                            Icon(
                                imageVector = MiuixIcons.Notes,
                                contentDescription = stringResource(R.string.ai_audit_title),
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp)
                    .windowInsetsPadding(bottomInsets),
            ) {
                // Status row: which model answers, and the way to reset the conversation.
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
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    val clearEnabled = uiState.messages.isNotEmpty()
                    val clearColor = if (clearEnabled) {
                        colorScheme.onBackground
                    } else {
                        colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
                    }
                    // A bare icon said nothing about what it does, so the clear action carries its
                    // own label while staying a single compact control.
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colorScheme.surfaceContainerHigh)
                            .clickable(
                                enabled = clearEnabled,
                                onClickLabel = stringResource(R.string.ai_console_clear),
                            ) { actions.onClear() }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = null,
                            tint = clearColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.ai_console_clear_short),
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = clearColor,
                            maxLines = 1,
                        )
                    }
                }
                // Which files the assistant may touch and whether shell commands are on: both are
                // easy to forget and both change what the model is allowed to propose.
                Text(
                    text = stringResource(
                        R.string.ai_console_scope_line,
                        stringResource(uiState.scope.labelRes),
                        stringResource(
                            if (uiState.allowShell) R.string.ai_console_shell_on
                            else R.string.ai_console_shell_off,
                        ),
                    ),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                AiAgentLogPanelMiuix(
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
                        .weight(1f)
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    overscrollEffect = null,
                ) {
                    if (uiState.messages.isEmpty()) {
                        item(key = "ai_console_empty") {
                            Text(
                                text = stringResource(R.string.ai_console_empty),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                    items(items = uiState.messages, key = { it.id }) { message ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            AiMessageMiuix(
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
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        color = colorScheme.error,
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
                                text = prompt,
                                onClick = { actions.onInputChange(prompt) },
                                modifier = Modifier
                                    .height(48.dp)
                                    .padding(end = 8.dp),
                            )
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
                            .background(colorScheme.surfaceContainerHigh)
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
                                            .background(colorScheme.secondaryContainer)
                                            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = attachment.name + " (" + formatSize(attachment.sizeBytes) + ")",
                                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            color = colorScheme.onSecondaryContainer,
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
                                                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                                color = colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // A bare text field inside the composer card: Miuix TextField would draw its own
                        // rounded container here, which turns the single reference container into a frame in a
                        // frame. BasicTextField keeps the text floating on the card, like the reference.
                        BasicTextField(
                            value = uiState.input,
                            onValueChange = actions.onInputChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp, max = 148.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            textStyle = MiuixTheme.textStyles.main.copy(color = colorScheme.onSurface),
                            cursorBrush = SolidColor(colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Default,
                            ),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (uiState.input.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.ai_console_input_hint),
                                            fontSize = MiuixTheme.textStyles.main.fontSize,
                                            color = colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
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
                                AiAttachButtonMiuix(
                                    enabled = !uiState.sending,
                                    onClick = pickAttachments,
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(20.dp)
                                        .background(colorScheme.dividerLine),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                AiModeSwitchMiuix(
                                    planMode = uiState.planMode,
                                    onToggle = actions.onTogglePlanMode,
                                )
                            }
                            AiSendButtonMiuix(
                                sending = uiState.sending,
                                enabled = uiState.input.isNotBlank() || uiState.attachments.isNotEmpty(),
                                onSend = actions.onSend,
                                onStop = actions.onStop,
                            )
                        }
                    }
                } else {
                    TextButton(
                        text = stringResource(R.string.ai_console_open_config),
                        onClick = actions.onOpenConfig,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )
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
private fun AiAttachButtonMiuix(
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
                colorScheme.onSurfaceVariantSummary
            } else {
                colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
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
private fun AiSendButtonMiuix(
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
                .background(if (active) colorScheme.primary else colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (sending) AiStopIcon else AiSendArrowIcon,
                contentDescription = stringResource(if (sending) R.string.ai_console_stop else R.string.ai_console_send),
                tint = if (active) colorScheme.onPrimary else colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
