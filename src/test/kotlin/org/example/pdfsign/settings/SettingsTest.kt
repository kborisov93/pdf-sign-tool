package org.example.pdfsign.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun `origin patterns`() {
        val settings = Settings(allowedOrigins = listOf("https://portal.example.com", "https://*.example.org", "http://localhost:*"))
        assertTrue(settings.isOriginAllowed("https://portal.example.com"))
        assertTrue(settings.isOriginAllowed("HTTPS://PORTAL.EXAMPLE.COM"))
        assertFalse(settings.isOriginAllowed("http://portal.example.com"))
        assertFalse(settings.isOriginAllowed("https://portal.example.com.evil.net"))
        assertTrue(settings.isOriginAllowed("https://app.example.org"))
        assertFalse(settings.isOriginAllowed("https://example.org.evil.net"))
        assertFalse(settings.isOriginAllowed("https://evil-example.org"))
        assertTrue(settings.isOriginAllowed("http://localhost:3000"))
        assertTrue(Settings().isOriginAllowed("https://anything.example"))
        assertFalse(Settings(allowedOrigins = emptyList()).isOriginAllowed("https://anything.example"))
    }

    @Test
    fun `properties round trip`() {
        val settings = Settings(4321, listOf("https://a.example", "https://b.example"), "http://tsa.example", "C:\\x\\p11.dll")
        assertEquals(settings, Settings.fromProperties(settings.toProperties()))
        assertEquals(listOf("https://a.example", "https://b.example"), Settings.parseOrigins(" https://a.example/ \n https://b.example,"))
    }
}
