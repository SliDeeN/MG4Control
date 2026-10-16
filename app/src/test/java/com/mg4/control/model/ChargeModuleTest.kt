package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Module « charge » de l'écran conducteur (2026-10-16) — la règle, sans Android. */
class ChargeModuleTest {

    @Test
    fun `la cible se lit en crans de 40 a 100 pour cent`() {
        assertEquals(40, ChargeDisplay.ciblePourcent(1))
        assertEquals(80, ChargeDisplay.ciblePourcent(5))
        assertEquals(100, ChargeDisplay.ciblePourcent(7))
        assertNull(ChargeDisplay.ciblePourcent(0))
        assertNull(ChargeDisplay.ciblePourcent(8))
        assertNull(ChargeDisplay.ciblePourcent(null))
    }

    @Test
    fun `le temps restant ecarte la sentinelle et le negatif`() {
        assertEquals(80, ChargeDisplay.minutesRestantes(80))
        assertEquals(0, ChargeDisplay.minutesRestantes(0))
        assertNull(ChargeDisplay.minutesRestantes(1023))
        assertNull(ChargeDisplay.minutesRestantes(-1))
        assertNull(ChargeDisplay.minutesRestantes(null))
    }

    @Test
    fun `le type de borne vient de la prise active`() {
        assertEquals(ChargeType.AC, ChargeDisplay.type(ac = 2, dc = 0))
        assertEquals(ChargeType.DC, ChargeDisplay.type(ac = 0, dc = 2))
        assertNull(ChargeDisplay.type(ac = 0, dc = 0))
        assertNull(ChargeDisplay.type(ac = 1023, dc = null))
        assertTrue(ChargeDisplay.priseSignalee(2, 0))
        assertFalse(ChargeDisplay.priseSignalee(0, 0))
        assertFalse(ChargeDisplay.priseSignalee(1023, null))
    }

    @Test
    fun `borne rapide releve sur SWI133`() {
        // Diagnostics du 2026-10-16 : alternatif 4 et continu 1, avant (état 5) comme pendant la
        // charge (état 10, 108 kW). L'alternatif n'est pas à zéro : le continu doit passer devant.
        assertEquals(ChargeType.DC, ChargeDisplay.type(ac = 4, dc = 1))
        assertTrue(ChargeDisplay.priseSignalee(4, 1))
    }

    @Test
    fun `les etats en charge sont ceux de SAIC`() {
        assertEquals(true, ChargeDisplay.enCharge(1))    // alternatif, 2026-09-19
        assertEquals(true, ChargeDisplay.enCharge(10))   // continu, 2026-10-16
        for (e in listOf(2, 3, 11, 12)) assertEquals(true, ChargeDisplay.enCharge(e))
        assertEquals(false, ChargeDisplay.enCharge(0))
        assertEquals(false, ChargeDisplay.enCharge(5))   // branché sur borne rapide, pas encore lancée
        assertNull(ChargeDisplay.enCharge(null))
    }

    @Test
    fun `une duree se decoupe en heures et minutes`() {
        assertEquals(1 to 5, ChargeDisplay.heuresMinutes(65))
        assertEquals(0 to 45, ChargeDisplay.heuresMinutes(45))
        assertEquals(0 to 0, ChargeDisplay.heuresMinutes(-3))
    }

    @Test
    fun `l energie chargee s integre au fil des releves`() {
        val c = ChargeEnCours()
        c.commencer(0L)
        // 11 kW pendant une heure, relevés chaque seconde.
        for (s in 0..3600) c.ajouter(s * 1000L, 11f)
        assertEquals(11f, c.energieKwh, 0.01f)
        assertEquals(60, c.dureeMinutes(3_600_000L))
    }

    @Test
    fun `un trou dans les releves n est pas integre`() {
        val c = ChargeEnCours()
        c.commencer(0L)
        c.ajouter(0L, 10f)
        c.ajouter(60_000L, 10f)   // une minute sans relevé : on ne sait pas ce qui s'est passé
        assertEquals(0f, c.energieKwh, 0.0001f)
        c.ajouter(61_000L, 10f)
        assertEquals(10f / 3600f, c.energieKwh, 0.0001f)
    }

    @Test
    fun `hors session rien ne s ajoute et une decharge ne retire rien`() {
        val c = ChargeEnCours()
        c.ajouter(0L, 10f)
        c.ajouter(1000L, 10f)
        assertEquals(0f, c.energieKwh, 0f)
        assertFalse(c.active)
        c.commencer(0L)
        c.ajouter(0L, -5f)
        c.ajouter(1000L, -5f)
        assertEquals(0f, c.energieKwh, 0f)
    }
}
