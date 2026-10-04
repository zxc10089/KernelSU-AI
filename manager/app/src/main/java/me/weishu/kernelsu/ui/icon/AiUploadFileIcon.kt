package me.weishu.kernelsu.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * "Upload a file" glyph for the AI console attach button, drawn by hand so that both flavours share
 * one glyph and no icon pack is pulled in for a single control (same approach as AiAssistantIcon.kt).
 *
 * Design: a file rising out of a tray. The tray is one closed outline (three overlapping bars would
 * be punched into holes by EvenOdd) and the arrow is a second, disjoint outline, so the whole vector
 * stays a single even-odd path that tints correctly on any surface, light or dark.
 *
 * Geometry (24x24 viewport, viewport units):
 * - tray: outer box x 4.3..19.7, bottom y 20.0, wall thickness 2.1, outer corner radius 2.0,
 *   inner corner radius 1.0, arm tops y 11.6
 * - arrow: tip (12, 3.2), shoulders y 9.4 spanning x 6.9..17.1, shaft x 10.5..13.5, shaft end y 15.5
 * - the arrow never touches the tray: 0.5 units of clearance to the arms, 2.4 to the inner floor
 *
 * Circular corners use the usual cubic constant 0.5523 * radius.
 */
val AiUploadFileIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AiUploadFile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            // Tray: left wall down, rounded floor, right wall up, then back along the inside.
            moveTo(4.3f, 11.6f)
            lineTo(4.3f, 18f)
            curveTo(4.3f, 19.1046f, 5.1954f, 20f, 6.3f, 20f)
            lineTo(17.7f, 20f)
            curveTo(18.8046f, 20f, 19.7f, 19.1046f, 19.7f, 18f)
            lineTo(19.7f, 11.6f)
            lineTo(17.6f, 11.6f)
            lineTo(17.6f, 16.9f)
            curveTo(17.6f, 17.4523f, 17.1523f, 17.9f, 16.6f, 17.9f)
            lineTo(7.4f, 17.9f)
            curveTo(6.8477f, 17.9f, 6.4f, 17.4523f, 6.4f, 16.9f)
            lineTo(6.4f, 11.6f)
            close()

            // Arrow: a single polygon, so it cannot intersect the tray outline above.
            moveTo(6.9f, 9.4f)
            lineTo(10.5f, 9.4f)
            lineTo(10.5f, 15.5f)
            lineTo(13.5f, 15.5f)
            lineTo(13.5f, 9.4f)
            lineTo(17.1f, 9.4f)
            lineTo(12f, 3.2f)
            close()
        }
    }.build()
}