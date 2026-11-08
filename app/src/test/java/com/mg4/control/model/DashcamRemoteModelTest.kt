package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pilotage de l'appli caméra pendant la surveillance (issue #180) : reconnaître l'appli d'après
 * ses récepteurs, durée demandée, compteur « REC », estimation de la place — sans Android.
 */
class DashcamRemoteModelTest {

    private val kamera = "com.drivehub.kamera"
    private val p = "$kamera.action."

    /** Les récepteurs publiés par jamakr4/MG4-360-Camera-App (branche de l'issue #96). */
    private val jamakr4 = listOf(
        DashcamContract.Receiver(kamera, "$kamera.remote.StartContinuousReceiver", listOf("${p}START_CONTINUOUS", "com.mg4.control.action.SHORTCUT")),
        DashcamContract.Receiver(kamera, "$kamera.remote.StopContinuousReceiver", listOf("${p}STOP_CONTINUOUS", "com.mg4.control.action.SHORTCUT")),
        DashcamContract.Receiver(kamera, "$kamera.remote.QueryStateReceiver", listOf("${p}QUERY_STATE", "com.mg4.control.action.SHORTCUT")),
        DashcamContract.Receiver(kamera, "$kamera.remote.TestUsbWriteReceiver", listOf("${p}TEST_USB_WRITE", "com.mg4.control.action.SHORTCUT")),
        DashcamContract.Receiver(kamera, "$kamera.dashcam.DashcamEventTriggerReceiver", listOf("${p}TRIGGER_DASHCAM_EVENT", "com.mg4.control.action.SHORTCUT")),
    )

    @Test
    fun `l'appli de jamakr4 est reconnue avec ses quatre commandes`() {
        val c = DashcamContract.find(jamakr4)!!
        assertEquals(kamera, c.packageName)
        assertEquals(p, c.prefix)
        assertEquals("$kamera.remote.StartContinuousReceiver", c.startClass)
        assertEquals("$kamera.remote.StopContinuousReceiver", c.stopClass)
        assertEquals("$kamera.remote.QueryStateReceiver", c.queryClass)
        assertEquals("$kamera.remote.TestUsbWriteReceiver", c.testUsbClass)
        assertEquals("${p}RECORDING_STATE", c.action(DashcamContract.STATE))
    }

    @Test
    fun `un fork qui garde le code sous un autre paquet est reconnu aussi`() {
        val fork = jamakr4.map { it.copy(packageName = "org.fork.cam") }
        assertEquals("org.fork.cam", DashcamContract.find(fork)!!.packageName)
    }

    @Test
    fun `sans STOP, ou avec seulement l'evenement dashcam, rien n'est pilotable`() {
        assertNull(DashcamContract.find(jamakr4.filterNot { "STOP_CONTINUOUS" in it.actions.first() }))
        assertNull(DashcamContract.find(jamakr4.takeLast(1)))
        assertNull(DashcamContract.find(emptyList()))
    }

    @Test
    fun `START et STOP doivent venir de la meme appli`() {
        val melange = listOf(jamakr4[0], jamakr4[1].copy(packageName = "autre.appli"))
        assertNull(DashcamContract.find(melange))
    }

    @Test
    fun `la question d'etat et le test de cle sont facultatifs`() {
        val c = DashcamContract.find(jamakr4.take(2))!!
        assertNull(c.queryClass)
        assertNull(c.testUsbClass)
    }

    @Test
    fun `duree demandee = surveillance + decompte, plafonnee a 120`() {
        assertEquals(31, DashcamContract.maxMinutes(30, 30))
        assertEquals(60, DashcamContract.maxMinutes(60, 0))
        assertEquals(62, DashcamContract.maxMinutes(60, 61))
        assertEquals(120, DashcamContract.maxMinutes(120, 30))
        assertEquals(1, DashcamContract.maxMinutes(0, -5))
    }

    @Test
    fun `estimation de la duree qui tient sur la cle`() {
        val gio = 1024L * 1024 * 1024
        assertEquals(120, DashcamContract.minutesFor(9 * gio))
        assertEquals(60, DashcamContract.minutesFor(9 * gio / 2))
        assertEquals(0, DashcamContract.minutesFor(0))
        assertNull(DashcamContract.minutesFor(-1))
    }

    @Test
    fun `etats lus sans tenir compte de la casse, inconnu sinon`() {
        assertEquals(DashcamRecState.STARTED, DashcamRecState.parse("started"))
        assertEquals(DashcamRecState.PAUSED, DashcamRecState.parse(" PAUSED "))
        assertEquals(DashcamRecState.UNKNOWN, DashcamRecState.parse("recording"))
        assertEquals(DashcamRecState.UNKNOWN, DashcamRecState.parse(null))
    }

    @Test
    fun `REC compte le temps filme, fige pendant la pause`() {
        val h = RecClock()
        h.onState(DashcamRecState.STARTING, 0)
        assertFalse(h.visible)
        h.onState(DashcamRecState.STARTED, 5_000)
        assertTrue(h.visible)
        assertEquals(10_000, h.elapsedMs(15_000))
        h.onState(DashcamRecState.PAUSED, 20_000)
        assertTrue(h.visible)
        assertEquals(15_000, h.elapsedMs(90_000))
        h.onState(DashcamRecState.STARTED, 100_000)
        assertEquals(25_000, h.elapsedMs(110_000))
        // Un « started » répété ne remet pas le départ à zéro.
        h.onState(DashcamRecState.STARTED, 110_000)
        assertEquals(35_000, h.elapsedMs(120_000))
    }

    @Test
    fun `REC disparait en erreur ou a l'arret, et un etat inconnu ne change rien`() {
        val h = RecClock()
        h.onState(DashcamRecState.STARTED, 0)
        h.onState(DashcamRecState.UNKNOWN, 1_000)
        assertTrue(h.visible)
        h.onState(DashcamRecState.ERROR, 2_000)
        assertFalse(h.visible)
        h.reset()
        h.onState(DashcamRecState.PAUSED, 0)
        assertFalse(h.visible)
    }

    @Test
    fun `format de la duree`() {
        assertEquals("00:00:00", RecClock.format(0))
        assertEquals("00:12:34", RecClock.format(754_999))
        assertEquals("02:00:00", RecClock.format(7_200_000))
    }
}
