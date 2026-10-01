package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Éclairement extérieur estimé : crépuscule, ciel dégagé, et prévision quand on l'a. */
class OutdoorLightTest {

    @Test
    fun `nuit noire au dela du crepuscule nautique`() {
        assertEquals(0.01, OutdoorLight.estimateLux(-20.0, null), 1e-9)
    }

    @Test
    fun `reperes du crepuscule`() {
        assertEquals(400.0, OutdoorLight.estimateLux(0.0, null), 1e-6)
        assertEquals(3.0, OutdoorLight.estimateLux(-6.0, null), 1e-6)
        assertEquals(0.01, OutdoorLight.estimateLux(-12.0, null), 1e-9)
        // À mi-chemin, la moyenne géométrique : l'interpolation se fait en log.
        assertEquals(Math.sqrt(400.0 * 3.0), OutdoorLight.estimateLux(-3.0, null), 1e-6)
    }

    @Test
    fun `sans prevision le ciel est suppose degage`() {
        // Haurwitz à 30° : ~490 W/m² → ~53 900 lx.
        assertEquals(53_883.0, OutdoorLight.estimateLux(30.0, null), 300.0)
    }

    @Test
    fun `la prevision remplace le ciel degage`() {
        assertEquals(300.0 * OutdoorLight.LUX_PAR_WM2, OutdoorLight.estimateLux(30.0, 300.0), 1e-6)
    }

    @Test
    fun `le crepuscule sert de plancher juste apres le lever`() {
        // Heure du lever : rayonnement moyen quasi nul alors qu'il fait déjà clair.
        assertEquals(400.0, OutdoorLight.estimateLux(1.0, 0.0), 1e-6)
    }

    @Test
    fun `un rayonnement negatif ne fait pas descendre sous le plancher`() {
        assertEquals(400.0, OutdoorLight.estimateLux(10.0, -5.0), 1e-6)
    }

    @Test
    fun `la lumiere croit avec la hauteur du soleil`() {
        val hauteurs = listOf(-15.0, -9.0, -4.0, 0.0, 3.0, 10.0, 25.0, 50.0)
        val lux = hauteurs.map { OutdoorLight.estimateLux(it, null) }
        assertTrue(lux.zipWithNext().all { (a, b) -> b >= a })
    }
}
