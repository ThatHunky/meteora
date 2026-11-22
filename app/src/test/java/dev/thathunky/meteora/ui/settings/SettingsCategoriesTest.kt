package dev.thathunky.meteora.ui.settings

import dev.thathunky.meteora.ui.settings.SettingsCategory.About
import dev.thathunky.meteora.ui.settings.SettingsCategory.Account
import dev.thathunky.meteora.ui.settings.SettingsCategory.Appearance
import dev.thathunky.meteora.ui.settings.SettingsCategory.Pc
import dev.thathunky.meteora.ui.settings.SettingsCategory.Playback
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoriesTest {
    @Test fun `categories in order`() =
        assertEquals(listOf(Appearance, Playback, SettingsCategory.Filters, Account, Pc, About), settingsCategories())

    @Test fun `every category is listed once`() =
        assertEquals(SettingsCategory.entries.toSet(), settingsCategories().toSet().also { assertEquals(it.size, settingsCategories().size) })
}
