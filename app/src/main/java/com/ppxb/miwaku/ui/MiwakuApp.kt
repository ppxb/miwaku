package com.ppxb.miwaku.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ppxb.miwaku.core.data.settings.ThemeMode
import com.ppxb.miwaku.ui.component.AppBottomBar
import com.ppxb.miwaku.ui.navigation.CategoryRoute
import com.ppxb.miwaku.ui.navigation.DetailRoute
import com.ppxb.miwaku.ui.navigation.ExploreRoute
import com.ppxb.miwaku.ui.navigation.FavoritesRoute
import com.ppxb.miwaku.ui.navigation.HomeRoute
import com.ppxb.miwaku.ui.navigation.MainTab
import com.ppxb.miwaku.ui.navigation.ReaderRoute
import com.ppxb.miwaku.ui.navigation.SearchRoute
import com.ppxb.miwaku.ui.navigation.SettingsRoute
import com.ppxb.miwaku.ui.navigation.isOn
import com.ppxb.miwaku.ui.navigation.navigateToDetail
import com.ppxb.miwaku.ui.navigation.navigateToReader
import com.ppxb.miwaku.ui.navigation.navigateToSearch
import com.ppxb.miwaku.ui.navigation.navigateToTab
import com.ppxb.miwaku.ui.screen.category.CategoryScreen
import com.ppxb.miwaku.ui.screen.detail.DetailScreen
import com.ppxb.miwaku.ui.screen.explore.ExploreScreen
import com.ppxb.miwaku.ui.screen.favorites.FavoritesScreen
import com.ppxb.miwaku.ui.screen.home.HomeScreen
import com.ppxb.miwaku.ui.screen.reader.ReaderScreen
import com.ppxb.miwaku.ui.screen.search.SearchScreen
import com.ppxb.miwaku.ui.screen.settings.SettingsScreen

@Composable
fun MiwakuApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    liquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    placeholderCovers: Boolean,
    onPlaceholderCoversChange: (Boolean) -> Unit,
    prefetchAhead: Int,
    onPrefetchAheadChange: (Int) -> Unit,
    wifiOnlyPrefetch: Boolean,
    onWifiOnlyPrefetchChange: (Boolean) -> Unit,
    lowMemory: Boolean,
    onLowMemoryChange: (Boolean) -> Unit,
    cacheLimitMb: Int,
    onCacheLimitMbChange: (Int) -> Unit,
) {
    val navController = rememberNavController()
    val currentDestination by navController.currentBackStackEntryAsState()
    val destination = currentDestination?.destination

    // Search is opened from the search button, not the capsule, so no tab is lit there.
    // Other nested pages (Settings today) keep the tab they were opened from.
    val selectedTab =
        when {
            destination.isOn(SearchRoute) -> null
            else -> MainTab.entries.firstOrNull { destination.isOn(it.route) } ?: MainTab.Home
        }
    // Settings, detail and reader are nested pages with their own back arrow, so the bar steps aside.
    val showBottomBar =
        !destination.isOn(SettingsRoute) &&
            !destination.isOn(DetailRoute::class) &&
            !destination.isOn(ReaderRoute::class)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = navController,
            startDestination = MainTab.Home.route,
            // Every destination enters from the left and leaves to the right, fading in step;
            // push and pop share the motion, so tab switches and back feel consistent.
            enterTransition = { fadeIn() + slideInHorizontally { -it } },
            exitTransition = { fadeOut() + slideOutHorizontally { it } },
            popEnterTransition = { fadeIn() + slideInHorizontally { -it } },
            popExitTransition = { fadeOut() + slideOutHorizontally { it } },
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenSettings = {
                        navController.navigate(SettingsRoute) { launchSingleTop = true }
                    },
                    onOpenComic = { navController.navigateToDetail(it.id) },
                    placeholderCovers = placeholderCovers,
                )
            }
            composable<FavoritesRoute> { FavoritesScreen() }
            composable<ExploreRoute> {
                ExploreScreen(
                    onOpenComic = { navController.navigateToDetail(it.id) },
                    placeholderCovers = placeholderCovers,
                )
            }
            composable<CategoryRoute> {
                CategoryScreen(
                    onOpenComic = { navController.navigateToDetail(it.id) },
                    placeholderCovers = placeholderCovers,
                )
            }
            composable<SearchRoute> {
                SearchScreen(
                    onOpenComic = { navController.navigateToDetail(it.id) },
                    placeholderCovers = placeholderCovers,
                )
            }
            composable<DetailRoute> {
                DetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { albumId, chapterId, page ->
                        navController.navigateToReader(albumId, chapterId, page)
                    },
                    placeholderCovers = placeholderCovers,
                )
            }
            composable<ReaderRoute> {
                ReaderScreen(
                    onBack = { navController.popBackStack() },
                    onNextChapter = { albumId, chapterId ->
                        navController.navigateToReader(albumId, chapterId, replace = true)
                    },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    liquidGlass = liquidGlass,
                    onLiquidGlassChange = onLiquidGlassChange,
                    placeholderCovers = placeholderCovers,
                    onPlaceholderCoversChange = onPlaceholderCoversChange,
                    prefetchAhead = prefetchAhead,
                    onPrefetchAheadChange = onPrefetchAheadChange,
                    wifiOnlyPrefetch = wifiOnlyPrefetch,
                    onWifiOnlyPrefetchChange = onWifiOnlyPrefetchChange,
                    lowMemory = lowMemory,
                    onLowMemoryChange = onLowMemoryChange,
                    cacheLimitMb = cacheLimitMb,
                    onCacheLimitMbChange = onCacheLimitMbChange,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        AnimatedVisibility(
            visible = showBottomBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            AppBottomBar(
                selectedTab = selectedTab,
                onTabSelect = { navController.navigateToTab(it) },
                searchSelected = destination.isOn(SearchRoute),
                onSearchClick = { navController.navigateToSearch() },
                liquidGlass = liquidGlass,
            )
        }
    }
}
