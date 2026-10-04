package me.weishu.kernelsu.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * "Send" glyph for the AI console composer: a solid upward arrow, drawn by hand so that both
 * flavours share one glyph and no icon pack is pulled in for a single control (same approach as
 * AiUploadFileIcon.kt).
 *
 * Geometry (24x24 viewport, viewport units): tip (12, 3.8); head shoulders at y 11.2 spanning
 * x 4.6..19.4; shaft x 8.8..15.2 down to y 20.2. Sharp corners only, so the arrow stays a single
 * even-odd sub-path that tints correctly on the filled send button.
 */
val AiSendArrowIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AiSendArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(12f, 3.8f)
            lineTo(19.4f, 11.2f)
            lineTo(15.2f, 11.2f)
            lineTo(15.2f, 20.2f)
            lineTo(8.8f, 20.2f)
            lineTo(8.8f, 11.2f)
            lineTo(4.6f, 11.2f)
            close()
        }
    }.build()
}

/**
 * "Stop" glyph: a solid rounded square that replaces the send arrow while a reply is streaming, so
 * the button keeps its position and its size while the action changes.
 *
 * Geometry: 12x12 square centred on the viewport (x and y 6..18) with a 2.6 corner radius; the four
 * corners use the usual cubic constant 0.5523 * radius.
 */
val AiStopIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AiStop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(8.6f, 6f)
            lineTo(15.4f, 6f)
            curveTo(16.836f, 6f, 18f, 7.164f, 18f, 8.6f)
            lineTo(18f, 15.4f)
            curveTo(18f, 16.836f, 16.836f, 18f, 15.4f, 18f)
            lineTo(8.6f, 18f)
            curveTo(7.164f, 18f, 6f, 16.836f, 6f, 15.4f)
            lineTo(6f, 8.6f)
            curveTo(6f, 7.164f, 7.164f, 6f, 8.6f, 6f)
            close()
        }
    }.build()
}
