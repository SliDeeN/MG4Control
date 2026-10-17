package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * État de santé de la batterie (SOH), sous le choix de la batterie (2026-10-14).
 *
 * Les pourcentages affichés couvrent la capacité utile de la batterie TELLE QU'ELLE EST : usée à
 * 95 %, une 64 kWh ne stocke plus 61,7 kWh mais 58,6. L'énergie d'une recharge se calcule donc
 * sur capacité utile × état de santé. 100 % par défaut : rien ne change pour qui n'y touche pas.
 */
class StatsSohTest {

    @Test fun `par defaut l etat de sante est de 100 pour cent et ne change rien`() {
        val s = StatsSettings()
        assertEquals(100f, s.stateOfHealthPercent, 0f)
        assertEquals(61.7f, s.effectiveCapacityKwh, 0.001f)
        assertEquals(37.0f, s.kwhFromSoc(20f, 80f)!!, 0.001f)   // 60 % de 61,7
    }

    @Test fun `une batterie usee stocke moins d energie par pour cent`() {
        val s = StatsSettings(stateOfHealthPercent = 90f)
        assertEquals(55.53f, s.effectiveCapacityKwh, 0.001f)
        assertEquals(33.3f, s.kwhFromSoc(20f, 80f)!!, 0.001f)   // 60 % de 55,53
        assertNull(s.kwhFromSoc(80f, 20f))
    }

    @Test fun `l etat de sante se regle par pas de 0,5 entre 50 et 100`() {
        assertEquals(99.5f, StatsSettings.stepSoh(100f, -1), 0f)
        assertEquals(100f, StatsSettings.stepSoh(100f, 1), 0f)
        assertEquals(50f, StatsSettings.stepSoh(50f, -1), 0f)
        assertEquals(98.5f, StatsSettings.stepSoh(98f, 1), 0f)
    }

    @Test fun `une valeur enregistree aberrante revient dans la plage, calee sur le pas`() {
        assertEquals(100f, StatsSettings.clampSoh(140f), 0f)
        assertEquals(50f, StatsSettings.clampSoh(10f), 0f)
        assertEquals(98.5f, StatsSettings.clampSoh(98.7f), 0f)
        assertEquals(100f, StatsSettings.clampSoh(Float.NaN), 0f)
    }
}
