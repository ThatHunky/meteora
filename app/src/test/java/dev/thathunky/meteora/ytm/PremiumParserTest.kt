package dev.thathunky.meteora.ytm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumParserTest {
    private fun fixture(name: String) =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/ytm/$name")) { name }.readText())

    // As the signed-out page has it (2026-11-22); a Premium page is expected to say true.
    private val freePage = """..."LOGOUT_URL":"/logout","IS_SUBSCRIBER":false,"IS_MOBILE_WEB":false,..."LOGGED_IN":false,"INNERTUBE_CLIENT_VERSION":"1.20261117.06.00","""
    private val premiumPage = freePage.replace("\"IS_SUBSCRIBER\":false", "\"IS_SUBSCRIBER\":true").replace("\"LOGGED_IN\":false", "\"LOGGED_IN\":true")

    @Test fun `page flags`() {
        assertEquals(false, PremiumParser.subscriber(freePage))
        assertEquals(false, PremiumParser.loggedIn(freePage))
        assertEquals(true, PremiumParser.subscriber(premiumPage))
        assertEquals("1.20261117.06.00", PremiumParser.clientVersion(freePage))
        assertNull(PremiumParser.subscriber("<html>no config</html>"))
    }

    @Test fun `free account menu offers the upgrade`() = assertEquals(true, PremiumParser.upgradeOffered(fixture("account-menu-free.json")))

    @Test fun `premium account menu offers none`() = assertEquals(false, PremiumParser.upgradeOffered(fixture("account-menu-premium.json")))

    @Test fun `something that is not a menu is unknown`() = assertNull(PremiumParser.upgradeOffered(Json.parseToJsonElement("""{"error":{}}""")))

    @Test fun `premium only when every marker agrees`() {
        assertTrue(PremiumMarkers(loggedIn = true, subscriber = true, upgradeOffered = false).isPremium)
        assertFalse(PremiumMarkers(true, true, true).isPremium)
        assertFalse(PremiumMarkers(true, false, false).isPremium)
        assertFalse(PremiumMarkers(false, true, false).isPremium)
        // Fail closed: anything unknown is not Premium.
        assertFalse(PremiumMarkers(true, true, null).isPremium)
        assertFalse(PremiumMarkers(true, null, false).isPremium)
        assertFalse(PremiumMarkers(null, null, null).isPremium)
    }

    @Test fun `menu shape names no person`() {
        val shape = PremiumParser.menuShape(fixture("account-menu-premium.json"))
        assertFalse(shape.contains("Test Person"))
        assertFalse(shape.contains("@test"))
        assertTrue(shape.contains("header=true"))
    }
}
