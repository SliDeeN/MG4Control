package com.mg4.control.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prévision en cache sur une grille de 5 × 5 points : géométrie de la grille, interpolation dans
 * le temps et dans l'espace, couverture de la zone, lecture des réponses Open-Meteo et aller-retour
 * du cache.
 */
class SolarForecastTest {

    private val t0 = 1_727_740_800L   // 2024-10-01 00:00 UTC, en secondes
    private val heures = listOf(t0, t0 + 3600, t0 + 7200)
    /** Paris : centre 48,9 / 2,4, pas de 0,13° en latitude et de 0,21° en longitude. */
    private val grille = ForecastGrid.around(48.8566, 2.3522)

    /** Prévision dont chaque point (ligne [i] du sud au nord, colonne [j] d'ouest en est) a sa série. */
    private fun prevision(serie: (i: Int, j: Int) -> List<Double?>) = SolarForecast(
        grille, fetchedAtMs = 0L, hoursS = heures,
        ghi = (0 until 25).map { serie(it / 5, it % 5) },
    )

    /** Partout : 0, 100 et 300 W/m², chacune moyenne de l'heure qui précède. */
    private val uniforme = prevision { _, _ -> listOf(0.0, 100.0, 300.0) }

    /** Le ciel s'éclaircit vers l'est : 100 W/m² de plus par colonne, de 0 à 400. */
    private val versLEst = prevision { _, j -> List(3) { 100.0 * j } }

    private fun latLigne(i: Double) = grille.latitude + (i - 2) * grille.stepLatDeg
    private fun lonColonne(j: Double) = grille.longitude + (j - 2) * grille.stepLonDeg

    @Test
    fun `grille centree sur la position arrondie`() {
        assertEquals(48.9, grille.latitude, 1e-9)
        assertEquals(2.4, grille.longitude, 1e-9)
        assertEquals(0.13, grille.stepLatDeg, 1e-9)   // 14,5 km
        assertEquals(0.21, grille.stepLonDeg, 1e-9)   // 15,4 km à cette latitude
        assertEquals(0.27, ForecastGrid.around(60.0, 10.0).stepLonDeg, 1e-9)
        val points = grille.points()
        assertEquals(25, points.size)
        assertEquals(48.64 to 1.98, points.first())   // sud-ouest
        assertEquals(48.64 to 2.19, points[1])        // puis vers l'est
        assertEquals(48.9 to 2.4, points[12])         // le centre
        assertEquals(49.16 to 2.82, points.last())    // nord-est
    }

    @Test
    fun `chaque valeur represente le milieu de l heure precedente`() {
        assertEquals(0.0, uniforme.ghiAt(48.9, 2.4, (t0 - 1800) * 1000)!!, 1e-9)
        assertEquals(100.0, uniforme.ghiAt(48.9, 2.4, (t0 + 1800) * 1000)!!, 1e-9)
        assertEquals(300.0, uniforme.ghiAt(48.9, 2.4, (t0 + 5400) * 1000)!!, 1e-9)
    }

    @Test
    fun `interpolation entre deux heures`() {
        // t0 : à mi-chemin entre les milieux des deux premières heures.
        assertEquals(50.0, uniforme.ghiAt(48.95, 2.45, t0 * 1000)!!, 1e-9)
    }

    @Test
    fun `hors de la plage couverte rien`() {
        assertNull(uniforme.ghiAt(48.9, 2.4, (t0 - 3600) * 1000))
        assertNull(uniforme.ghiAt(48.9, 2.4, (t0 + 6000) * 1000))
    }

    @Test
    fun `un trou dans les donnees rend null`() {
        val trouee = prevision { _, _ -> listOf(0.0, null, 300.0) }
        assertNull(trouee.ghiAt(48.9, 2.4, t0 * 1000))
    }

    @Test
    fun `la meteo suit la position dans la zone`() {
        val t = (t0 + 1800) * 1000
        assertEquals(200.0, versLEst.ghiAt(48.9, 2.4, t)!!, 1e-6)                          // centre
        assertEquals(0.0, versLEst.ghiAt(48.9, lonColonne(0.0), t)!!, 1e-6)                // bord ouest
        assertEquals(400.0, versLEst.ghiAt(latLigne(4.0), lonColonne(4.0), t)!!, 1e-6)     // coin nord-est
        assertEquals(250.0, versLEst.ghiAt(latLigne(1.3), lonColonne(2.5), t)!!, 1e-6)     // entre deux colonnes
    }

    @Test
    fun `au dela de la grille son bord sur un pas`() {
        val t = (t0 + 1800) * 1000
        assertEquals(400.0, versLEst.ghiAt(48.9, lonColonne(4.6), t)!!, 1e-6)
        assertNull(versLEst.ghiAt(48.9, lonColonne(5.2), t))
        assertNull(versLEst.ghiAt(latLigne(-1.2), 2.4, t))
    }

    @Test
    fun `un point sans valeur est remplace par ses voisins`() {
        val centreVide = prevision { i, j ->
            if (i == 2 && j == 2) listOf(null, null, null) else List(3) { 100.0 * j }
        }
        val t = (t0 + 1800) * 1000
        // À mi-chemin entre le centre (vide) et son voisin de l'est (300 W/m²).
        assertEquals(300.0, centreVide.ghiAt(48.9, lonColonne(2.5), t)!!, 1e-6)
        // Pile sur le centre : aucun autre point n'a de poids.
        assertNull(centreVide.ghiAt(48.9, 2.4, t))
    }

    @Test
    fun `zone et distance au centre`() {
        val t = t0 * 1000
        assertTrue(uniforme.covers(48.95, 2.45, t))                 // ~6,7 km
        assertEquals(55.6, uniforme.distanceKm(49.4, 2.4), 0.5)
        assertFalse(uniforme.covers(49.4, 2.4, t))                  // ~55 km : hors zone
        // 40 km au nord : à retélécharger, mais le bord de la grille sert en attendant.
        assertTrue(uniforme.distanceKm(49.26, 2.4) > SolarForecast.MAX_DISTANCE_KM)
        assertTrue(uniforme.covers(49.26, 2.4, t))
    }

    @Test
    fun `grille a cheval sur l antimeridien`() {
        val fidji = ForecastGrid.around(-16.8, 179.9)
        val points = fidji.points()
        assertTrue(points.all { it.second >= -180.0 && it.second < 180.0 })
        assertEquals(-179.82, points[4].second, 1e-9)   // l'est passe en longitudes négatives
        val p = SolarForecast(fidji, 0L, heures, List(25) { k -> List(3) { 100.0 * (k % 5) } })
        // Juste à l'est de l'antiméridien : entre les colonnes 3 et 4.
        assertEquals(350.0, p.ghiAt(-16.8, -179.89, (t0 + 1800) * 1000)!!, 1e-6)
    }

    @Test
    fun `lecture des reponses open meteo dans l ordre de la grille`() {
        // Ce que renvoie Open-Meteo pour plusieurs points : un objet par point, dans l'ordre de la
        // requête. Ici, le point k vaut k W/m².
        val json = (0 until 25).joinToString(",", "[", "]") { k ->
            """{"latitude":48.9,"longitude":2.4,"location_id":$k,
               "hourly":{"time":[$t0,${t0 + 3600}],"shortwave_radiation":[$k.0,$k.5]}}"""
        }
        val reponses = Gson().fromJson(json, Array<OpenMeteoResponse>::class.java).toList()
        val p = SolarForecast.fromResponses(reponses, grille, fetchedAtMs = 42L)
        assertNotNull(p)
        assertEquals(listOf(t0, t0 + 3600), p!!.hoursS)
        assertEquals(25, p.ghi.size)
        assertEquals(listOf(7.0, 7.5), p.ghi[7])
        assertEquals(42L, p.fetchedAtMs)
    }

    @Test
    fun `reponses incompletes refusees`() {
        val bonne = OpenMeteoResponse(hourly = OpenMeteoHourly(listOf(t0, t0 + 3600), listOf(0.0, 1.0)))
        val toutes = List(25) { bonne }
        assertNotNull(SolarForecast.fromResponses(toutes, grille, 0L))
        assertNull(SolarForecast.fromResponses(toutes.drop(1), grille, 0L))   // un point manque
        val decalee = OpenMeteoResponse(hourly = OpenMeteoHourly(listOf(t0 + 3600, t0 + 7200), listOf(0.0, 1.0)))
        assertNull(SolarForecast.fromResponses(toutes.take(24) + decalee, grille, 0L))
        val sansRayonnement = OpenMeteoResponse(hourly = OpenMeteoHourly(listOf(t0, t0 + 3600)))
        assertNull(SolarForecast.fromResponses(toutes.take(24) + sansRayonnement, grille, 0L))
        assertNull(SolarForecast.fromResponses(null, grille, 0L))
    }

    @Test
    fun `aller retour par le cache`() {
        assertEquals(versLEst, SolarForecast.fromJson(versLEst.toJson()))
    }

    @Test
    fun `ancien cache a un seul point ignore`() {
        // Format d'avant la grille : traité comme absent, la prochaine requête le remplace.
        val ancien = """{"latitude":48.9,"longitude":2.4,"fetchedAtMs":0,
            "hoursS":[$t0,${t0 + 3600}],"ghi":[0.0,12.5]}"""
        assertNull(SolarForecast.fromJson(ancien))
        assertNull(SolarForecast.fromJson("""{"fetchedAtMs":0}"""))
        assertNull(SolarForecast.fromJson("pas du json"))
    }
}
