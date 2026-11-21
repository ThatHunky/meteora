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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.thathunky.meteora.R
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.ui.browse.ActionsViewModel
import dev.thathunky.meteora.ui.browse.ArtistScreen
import dev.thathunky.meteora.ui.browse.CollectionScreen
import dev.thathunky.meteora.ui.browse.ItemAction
import dev.thathunky.meteora.ui.browse.ItemActionsSheet
import dev.thathunky.meteora.ui.browse.Opener
import dev.thathunky.meteora.ui.browse.SearchScreen
import dev.thathunky.meteora.ui.browse.HomeScreen
import dev.thathunky.meteora.ui.browse.LibraryScreen
import dev.thathunky.meteora.ui.signin.SignInScreen
import dev.thathunky.meteora.ui.pairing.PairingScreen
import dev.thathunky.meteora.ui.player.PlayerHost
import dev.thathunky.meteora.ytm.MusicItem

private data class Tab(val route: Route, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, R.string.nav_home, Icons.Outlined.Home),
    Tab(SearchRoute, R.string.nav_search, Icons.Outlined.Search),
    Tab(LibraryRoute, R.string.nav_library, Icons.Outlined.LibraryMusic),
    Tab(SettingsRoute, R.string.nav_settings, Icons.Outlined.Settings),
)

/** Room left under scrolling content for the floating mini player. */
private val MiniPlayerSpace = 88.dp

@Composable
fun MeteoraShell(vm: ShellViewModel = hiltViewModel(), actionsVm: ActionsViewModel = hiltViewModel()) {
    val backStack = rememberNavBackStack(HomeRoute)
    val connection by vm.connection.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val pc by vm.pairedPc.collectAsStateWithLifecycle()
    val actions = actionsVm.actions

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
    var sheetFor by remember { mutableStateOf<MusicItem?>(null) }
    val hasPlayer = playback?.track != null
    val contentBottom = if (hasPlayer) MiniPlayerSpace else 0.dp

    val opener = Opener { item ->
        when (item) {
            is MusicItem.Song -> actions.play(item)
            is MusicItem.Album -> backStack.add(AlbumRoute(item.browseId))
            is MusicItem.Playlist -> backStack.add(PlaylistRoute(item.playlistId))
            is MusicItem.Artist -> backStack.add(ArtistRoute(item.browseId))
        }
    }
    val longPress: (MusicItem) -> Unit = { sheetFor = it }

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
                                    } else if (backStack.size > 1) {
                                        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
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
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = entryProvider {
                    entry<HomeRoute> {
                        HomeScreen(opener, longPress, onSignIn = { backStack.add(SignInRoute) }, contentBottom, header = {
                            if (connection !is ConnectionState.Connected) ConnectionBanner(connection) { vm.reconnect() }
                        })
                    }
                    entry<SearchRoute> { SearchScreen(opener, longPress, contentBottom) }
                    entry<LibraryRoute> {
                        LibraryScreen(
                            opener, longPress,
                            onSignIn = { backStack.add(SignInRoute) },
                            onPlayLiked = { shuffle, ids ->
                                if (shuffle) actions.shuffleCollection("LM", ids)
                                else ids.firstOrNull()?.let { actions.playCollectionTrack("LM", it) }
                            },
                            bottomPadding = contentBottom,
                        )
                    }
                    entry<SignInRoute> { SignInScreen(onDone = { backStack.removeAt(backStack.lastIndex) }) }
                    entry<AlbumRoute> { r -> CollectionScreen(r.browseId, isAlbum = true, opener, longPress, contentBottom) }
                    entry<PlaylistRoute> { r -> CollectionScreen(r.playlistId, isAlbum = false, opener, longPress, contentBottom) }
                    entry<ArtistRoute> { r -> ArtistScreen(r.browseId, opener, longPress, contentBottom) }
                    entry<SettingsRoute> {
                        val ytmSignedIn by vm.ytmSignedIn.collectAsStateWithLifecycle()
                        SettingsScreen(pc?.name, pc?.preferredOrder(), backStack, vm::forgetPc, ytmSignedIn, vm::signOutYtm)
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

    sheetFor?.let { item ->
        ItemActionsSheet(
            item = item,
            onAction = { a ->
                when (a) {
                    ItemAction.PlayNext -> actions.playNext(item)
                    ItemAction.AddToQueue -> actions.addToQueue(item)
                    ItemAction.Radio -> (item as? MusicItem.Song)?.let(actions::radio)
                    ItemAction.GoToAlbum -> (item as? MusicItem.Song)?.album?.browseId?.let { backStack.add(AlbumRoute(it)) }
                    ItemAction.GoToArtist -> (item as? MusicItem.Song)?.artists?.firstNotNullOfOrNull { it.browseId }?.let { backStack.add(ArtistRoute(it)) }
                }
            },
            onDismiss = { sheetFor = null },
        )
    }
}

@Composable
private fun SettingsScreen(
    name: String?,
    addresses: List<String>?,
    backStack: NavBackStack<NavKey>,
    onForget: () -> Unit,
    ytmSignedIn: Boolean,
    onSignOut: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListItem(
            headlineContent = { Text(stringResource(if (ytmSignedIn) R.string.ytm_signed_in else R.string.ytm_signed_out)) },
            overlineContent = { Text(stringResource(R.string.ytm_account)) },
        )
        if (ytmSignedIn) OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.signout_action)) }
        else Button(onClick = { backStack.add(SignInRoute) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.signin_action)) }
        ListItem(
            headlineContent = { Text(name ?: stringResource(R.string.not_connected)) },
            supportingContent = { Text(addresses?.joinToString(" · ").orEmpty()) },
            overlineContent = { Text(stringResource(R.string.settings_pc)) },
        )
        Button(onClick = { backStack.add(PairingRoute) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.settings_repair))
        }
        OutlinedButton(onClick = onForget) { Text(stringResource(R.string.settings_forget)) }
        Text(
            stringResource(R.string.settings_forget_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ConnectionBanner(connection: ConnectionState, onRetry: () -> Unit) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.padding(16.dp),
    ) {
        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    when (connection) {
                        ConnectionState.Connecting, ConnectionState.Idle -> R.string.reconnecting
                        else -> R.string.pc_offline
                    },
                ),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (connection is ConnectionState.Offline) {
                androidx.compose.material3.TextButton(onClick = onRetry) { Text(stringResource(R.string.action_reconnect)) }
            }
        }
    }
}
