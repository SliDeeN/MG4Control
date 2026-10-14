package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Énergie d'un trajet par intégration de la puissance batterie (tension × courant), pour les
 * firmwares dont les compteurs d'énergie restent à zéro (issue #117, SWI68).
 */
class PowerIntegratorTest {

    private val heure = 3_600_000L

    /** Alimente l'intégrateur d'une puissance constante, un relevé par seconde. */
    private fun constante(i: PowerIntegrator, kw: Float, depuisMs: Long, dureeMs: Long) {
        var t = depuisMs
        while (t <= depuisMs + dureeMs) {
            i.add(t, kw)
            t += 1_000L
        }
    }

    @Test
    fun `dix kilowatts pendant une heure font dix kilowattheures`() {
        val i = PowerIntegrator()
        constante(i, 10f, 0, heure)
        assertEquals(10f, i.result().consumedKwh, 0.001f)
        assertEquals(0f, i.result().regenKwh, 0.001f)
    }

    @Test
    fun `une puissance negative compte en recuperation`() {
        val i = PowerIntegrator()
        constante(i, -6f, 0, heure / 2)
        assertEquals(0f, i.result().consumedKwh, 0.001f)
        assertEquals(3f, i.result().regenKwh, 0.001f)
    }

    @Test
    fun `le net est la consommation moins la recuperation`() {
        val i = PowerIntegrator()
        constante(i, 12f, 0, heure / 2)
        constante(i, -4f, heure / 2 + 1_000, heure / 2)
        assertEquals(i.result().consumedKwh - i.result().regenKwh, i.result().netKwh, 0.0001f)
        assertEquals(4f, i.result().netKwh, 0.02f)
    }

    @Test
    fun `un changement de signe est partage au passage par zero`() {
        val i = PowerIntegrator()
        i.add(0, 36f)
        i.add(2_000, -36f)
        // Deux triangles d'une seconde : 36 kW × 1 s ÷ 2 = 18 kW·s = 0,005 kWh chacun.
        assertEquals(0.005f, i.result().consumedKwh, 0.00001f)
        assertEquals(0.005f, i.result().regenKwh, 0.00001f)
    }

    @Test
    fun `un trou trop long n est pas integre`() {
        val i = PowerIntegrator()
        i.add(0, 10f)
        i.add(PowerIntegrator.MAX_GAP_MS + 1, 10f)
        assertEquals(0f, i.result().consumedKwh, 0f)
        assertEquals(1, i.result().gaps)
        assertEquals(0L, i.result().coveredMs)
    }

    @Test
    fun `un intervalle a la limite du trou est integre`() {
        val i = PowerIntegrator()
        i.add(0, 36f)
        i.add(PowerIntegrator.MAX_GAP_MS, 36f)
        assertEquals(36f * PowerIntegrator.MAX_GAP_MS / heure, i.result().consumedKwh, 0.00001f)
        assertEquals(0, i.result().gaps)
        assertEquals(PowerIntegrator.MAX_GAP_MS, i.result().coveredMs)
    }

    @Test
    fun `une lecture manquee est sautee sans casser la suite`() {
        val i = PowerIntegrator()
        i.add(0, 36f)
        i.add(1_000, null)
        i.add(2_000, 36f)
        assertEquals(36f * 2_000 / heure, i.result().consumedKwh, 0.00001f)
        assertEquals(2, i.result().samples)
        assertEquals(1, i.result().missed)
    }

    @Test
    fun `une horloge qui recule ne produit rien`() {
        val i = PowerIntegrator()
        i.add(5_000, 36f)
        i.add(4_000, 36f)
        assertEquals(0f, i.result().consumedKwh, 0f)
    }

    @Test
    fun `sans mesure le resultat est vide`() {
        val r = PowerIntegrator().result()
        assertEquals(0, r.samples)
        assertEquals(0f, r.netKwh, 0f)
    }

    @Test
    fun `la remise a zero efface tout`() {
        val i = PowerIntegrator()
        constante(i, 10f, 0, 60_000)
        i.reset()
        assertEquals(PowerIntegrator.Result(0f, 0f, 0, 0, 0, 0L), i.result())
        // Le dernier point d'avant la remise à zéro ne sert pas de départ au trajet suivant.
        i.add(61_000, 10f)
        assertEquals(0f, i.result().consumedKwh, 0f)
    }

    @Test
    fun `puissance tension fois courant positive en decharge`() {
        assertEquals(41.775f, PowerIntegrator.batteryPowerKw(417.75f, 100f)!!, 0.001f)
        assertEquals(-5.226f, PowerIntegrator.batteryPowerKw(408.25f, -12.8f)!!, 0.001f)
    }

    @Test
    fun `tension hors de la plage d une batterie 400 V ecartee`() {
        assertNull(PowerIntegrator.batteryPowerKw(199.9f, 10f))
        assertNull(PowerIntegrator.batteryPowerKw(500.1f, 10f))
        assertNull(PowerIntegrator.batteryPowerKw(0f, 10f))
    }

    @Test
    fun `tension aux bornes acceptee`() {
        assertEquals(2f, PowerIntegrator.batteryPowerKw(200f, 10f)!!, 0.001f)
        assertEquals(5f, PowerIntegrator.batteryPowerKw(500f, 10f)!!, 0.001f)
    }

    @Test
    fun `courant hors des bornes du vehicule ecarte`() {
        assertNull(PowerIntegrator.batteryPowerKw(400f, -1000.5f))
        assertNull(PowerIntegrator.batteryPowerKw(400f, 2277f))
        assertEquals(-400f, PowerIntegrator.batteryPowerKw(400f, -1000f)!!, 0.001f)
        assertEquals(910.7f, PowerIntegrator.batteryPowerKw(400f, 2276.75f)!!, 0.001f)
    }

    @Test
    fun `lecture absente ou non finie ecartee`() {
        assertNull(PowerIntegrator.batteryPowerKw(null, 10f))
        assertNull(PowerIntegrator.batteryPowerKw(400f, null))
        assertNull(PowerIntegrator.batteryPowerKw(Float.NaN, 10f))
        assertNull(PowerIntegrator.batteryPowerKw(400f, Float.POSITIVE_INFINITY))
    }
}
