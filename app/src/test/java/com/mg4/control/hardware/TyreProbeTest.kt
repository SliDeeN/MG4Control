package com.mg4.control.hardware

import com.mg4.control.hardware.TyreProbe.Lu
import com.mg4.control.hardware.TyreProbe.Roue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sonde pneus (2026-11-20) : les codes relevés dans les firmwares et ce que la fenêtre en tire. */
class TyreProbeTest {

    @Test
    fun `each wheel carries the codes found in the firmwares`() {
        assertEquals(listOf(0x21401553, 0x21401554, 0x21401555, 0x21401556), Roue.values().map { it.pression })
        assertEquals(listOf(0x21401557, 0x21401558, 0x21401559, 0x2140155a), Roue.values().map { it.etat })
        assertEquals(listOf(0x2140155b, 0x2140155c, 0x2140155d, 0x2140155e), Roue.values().map { it.temperature })
        // VehicleAreaWheel : avant gauche 1, avant droite 2, arrière gauche 4, arrière droite 8.
        assertEquals(listOf(0x1, 0x2, 0x4, 0x8), Roue.values().map { it.zone })
        assertEquals(0x21407b49, TyreProbe.PROP_UNITE)
        assertEquals(0x17600309, TyreProbe.PROP_STANDARD)
    }

    @Test
    fun `the shown value is the first route that answers`() {
        val refus = Lu.echec("IllegalArgumentException")
        assertEquals(290, TyreProbe.Voies(Lu.de(290), Lu.de(0), Lu.de(1)).valeur)
        assertEquals(31, TyreProbe.Voies(refus, Lu.de(null), Lu.de(31)).valeur)
        assertNull(TyreProbe.Voies(refus, refus, Lu.echec("sensor absent")).valeur)
        assertEquals("null", Lu.de(null).texte)
    }

    private fun releve(pression: Int?, etat: Int?, unite: Int? = 0) = TyreProbe.Releve(
        roues = Roue.values().map { r ->
            val p = if (r == Roue.ARRIERE_GAUCHE) pression else 290
            TyreProbe.RoueLue(r, voies(p), voies(30), voies(if (r == Roue.ARRIERE_GAUCHE) etat else 0), Lu.de(0f))
        },
        unite = voies(unite),
    )

    private fun voies(n: Int?) = TyreProbe.Voies(Lu.de(n), Lu.de(n), Lu.echec("sensor absent"))

    @Test
    fun `only what moved goes to the log`() {
        val a = releve(290, 0)
        assertEquals("", TyreProbe.changements(a, releve(290, 0)))
        val ch = TyreProbe.changements(a, releve(198, 3, unite = 1))
        assertTrue(ch, ch.contains("unité 0→1"))
        assertTrue(ch, ch.contains("ARRIERE_GAUCHE pression 290→198"))
        assertTrue(ch, ch.contains("ARRIERE_GAUCHE état 0→3"))
        assertTrue(ch, !ch.contains("AVANT"))
    }
}
