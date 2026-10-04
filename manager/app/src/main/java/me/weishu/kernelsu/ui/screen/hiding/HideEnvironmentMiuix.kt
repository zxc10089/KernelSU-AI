package me.weishu.kernelsu.ui.screen.hiding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.HidingPackCategory
import me.weishu.kernelsu.data.model.HidingPackEntry
import me.weishu.kernelsu.data.model.HidingRuntimeMode
import me.weishu.kernelsu.data.repository.isSoftRebootPreferred
import me.weishu.kernelsu.ui.component.rebootlistpopup.rememberRebootAction
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val SMALL_TEXT = 12.sp

// Leading-icon geometry of a card header, in the same numbers BasicComponent uses internally.
private val HEADER_ICON_SIZE = 24.dp

// 12.dp (the old StatusIcon end padding) + the 8.dp spacer BasicComponent puts between its
// start slot and its content column: keeps every title at exactly the same x as before.
private val HEADER_ICON_GAP = 20.dp

// HEADER_ICON_SIZE + HEADER_ICON_GAP: BasicComponent's start slot used to push the whole content
// column (title *and* summary) this far right, so the summary stays indented under the title
// instead of hanging below the icon.
private val HEADER_SUMMARY_INSET = 44.dp

// A pack entry's "安装 / 已安装" end action plus BasicComponent's own 8.dp end spacer: only applied
// when the header really does carry an end action, so a wrapped summary cannot run under it.
private val HEADER_SUMMARY_END_INSET = 56.dp

@Composable
fun HideEnvironmentPagerMiuix(
    uiState: HideEnvironmentUiState,
    actions: HideEnvironmentActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(id = R.string.hide_environment),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    if (!uiState.isFullFeatured) {
                        UnsupportedCardMiuix(actions)
                    } else {
                        RuntimeModeBannerMiuix(uiState)
                        EnvironmentStatusCardMiuix(uiState)
                        HidingSwitchesCardMiuix(uiState, actions)
                        OneKeyHideCardMiuix(uiState, actions)
                        QuickInstallCardMiuix(uiState, actions)
                        HidingPackCardMiuix(uiState, actions)
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}

@Composable
private fun UnsupportedCardMiuix(actions: HideEnvironmentActions) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        HeaderComponentMiuix(
            icon = Icons.Rounded.Warning,
            title = stringResource(id = R.string.hide_environment),
            summary = stringResource(id = R.string.hide_environment_unsupported)
        )
        RefreshPreference(actions.onRefresh)
    }
}

@Composable
private fun EnvironmentStatusCardMiuix(uiState: HideEnvironmentUiState) {
    val kernelSummary = listOf(uiState.androidRelease, uiState.kernelRelease)
        .filter { it.isNotBlank() }
        .joinToString(" | ")

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        HeaderComponentMiuix(
            icon = Icons.Rounded.Info,
            title = stringResource(id = R.string.hide_environment_status_title),
            summary = stringResource(id = R.string.hide_environment_summary)
        )
        // Boolean capabilities are only rendered when they hold; the repo has no yes/no string key.
        if (uiState.isFullFeatured) {
            HeaderComponentMiuix(
                icon = Icons.Rounded.CheckCircle,
                title = stringResource(id = R.string.hide_environment_full_featured),
                summary = null
            )
        }
        if (uiState.isSafeMode) {
            HeaderComponentMiuix(
                icon = Icons.Rounded.Warning,
                title = stringResource(id = R.string.hide_environment_safe_mode),
                summary = null
            )
        }
        if (uiState.isLkmMode) {
            HeaderComponentMiuix(
                icon = Icons.Rounded.Extension,
                title = stringResource(id = R.string.hide_environment_lkm_mode),
                summary = null
            )
        }
        if (uiState.isLateLoadMode) {
            HeaderComponentMiuix(
                icon = Icons.Rounded.RestartAlt,
                title = stringResource(id = R.string.hide_environment_late_load),
                summary = null
            )
        }
        HeaderComponentMiuix(
            icon = Icons.Rounded.SystemUpdate,
            title = stringResource(id = R.string.hide_environment_kernel_uapi),
            summary = uiState.kernelUapiVersion.toString()
        )
        HeaderComponentMiuix(
            icon = Icons.Rounded.SystemUpdate,
            title = stringResource(id = R.string.hide_environment_manager_uapi),
            summary = uiState.managerUapiVersion.toString()
        )
        HeaderComponentMiuix(
            icon = Icons.Rounded.Info,
            title = stringResource(id = R.string.hide_environment_kernel_release),
            summary = kernelSummary
        )
        HeaderComponentMiuix(
            icon = Icons.Rounded.Security,
            title = stringResource(id = R.string.hide_environment_selinux_status),
            summary = uiState.selinuxStatus
        )
        // The risk notice occupies ONE row (user m03679: "风险提示仅占用一个地方 / 不用占用三个地方",
        // "下面那些给开发者看的去除掉", "加入免责声明即可"): the user-facing disclaimer is the summary,
        // and the keybox/private-key + APK-size lines are gone from the screen.
        // hide_environment_risk_keybox / _size stay defined (the frozen contract forbids changing the
        // 33 existing hide_environment* key names) but are intentionally not rendered any more.
        HeaderComponentMiuix(
            icon = Icons.Rounded.BugReport,
            title = stringResource(id = R.string.hide_environment_risk_title),
            summary = stringResource(id = R.string.hide_environment_risk_license)
        )
    }
}

@Composable
private fun HidingSwitchesCardMiuix(uiState: HideEnvironmentUiState, actions: HideEnvironmentActions) {
    val suCompatItems = listOf(
        stringResource(id = R.string.settings_mode_enable_by_default),
        stringResource(id = R.string.settings_mode_disable_until_reboot),
        stringResource(id = R.string.settings_mode_disable_always),
    )

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        HeaderComponentMiuix(
            icon = Icons.Rounded.Settings,
            title = stringResource(id = R.string.hide_environment_switches_title),
            summary = null
        )
        OverlayDropdownPreference(
            title = stringResource(id = R.string.settings_sucompat),
            summary = featureStatusSummary(
                status = uiState.suCompatStatus,
                fallback = stringResource(id = R.string.settings_sucompat_summary)
            ),
            items = suCompatItems,
            startAction = { PreferenceIcon(Icons.Rounded.AdminPanelSettings) },
            enabled = uiState.suCompatStatus == "supported",
            selectedIndex = uiState.suCompatMode,
            onSelectedIndexChange = actions.onSetSuCompatMode
        )
        SwitchPreference(
            title = stringResource(id = R.string.settings_kernel_umount),
            summary = featureStatusSummary(
                status = uiState.kernelUmountStatus,
                fallback = stringResource(id = R.string.settings_kernel_umount_summary)
            ),
            startAction = { PreferenceIcon(Icons.Rounded.LayersClear) },
            enabled = uiState.kernelUmountStatus == "supported",
            checked = uiState.isKernelUmountEnabled,
            onCheckedChange = actions.onSetKernelUmountEnabled
        )
        SwitchPreference(
            title = stringResource(id = R.string.settings_selinux_hide),
            summary = featureStatusSummary(
                status = uiState.selinuxHideStatus,
                fallback = stringResource(id = R.string.settings_selinux_hide_summary)
            ),
            startAction = { PreferenceIcon(Icons.Rounded.Security) },
            enabled = uiState.selinuxHideStatus == "supported",
            checked = uiState.isSelinuxHideEnabled,
            onCheckedChange = actions.onSetSelinuxHideEnabled
        )
        SwitchPreference(
            title = stringResource(id = R.string.settings_umount_modules_default),
            summary = stringResource(id = R.string.settings_umount_modules_default_summary),
            startAction = { PreferenceIcon(Icons.AutoMirrored.Rounded.Rule) },
            checked = uiState.isDefaultUmountModules,
            onCheckedChange = actions.onSetDefaultUmountModules
        )
    }
}

/**
 * Top-of-page filter notice. It is the only place the visible entry count is stated, and it uses
 * the same filtered list the rest of the page renders.
 */
@Composable
private fun RuntimeModeBannerMiuix(uiState: HideEnvironmentUiState) {
    val unknown = uiState.runtimeMode == HidingRuntimeMode.Unknown
    if (!unknown && uiState.packState != HidingPackState.Ready) return

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        HeaderComponentMiuix(
            icon = if (unknown) Icons.Rounded.Warning else Icons.Rounded.Info,
            title = if (unknown) {
                stringResource(id = R.string.hide_environment_mode_unknown)
            } else {
                stringResource(
                    id = R.string.hide_environment_mode_filtered,
                    uiState.runtimeModeLabel,
                    uiState.packEntries.size - uiState.visibleEntries.size,
                )
            },
            summary = null
        )
    }
}

@Composable
private fun OneKeyHideCardMiuix(uiState: HideEnvironmentUiState, actions: HideEnvironmentActions) {
    val running = uiState.isOneKeyRunning

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        HeaderComponentMiuix(
            icon = Icons.Rounded.LayersClear,
            title = stringResource(id = R.string.hide_environment_onekey_title),
            summary = stringResource(id = R.string.hide_environment_onekey_summary)
        )
        CheckboxPreference(
            title = stringResource(id = R.string.hide_environment_onekey_lsposed),
            summary = if (uiState.isLsposedOptionAvailable) {
                null
            } else {
                stringResource(id = R.string.hide_environment_onekey_lsposed_requires_zygisk)
            },
            checked = uiState.oneKeyOptions.installLsposed,
            onCheckedChange = actions.onSetOneKeyLsposed,
            startAction = { PreferenceIcon(Icons.Rounded.Extension) },
            enabled = uiState.isLsposedOptionAvailable && !running
        )
        CheckboxPreference(
            title = stringResource(id = R.string.hide_environment_onekey_brick_rescue),
            summary = null,
            checked = uiState.oneKeyOptions.addBrickRescue,
            onCheckedChange = actions.onSetOneKeyBrickRescue,
            startAction = { PreferenceIcon(Icons.Rounded.RestartAlt) },
            enabled = !running
        )
        StepLogMiuix(uiState.oneKeyLog)
        // The batch always ends in 需重启后生效, so the reboot entry lives right next to the batch
        // button: the whole "hide -> reboot -> verify" loop stays on one card. Reuses the app-wide
        // reboot path (late-load confirm dialog) and honours the soft-reboot preference, exactly
        // like the module screen's snackbar action.
        val onReboot = rememberRebootAction()
        val softReboot = isSoftRebootPreferred()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = stringResource(id = R.string.hide_environment_onekey_start),
                onClick = actions.onStartOneKeyHide,
                enabled = !uiState.isBatchRunning && uiState.packState == HidingPackState.Ready,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            TextButton(
                text = stringResource(id = if (softReboot) R.string.reboot_soft else R.string.reboot),
                onClick = { onReboot(if (softReboot) "soft_reboot" else "") },
                enabled = !uiState.isBatchRunning
            )
        }
    }
}

@Composable
private fun QuickInstallCardMiuix(uiState: HideEnvironmentUiState, actions: HideEnvironmentActions) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        HeaderComponentMiuix(
            icon = Icons.Rounded.BugReport,
            title = stringResource(id = R.string.hide_environment_quick_title),
            summary = stringResource(id = R.string.hide_environment_quick_summary)
        )
        if (uiState.packState == HidingPackState.Ready) {
            uiState.quickEntries.forEach { entry ->
                val installState = uiState.installStateOf(entry)
                CheckboxPreference(
                    title = entry.name,
                    summary = detectorSummary(entry, installState),
                    checked = entry.id in uiState.quickSelectedIds,
                    onCheckedChange = { actions.onToggleQuickEntry(entry.id) },
                    startAction = { PreferenceIcon(categoryIcon(entry.category)) },
                    enabled = !uiState.isBatchRunning &&
                        installState != HidingPackInstallState.Installed
                )
            }
        }
        StepLogMiuix(uiState.quickLog)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            TextButton(
                text = stringResource(id = R.string.hide_environment_quick_start),
                onClick = actions.onStartQuickInstall,
                enabled = !uiState.isBatchRunning && uiState.quickSelectedIds.isNotEmpty(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
private fun StepLogMiuix(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        lines.forEach { line ->
            Text(text = line, fontSize = SMALL_TEXT)
        }
    }
}

@Composable
private fun detectorSummary(entry: HidingPackEntry, installState: HidingPackInstallState): String {
    val stateLabel = when (installState) {
        HidingPackInstallState.NotInstalled -> ""
        HidingPackInstallState.Installing -> stringResource(id = R.string.hide_environment_pack_installing)
        HidingPackInstallState.Installed -> stringResource(id = R.string.hide_environment_pack_installed)
        HidingPackInstallState.Failed -> stringResource(id = R.string.hide_environment_pack_failed)
    }
    return listOfNotNull(entry.packageName, stateLabel.takeIf { it.isNotEmpty() }).joinToString(" | ")
}

@Composable
private fun HidingPackCardMiuix(uiState: HideEnvironmentUiState, actions: HideEnvironmentActions) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        HeaderComponentMiuix(
            icon = Icons.Rounded.Extension,
            title = stringResource(id = R.string.hide_environment_pack_title),
            summary = stringResource(id = R.string.hide_environment_pack_summary)
        )
        when (uiState.packState) {
            HidingPackState.Loading -> Unit
            HidingPackState.Unavailable -> HeaderComponentMiuix(
                icon = Icons.Rounded.BugReport,
                title = stringResource(id = R.string.hide_environment_pack_empty),
                summary = null
            )

            // bundleEntries = visible minus detectors (quick-install card only) and with the
            // PathMask variants collapsed to the branch matching this kernel (users m03682/m03683).
            HidingPackState.Ready -> uiState.bundleEntries.forEach { entry ->
                HidingPackEntryMiuix(uiState, entry, actions)
            }
        }
        RefreshPreference(actions.onRefresh)
    }
}

@Composable
private fun HidingPackEntryMiuix(
    uiState: HideEnvironmentUiState,
    entry: HidingPackEntry,
    actions: HideEnvironmentActions,
) {
    val scopeLabel = stringResource(id = scopeStringRes(entry.category))
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

    HeaderComponentMiuix(
        icon = categoryIcon(entry.category),
        title = entry.name,
        summary = summary,
        endActions = {
            HidingPackEndActionMiuix(
                entry = entry,
                installState = uiState.installStateOf(entry),
                onInstall = actions.onInstallPackEntry
            )
        }
    )
}

@Composable
private fun HidingPackEndActionMiuix(
    entry: HidingPackEntry,
    installState: HidingPackInstallState,
    onInstall: (HidingPackEntry) -> Unit,
) {
    when {
        !entry.isInstallable -> Text(
            text = stringResource(id = R.string.hide_environment_pack_scope_data),
            fontSize = SMALL_TEXT
        )

        installState == HidingPackInstallState.Installed -> Text(
            text = stringResource(id = R.string.hide_environment_pack_installed),
            fontSize = SMALL_TEXT
        )

        installState == HidingPackInstallState.Installing -> Text(
            text = stringResource(id = R.string.hide_environment_pack_installing),
            fontSize = SMALL_TEXT
        )

        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (installState == HidingPackInstallState.Failed) {
                Text(
                    text = stringResource(id = R.string.hide_environment_pack_failed),
                    fontSize = SMALL_TEXT
                )
                Spacer(Modifier.width(6.dp))
            }
            TextButton(
                text = stringResource(id = R.string.hide_environment_pack_install),
                onClick = { onInstall(entry) },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
private fun RefreshPreference(onRefresh: () -> Unit) {
    ArrowPreference(
        title = stringResource(id = R.string.hide_environment_refresh),
        summary = null,
        startAction = { PreferenceIcon(Icons.Rounded.Refresh) },
        onClick = onRefresh
    )
}

/**
 * Card header whose leading icon sits on the title line (user m03680: "这里的字体和图标不在同一高度").
 *
 * Miuix' own [BasicComponent] centres its `startAction` on the whole title+summary block
 * (`startTop = (rowHeight - startHeight) / 2` in Component.kt), so a two-line header pins the icon
 * to the summary line. Rendering the icon inside the component's content column instead keeps it on
 * the title line. The zero-height slot means the icon does not add a pixel of row height, and the
 * icon itself is drawn with `requiredSize` because that slot's 0.dp max height would otherwise
 * squeeze a plain `size(24.dp)` icon down to nothing. The rest of the layout is byte-for-byte what
 * the library produced: same 16.dp inside margin, same font sizes/weights/colours, same 20.dp icon
 * gap, and a summary that keeps the library's 44.dp content-column indent (plus an end inset only
 * when the header also carries an end action).
 */
@Composable
private fun HeaderComponentMiuix(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    endActions: (@Composable RowScope.() -> Unit)? = null,
) {
    BasicComponent(endActions = endActions) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A zero-height slot: the 24.dp icon is drawn centred on it, overflowing symmetrically,
            // which puts the icon's centre exactly on the title's centre without growing the row.
            Box(
                modifier = Modifier
                    .width(HEADER_ICON_SIZE)
                    .height(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    // requiredSize, not size: the zero-height slot constrains its child, and a
                    // plain size(24.dp) would be squeezed to nothing by that 0.dp max height,
                    // which is exactly how the icon disappeared once already.
                    modifier = Modifier.requiredSize(HEADER_ICON_SIZE),
                    tint = colorScheme.onBackground
                )
            }
            Spacer(Modifier.width(HEADER_ICON_GAP))
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onBackground
            )
        }
        if (summary != null) {
            Text(
                text = summary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(
                    start = HEADER_SUMMARY_INSET,
                    end = if (endActions != null) HEADER_SUMMARY_END_INSET else 0.dp
                )
            )
        }
    }
}

@Composable
private fun PreferenceIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = colorScheme.onBackground
    )
}

@Composable
private fun featureStatusSummary(status: String, fallback: String): String = when (status) {
    "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
    "managed" -> stringResource(id = R.string.feature_status_managed_summary)
    else -> fallback
}

private fun categoryIcon(category: HidingPackCategory): ImageVector = when (category) {
    HidingPackCategory.Module -> Icons.Rounded.Extension
    HidingPackCategory.PathMask -> Icons.Rounded.LayersClear
    HidingPackCategory.Detector -> Icons.Rounded.BugReport
    HidingPackCategory.Data -> Icons.Rounded.Info
}

private fun scopeStringRes(category: HidingPackCategory): Int = when (category) {
    HidingPackCategory.Module -> R.string.hide_environment_pack_scope_modules
    HidingPackCategory.PathMask -> R.string.hide_environment_pack_scope_pathmask
    HidingPackCategory.Detector -> R.string.hide_environment_pack_scope_detectors
    HidingPackCategory.Data -> R.string.hide_environment_pack_scope_data
}
