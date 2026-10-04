package me.weishu.kernelsu.ui.screen.home

import androidx.compose.ui.unit.dp

/**
 * Layout constants shared by the Miuix and the Material home screens.
 *
 * Single source of truth so the two UI modes cannot drift apart.
 * See design/home-ui-review.md (P0-3) for the measurements behind these values.
 *
 * Do not inline these numbers in HomeMiuix.kt / HomeMaterial.kt: the whole point
 * of this file is that both modes move together.
 */
object HomeDimens {
    /** Horizontal page padding. Both modes use it so card edges line up. */
    val PageHorizontal = 12.dp

    /** Gap between the top bar and the first card (Miuix pads the column, Material the scaffold). */
    val PageTop = 12.dp

    /** Vertical gap between two stacked cards. */
    val CardSpacing = 12.dp

    /** Inner padding of a card's content column. */
    val CardInner = 16.dp

    /** Bottom gap of an info row; the last row of a card relies on [CardInner] instead. */
    val InfoRowBottom = 16.dp
}
