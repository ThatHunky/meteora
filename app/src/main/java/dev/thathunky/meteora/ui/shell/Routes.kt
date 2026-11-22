package dev.thathunky.meteora.ui.shell

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable sealed interface Route : NavKey

@Serializable data object HomeRoute : Route

@Serializable data object SearchRoute : Route

@Serializable data object LibraryRoute : Route

@Serializable data object SettingsRoute : Route

@Serializable data object PairingRoute : Route

@Serializable data class AlbumRoute(val browseId: String) : Route

@Serializable data class PlaylistRoute(val playlistId: String) : Route

@Serializable data class ArtistRoute(val browseId: String) : Route

@Serializable data object SignInRoute : Route

@Serializable data object DownloadsRoute : Route

// Settings sub-screens, pushed over SettingsRoute so Settings stays the selected tab.
@Serializable data object SettingsAppearanceRoute : Route

@Serializable data object SettingsPlaybackRoute : Route

@Serializable data object SettingsAccountRoute : Route

@Serializable data object SettingsPcRoute : Route

@Serializable data object SettingsAboutRoute : Route

@Serializable data object SettingsFiltersRoute : Route
