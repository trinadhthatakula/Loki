package com.valhalla.loki.model

import com.valhalla.loki.R

data class NavItem(
    val title: String = "Apps",
    val icon: Int = R.drawable.apps
)

val navItems = listOf(
    NavItem(),
    NavItem("Saved", R.drawable.folder_check),
    NavItem("Settings", R.drawable.settings_filled)
)

