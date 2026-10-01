package com.mg4.control.automation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Requête Open-Meteo : position arrondie à ~10 km, et point décimal quelle que soit la langue.
 *
 * ⚠️ Sur la voiture, `String.format(Locale.ROOT, "%.1f")` rendait une virgule — ce que la JVM des
 * tests ne reproduit pas. D'où l'URL écrite sans formateur, et ce test qui vérifie l'absence de
 * toute virgule : Open-Meteo lit `48,9` comme deux latitudes et répond par un tableau.
 */
class OpenMeteoClientTest {

    private val avant = Locale.getDefault()

    @After
    fun restaure() = Locale.setDefault(avant)

    @Test
    fun `coordonnees arrondies au dixieme`() {
        assertEquals(48.9, OpenMeteoClient.arrondi(48.8566), 1e-9)
        assertEquals(-33.9, OpenMeteoClient.arrondi(-33.87), 1e-9)
    }

    @Test
    fun `point decimal meme en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val url = OpenMeteoClient.url(48.8566, 2.3522)
        assertTrue(url, url.contains("latitude=48.9&longitude=2.4&"))
        assertFalse(url, url.contains(","))
        assertTrue(url, url.contains("hourly=shortwave_radiation"))
        assertTrue(url, url.contains("forecast_days=3"))
        assertTrue(url, url.contains("timeformat=unixtime"))
    }

    @Test
    fun `coordonnees negatives et entieres`() {
        Locale.setDefault(Locale.GERMANY)
        val url = OpenMeteoClient.url(-33.87, 151.0)
        assertTrue(url, url.contains("latitude=-33.9&longitude=151.0&"))
        assertFalse(url, url.contains(","))
    }
}
