package me.weishu.kernelsu.ui.screen.hiding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.viewmodel.HideEnvironmentViewModel

@Suppress("UNUSED_PARAMETER")
@Composable
fun HideEnvironmentPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<HideEnvironmentViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            viewModel.refresh()
        }
    }

    val actions = HideEnvironmentActions(
        onRefresh = viewModel::refresh,
        onSetSelinuxHideEnabled = viewModel::setSelinuxHideEnabled,
        onSetKernelUmountEnabled = viewModel::setKernelUmountEnabled,
        onSetSuCompatMode = viewModel::setSuCompatMode,
        onSetDefaultUmountModules = viewModel::setDefaultUmountModules,
        onInstallPackEntry = viewModel::installPackEntry,
        onSetOneKeyLsposed = viewModel::setOneKeyLsposed,
        onSetOneKeyBrickRescue = viewModel::setOneKeyBrickRescue,
        onStartOneKeyHide = viewModel::startOneKeyHide,
        onToggleQuickEntry = viewModel::toggleQuickEntry,
        onStartQuickInstall = viewModel::startQuickInstall,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> HideEnvironmentPagerMiuix(uiState, actions, bottomInnerPadding)
        UiMode.Material -> HideEnvironmentPagerMaterial(uiState, actions, bottomInnerPadding)
    }
}
