package com.ppxb.miwaku.ui.reader.viewer

/**
 * Which third of the screen a tap landed in. Only paged modes use this: a webtoon tap has nowhere
 * to page to, so it always means "menu".
 */
enum class TapZone { Start, Center, End }

/** What the shell should do for a tap. Direction is the viewer's business, not the zone's. */
enum class NavigationAction { Previous, Next, Menu }

/**
 * Maps a tap to a zone. Mirrors komikku's `ViewerNavigation`: the two outer thirds turn the page and
 * the middle third opens the menu, so a reader never needs to aim at a small target.
 */
object ViewerNavigation {
    private const val EDGE_FRACTION = 1f / 3f

    fun zoneOf(
        x: Float,
        width: Float,
    ): TapZone {
        if (width <= 0f) return TapZone.Center
        val fraction = (x / width).coerceIn(0f, 1f)
        return when {
            fraction < EDGE_FRACTION -> TapZone.Start
            fraction > 1f - EDGE_FRACTION -> TapZone.End
            else -> TapZone.Center
        }
    }

    fun actionFor(zone: TapZone): NavigationAction =
        when (zone) {
            TapZone.Start -> NavigationAction.Previous
            TapZone.End -> NavigationAction.Next
            TapZone.Center -> NavigationAction.Menu
        }
}
