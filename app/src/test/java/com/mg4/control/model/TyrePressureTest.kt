package com.mg4.control.model

import com.mg4.control.model.TyrePressure.Alert
import com.mg4.control.model.TyrePressure.PressureUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Pression des pneus : unités et codes d'état (relevé SWI133 du 2026-11-20). */
class TyrePressureTest {

    @Test
    fun `pressure in kPa is shown like the cluster in bar, and in PSI or kPa`() {
        // Relevé : 252 / 248 / 256 kPa ↔ 2,5 / 2,5 / 2,6 bar au combiné.
        assertEquals("2,5", TyrePressure.format(252, PressureUnit.BAR, Locale.FRANCE))
        assertEquals("2,5", TyrePressure.format(248, PressureUnit.BAR, Locale.FRANCE))
        assertEquals("2,6", TyrePressure.format(256, PressureUnit.BAR, Locale.FRANCE))
        assertEquals("2.5", TyrePressure.format(252, PressureUnit.BAR, Locale.UK))
        assertEquals("37", TyrePressure.format(252, PressureUnit.PSI, Locale.FRANCE))
        assertEquals("36", TyrePressure.format(248, PressureUnit.PSI, Locale.FRANCE))
        assertEquals("252", TyrePressure.format(252, PressureUnit.KPA, Locale.FRANCE))
    }

    @Test
    fun `the unit follows the cluster when known, bar otherwise`() {
        assertEquals(PressureUnit.BAR, PressureUnit.fromCluster(0))
        assertEquals(PressureUnit.BAR, PressureUnit.fromCluster(1))
        assertEquals(PressureUnit.BAR, PressureUnit.fromCluster(null))
        assertEquals(PressureUnit.PSI, PressureUnit.fromName("PSI"))
        assertNull(PressureUnit.fromName("psi?"))
    }

    @Test
    fun `an out of range pressure is no value`() {
        assertEquals(252, TyrePressure.valide(252))
        assertEquals(0, TyrePressure.valide(0))
        assertNull(TyrePressure.valide(-1))
        assertNull(TyrePressure.valide(1023))
        assertNull(TyrePressure.valide(null))
    }

    @Test
    fun `codes are read like the SAIC voice`() {
        assertEquals(Alert.NORMAL, TyrePressure.alert(0, 0, pressionLue = true))
        assertEquals(Alert.NORMAL, TyrePressure.alert(1, 0, pressionLue = true))
        assertEquals(Alert.ALERT, TyrePressure.alert(3, 0, pressionLue = true))
        assertEquals(Alert.ALERT, TyrePressure.alert(2, 0, pressionLue = false))
        // 6 seul ne compte pas ; sur les deux pneus de l'essieu, si.
        assertEquals(Alert.LONE_SIX, TyrePressure.alert(6, 0, pressionLue = true))
        assertEquals(Alert.LONE_SIX, TyrePressure.alert(6, null, pressionLue = true))
        assertEquals(Alert.ALERT, TyrePressure.alert(6, 6, pressionLue = true))
        // Code illisible : normal si la pression est là, sinon pas de valeur.
        assertEquals(Alert.NORMAL, TyrePressure.alert(null, 0, pressionLue = true))
        assertEquals(Alert.NO_VALUE, TyrePressure.alert(null, 0, pressionLue = false))
    }

    @Test
    fun `the summaries used by automations, bubbles and the driver display`() {
        assertEquals(248, TyrePressure.lowest(listOf(252, 248, 1023, 256)))
        assertNull(TyrePressure.lowest(listOf(null, null, null, null)))
        assertEquals(36, TyrePressure.hottest(listOf(16, 36, null, 20)))
        assertNull(TyrePressure.hottest(listOf(null, null, null, null)))
        assertEquals(false, TyrePressure.anyAlert(listOf(0, 0, 1, 0)))
        assertEquals(true, TyrePressure.anyAlert(listOf(0, 0, 3, 0)))
        // 6 seul ne compte pas ; sur l'essieu avant, si.
        assertEquals(false, TyrePressure.anyAlert(listOf(6, 0, 0, 0)))
        assertEquals(true, TyrePressure.anyAlert(listOf(6, 6, 0, 0)))
        assertNull(TyrePressure.anyAlert(listOf(null, null, null, null)))
    }

    @Test
    fun `bubbles and driver display slots show the pressure in the chosen unit`() {
        assertEquals("2,5" to "bar", BubbleInfo.pressure(RuleSignal.TYRE_PRESSURE_FL, 252.0, PressureUnit.BAR, Locale.FRANCE))
        assertEquals("37" to "PSI", BubbleInfo.pressure(RuleSignal.TYRE_PRESSURE_MIN, 252.0, PressureUnit.PSI, Locale.FRANCE))
        assertEquals("252" to "kPa", BubbleInfo.pressure(RuleSignal.TYRE_PRESSURE_RR, 252.0, PressureUnit.KPA, Locale.FRANCE))
        assertNull(BubbleInfo.pressure(RuleSignal.TYRE_PRESSURE_FL, null, PressureUnit.BAR, Locale.FRANCE))
        assertNull(BubbleInfo.pressure(RuleSignal.TYRE_TEMP_MAX, 16.0, PressureUnit.BAR, Locale.FRANCE))
        val choix = BubbleInfo.choices()
        listOf(RuleSignal.TYRE_PRESSURE_MIN, RuleSignal.TYRE_PRESSURE_FL, RuleSignal.TYRE_TEMP_MAX, RuleSignal.TYRE_ALERT)
            .forEach { assertEquals(it.name, true, it.name in choix) }
    }
}
