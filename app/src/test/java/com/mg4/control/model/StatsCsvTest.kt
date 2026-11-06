package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Export CSV des Statistiques (issue #206) : un fichier trajets, un fichier recharges, lisibles
 * par Excel (BOM, « ; », virgule décimale hors anglais), cases vides pour l'inconnu.
 */
class StatsCsvTest {

    private val utc = TimeZone.getTimeZone("UTC")
    // 2026-11-03 08:00 UTC
    private val t0 = 1_793_692_800_000L

    private fun trajet(debut: Long = t0) = Trip(
        startMs = debut, endMs = debut + 25 * 60_000L, distanceKm = 23, energyKwh = 4.2f,
        climateKwh = 0.3f, accessoriesKwh = 0.1f, regenKwh = 0.8f, socStart = 80f, socEnd = 75.5f,
        outsideTempC = 9.4f, integratedKm = 23.4f, odometerEndKm = 11_221,
    )

    private fun lignes(csv: String) = csv.removePrefix(StatsCsv.BOM).trimEnd().split("\r\n")

    @Test
    fun `trajets - BOM, en-tete et une ligne par trajet dans l'ordre`() {
        val csv = StatsCsv.trips(listOf(trajet(t0 + 3_600_000L), trajet()), virgule = true, zone = utc)
        assertTrue(csv.startsWith(StatsCsv.BOM))
        val l = lignes(csv)
        assertEquals(3, l.size)
        assertEquals(StatsCsv.TRIP_HEADERS.joinToString(";"), l[0])
        assertTrue(l[1].startsWith("2026-11-03 08:00;2026-11-03 08:25;25;23,4;11221;3,4;4,2;0,8;"))
        assertTrue(l[2].startsWith("2026-11-03 09:00;"))
        assertEquals(StatsCsv.TRIP_HEADERS.size, l[1].split(";").size)
    }

    @Test
    fun `anglais - point decimal`() {
        val l = lignes(StatsCsv.trips(listOf(trajet()), virgule = false, zone = utc))
        assertTrue(l[1].contains(";23.4;11221;3.4;4.2;0.8;"))
        // Trajet d'avant la colonne : kilométrage inconnu, case vide.
        assertTrue(lignes(StatsCsv.trips(listOf(trajet().copy(odometerEndKm = null)), virgule = false, zone = utc))[1]
            .contains(";23.4;;3.4;"))
    }

    @Test
    fun `recharges - type, prix, cout, et duree vide si reconstituee sans horaires`() {
        val vue = ChargeSession(startMs = t0, endMs = t0 + 3_600_000L, type = ChargeType.AC, socStart = 20f,
            socEnd = 70f, energyKwh = 31f, measuredPowerKw = 7.1f, outsideTempC = 5f)
        val nuit = vue.copy(startMs = t0 + 86_400_000L, endMs = t0 + 90_000_000L, type = ChargeType.DC,
            reconstructed = true, tariffOverride = 0.5f)
        val reglages = StatsSettings(priceAc = 0.2f, priceDc = 0.45f, currency = "€")
        val l = lignes(StatsCsv.charges(listOf(nuit, vue), reglages, virgule = true, zone = utc))
        assertEquals(StatsCsv.CHARGE_HEADERS.joinToString(";"), l[0])
        assertEquals("2026-11-03 08:00;2026-11-03 09:00;60;AC;20,0;70,0;31,00;percent;7,1;0,200;6,20;€;5,0;recorded;no", l[1])
        val c = l[2].split(";")
        assertEquals("", c[2])
        assertEquals("DC", c[3])
        assertEquals("0,500", c[9])
        assertEquals("15,50", c[10])
        assertEquals("yes", c[14])
    }

    @Test
    fun `inconnu = case vide, jamais zero`() {
        assertEquals("", StatsCsv.nombre(null, 1, true))
        assertEquals("", StatsCsv.nombre(Float.NaN, 1, true))
        assertEquals("0,0", StatsCsv.nombre(0f, 1, true))
    }

    @Test
    fun `case avec separateur ou guillemet entre guillemets`() {
        assertEquals("abc", StatsCsv.cellule("abc"))
        assertEquals("\"a;b\"", StatsCsv.cellule("a;b"))
        assertEquals("\"dit \"\"oui\"\"\"", StatsCsv.cellule("dit \"oui\""))
    }

    @Test
    fun `nom de fichier date`() {
        assertEquals("mg4control_trips_2026-11-03.csv", StatsCsv.fileName("trips", t0, utc))
    }
}
