package dev.thathunky.meteora.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import dev.thathunky.meteora.share.share
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
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
import dev.thathunky.meteora.ui.components.MeteoraNavBar
import dev.thathunky.meteora.ui.components.NavItem
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.stream.StreamQuality
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
import kotlinx.coroutines.delay

private data class Tab(val route: Route, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, R.string.nav_home, Icons.Outlined.Home),
    Tab(SearchRoute, R.string.nav_search, Icons.Outlined.Search),
    Tab(LibraryRoute, R.string.nav_library, Icons.Outlined.LibraryMusic),
    Tab(SettingsRoute, R.string.nav_settings, Icons.Outlined.Settings),
)

@Composable
fun MeteoraShell(vm: ShellViewModel = hiltViewModel(), actionsVm: ActionsViewModel = hiltViewModel()) {
    // One back stack per tab, so switching tabs keeps each tab's opened pages, scroll positions
    // and view models (Home no longer reloads, Search keeps its results). All are saveable.
    val stacks: List<NavBackStack<NavKey>> = tabs.map { rememberNavBackStack(it.route) }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val backStack = stacks[tabIndex]
    // Read at call time, so callbacks made in an earlier composition still act on the current tab.
    val push: (Route) -> Unit = { route ->
        val s = stacks[tabIndex]
        // A quick double tap would otherwise open the same page twice.
        if (s.lastOrNull() != route) s.add(route)
    }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val hasPlayer by vm.hasTrack.collectAsStateWithLifecycle()
    val pc by vm.pairedPc.collectAsStateWithLifecycle()
    val actions = actionsVm.actions
    val context = LocalContext.current

    LifecycleStartEffect(Unit) {
        vm.onForeground()
        onStopOrDispose { vm.onBackground() }
    }

    val needsPairing = connection == ConnectionState.Unpaired || connection == ConnectionState.Unauthorized
    LaunchedEffect(needsPairing) {
        if (needsPairing) push(PairingRoute)
    }

    val inPairing = backStack.lastOrNull() == PairingRoute || backStack.lastOrNull() == SignInRoute
    val currentTab = tabs[tabIndex]
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    var sheetFor by remember { mutableStateOf<MusicItem?>(null) }
    // The mini player is drawn over the content, right on top of the tabs (one dock), so content
    // leaves its row free. The tabs themselves are already in the Scaffold's padding.
    val contentBottom = if (hasPlayer) LocalLook.current.miniSpace else 0.dp
    // PlayerHost draws nothing without a track. Without this, a player that was open when the
    // track went away would come back full-screen on the next track.
    // After a pause, because a hand-off between PC and phone has no track for a moment.
    LaunchedEffect(hasPlayer) {
        if (!hasPlayer) {
            delay(1_500)
            playerExpanded = false
        }
    }

    val opener = Opener { item ->
        when (item) {
            is MusicItem.Song -> actions.play(item)
            is MusicItem.Album -> push(AlbumRoute(item.browseId))
            is MusicItem.Playlist -> push(PlaylistRoute(item.playlistId))
            is MusicItem.Artist -> push(ArtistRoute(item.browseId))
        }
    }
    val longPress: (MusicItem) -> Unit = { sheetFor = it }
    val goBack: () -> Unit = {
        val s = stacks[tabIndex]
        if (s.size > 1) s.removeAt(s.lastIndex)
    }
    // Pairing and sign-in are removed by key: popping "the last entry" would take the wrong page
    // if something else had been pushed on top meanwhile, or empty a stack.
    val leave: (Route) -> Unit = { route -> stacks.forEach { s -> if (s.size > 1) s.removeAll { it == route } } }

    // Back on another tab's first page goes to Home, as in the official app, instead of leaving.
    BackHandler(enabled = tabIndex != 0 && backStack.size == 1 && !playerExpanded) { tabIndex = 0 }

    val provider = entryProvider<NavKey> {
        entry<HomeRoute> {
            HomeScreen(opener, longPress, onSignIn = { push(SignInRoute) }, contentBottom, header = {
                if (connection !is ConnectionState.Connected && connection != ConnectionState.Local) ConnectionBanner(connection) { vm.reconnect() }
            })
        }
        entry<SearchRoute> { SearchScreen(opener, longPress, contentBottom) }
        entry<LibraryRoute> {
            LibraryScreen(
                opener, longPress,
                onSignIn = { push(SignInRoute) },
                onPlayLiked = { shuffle, ids ->
                    if (shuffle) actions.shuffleCollection("LM", ids)
                    else ids.firstOrNull()?.let { actions.playCollectionTrack("LM", it) }
                },
                bottomPadding = contentBottom,
            )
        }
        entry<SignInRoute> { SignInScreen(onDone = { leave(SignInRoute) }) }
        entry<AlbumRoute> { r -> CollectionScreen(r.browseId, isAlbum = true, opener, longPress, contentBottom) }
        entry<PlaylistRoute> { r -> CollectionScreen(r.playlistId, isAlbum = false, opener, longPress, contentBottom) }
        entry<ArtistRoute> { r -> ArtistScreen(r.browseId, opener, longPress, contentBottom) }
        entry<SettingsRoute> { SettingsScreen(vm, push, contentBottom) }
        entry<SettingsAppearanceRoute> { AppearanceSettingsScreen(goBack, contentBottom) }
        entry<SettingsPlaybackRoute> { PlaybackSettingsScreen(vm, goBack, contentBottom) }
        entry<SettingsAccountRoute> { AccountSettingsScreen(vm, push, goBack, contentBottom) }
        entry<SettingsPcRoute> { PcSettingsScreen(vm, push, goBack, contentBottom) }
        entry<SettingsAboutRoute> { AboutSettingsScreen(goBack, contentBottom) }
        entry<PairingRoute> {
            // Back to where pairing was opened from: Home on first start, Settings → PC on re-pair.
            PairingScreen(
                onDone = { leave(PairingRoute) },
                onSkip = {
                    vm.setTarget(dev.thathunky.meteora.data.PlaybackTarget.Phone)
                    leave(PairingRoute)
                },
            )
        }
    }
    // Each tab's entries keep their saved state and view models while another tab shows; they are
    // dropped only when popped from their own stack. The tab is part of the content key, so the
    // same album open in two tabs gets two states and two view models.
    val entries = stacks.mapIndexed { i, stack ->
        rememberDecoratedNavEntries(
            backStack = stack,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = { key ->
                val e = provider(key)
                NavEntry(key, contentKey = "$i:${e.contentKey}", metadata = e.metadata) { e.Content() }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (!inPairing) {
                    val items = tabs.map { NavItem(stringResource(it.label), it.icon) }
                    // With a track, the mini player row is the top of the dock and carries its corners.
                    MeteoraNavBar(items, tabIndex, top = !hasPlayer, onSelect = { i ->
                        if (i != tabIndex) {
                            tabIndex = i
                        } else {
                            // The selected tab again: back to its first page.
                            val s = stacks[i]
                            while (s.size > 1) s.removeAt(s.lastIndex)
                        }
                    })
                }
            },
        ) { padding ->
            bottomBarHeight = padding.calculateBottomPadding()
            NavDisplay(
                entries = entries[tabIndex],
                onBack = goBack,
                modifier = Modifier.padding(padding),
            )
        }
        if (!inPairing) {
            PlayerHost(
                expanded = playerExpanded,
                onExpandedChange = { playerExpanded = it },
                miniBottomPadding = bottomBarHeight,
                // The player covers the pages: collapse it so the opened page shows.
                onOpenAlbum = { id ->
                    playerExpanded = false
                    push(AlbumRoute(id))
                },
                onOpenArtist = { id ->
                    playerExpanded = false
                    push(ArtistRoute(id))
                },
                onRadio = actions::radioFromCurrent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    sheetFor?.let { item ->
        ItemActionsSheet(
            item = item,
            onAction = { a ->
                when (a) {
                    ItemAction.Play -> actions.play(item)
                    ItemAction.Shuffle -> actions.shuffle(item)
                    ItemAction.PlayNext -> actions.playNext(item)
                    ItemAction.AddToQueue -> actions.addToQueue(item)
                    ItemAction.Radio -> actions.radio(item)
                    ItemAction.Share -> context.share(item)
                    ItemAction.GoToAlbum -> (item as? MusicItem.Song)?.album?.browseId?.let { push(AlbumRoute(it)) }
                    ItemAction.GoToArtist -> (item as? MusicItem.Song)?.artists?.firstNotNullOfOrNull { it.browseId }?.let { push(ArtistRoute(it)) }
                }
            },
            onDismiss = { sheetFor = null },
        )
    }
}

@Composable
private fun ConnectionBanner(connection: ConnectionState, onRetry: () -> Unit) {
    dev.thathunky.meteora.ui.components.MSurface(
        tone = dev.thathunky.meteora.ui.components.Tone.Error,
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
                color = dev.thathunky.meteora.ui.components.surfaceContentColor(dev.thathunky.meteora.ui.components.Tone.Error),
                modifier = Modifier.weight(1f),
            )
            if (connection is ConnectionState.Offline) {
                dev.thathunky.meteora.ui.components.MButton(stringResource(R.string.action_reconnect), onRetry, kind = dev.thathunky.meteora.ui.components.ButtonKind.Text)
            }
        }
    }
}
