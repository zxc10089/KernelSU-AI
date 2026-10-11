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

    /**
     * The one text axis of a home page (P2-3): [PageHorizontal] + [CardInner].
     *
     * Card content already sits on it. The top-bar titles are moved onto it as well (Miuix
     * `TopAppBar(titlePadding = ...)`, Material's title gets [TopBarTitleExtraInset]), so that a
     * page has a single left edge for text instead of the 12 / 26 / 28dp trio the review measured.
     */
    val ContentAxis = PageHorizontal + CardInner

    /**
     * Extra gap above a section header (P1-2). Combined with [CardSpacing] the header ends up
     * 24dp below the previous block and 12dp above the group it introduces, which is what makes
     * the header read as belonging to the cards underneath it.
     */
    val SectionTop = 12.dp

    /**
     * Material's `LargeFlexibleTopAppBar` insets its title slot by 16dp on this device (measured:
     * dump X1 = 16dp), so this extra inset lands the title on [ContentAxis].
     */
    val TopBarTitleExtraInset = ContentAxis - 16.dp

    /** Bottom gap of an info row; the last row of a card relies on [CardInner] instead. */
    val InfoRowBottom = 16.dp

    /**
     * Success watermark (the big check ring on the status card) — P1-1.
     *
     * It is anchored to the card's bottom-end corner, so it must be *inset* from that corner
     * ([StatusBadgeInsetEnd] / [StatusBadgeInsetBottom]) rather than offset outwards: the card
     * clips its content to its own rounded shape, and the previous `offset(27.dp, 31.dp)` cut the
     * ring open at the bottom-right corner (see design/home-ui-review.md §7 P1-1).
     */
    val StatusBadge = 96.dp
    val StatusBadgeInsetEnd = 16.dp
    val StatusBadgeInsetBottom = 12.dp

    /**
     * Status-card inner paddings (status-card unification, §7.2-1 / §14.5).
     *
     * The card has three content slots — the text block (top-start), the working-mode label
     * (bottom-start) and the [StatusBadge] watermark (bottom-end). They are pinned here because
     * the Material card used to be an M3 `ListItem`, whose leading icon slot pushed the text out
     * to X1 = 64dp while the Miuix text sits on [ContentAxis] (28dp). Material now mirrors the
     * Miuix slots, so neither screen may inline these numbers again.
     *
     * The card height follows the watermark: [StatusBadge] + [StatusBadgeInsetBottom] = 108dp.
     */
    val StatusContentPaddingH = 16.dp
    val StatusContentPaddingV = 14.dp
    val StatusModePaddingH = 16.dp
    val StatusModePaddingV = 10.dp

    /**
     * Height of the status card body: [StatusBadge] + [StatusBadgeInsetBottom] = 108dp.
     *
     * Miuix reaches it through `Row(height(IntrinsicSize.Min))`, which hands the card a definite
     * height. The Material mirror states it explicitly, because its three slots are `fillMaxSize()`
     * children of a plain `Box`: with only the unbounded scroll constraints coming in, that `Box`
     * measured the slots against an undefined height and the bottom-start mode label collapsed onto
     * the top edge (device measurement of the first attempt: y = +10dp from the card top, clipped to
     * 4dp, instead of +79dp / 19dp).
     */
    val StatusCardHeight = StatusBadge + StatusBadgeInsetBottom

    /** Gap between the status title and the version line under it. */
    val StatusTitleGap = 1.dp
}
