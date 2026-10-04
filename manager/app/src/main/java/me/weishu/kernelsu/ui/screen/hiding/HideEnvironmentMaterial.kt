package me.weishu.kernelsu.ui.screen.hiding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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

@Composable
fun HideEnvironmentPagerMaterial(
    uiState: HideEnvironmentUiState,
    actions: HideEnvironmentActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            HideEnvironmentTopBar(scrollBehavior = scrollBehavior)
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            if (!uiState.isFullFeatured) {
                UnsupportedColumnMaterial(actions)
            } else {
                RuntimeModeBannerMaterial(uiState)
                EnvironmentStatusColumnMaterial(uiState)
                HidingSwitchesColumnMaterial(uiState, actions)
                OneKeyHideColumnMaterial(uiState, actions)
                QuickInstallColumnMaterial(uiState, actions)
                HidingPackColumnMaterial(uiState, actions)
            }
            Spacer(modifier = Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
private fun HideEnvironmentTopBar(scrollBehavior: TopAppBarScrollBehavior) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(id = R.string.hide_environment)) },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun UnsupportedColumnMaterial(actions: HideEnvironmentActions) {
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        content = listOf(
            {
                SegmentedListItem(
                    headlineContent = { Text(stringResource(id = R.string.hide_environment)) },
                    supportingContent = { Text(stringResource(id = R.string.hide_environment_unsupported)) },
                    leadingContent = { Icon(Icons.Filled.Warning, null) }
                )
            },
            {
                SegmentedListItem(
                    onClick = actions.onRefresh,
                    headlineContent = { Text(stringResource(id = R.string.hide_environment_refresh)) },
                    leadingContent = { Icon(Icons.Filled.Refresh, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
                )
            }
        )
    )
}

@Composable
private fun EnvironmentStatusColumnMaterial(uiState: HideEnvironmentUiState) {
    val kernelSummary = listOf(uiState.androidRelease, uiState.kernelRelease)
        .filter { it.isNotBlank() }
        .joinToString(" | ")

    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = stringResource(id = R.string.hide_environment_status_title),
        content = buildList {
            add {
                StatusRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(id = R.string.hide_environment_summary)
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
            if (uiState.isLkmMode) {
                add {
                    StatusRow(
                        icon = Icons.Filled.Extension,
                        title = stringResource(id = R.string.hide_environment_lkm_mode)
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
            add {
                StatusRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = stringResource(id = R.string.hide_environment_kernel_uapi),
                    summary = uiState.kernelUapiVersion.toString()
                )
            }
            add {
                StatusRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = stringResource(id = R.string.hide_environment_manager_uapi),
                    summary = uiState.managerUapiVersion.toString()
                )
            }
            add {
                StatusRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(id = R.string.hide_environment_kernel_release),
                    summary = kernelSummary
                )
            }
            add {
                StatusRow(
                    icon = Icons.Filled.Security,
                    title = stringResource(id = R.string.hide_environment_selinux_status),
                    summary = uiState.selinuxStatus
                )
            }
            add {
                // One row only: title + the user-facing disclaimer as summary.
                // hide_environment_risk_keybox / _size stay defined but are no longer rendered.
                StatusRow(
                    icon = Icons.Filled.BugReport,
                    title = stringResource(id = R.string.hide_environment_risk_title),
                    summary = stringResource(id = R.string.hide_environment_risk_license)
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
 * Top-of-page filter notice. It is the only place the visible entry count is stated, and it uses
 * the same filtered list the rest of the page renders.
 */
@Composable
private fun RuntimeModeBannerMaterial(uiState: HideEnvironmentUiState) {
    val unknown = uiState.runtimeMode == HidingRuntimeMode.Unknown
    if (!unknown && uiState.packState != HidingPackState.Ready) return

    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        content = listOf(
            {
                SegmentedListItem(
                    headlineContent = {
                        Text(
                            if (unknown) {
                                stringResource(id = R.string.hide_environment_mode_unknown)
                            } else {
                                stringResource(
                                    id = R.string.hide_environment_mode_filtered,
                                    uiState.runtimeModeLabel,
                                    uiState.packEntries.size - uiState.visibleEntries.size,
                                )
                            }
                        )
                    },
                    leadingContent = {
                        Icon(if (unknown) Icons.Filled.Warning else Icons.Filled.Info, null)
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
                        leadingContent = { Icon(Icons.Filled.LayersClear, null) }
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
                        checked = uiState.oneKeyOptions.installLsposed,
                        enabled = uiState.isLsposedOptionAvailable && !running,
                        onCheckedChange = actions.onSetOneKeyLsposed
                    )
                },
                {
                    SegmentedCheckboxItem(
                        title = stringResource(id = R.string.hide_environment_onekey_brick_rescue),
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
                                checked = entry.id in uiState.quickSelectedIds,
                                enabled = !uiState.isBatchRunning &&
                                    installState != HidingPackInstallState.Installed,
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

@Composable
private fun StepLogMaterial(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(modifier = Modifier.padding(top = 6.dp)) {
        lines.forEach { line ->
            Text(text = line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun materialDetectorSummary(entry: HidingPackEntry, installState: HidingPackInstallState): String {
    val stateLabel = when (installState) {
        HidingPackInstallState.NotInstalled -> ""
        HidingPackInstallState.Installing -> stringResource(id = R.string.hide_environment_pack_installing)
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
            when (uiState.packState) {
                HidingPackState.Loading -> Unit
                HidingPackState.Unavailable -> add {
                    SegmentedListItem(
                        headlineContent = { Text(stringResource(id = R.string.hide_environment_pack_empty)) },
                        leadingContent = { Icon(Icons.Filled.BugReport, null) }
                    )
                }

                // bundleEntries = visible minus detectors (quick-install card only) and with the
                // PathMask variants collapsed to the branch matching this kernel.
                HidingPackState.Ready -> uiState.bundleEntries.forEach { entry ->
                    add { HidingPackEntryMaterial(uiState, entry, actions) }
                }
            }
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
