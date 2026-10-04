package me.weishu.kernelsu.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom bottom-bar icon for the "environment hiding" destination.
 *
 * Design: a solid shield whose silhouette stays intact, with a bold 45-degree bar punched out
 * of it as negative space. The bar IS the "slash" of a prohibition mark, expressed as negative
 * space rather than as an overlaid stroke: in a monochrome vector whose fill is re-tinted by
 * `Icon`, a same-colour stroke would be invisible against the shield.
 *
 * Why the silhouette is left intact instead of being sliced all the way through: the shield's
 * lower-right facet has a slope of 0.97, which is nearly parallel to a 45-degree cut, so any
 * through-cut leaves a 1-2 px sliver that reads as a detached speck at 24 dp. Keeping the
 * outline whole also keeps this icon distinct from the also-shield-shaped `Icons.Rounded.Security`
 * used by the SuperUser tab (that one has a centred keyhole; this one has a diagonal bar).
 *
 * Geometry (24 x 24 viewport, rendered at 24 dp):
 *  - Overall bounds x = 3.75 .. 20.25, y = 2.2 .. 21.8, i.e. inside the 2..22 safe area.
 *  - Silhouette: flat top edge 6.1 .. 17.9 at y = 2.2 with 2.35-radius rounded shoulders,
 *    vertical sides at x = 3.75 / 20.25 from y = 4.55 down to y = 13.8, then straight
 *    (faceted, not curved) tapers meeting at the tip (12.0, 21.8).
 *  - Bar: rectangle 3.00 dp wide, long axis at 45 degrees along y = x - 0.6, spanning the
 *    centreline from (6.8, 6.2) to (16.2, 15.6). Its two 45-degree edges are the lines
 *    y = x - 2.7214 and y = x + 1.5214; perpendicular width = 4.2428 / sqrt(2) = 3.0001 dp.
 *  - Bar corners sit 1.50 .. 2.94 dp inside the outline (tightest at the lower-right end, against
 *    the 0.97-slope facet), so the shield keeps a thin solid bridge there and never breaks apart.
 *
 * The two subpaths are disjoint (the bar is strictly interior), so `PathFillType.EvenOdd`
 * resolves them exactly: a point inside the shield only has crossing number 1 (filled), a point
 * inside the bar has crossing number 2 (hole). No boolean path operations are involved.
 *
 * The companion SVG at `design/hide-environment-icon.svg` carries the identical point list.
 * Not verified by a compiler here - see the self-check list in `design/environment-hide-spec.md`.
 */
val HideEnvironmentIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "HideEnvironment",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            // Subpath 1 - shield silhouette: upper-left shoulder, top edge, upper-right
            // shoulder, right side, lower-right facet, tip, then close() back up the
            // lower-left facet to the start of the left side.
            moveTo(3.75f, 4.55f)
            curveTo(3.75f, 3.25f, 4.8f, 2.2f, 6.1f, 2.2f)
            lineTo(17.9f, 2.2f)
            curveTo(19.2f, 2.2f, 20.25f, 3.25f, 20.25f, 4.55f)
            lineTo(20.25f, 13.8f)
            lineTo(12.0f, 21.8f)
            lineTo(3.75f, 13.8f)
            close()
            // Subpath 2 - the 45-degree negative-space bar, strictly interior to subpath 1.
            moveTo(7.8607f, 5.1393f)
            lineTo(17.2607f, 14.5393f)
            lineTo(15.1393f, 16.6607f)
            lineTo(5.7393f, 7.2607f)
            close()
        }
    }.build()
}
