package me.weishu.kernelsu.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * "AI assistant" robot head, drawn by hand because miuix-icons 0.9.3 ships no AI / robot /
 * bot glyph at all (the `extended` set was enumerated in full while building this feature).
 * Material's `Icons.Rounded.SmartToy` is used on the Material side; this vector keeps the
 * Miuix side free of a cross-design-system icon and matches the existing precedent set by
 * `HideEnvironmentIcon.kt`.
 *
 * Design: a solid rounded head silhouette carrying the negative space that reads as a face.
 * Everything is one even-odd path, so the eyes and the mouth fall out as holes instead of being
 * painted in the background colour (which would break on tinted surfaces).
 *
 * Geometry (24x24 viewport, all values in viewport units):
 * - antenna ball: circle, centre (12, 3.4), r = 1.0
 * - antenna stem: rectangle x 11.2..12.8, y 4.4..8.0
 * - head: rounded rectangle x 4..20, y 8..19.5, corner radius 3.2
 * - eyes: rounded rectangles 3.0 x 3.0, r = 1.2, centred on y = 12.5,
 *   left x 7.4..10.4 and right x 13.6..16.6
 * - mouth: rounded rectangle x 9.6..14.4, y 15.8..17.2, r = 0.7
 * - ears: vertical stadiums x 1.6..4.0 and x 20.0..22.4, y 12.4..15.8, r = 1.2
 *
 * Sub-paths only ever touch (never overlap) so even-odd resolves cleanly: the ears stop exactly
 * on x = 4 and x = 20, the stem stops exactly on y = 8 and the ball exactly on y = 4.4.
 * Circular caps are approximated with the usual cubic constant 0.5523 * r.
 */
val AiAssistantIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AiAssistant",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            // Antenna ball.
            moveTo(12f, 2.4f)
            curveTo(12.5523f, 2.4f, 13f, 2.8477f, 13f, 3.4f)
            curveTo(13f, 3.9523f, 12.5523f, 4.4f, 12f, 4.4f)
            curveTo(11.4477f, 4.4f, 11f, 3.9523f, 11f, 3.4f)
            curveTo(11f, 2.8477f, 11.4477f, 2.4f, 12f, 2.4f)
            close()

            // Antenna stem.
            moveTo(11.2f, 4.4f)
            lineTo(12.8f, 4.4f)
            lineTo(12.8f, 8f)
            lineTo(11.2f, 8f)
            close()

            // Head.
            moveTo(7.2f, 8f)
            lineTo(16.8f, 8f)
            curveTo(18.5674f, 8f, 20f, 9.4326f, 20f, 11.2f)
            lineTo(20f, 16.3f)
            curveTo(20f, 18.0674f, 18.5674f, 19.5f, 16.8f, 19.5f)
            lineTo(7.2f, 19.5f)
            curveTo(5.4326f, 19.5f, 4f, 18.0674f, 4f, 16.3f)
            lineTo(4f, 11.2f)
            curveTo(4f, 9.4326f, 5.4326f, 8f, 7.2f, 8f)
            close()

            // Left eye (hole).
            moveTo(8.6f, 11f)
            lineTo(9.2f, 11f)
            curveTo(9.8628f, 11f, 10.4f, 11.5372f, 10.4f, 12.2f)
            lineTo(10.4f, 12.8f)
            curveTo(10.4f, 13.4628f, 9.8628f, 14f, 9.2f, 14f)
            lineTo(8.6f, 14f)
            curveTo(7.9372f, 14f, 7.4f, 13.4628f, 7.4f, 12.8f)
            lineTo(7.4f, 12.2f)
            curveTo(7.4f, 11.5372f, 7.9372f, 11f, 8.6f, 11f)
            close()

            // Right eye (hole).
            moveTo(14.8f, 11f)
            lineTo(15.4f, 11f)
            curveTo(16.0628f, 11f, 16.6f, 11.5372f, 16.6f, 12.2f)
            lineTo(16.6f, 12.8f)
            curveTo(16.6f, 13.4628f, 16.0628f, 14f, 15.4f, 14f)
            lineTo(14.8f, 14f)
            curveTo(14.1372f, 14f, 13.6f, 13.4628f, 13.6f, 12.8f)
            lineTo(13.6f, 12.2f)
            curveTo(13.6f, 11.5372f, 14.1372f, 11f, 14.8f, 11f)
            close()

            // Mouth (hole).
            moveTo(10.3f, 15.8f)
            lineTo(13.7f, 15.8f)
            curveTo(14.0866f, 15.8f, 14.4f, 16.1134f, 14.4f, 16.5f)
            curveTo(14.4f, 16.8866f, 14.0866f, 17.2f, 13.7f, 17.2f)
            lineTo(10.3f, 17.2f)
            curveTo(9.9134f, 17.2f, 9.6f, 16.8866f, 9.6f, 16.5f)
            curveTo(9.6f, 16.1134f, 9.9134f, 15.8f, 10.3f, 15.8f)
            close()

            // Left ear.
            moveTo(1.6f, 13.6f)
            lineTo(1.6f, 14.6f)
            curveTo(1.6f, 15.2628f, 2.1372f, 15.8f, 2.8f, 15.8f)
            curveTo(3.4628f, 15.8f, 4f, 15.2628f, 4f, 14.6f)
            lineTo(4f, 13.6f)
            curveTo(4f, 12.9372f, 3.4628f, 12.4f, 2.8f, 12.4f)
            curveTo(2.1372f, 12.4f, 1.6f, 12.9372f, 1.6f, 13.6f)
            close()

            // Right ear.
            moveTo(20f, 13.6f)
            lineTo(20f, 14.6f)
            curveTo(20f, 15.2628f, 20.5372f, 15.8f, 21.2f, 15.8f)
            curveTo(21.8628f, 15.8f, 22.4f, 15.2628f, 22.4f, 14.6f)
            lineTo(22.4f, 13.6f)
            curveTo(22.4f, 12.9372f, 21.8628f, 12.4f, 21.2f, 12.4f)
            curveTo(20.5372f, 12.4f, 20f, 12.9372f, 20f, 13.6f)
            close()
        }
    }.build()
}
