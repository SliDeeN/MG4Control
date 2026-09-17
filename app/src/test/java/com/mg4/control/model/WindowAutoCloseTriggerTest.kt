package com.mg4.control.model

import com.mg4.control.model.WindowAutoCloseTrigger.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fermeture automatique des vitres en quittant la voiture — logique reprise de winclose :
 * armée après avoir roulé (vitesse ou durée), déclenchée par l'ouverture de la porte conducteur
 * en P, fermeture après un délai, annulée si la porte se rouvre ou si le levier quitte P.
 */
class WindowAutoCloseTriggerTest {

    private fun trigger() = WindowAutoCloseTrigger(armSpeedKmh = 20f, armAfterMs = 300_000, delayMs = 5_000, cooldownMs = 60_000)

    /** Voiture démarrée à t=0 puis 25 km/h atteints : déclencheur armé. */
    private fun armed(): WindowAutoCloseTrigger = trigger().apply {
        onIgnitionRun(0)
        onTick(1_000, speedKmh = 25f, inPark = false)
    }

    @Test
    fun `non arme au demarrage`() {
        val t = trigger()
        t.onIgnitionRun(0)
        assertFalse(t.isArmed)
        assertEquals(Action.NONE, t.onDriverDoorOpened(1_000, inPark = true).action)
    }

    @Test
    fun `arme par la vitesse`() {
        val t = trigger()
        t.onIgnitionRun(0)
        t.onTick(1_000, speedKmh = 19.9f, inPark = false)
        assertFalse(t.isArmed)
        t.onTick(2_000, speedKmh = 20f, inPark = false)
        assertTrue(t.isArmed)
    }

    @Test
    fun `arme par la duree de marche`() {
        val t = trigger()
        t.onIgnitionRun(0)
        t.onTick(299_999, speedKmh = 0f, inPark = true)
        assertFalse(t.isArmed)
        t.onTick(300_000, speedKmh = 0f, inPark = true)
        assertTrue(t.isArmed)
    }

    @Test
    fun `vitesse illisible n'arme pas`() {
        val t = trigger()
        t.onIgnitionRun(0)
        t.onTick(1_000, speedKmh = null, inPark = null)
        assertFalse(t.isArmed)
    }

    @Test
    fun `porte ouverte en P apres avoir roule = fermeture programmee`() {
        val t = armed()
        assertEquals(Action.SCHEDULE, t.onDriverDoorOpened(10_000, inPark = true).action)
        assertTrue(t.isPending)
    }

    @Test
    fun `porte ouverte hors P ou rapport inconnu = rien`() {
        assertEquals(Action.NONE, armed().onDriverDoorOpened(10_000, inPark = false).action)
        assertEquals(Action.NONE, armed().onDriverDoorOpened(10_000, inPark = null).action)
    }

    @Test
    fun `extinction avant l'ouverture de porte conserve l'armement`() {
        // Sur un VE, le conducteur éteint la voiture AVANT d'ouvrir la porte.
        val t = armed()
        t.onIgnitionOff()
        assertEquals(Action.SCHEDULE, t.onDriverDoorOpened(10_000, inPark = true).action)
    }

    @Test
    fun `fermeture une fois le delai ecoule`() {
        val t = armed()
        t.onDriverDoorOpened(10_000, inPark = true)
        assertEquals(Action.NONE, t.onTick(14_999, speedKmh = 0f, inPark = true).action)
        assertEquals(Action.CLOSE, t.onTick(15_000, speedKmh = 0f, inPark = true).action)
        assertFalse(t.isPending)
    }

    @Test
    fun `porte rouverte pendant le delai = annulation`() {
        val t = armed()
        t.onDriverDoorOpened(10_000, inPark = true)
        assertEquals(Action.CANCEL, t.onDriverDoorOpened(12_000, inPark = true).action)
        assertFalse(t.isPending)
        assertEquals(Action.NONE, t.onTick(20_000, speedKmh = 0f, inPark = true).action)
    }

    @Test
    fun `abandon explicite de l'attente`() {
        val t = armed()
        assertFalse(t.cancelPending())
        t.onDriverDoorOpened(10_000, inPark = true)
        assertTrue(t.cancelPending())
        assertEquals(Action.NONE, t.onTick(20_000, speedKmh = 0f, inPark = true).action)
    }

    @Test
    fun `levier sorti de P pendant le delai = annulation`() {
        val t = armed()
        t.onDriverDoorOpened(10_000, inPark = true)
        assertEquals(Action.CANCEL, t.onTick(11_000, speedKmh = 0f, inPark = false).action)
    }

    @Test
    fun `rapport illisible pendant le delai n'annule pas`() {
        val t = armed()
        t.onDriverDoorOpened(10_000, inPark = true)
        assertEquals(Action.CLOSE, t.onTick(15_000, speedKmh = null, inPark = null).action)
    }

    @Test
    fun `un declenchement desarme jusqu'au prochain trajet`() {
        val t = armed()
        t.onDriverDoorOpened(10_000, inPark = true)
        t.onTick(15_000, speedKmh = 0f, inPark = true)
        assertFalse(t.isArmed)
        assertEquals(Action.NONE, t.onDriverDoorOpened(200_000, inPark = true).action)
    }

    @Test
    fun `nouveau trajet = nouvel armement necessaire`() {
        val t = armed()
        t.onIgnitionRun(500_000)
        assertFalse(t.isArmed)
    }

    @Test
    fun `rearme apres la duree de marche comptee depuis le declenchement`() {
        val t = armed()                                      // toujours en marche depuis t=0
        t.onDriverDoorOpened(400_000, inPark = true)
        t.onTick(405_000, speedKmh = 0f, inPark = true)      // fermeture
        t.onTick(600_000, speedKmh = 0f, inPark = true)
        assertFalse(t.isArmed)                               // la durée ne compte pas depuis le démarrage
        t.onTick(700_000, speedKmh = 0f, inPark = true)
        assertTrue(t.isArmed)
    }

    @Test
    fun `pause entre deux declenchements`() {
        val t = armed()
        t.onDriverDoorOpened(10_000, inPark = true)
        t.onDriverDoorOpened(11_000, inPark = true)          // annulation
        t.onTick(12_000, speedKmh = 30f, inPark = false)     // réarmé en roulant
        assertEquals(Action.NONE, t.onDriverDoorOpened(40_000, inPark = true).action)
        assertEquals(Action.SCHEDULE, t.onDriverDoorOpened(70_000, inPark = true).action)
    }
}
