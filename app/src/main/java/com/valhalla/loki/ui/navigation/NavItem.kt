package com.valhalla.loki.ui.navigation

import com.valhalla.loki.R

/**
 * One entry in the bottom navigation bar.
 *
 * [route] is part of the item rather than derived from its index so the bar and the back stacks
 * cannot drift apart: `HomeScreen` builds one stack per item from this list, in this order, and
 * seeds each with the item's own route.
 */
data class NavItem(
    val title: String = "Apps",
    val icon: Int = R.drawable.apps,
    val route: LokiRoute = LokiRoute.Apps,
)

val navItems = listOf(
    NavItem(),
    NavItem("Saved", R.drawable.folder_check, LokiRoute.Saved),
    NavItem("Settings", R.drawable.settings_filled, LokiRoute.Settings),
)
