package me.weishu.kernelsu.ui.screen.hiding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.HidingPackCategory
import me.weishu.kernelsu.data.model.HidingPackEntry
import me.weishu.kernelsu.data.model.HidingRuntimeMode
import me.weishu.kernelsu.data.repository.isSoftRebootPreferred
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedCheckboxItem
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedDropdownItem
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedSwitchItem
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.component.rebootlistpopup.rememberRebootAction
import me.weishu.kernelsu.ui.component.statustag.StatusTag

@Composable
fun HideEnvironmentPagerMaterial(
    uiState: HideEnvironmentUiState,
    actions: HideEnvironmentActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            HideEnvironmentTopBar(scrollBehavior = scrollBehavior, onRefresh = actions.onRefresh)
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            // The status card is read-only and true on every device, so it stays visible even when
            // the kernel cannot honour the switches; only the action cards are replaced.
            RuntimeModeBannerMaterial(uiState)
            EnvironmentStatusColumnMaterial(uiState, actions)
            if (!uiState.isFullFeatured) {
                UnsupportedColumnMaterial()
            } else {
                HidingSwitchesColumnMaterial(uiState, actions)
                OneKeyHideColumnMaterial(uiState, actions)
                QuickInstallColumnMaterial(uiState, actions)
                HidingPackColumnMaterial(uiState, actions)
                RiskNoticeColumnMaterial()
            }
            Spacer(modifier = Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
private fun HideEnvironmentTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onRefresh: () -> Unit,
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(id = R.string.hide_environment)) },
        // The pack card no longer carries its own refresh row, so this is the refresh entry that is
        // reachable at every scroll position, collapsed app bar included.
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(id = R.string.hide_environment_refresh)
                )
            }
        },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun UnsupportedColumnMaterial() {
    // The app bar above already says 环境隐藏, so repeating it as the row title was pure
    // duplication. The refresh row moved into the status card, which renders in this state too.
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        content = listOf(
            {
                SegmentedListItem(
                    headlineContent = { Text(stringResource(id = R.string.hide_environment_unsupported)) },
                    leadingContent = { Icon(Icons.Filled.Warning, null) }
                )
            }
        )
    )
}

@Composable
private fun EnvironmentStatusColumnMaterial(
    uiState: HideEnvironmentUiState,
    actions: HideEnvironmentActions,
) {
    val kernelSummary = listOf(uiState.androidRelease, uiState.kernelRelease)
        .filter { it.isNotBlank() }
        .joinToString(" | ")

    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(id = R.string.hide_environment_status_title),
        content = buildList {
            // The mode is stated once in the banner above and the licence at the end of the page, so
            // neither is repeated here. The kernel release row carries the android/kernel summary
            // that used to occupy a row of its own. Status rows only ever use the
            // Info/CheckCircle/Warning/RestartAlt set; Security stays on the SELinux *switch*.
            add {
                StatusRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(id = R.string.hide_environment_kernel_release),
                    summary = kernelSummary
                )
            }
            add {
                StatusRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(id = R.string.hide_environment_kernel_uapi),
                    summary = uiState.kernelUapiVersion.toString()
                )
            }
            add {
                StatusRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(id = R.string.hide_environment_manager_uapi),
                    summary = uiState.managerUapiVersion.toString()
                )
            }
            add {
                StatusRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(id = R.string.hide_environment_selinux_status),
                    summary = uiState.selinuxStatus
                )
            }
            // Boolean capabilities are only rendered when they hold; the repo has no yes/no string key.
            if (uiState.isFullFeatured) {
                add {
                    StatusRow(
                        icon = Icons.Filled.CheckCircle,
                        title = stringResource(id = R.string.hide_environment_full_featured)
                    )
                }
            }
            if (uiState.isSafeMode) {
                add {
                    StatusRow(
                        icon = Icons.Filled.Warning,
                        title = stringResource(id = R.string.hide_environment_safe_mode)
                    )
                }
            }
            if (uiState.isLateLoadMode) {
                add {
                    StatusRow(
                        icon = Icons.Filled.RestartAlt,
                        title = stringResource(id = R.string.hide_environment_late_load)
                    )
                }
            }
            // The only refresh entry point inside the page content: it used to hide at the bottom of
            // the pack card, i.e. below the fold on a 360dp screen.
            add {
                SegmentedListItem(
                    onClick = actions.onRefresh,
                    headlineContent = { Text(stringResource(id = R.string.hide_environment_refresh)) },
                    leadingContent = { Icon(Icons.Filled.Refresh, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
                )
            }
        }
    )
}

@Composable
private fun HidingSwitchesColumnMaterial(uiState: HideEnvironmentUiState, actions: HideEnvironmentActions) {
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(id = R.string.hide_environment_switches_title),
        content = listOf(
            {
                SegmentedDropdownItem(
                    icon = Icons.Filled.AdminPanelSettings,
                    title = stringResource(id = R.string.settings_sucompat),
                    summary = materialFeatureStatusSummary(
                        status = uiState.suCompatStatus,
                        fallback = stringResource(id = R.string.settings_sucompat_summary)
                    ),
                    items = listOf(
                        stringResource(id = R.string.settings_mode_enable_by_default),
                        stringResource(id = R.string.settings_mode_disable_until_reboot),
                        stringResource(id = R.string.settings_mode_disable_always),
                    ),
                    enabled = uiState.suCompatStatus == "supported",
                    selectedIndex = uiState.suCompatMode,
                    onItemSelected = actions.onSetSuCompatMode
                )
            },
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.LayersClear,
                    title = stringResource(id = R.string.settings_kernel_umount),
                    summary = materialFeatureStatusSummary(
                        status = uiState.kernelUmountStatus,
                        fallback = stringResource(id = R.string.settings_kernel_umount_summary)
                    ),
                    enabled = uiState.kernelUmountStatus == "supported",
                    checked = uiState.isKernelUmountEnabled,
                    onCheckedChange = actions.onSetKernelUmountEnabled
                )
            },
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.Security,
                    title = stringResource(id = R.string.settings_selinux_hide),
                    summary = materialFeatureStatusSummary(
                        status = uiState.selinuxHideStatus,
                        fallback = stringResource(id = R.string.settings_selinux_hide_summary)
                    ),
                    enabled = uiState.selinuxHideStatus == "supported",
                    checked = uiState.isSelinuxHideEnabled,
                    onCheckedChange = actions.onSetSelinuxHideEnabled
                )
            },
            {
                SegmentedSwitchItem(
                    icon = Icons.AutoMirrored.Filled.Rule,
                    title = stringResource(id = R.string.settings_umount_modules_default),
                    summary = stringResource(id = R.string.settings_umount_modules_default_summary),
                    checked = uiState.isDefaultUmountModules,
                    onCheckedChange = actions.onSetDefaultUmountModules
                )
            }
        )
    )
}

/**
 * Runtime-mode banner. The mode is tri-state (LKM / GKI built-in / not detected) and this is the
 * single place the page states it: a bare Boolean could label an unanswerable kernel as GKI. The
 * filter count is appended only when a pack is loaded, because it is the pack that gets filtered.
 */
@Composable
private fun RuntimeModeBannerMaterial(uiState: HideEnvironmentUiState) {
    val unknown = uiState.runtimeMode == HidingRuntimeMode.Unknown
    val hasPack = uiState.packState == HidingPackState.Ready

    val (tagBackground, tagContent) = when {
        unknown -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        uiState.runtimeMode == HidingRuntimeMode.Lkm ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary

        else ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        content = listOf(
            {
                SegmentedListItem(
                    headlineContent = {
                        Text(
                            when {
                                unknown -> stringResource(id = R.string.hide_environment_mode_unknown)
                                hasPack -> stringResource(
                                    id = R.string.hide_environment_mode_filtered,
                                    uiState.runtimeModeLabel,
                                    uiState.packEntries.size - uiState.visibleEntries.size,
                                )

                                else -> stringResource(
                                    id = R.string.hide_environment_mode_known,
                                    uiState.runtimeModeLabel,
                                )
                            }
                        )
                    },
                    leadingContent = {
                        StatusTag(
                            label = if (unknown) {
                                stringResource(id = R.string.hide_environment_mode_tag_unknown)
                            } else {
                                uiState.runtimeModeLabel
                            },
                            backgroundColor = tagBackground,
                            contentColor = tagContent,
                        )
                    }
                )
            }
        )
    )
}

@Composable
private fun OneKeyHideColumnMaterial(uiState: HideEnvironmentUiState, actions: HideEnvironmentActions) {
    val running = uiState.isOneKeyRunning

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)) {
        SegmentedColumn(
            title = stringResource(id = R.string.hide_environment_onekey_title),
            content = listOf(
                {
                    SegmentedListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.hide_environment_onekey_summary))
                        },
                        leadingContent = { Icon(Icons.Filled.AutoFixHigh, null) }
                    )
                },
                {
                    SegmentedCheckboxItem(
                        title = stringResource(id = R.string.hide_environment_onekey_lsposed),
                        summary = if (uiState.isLsposedOptionAvailable) {
                            null
                        } else {
                            stringResource(id = R.string.hide_environment_onekey_lsposed_requires_zygisk)
                        },
                        icon = Icons.Filled.Extension,
                        checked = uiState.oneKeyOptions.installLsposed,
                        enabled = uiState.isLsposedOptionAvailable && !running,
                        onCheckedChange = actions.onSetOneKeyLsposed
                    )
                },
                {
                    SegmentedCheckboxItem(
                        title = stringResource(id = R.string.hide_environment_onekey_brick_rescue),
                        icon = Icons.Filled.RestartAlt,
                        checked = uiState.oneKeyOptions.addBrickRescue,
                        enabled = !running,
                        onCheckedChange = actions.onSetOneKeyBrickRescue
                    )
                }
            )
        )
        StepLogMaterial(uiState.oneKeyLog)
        // The batch always ends in 需重启后生效, so the reboot entry sits next to the batch button
        // (same app-wide reboot path and soft-reboot preference as the module screen).
        val onReboot = rememberRebootAction()
        val softReboot = isSoftRebootPreferred()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !uiState.isBatchRunning && uiState.packState == HidingPackState.Ready,
                onClick = actions.onStartOneKeyHide
            ) { Text(stringResource(id = R.string.hide_environment_onekey_start)) }
            OutlinedButton(
                enabled = !uiState.isBatchRunning,
                onClick = { onReboot(if (softReboot) "soft_reboot" else "") }
            ) { Text(stringResource(id = if (softReboot) R.string.reboot_soft else R.string.reboot)) }
        }
    }
}

@Composable
private fun QuickInstallColumnMaterial(uiState: HideEnvironmentUiState, actions: HideEnvironmentActions) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)) {
        SegmentedColumn(
            title = stringResource(id = R.string.hide_environment_quick_title),
            content = buildList {
                add {
                    SegmentedListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.hide_environment_quick_summary))
                        },
                        leadingContent = { Icon(Icons.Filled.BugReport, null) }
                    )
                }
                if (uiState.packState == HidingPackState.Ready) {
                    uiState.quickEntries.forEach { entry ->
                        add {
                            val installState = uiState.installStateOf(entry)
                            SegmentedCheckboxItem(
                                title = entry.name,
                                summary = materialDetectorSummary(entry, installState),
                                icon = materialCategoryIcon(entry.category),
                                checked = entry.id in uiState.quickSelectedIds,
                                enabled = !uiState.isBatchRunning &&
                                    installState != HidingPackInstallState.Installed &&
                                    installState != HidingPackInstallState.PendingReboot,
                                onCheckedChange = { actions.onToggleQuickEntry(entry.id) }
                            )
                        }
                    }
                }
            }
        )
        StepLogMaterial(uiState.quickLog)
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = !uiState.isBatchRunning && uiState.quickSelectedIds.isNotEmpty(),
            onClick = actions.onStartQuickInstall
        ) { Text(stringResource(id = R.string.hide_environment_quick_start)) }
    }
}

/**
 * The step log of a batch run. Success and failure used to share one colour and one style, and an
 * unbounded list could push the batch button out of the card, so the outcome is colour-coded, the
 * final line (always 需重启后生效) is emphasised, and the block scrolls under a fixed ceiling.
 */
@Composable
private fun StepLogMaterial(lines: List<String>) {
    if (lines.isEmpty()) return
    // Locale-proof failure markers: the step line embeds "失败:%s"/"Failed: %s" and the quick log
    // embeds the short failure label, so both families are recognised without matching English.
    val failedMarker = stringResource(id = R.string.hide_environment_step_failed, "")
    val failedLabel = stringResource(id = R.string.hide_environment_pack_failed)
    Column(
        modifier = Modifier
            .heightIn(max = 160.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
    ) {
        lines.forEachIndexed { index, line ->
            val failed = line.contains(failedMarker.trim()) || line.contains(failedLabel)
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (index == lines.lastIndex) FontWeight.Medium else null
            )
        }
    }
}

@Composable
private fun materialDetectorSummary(entry: HidingPackEntry, installState: HidingPackInstallState): String {
    val stateLabel = when (installState) {
        HidingPackInstallState.NotInstalled -> ""
        HidingPackInstallState.Installing -> stringResource(id = R.string.hide_environment_pack_installing)
        HidingPackInstallState.PendingReboot -> stringResource(id = R.string.hide_environment_pack_reboot_hint)
        HidingPackInstallState.Installed -> stringResource(id = R.string.hide_environment_pack_installed)
        HidingPackInstallState.Failed -> stringResource(id = R.string.hide_environment_pack_failed)
    }
    return listOfNotNull(entry.packageName, stateLabel.takeIf { it.isNotEmpty() }).joinToString(" | ")
}

@Composable
private fun HidingPackColumnMaterial(uiState: HideEnvironmentUiState, actions: HideEnvironmentActions) {
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(id = R.string.hide_environment_pack_title),
        content = buildList {
            add {
                SegmentedListItem(
                    headlineContent = { Text(stringResource(id = R.string.hide_environment_pack_summary)) },
                    leadingContent = { Icon(Icons.Filled.Extension, null) }
                )
            }
            // Six PathMask variants share one moduleId and all apply to the LKM mode, so they are
            // only collapsed to the matching branch when one really matches. Say so instead of
            // silently showing six near-identical rows that overwrite each other when installed.
            if (uiState.packState == HidingPackState.Ready &&
                !uiState.pathMaskMatched &&
                uiState.visibleEntries.any { it.category == HidingPackCategory.PathMask }
            ) {
                add {
                    SegmentedListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.hide_environment_onekey_no_branch))
                        },
                        leadingContent = { Icon(Icons.Filled.Warning, null) }
                    )
                }
            }
            when (uiState.packState) {
                HidingPackState.Loading -> Unit
                HidingPackState.Unavailable -> add {
                    SegmentedListItem(
                        // Info, not BugReport: an empty pack is not a detector fact.
                        headlineContent = { Text(stringResource(id = R.string.hide_environment_pack_empty)) },
                        leadingContent = { Icon(Icons.Filled.Info, null) }
                    )
                }

                // bundleEntries = visible minus detectors (quick-install card only) and with the
                // PathMask variants collapsed to the branch matching this kernel.
                HidingPackState.Ready -> uiState.bundleEntries.forEach { entry ->
                    add { HidingPackEntryMaterial(uiState, entry, actions) }
                }
            }
        }
    )
}

@Composable
private fun HidingPackEntryMaterial(
    uiState: HideEnvironmentUiState,
    entry: HidingPackEntry,
    actions: HideEnvironmentActions,
) {
    val installState = uiState.installStateOf(entry)
    val scopeLabel = stringResource(id = materialScopeStringRes(entry.category))
    val recommendedLabel = stringResource(id = R.string.hide_environment_pack_recommended)
    val summary = buildString {
        append(scopeLabel)
        append(" | v")
        append(entry.version)
        // Six PathMask variants share one moduleId, so while none matches this kernel the branch is
        // the only part of the row that tells them apart.
        if (entry.category == HidingPackCategory.PathMask && !uiState.pathMaskMatched) {
            entry.branch?.let {
                append(" | ")
                append(it)
            }
        }
        if (entry.description.isNotBlank()) {
            append(" | ")
            append(entry.description)
        }
        if (uiState.isRecommended(entry)) {
            append(" | ")
            append(recommendedLabel)
        }
    }
    val canInstall = entry.isInstallable &&
        installState != HidingPackInstallState.Installed &&
        installState != HidingPackInstallState.PendingReboot &&
        installState != HidingPackInstallState.Installing
    val onInstallClick: (() -> Unit)? = if (canInstall) {
        { actions.onInstallPackEntry(entry) }
    } else {
        null
    }

    SegmentedListItem(
        onClick = onInstallClick,
        headlineContent = { Text(entry.name) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(materialCategoryIcon(entry.category), null) },
        trailingContent = {
            val label = materialInstallStateLabel(installState)
            if (label != null) {
                Text(label)
            } else if (entry.isInstallable) {
                Text(stringResource(id = R.string.hide_environment_pack_install))
            }
        }
    )
}

/**
 * Licence notice. It used to be one more row inside the environment-status card, where it read as
 * a property of the running environment; the licence is not, so it gets its own block at the end.
 */
@Composable
private fun RiskNoticeColumnMaterial() {
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(id = R.string.hide_environment_risk_title),
        content = listOf(
            {
                SegmentedListItem(
                    headlineContent = {
                        Text(stringResource(id = R.string.hide_environment_risk_license))
                    },
                    leadingContent = { Icon(Icons.Filled.Info, null) }
                )
            }
        )
    )
}

@Composable
private fun StatusRow(icon: ImageVector, title: String) {
    SegmentedListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, null) }
    )
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, summary: String) {
    SegmentedListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, null) }
    )
}

@Composable
private fun materialFeatureStatusSummary(status: String, fallback: String): String = when (status) {
    "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
    "managed" -> stringResource(id = R.string.feature_status_managed_summary)
    else -> fallback
}

@Composable
private fun materialInstallStateLabel(state: HidingPackInstallState): String? = when (state) {
    HidingPackInstallState.NotInstalled -> null
    HidingPackInstallState.Installing -> stringResource(id = R.string.hide_environment_pack_installing)
    HidingPackInstallState.PendingReboot -> stringResource(id = R.string.hide_environment_pack_reboot_hint)
    HidingPackInstallState.Installed -> stringResource(id = R.string.hide_environment_pack_installed)
    HidingPackInstallState.Failed -> stringResource(id = R.string.hide_environment_pack_failed)
}

private fun materialCategoryIcon(category: HidingPackCategory): ImageVector = when (category) {
    HidingPackCategory.Module -> Icons.Filled.Extension
    HidingPackCategory.PathMask -> Icons.Filled.LayersClear
    HidingPackCategory.Detector -> Icons.Filled.BugReport
    HidingPackCategory.Data -> Icons.Filled.Info
}

private fun materialScopeStringRes(category: HidingPackCategory): Int = when (category) {
    HidingPackCategory.Module -> R.string.hide_environment_pack_scope_modules
    HidingPackCategory.PathMask -> R.string.hide_environment_pack_scope_pathmask
    HidingPackCategory.Detector -> R.string.hide_environment_pack_scope_detectors
    HidingPackCategory.Data -> R.string.hide_environment_pack_scope_data
}

@Preview(name = "StepLog")
@Composable
private fun StepLogMaterialPreview() {
    StepLogMaterial(
        listOf(
            stringResource(
                R.string.hide_environment_step, 1, 3, "hybrid_mount",
                stringResource(R.string.hide_environment_step_ok)
            ),
            stringResource(
                R.string.hide_environment_step, 2, 3, "zygisksu",
                stringResource(R.string.hide_environment_step_failed, "sha256 mismatch")
            ),
            stringResource(R.string.hide_environment_pack_reboot_hint)
        )
    )
}

@Preview(name = "StepLog mdpi 360dp", device = "spec:width=360dp,height=640dp,dpi=160")
@Composable
private fun StepLogMaterialMdpiPreview() {
    StepLogMaterialPreview()
}
