package com.ppxb.miwaku.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ppxb.miwaku.data.settings.ThemeMode
import com.ppxb.miwaku.ui.component.AppBottomBar
import com.ppxb.miwaku.ui.navigation.CategoryRoute
import com.ppxb.miwaku.ui.navigation.ExploreRoute
import com.ppxb.miwaku.ui.navigation.FavoritesRoute
import com.ppxb.miwaku.ui.navigation.HomeRoute
import com.ppxb.miwaku.ui.navigation.MainTab
import com.ppxb.miwaku.ui.navigation.SearchRoute
import com.ppxb.miwaku.ui.navigation.SettingsRoute
import com.ppxb.miwaku.ui.navigation.isOn
import com.ppxb.miwaku.ui.navigation.navigateToSearch
import com.ppxb.miwaku.ui.navigation.navigateToTab
import com.ppxb.miwaku.ui.screen.category.CategoryScreen
import com.ppxb.miwaku.ui.screen.explore.ExploreScreen
import com.ppxb.miwaku.ui.screen.favorites.FavoritesScreen
import com.ppxb.miwaku.ui.screen.home.HomeScreen
import com.ppxb.miwaku.ui.screen.search.SearchScreen
import com.ppxb.miwaku.ui.screen.settings.SettingsScreen

@Composable
fun MiwakuApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    liquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
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
    // Settings is a detail page with its own back arrow, so the bar steps aside.
    val showBottomBar = !destination.isOn(SettingsRoute)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
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
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MainTab.Home.route,
            // Every destination enters from the left and leaves to the right, fading in step;
            // push and pop share the motion, so tab switches and back feel consistent.
            enterTransition = { fadeIn() + slideInHorizontally { -it } },
            exitTransition = { fadeOut() + slideOutHorizontally { it } },
            popEnterTransition = { fadeIn() + slideInHorizontally { -it } },
            popExitTransition = { fadeOut() + slideOutHorizontally { it } },
            modifier =
                Modifier
                    .fillMaxSize()
                    // Edge-to-edge: pages paint their backgrounds (Settings' glow included) under
                    // the status bar and inset only their content. The bar still reserves the
                    // bottom, and when it is hidden this collapses to the system bar inset.
                    .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable<HomeRoute> {
                HomeScreen(onOpenSettings = { navController.navigate(SettingsRoute) })
            }
            composable<FavoritesRoute> { FavoritesScreen() }
            composable<ExploreRoute> { ExploreScreen() }
            composable<CategoryRoute> { CategoryScreen() }
            composable<SearchRoute> { SearchScreen() }
            composable<SettingsRoute> {
                SettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    liquidGlass = liquidGlass,
                    onLiquidGlassChange = onLiquidGlassChange,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
