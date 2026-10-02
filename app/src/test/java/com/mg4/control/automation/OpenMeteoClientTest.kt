package com.mg4.control.automation

import com.mg4.control.model.ForecastGrid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Requête Open-Meteo : les 25 points de la grille, au point décimal quelle que soit la langue.
 *
 * ⚠️ Sur la voiture, `String.format(Locale.ROOT, "%.1f")` rendait une virgule — ce que la JVM des
 * tests ne reproduit pas. D'où l'URL écrite sans formateur, et ces tests qui relisent chaque
 * coordonnée : les virgules ne doivent séparer que les points (Open-Meteo lirait `48,9` comme deux
 * latitudes).
 */
class OpenMeteoClientTest {

    private val avant = Locale.getDefault()

    @After
    fun restaure() = Locale.setDefault(avant)

    private fun parametres(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun `vingt-cinq points au point decimal meme en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val grille = ForecastGrid.around(48.8566, 2.3522)
        val p = parametres(OpenMeteoClient.url(grille))
        val latitudes = p.getValue("latitude").split(',')
        assertEquals(grille.points().map { it.first }, latitudes.map { it.toDouble() })
        assertEquals(grille.points().map { it.second }, p.getValue("longitude").split(',').map { it.toDouble() })
        assertEquals("48.64", latitudes.first())
        assertEquals("48.9", latitudes[12])
        assertEquals("shortwave_radiation", p["hourly"])
        assertEquals("3", p["forecast_days"])
        assertEquals("unixtime", p["timeformat"])
    }

    @Test
    fun `coordonnees negatives`() {
        Locale.setDefault(Locale.GERMANY)
        val grille = ForecastGrid.around(-33.87, 151.0)
        assertEquals(-33.9, grille.latitude, 1e-9)
        assertEquals(151.0, grille.longitude, 1e-9)
        val p = parametres(OpenMeteoClient.url(grille))
        assertEquals(grille.points().map { it.first }, p.getValue("latitude").split(',').map { it.toDouble() })
        assertEquals(grille.points().map { it.second }, p.getValue("longitude").split(',').map { it.toDouble() })
    }

    @Test
    fun `reponse en tableau un objet par point`() {
        val r = OpenMeteoClient.lire("""[
            {"hourly":{"time":[1,2],"shortwave_radiation":[0.0,1.0]}},
            {"location_id":1,"hourly":{"time":[1,2],"shortwave_radiation":[2.0,null]}}]""")
        assertEquals(2, r!!.size)
        assertEquals(listOf(2.0, null), r[1]!!.hourly!!.ghi)
    }
}
