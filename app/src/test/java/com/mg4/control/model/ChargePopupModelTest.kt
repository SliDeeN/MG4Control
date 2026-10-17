package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Pop-up de charge (2026-10-16) : limite de charge et journal de la session, sans Android. */
class ChargePopupModelTest {

    @Test
    fun `la limite va de 40 a 100 pour cent par crans de dix`() {
        assertEquals(listOf(40, 50, 60, 70, 80, 90, 100), ChargeLimit.CRANS)
        assertEquals(80, ChargeLimit.cran(78.4f))
        assertEquals(90, ChargeLimit.cran(86f))
        assertEquals(40, ChargeLimit.cran(12f))
        assertEquals(100, ChargeLimit.cran(130f))
    }

    @Test
    fun `codes vehicule de l appli de charge SAIC`() {
        // EvCharge (A9 SWI69) : 1 → 40 % … 7 → 100 % ; relevé SWI133 du 2026-10-16 : code 5 = 80 %.
        assertEquals(1, ChargeLimit.code(40))
        assertEquals(5, ChargeLimit.code(80))
        assertEquals(7, ChargeLimit.code(100))
        assertEquals(80, ChargeLimit.pct(5))
        assertNull(ChargeLimit.pct(0))
        ChargeLimit.CRANS.forEach { assertEquals(it, ChargeLimit.pct(ChargeLimit.code(it))) }
    }

    @Test
    fun `un point toutes les cinq secondes mais l energie a chaque releve`() {
        val log = ChargeLog(debutMs = 1_000L, debutHorlogeMs = null, socDebut = 18f, autonomieDebutKm = 68)
        // 100 kW pendant une minute, relevés chaque seconde.
        for (s in 0..60) log.ajouter(1_000L + s * 1_000L, 100f, 18f + s * 0.05f)
        assertEquals(13, log.points.size)            // t = 0, 5, 10 … 60 s
        assertEquals(0L, log.points.first().tMs)
        assertEquals(100f / 60f, log.energieKwh, 0.01f)
        assertEquals(100f, log.kwMax!!, 0f)
        assertEquals(1, log.dureeMinutes(61_000L))
    }

    @Test
    fun `une charge finie ne bouge plus, et peut reprendre`() {
        val log = ChargeLog(0L, null, 50f, null)
        log.ajouter(0L, 7f, 50f)
        log.ajouter(1_000L, 7f, 50f)
        log.terminer(1_000L)
        assertTrue(log.finie)
        val e = log.energieKwh
        log.ajouter(2_000L, 7f, 50f)
        assertEquals(e, log.energieKwh, 0f)
        assertEquals(0, log.dureeMinutes(10 * 60_000L))   // figée à la fin
        log.reprendre()
        assertFalse(log.finie)
    }

    @Test
    fun `la courbe se reduit en gardant le dernier point`() {
        val pts = (0 until 1_000).map { ChargePoint(it * 5_000L, it.toFloat(), null) }
        val r = ChargeLog.echantillonner(pts, 100)
        assertTrue(r.size <= 101)
        assertEquals(pts.first(), r.first())
        assertEquals(pts.last(), r.last())
        assertEquals(pts.take(50), ChargeLog.echantillonner(pts.take(50), 100))
    }

    @Test
    fun `axe des kW - le haut est la puissance max atteinte`() {
        val pts = listOf(ChargePoint(0L, 12f, 20f), ChargePoint(5_000L, 88.4f, 21f), ChargePoint(10_000L, null, 22f))
        // Le maximum relevé entre deux points l'emporte : c'est le chiffre « max … kW » du pop-up.
        assertEquals(90f, ChargeCurveScale.hautKw(90f, pts), 0.001f)
        assertEquals(88.4f, ChargeCurveScale.hautKw(null, pts), 0.001f)
        assertEquals(ChargeCurveScale.VIDE_KW, ChargeCurveScale.hautKw(null, emptyList()), 0f)
        assertEquals(ChargeCurveScale.VIDE_KW, ChargeCurveScale.hautKw(0f, listOf(ChargePoint(0L, null, 50f))), 0f)
        assertEquals("90", ChargeCurveScale.libelle(90f, 90f, Locale.FRENCH))
        assertEquals("45", ChargeCurveScale.libelle(45f, 90f, Locale.FRENCH))
        assertEquals("0", ChargeCurveScale.libelle(0f, 90f, Locale.FRENCH))
        // Sous 10 kW (AC), une décimale dans la langue du pop-up ; le zéro reste « 0 ».
        assertEquals("7,2", ChargeCurveScale.libelle(7.2f, 7.2f, Locale.FRENCH))
        assertEquals("3.6", ChargeCurveScale.libelle(3.6f, 7.2f, Locale.US))
        assertEquals("0", ChargeCurveScale.libelle(0f, 7.2f, Locale.FRENCH))
    }

    @Test
    fun `la session des Statistiques se retrouve par chevauchement`() {
        // Charge commencée à 15:31 (horloge), toujours en cours dix minutes plus tard.
        val debut = 1_700_000_000_000L
        val log = ChargeLog(debutMs = 0L, debutHorlogeMs = debut, socDebut = 18f, autonomieDebutKm = null)
        val maintenant = 10 * 60_000L
        val horloge = debut + maintenant
        assertTrue(log.recouvre(debut + 30_000L, debut + 9 * 60_000L, horloge, maintenant))
        assertFalse(log.recouvre(debut - 60 * 60_000L, debut - 1, horloge, maintenant))
        // Sans horloge crédible, on ne devine pas.
        assertFalse(ChargeLog(0L, null, null, null).recouvre(debut, debut + 1, horloge, maintenant))
    }
}
