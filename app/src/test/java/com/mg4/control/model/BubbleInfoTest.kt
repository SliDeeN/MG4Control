package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Bulles d'information (issue #236) : ce qui s'affiche, et une bulle d'avant inchangée. */
class BubbleInfoTest {

    private fun bulle() = FloatingButton(id = "a", action = "CHARGE_POPUP")

    @Test
    fun `an existing bubble stays a title bubble that runs its action`() {
        val b = bulle()
        assertFalse(BubbleInfo.isInfo(b))
        assertTrue(BubbleInfo.tapRuns(b))
        assertEquals(BubbleInfo.STYLE_LABEL, BubbleInfo.style(b))
        assertFalse(BubbleInfo.tapRuns(b.copy(tapAction = false)))
        assertEquals(BubbleInfo.STYLE_ICON, BubbleInfo.style(b.copy(infoStyle = BubbleInfo.STYLE_ICON)))
        assertEquals(BubbleInfo.STYLE_LABEL, BubbleInfo.style(b.copy(infoStyle = "inconnu")))
    }

    @Test
    fun `values are formatted with units, the screen's decimal separator, and a dash when unreadable`() {
        val fr = Locale.FRANCE
        assertEquals("64 %", BubbleInfo.format(64.0, " %", "ON", "OFF", locale = fr))
        assertEquals("21,5 °C", BubbleInfo.format(21.5, " °C", "ON", "OFF", locale = fr))
        assertEquals("21.5 °C", BubbleInfo.format(21.5, " °C", "ON", "OFF", locale = Locale.UK))
        assertEquals("ON", BubbleInfo.format(true, "", "ON", "OFF"))
        assertEquals("OFF", BubbleInfo.format(false, "", "ON", "OFF"))
        assertEquals("Éco", BubbleInfo.format("ECO", "", "ON", "OFF", { if (it == "ECO") "Éco" else it }))
        assertEquals(BubbleInfo.UNKNOWN, BubbleInfo.format(null, " %", "ON", "OFF"))
        assertEquals(BubbleInfo.UNKNOWN, BubbleInfo.format(Double.NaN, " %", "ON", "OFF"))
        assertEquals(BubbleInfo.UNKNOWN, BubbleInfo.format("  ", "", "ON", "OFF"))
    }

    @Test
    fun `a yes-no value colours the bubble, other values do not`() {
        assertEquals(true, BubbleInfo.toggleState(true))
        assertEquals(false, BubbleInfo.toggleState(false))
        assertNull(BubbleInfo.toggleState(64.0))
        assertNull(BubbleInfo.toggleState(null))
    }

    @Test
    fun `choices start with the most useful, keep time, and drop what the car cannot read`() {
        val tout = BubbleInfo.choices()
        assertEquals(BubbleInfo.FIRST, tout.take(BubbleInfo.FIRST.size))
        assertTrue(BubbleInfo.TIME in tout)
        assertFalse(RuleSignal.FIRMWARE.name in tout)
        // Retirée des automatisations, la clé éloignée ne se propose nulle part (2026-11-12).
        assertFalse(RuleSignal.KEY_AWAY.name in tout)
        assertEquals(tout.size, tout.distinct().size)
        val sansClim = BubbleInfo.choices { it != RuleSignal.HVAC_TEMP }
        assertFalse(RuleSignal.HVAC_TEMP.name in sansClim)
        assertTrue(BubbleInfo.fast(BubbleInfo.TIME) && BubbleInfo.fast(RuleSignal.SPEED.name))
        assertFalse(BubbleInfo.fast(RuleSignal.SOC.name))
    }
}
