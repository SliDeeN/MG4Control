package com.mg4.control.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prévision en cache : interpolation horaire, couverture dans le temps et dans l'espace, lecture
 * de la réponse Open-Meteo et aller-retour par Gson (c'est ainsi qu'elle est mise en cache).
 */
class SolarForecastTest {

    private val t0 = 1_727_740_800L   // 2024-10-01 00:00 UTC, en secondes

    /** Trois heures : 0, 100 et 300 W/m², chacune moyenne de l'heure qui précède. */
    private fun prevision(ghi: List<Double?> = listOf(0.0, 100.0, 300.0)) =
        SolarForecast(48.9, 2.4, fetchedAtMs = 0L, hoursS = listOf(t0, t0 + 3600, t0 + 7200), ghi = ghi)

    @Test
    fun `chaque valeur represente le milieu de l heure precedente`() {
        val p = prevision()
        assertEquals(0.0, p.ghiAt((t0 - 1800) * 1000)!!, 1e-9)
        assertEquals(100.0, p.ghiAt((t0 + 1800) * 1000)!!, 1e-9)
        assertEquals(300.0, p.ghiAt((t0 + 5400) * 1000)!!, 1e-9)
    }

    @Test
    fun `interpolation entre deux heures`() {
        // t0 : à mi-chemin entre les milieux des deux premières heures.
        assertEquals(50.0, prevision().ghiAt(t0 * 1000)!!, 1e-9)
    }

    @Test
    fun `hors de la plage couverte rien`() {
        val p = prevision()
        assertNull(p.ghiAt((t0 - 3600) * 1000))
        assertNull(p.ghiAt((t0 + 6000) * 1000))
    }

    @Test
    fun `un trou dans les donnees rend null`() {
        assertNull(prevision(listOf(0.0, null, 300.0)).ghiAt(t0 * 1000))
    }

    @Test
    fun `couverture limitee a trente kilometres`() {
        val p = prevision()
        assertTrue(p.covers(48.95, 2.45, t0 * 1000))     // ~6,7 km
        assertFalse(p.covers(49.4, 2.4, t0 * 1000))      // ~55 km
        assertEquals(55.6, p.distanceKm(49.4, 2.4), 0.5)
    }

    @Test
    fun `lecture d une reponse open meteo`() {
        val json = """{"latitude":48.86,"longitude":2.36,"generationtime_ms":0.2,
            "hourly_units":{"time":"unixtime","shortwave_radiation":"W/m²"},
            "hourly":{"time":[$t0,${t0 + 3600}],"shortwave_radiation":[0.0,12.5]}}"""
        val r = Gson().fromJson(json, OpenMeteoResponse::class.java)
        val p = SolarForecast.fromResponse(r, 48.9, 2.4, fetchedAtMs = 42L)
        assertNotNull(p)
        assertEquals(listOf(t0, t0 + 3600), p!!.hoursS)
        assertEquals(12.5, p.ghi[1]!!, 1e-9)
    }

    @Test
    fun `reponse incomplete refusee`() {
        val sansRayonnement = Gson().fromJson("""{"hourly":{"time":[$t0,${t0 + 3600}]}}""",
            OpenMeteoResponse::class.java)
        assertNull(SolarForecast.fromResponse(sansRayonnement, 0.0, 0.0, 0L))
        assertNull(SolarForecast.fromResponse(null, 0.0, 0.0, 0L))
    }

    @Test
    fun `aller retour par gson pour le cache`() {
        val p = prevision()
        val relu = Gson().fromJson(Gson().toJson(p), SolarForecast::class.java)
        assertEquals(p, relu)
    }
}
