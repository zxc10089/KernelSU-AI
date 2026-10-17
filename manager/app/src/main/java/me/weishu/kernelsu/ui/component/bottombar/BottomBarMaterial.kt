package me.weishu.kernelsu.ui.component.bottombar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalMainPagerState
import me.weishu.kernelsu.ui.icon.HideEnvironmentIcon
import me.weishu.kernelsu.ui.icon.HideEnvironmentIconOutlined
import me.weishu.kernelsu.ui.theme.TypeTokens
import me.weishu.kernelsu.ui.util.rootAvailable
import me.weishu.kernelsu.ui.util.requiresNewKernel

@Composable
fun BottomBarMaterial(navigationBadge: NavigationBadgeState) {
    val isManager = Natives.isManager
    // root-only unlock: a self-built APK is never crowned manager by the kernel
    val fullFeatured = rootAvailable() && (!isManager || !requiresNewKernel())
    val mainPagerState = LocalMainPagerState.current

    if (!fullFeatured) return

    val items = listOf(
        Triple(R.string.home, Icons.Filled.Home, Icons.Outlined.Home),
        Triple(R.string.superuser, Icons.Filled.Shield, Icons.Outlined.Shield),
        Triple(R.string.hide_environment_tab, HideEnvironmentIcon, HideEnvironmentIconOutlined),
        Triple(R.string.module, Icons.Filled.Extension, Icons.Outlined.Extension),
        Triple(R.string.settings, Icons.Filled.Settings, Icons.Outlined.Settings)
    )

    ShortNavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        )
    ) {
        items.forEachIndexed { index, (label, selectedIcon, unselectedIcon) ->
            val selected = mainPagerState.selectedPage == index
            ShortNavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        mainPagerState.animateToPage(index)
                    }
                },
                icon = {
                    NavigationIconWithBadge(
                        icon = if (selected) selectedIcon else unselectedIcon,
                        contentDescription = stringResource(label),
                        badge = badgeFor(index, navigationBadge),
                        badgeOffset = BottomBarBadgeOffset,
                    )
                },
                label = {
                    // P2-1: the very same label as the Miuix floating bottom bar (11sp with a
                    // 14dp line box). Measured ink height: Miuix 14.0dp, Material 13.0dp (1dp
                    // apart, inside the review's +-1dp convention); Material's old 16dp default
                    // was line-height driven and is gone.
                    Text(
                        stringResource(label),
                        style = TypeTokens.navLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

/**
 * P2-1 badge alignment. Material3's [BadgedBox] anchors the badge to the icon's top-end corner,
 * while the Miuix bottom bar anchors it to a 48dp icon box, so once the icon glyph centres agreed
 * (Miuix 847dp vs Material 848dp) the badge text still disagreed by (1.34dp, 2.00dp) - measured
 * with uiautomator at density 480:
 *   Miuix    badge text at (125.67dp, 832dp)  _artifacts/device/home-ui/tokens-after-miuix.table.txt
 *   Material badge text at (124.33dp, 834dp)  _artifacts/device/home-ui/tokens-after-material.table.txt
 * The bar passes [BottomBarBadgeOffset] to cancel that; the rail keeps [DpOffset.Zero].
 */
private val BottomBarBadgeOffset = DpOffset(x = 1.5.dp, y = (-2).dp)

@Composable
internal fun NavigationIconWithBadge(
    icon: ImageVector,
    contentDescription: String?,
    badge: NavBadge?,
    badgeOffset: DpOffset = DpOffset.Zero,
) {
    val shift = Modifier.offset(x = badgeOffset.x, y = badgeOffset.y)
    if (badge != null) {
        BadgedBox(
            badge = {
                when (badge.tone) {
                    BadgeTone.Alert -> Badge(shift) {
                        Text(badge.count.toString())
                    }

                    BadgeTone.Accent -> Badge(
                        modifier = shift,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(badge.count.toString())
                    }
                }
            }
        ) {
            Icon(icon, contentDescription)
        }
    } else {
        Icon(icon, contentDescription)
    }
}
