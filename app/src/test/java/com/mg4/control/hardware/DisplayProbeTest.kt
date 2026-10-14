package com.mg4.control.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les parties pures de la sonde des écrans : noms lisibles et tri du dump du service display. */
class DisplayProbeTest {

    @Test fun `les drapeaux connus sont nommes, les autres gardes en hexadecimal`() {
        assertEquals("aucun", DisplayProbe.nomsDrapeaux(0))
        assertEquals("PRIVATE|PRESENTATION", DisplayProbe.nomsDrapeaux(0x4 or 0x8))
        assertEquals("SECURE|0x100", DisplayProbe.nomsDrapeaux(0x2 or 0x100))
    }

    @Test fun `etat et type inconnus restent lisibles`() {
        assertEquals("ON", DisplayProbe.nomEtat(2))
        assertEquals("inconnu(42)", DisplayProbe.nomEtat(42))
        assertEquals("BUILT_IN", DisplayProbe.nomType(1))
        assertEquals("inconnu(9)", DisplayProbe.nomType(9))
    }

    @Test fun `seules les descriptions des peripheriques et des ecrans logiques sont gardees`() {
        val dump = listOf(
            "DISPLAY MANAGER (dumpsys display)",
            "Display Devices: size=2",
            "  DisplayDeviceInfo{\"Built-in Screen\": uniqueId=\"local:0\", 1920 x 720, type BUILT_IN}",
            "    mAdapter=LocalDisplayAdapter",
            "  DisplayDeviceInfo{\"HDMI Screen\": uniqueId=\"local:1\", 1280 x 480, type HDMI}",
            "Logical Displays: size=2",
            "    mBaseDisplayInfo=DisplayInfo{\"HDMI Screen\", displayId 1, real 1280 x 480}",
        )
        val gardees = DisplayProbe.lignesUtiles(dump)
        assertEquals(3, gardees.size)
        assertTrue(gardees[1].startsWith("  DisplayDeviceInfo{\"HDMI Screen\""))
        assertTrue(gardees[2].startsWith("  mBaseDisplayInfo="))
    }

    @Test fun `une ligne demesuree est coupee`() {
        val longue = "DisplayDeviceInfo{" + "x".repeat(2000) + "}"
        val gardee = DisplayProbe.lignesUtiles(listOf(longue)).single()
        assertTrue(gardee.endsWith(" …"))
        assertTrue(gardee.length < 800)
    }
}
