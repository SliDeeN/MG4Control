package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #251 : relancer la détection d'un téléphone branché avant l'écran. */
class UsbPhoneTest {

    private fun dev(vid: Int, pid: Int, bus: String = "002", classes: List<Int> = listOf(6)) =
        UsbSeen(vid, pid, "/dev/bus/usb/$bus/003", classes)

    @Test fun `iPhone reconnu comme AllGo`() {
        assertEquals(UsbPhoneKind.IPHONE, UsbPhoneRule.kind(dev(0x05ac, 0x12a8)))
        // Autre appareil Apple (iPod, clavier…) : AllGo ne le prend pas pour CarPlay.
        assertNull(UsbPhoneRule.kind(dev(0x05ac, 0x1234)))
    }

    @Test fun `telephone Android, Pixel compris, hors AOAP`() {
        assertEquals(UsbPhoneKind.ANDROID, UsbPhoneRule.kind(dev(0x04e8, 0x6860)))
        assertEquals(UsbPhoneKind.ANDROID, UsbPhoneRule.kind(dev(0x18d1, 0x4ee1)))
        assertNull(UsbPhoneRule.kind(dev(0x18d1, 0x2d01)))
        assertTrue(UsbPhoneRule.anyAoap(listOf(dev(0x18d1, 0x2d00))))
        assertFalse(UsbPhoneRule.anyAoap(listOf(dev(0x18d1, 0x4ee1))))
        val bascule = dev(0x18d1, 0x2d01)
        assertEquals(bascule, UsbPhoneRule.firstAoap(listOf(dev(0x0781, 0x5581, classes = listOf(8)), bascule)))
        assertNull(UsbPhoneRule.firstAoap(listOf(dev(0x18d1, 0x2d00, bus = "001"))))
    }

    @Test fun `port interne, hubs, cles et root hubs ecartes`() {
        assertNull(UsbPhoneRule.kind(dev(0x04e8, 0x6860, bus = "001")))
        assertNull(UsbPhoneRule.kind(dev(0x05ac, 0x12a8, bus = "001")))
        assertNull(UsbPhoneRule.kind(dev(0x0781, 0x5581, classes = listOf(8))))
        assertNull(UsbPhoneRule.kind(dev(0x05e3, 0x0610, classes = listOf(9))))
        assertNull(UsbPhoneRule.kind(dev(0x1d6b, 0x0002)))
        assertTrue(UsbPhoneRule.isGrayPort("/dev/bus/usb/001/002"))
        assertFalse(UsbPhoneRule.isGrayPort("/dev/bus/usb/002/001"))
        assertFalse(UsbPhoneRule.isGrayPort(null))
    }

    @Test fun `Android d abord s il y a les deux, sinon l iPhone`() {
        val iphone = dev(0x05ac, 0x12a8)
        val android = dev(0x04e8, 0x6860)
        val cle = dev(0x0781, 0x5581, classes = listOf(8))
        assertEquals(android, UsbPhoneRule.pick(listOf(iphone, cle, android)))
        assertEquals(iphone, UsbPhoneRule.pick(listOf(cle, iphone)))
        assertNull(UsbPhoneRule.pick(listOf(cle)))
    }

    @Test fun `demarrage recent et vraie veille`() {
        assertTrue(ProjectionKickRule.recentBoot(40_000, 180_000))
        assertFalse(ProjectionKickRule.recentBoot(600_000, 180_000))
        assertTrue(ProjectionKickRule.slept(1_000, 3_700_000, 60_000))
        assertFalse(ProjectionKickRule.slept(1_000, 5_000, 60_000))
    }
}
