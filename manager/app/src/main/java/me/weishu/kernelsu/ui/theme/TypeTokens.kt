package me.weishu.kernelsu.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Semantic type tokens shared by the Miuix and the Material UI.
 *
 * Single source of truth so the two modes cannot drift apart. The Miuix side already renders
 * these numbers (17sp/14sp *are* Miuix `headline1`/`body2`), and the Material side passes the
 * same token into `Text(style = ...)`; before this file the two modes ran 4-6sp apart on card
 * text and the card ink heights did not match. See design/home-ui-review.md (P1-3, P2-1, P2-3)
 * for the on-device measurements behind every value.
 *
 * `lineHeight` is Unspecified on purpose. A Miuix text style only carries a font size, so its
 * line box is the font metric (17sp -> 19.67dp on a 480dpi panel). Material's M3 styles ship a
 * lineHeight (titleSmall/bodyMedium = 20sp), which inflates the reported node height to 20dp and
 * would fail the P1-3 "+-1dp between the two dumps" check; the bottom-bar label is the one place
 * where an explicit lineHeight is wanted, because there the Miuix side pins it too.
 *
 * Do not inline these numbers in HomeMiuix.kt / HomeMaterial.kt / BottomBar*.kt.
 */
object TypeTokens {
    /** Large page title of the top app bar, identical to Miuix `title1` (32sp). */
    val pageTitle = TextStyle(fontSize = 32.sp, lineHeight = TextUnit.Unspecified)

    /**
     * Card-group / section header (P1-2).
     *
     * The size is the Miuix `title3` step (20sp) - the review asks for a step between the 32sp
     * page title and the 17sp card title, and 20sp is the only existing Miuix step that fills the
     * 22-24dp hole the review measured. `FontWeight.Medium` is this file's choice so the header
     * still reads as a header against card titles of a similar weight.
     */
    val sectionTitle = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = TextUnit.Unspecified,
    )

    /** Card title, identical to Miuix `headline1` (17sp) at Medium. */
    val cardTitle = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = TextUnit.Unspecified,
    )

    /** Card body / info value, identical to Miuix `body2` (14sp). */
    val cardBody = TextStyle(fontSize = 14.sp, lineHeight = TextUnit.Unspecified)

    /**
     * Status-card text (status-card unification, design/home-ui-review.md §7.2-1 / §14.5).
     *
     * The Miuix status card already rendered these numbers (22sp / 15sp / 16sp), but the Material
     * card ran M3 `titleMediumEmphasized` / `bodyMedium` next to an M3 `ListItem` leading slot, so
     * the same card reported a different left edge (X1 = 64dp vs 28dp) and a different ink scale
     * (24 / 20 / 16dp vs 25.67 / 17.67 / 19dp). Material now mirrors the Miuix card geometry, so
     * both sides consume these tokens and must report the same ink heights.
     */
    val statusTitle = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = TextUnit.Unspecified,
    )

    /** Status-card version line (Miuix: 15sp Medium). */
    val statusMeta = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = TextUnit.Unspecified,
    )

    /** Status-card working-mode label (`LKM` / `GKI`; Miuix: 16sp Medium). */
    val statusMode = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = TextUnit.Unspecified,
    )

    /**
     * Bottom-bar label (P2-1), identical to the Miuix floating bottom bar
     * (BottomBarMiuix.kt: fontSize 11sp, lineHeight 14sp), so both modes report a 14dp label.
     */
    val navLabel = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
}
