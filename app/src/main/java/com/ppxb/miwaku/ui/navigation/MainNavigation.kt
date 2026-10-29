package com.ppxb.miwaku.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.ppxb.miwaku.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object HomeRoute

@Serializable
data object FavoritesRoute

@Serializable
data object ExploreRoute

@Serializable
data object CategoryRoute

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute

@Serializable
data object DownloadsRoute

@Serializable
data class DetailRoute(val id: String)

@Serializable
data class ReaderRoute(
    val albumId: String,
    val chapterId: String,
    val page: Int = 0,
)

/** The four bottom-bar destinations, in display order. */
enum class MainTab(
    val route: Any,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home(HomeRoute, R.string.tab_home, Icons.Rounded.Home),
    Favorites(FavoritesRoute, R.string.tab_favorites, Icons.Rounded.Favorite),
    Explore(ExploreRoute, R.string.tab_explore, Icons.Rounded.Explore),
    Category(CategoryRoute, R.string.tab_category, Icons.Rounded.Category),
}

/** True when the current destination, or any of its parent graphs, matches [route]. */
fun NavDestination?.isOn(route: Any): Boolean =
    this?.hierarchy?.any { it.hasRoute(route::class) } == true

fun NavDestination?.isOn(route: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(route) } == true

/** Switches tabs while keeping every tab's scroll/input state for the next visit. */
fun NavController.navigateToTab(tab: MainTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Search is a top-level destination like the tabs: opening it parks the current tab's state
 * instead of stacking on top of it, so switching back never lands on a stale previous page.
 */
fun NavController.navigateToSearch() {
    navigate(SearchRoute) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

fun NavController.navigateToDetail(id: String) {
    navigate(DetailRoute(id)) { launchSingleTop = true }
}

fun NavController.navigateToReader(
    albumId: String,
    chapterId: String,
    page: Int = 0,
    replace: Boolean = false,
) {
    val readerEntry =
        currentBackStackEntry?.takeIf { it.destination.hasRoute(ReaderRoute::class) }
    if (replace) {
        // A stale auto-advance callback must never push a reader on top of another screen.
        if (readerEntry == null) return
        if (readerEntry.savedStateHandle.get<String>("albumId") == albumId &&
            readerEntry.savedStateHandle.get<String>("chapterId") == chapterId
        ) {
            return
        }
    }
    navigate(ReaderRoute(albumId, chapterId, page)) {
        launchSingleTop = true
        if (replace && readerEntry != null) {
            popUpTo(readerEntry.destination.id) { inclusive = true }
        }
    }
}
