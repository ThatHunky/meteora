package dev.thathunky.meteora.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.thathunky.meteora.R

private data class Tab(val route: Route, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, R.string.nav_home, Icons.Outlined.Home),
    Tab(SearchRoute, R.string.nav_search, Icons.Outlined.Search),
    Tab(LibraryRoute, R.string.nav_library, Icons.Outlined.LibraryMusic),
    Tab(SettingsRoute, R.string.nav_settings, Icons.Outlined.Settings),
)

@Composable
fun MeteoraShell() {
    val backStack = rememberNavBackStack(HomeRoute)
    val currentTab = tabs.firstOrNull { it.route == backStack.firstOrNull() } ?: tabs.first()

    Scaffold(
        bottomBar = {
            ShortNavigationBar {
                tabs.forEach { tab ->
                    ShortNavigationBarItem(
                        selected = tab == currentTab,
                        onClick = {
                            if (tab != currentTab) {
                                backStack.clear()
                                backStack.add(tab.route)
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            modifier = Modifier.padding(padding),
            entryProvider = entryProvider {
                entry<HomeRoute> { Placeholder("M5") }
                entry<SearchRoute> { Placeholder("M4") }
                entry<LibraryRoute> { Placeholder("M5") }
                entry<SettingsRoute> { Placeholder("M1") }
            },
        )
    }
}

@Composable
private fun Placeholder(milestone: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator()
        Text(
            stringResource(R.string.placeholder_coming, milestone),
            style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.align(Alignment.BottomCenter).padding(48.dp),
        )
    }
}
