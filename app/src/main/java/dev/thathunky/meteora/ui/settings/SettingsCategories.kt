package dev.thathunky.meteora.ui.settings

/** The rows of the top-level Settings list; each opens its own sub-screen. */
enum class SettingsCategory { Appearance, Playback, Filters, Account, Pc, About }

/** In this order: what people change most first, the notice and version last. */
fun settingsCategories(): List<SettingsCategory> = listOf(
    SettingsCategory.Appearance,
    SettingsCategory.Playback,
    SettingsCategory.Filters,
    SettingsCategory.Account,
    SettingsCategory.Pc,
    SettingsCategory.About,
)
