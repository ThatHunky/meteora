package dev.thathunky.meteora.ui.shell

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable sealed interface Route : NavKey

@Serializable data object HomeRoute : Route

@Serializable data object SearchRoute : Route

@Serializable data object LibraryRoute : Route

@Serializable data object SettingsRoute : Route

@Serializable data object PairingRoute : Route
