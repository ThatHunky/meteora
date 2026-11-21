package dev.thathunky.meteora.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.thathunky.meteora.ui.player.PlayerHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.thathunky.meteora.R
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.ui.debug.DebugPlayerCard
import dev.thathunky.meteora.ui.pairing.PairingScreen

private data class Tab(val route: Route, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, R.string.nav_home, Icons.Outlined.Home),
    Tab(SearchRoute, R.string.nav_search, Icons.Outlined.Search),
    Tab(LibraryRoute, R.string.nav_library, Icons.Outlined.LibraryMusic),
    Tab(SettingsRoute, R.string.nav_settings, Icons.Outlined.Settings),
)

@Composable
fun MeteoraShell(vm: ShellViewModel = hiltViewModel()) {
    val backStack = rememberNavBackStack(HomeRoute)
    val connection by vm.connection.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val pc by vm.pairedPc.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        vm.onForeground()
        onStopOrDispose { vm.onBackground() }
    }

    val needsPairing = connection == ConnectionState.Unpaired || connection == ConnectionState.Unauthorized
    LaunchedEffect(needsPairing) {
        if (needsPairing && backStack.lastOrNull() != PairingRoute) backStack.add(PairingRoute)
    }

    val inPairing = backStack.lastOrNull() == PairingRoute
    val currentTab = tabs.firstOrNull { it.route == backStack.firstOrNull() } ?: tabs.first()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            if (!inPairing) {
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
            }
        },
    ) { padding ->
        bottomBarHeight = padding.calculateBottomPadding()
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            modifier = Modifier.padding(padding),
            entryProvider = entryProvider {
                entry<HomeRoute> {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        if (playback?.track == null) DebugPlayerCard(connection, playback, vm::send)
                        else Placeholder("M5")
                    }
                }
                entry<SearchRoute> { Placeholder("M4") }
                entry<LibraryRoute> { Placeholder("M5") }
                entry<SettingsRoute> {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ListItem(
                            headlineContent = { Text(pc?.name ?: stringResource(R.string.not_connected)) },
                            supportingContent = { Text(pc?.preferredOrder()?.joinToString(" · ").orEmpty()) },
                            overlineContent = { Text(stringResource(R.string.settings_pc)) },
                        )
                        Button(onClick = { backStack.add(PairingRoute) }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.settings_repair))
                        }
                        OutlinedButton(onClick = vm::forgetPc) { Text(stringResource(R.string.settings_forget)) }
                        Text(
                            stringResource(R.string.settings_forget_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                entry<PairingRoute> {
                    PairingScreen(onDone = {
                        backStack.clear()
                        backStack.add(HomeRoute)
                    })
                }
            },
        )
    }
    if (!inPairing) {
        PlayerHost(
            expanded = playerExpanded,
            onExpandedChange = { playerExpanded = it },
            miniBottomPadding = bottomBarHeight,
            modifier = Modifier.fillMaxSize(),
        )
    }
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
