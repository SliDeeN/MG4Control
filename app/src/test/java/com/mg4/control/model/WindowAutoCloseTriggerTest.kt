package com.mg4.control.model

import com.mg4.control.model.WindowAutoCloseTrigger.Action
import com.mg4.control.model.WindowAutoCloseTrigger.Arming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fermeture automatique des vitres en quittant la voiture — logique de winclose :
 * armée après avoir roulé (vitesse et/ou durée, réglables), déclenchée quand la voiture sort du
 * mode READY en P (sur MG4 : ouverture de la porte conducteur), fermeture après un délai réglable,
 * annulée si la voiture repasse en READY ou si le levier quitte P.
 */
class WindowAutoCloseTriggerTest {

    private val speedOrTime = Arming(speedKmh = 20f, afterMs = 300_000, requireBoth = false)

    private fun trigger(arming: Arming = speedOrTime) =
        WindowAutoCloseTrigger(arming, delayMs = 5_000, cooldownMs = 60_000)

    /** READY à t=0 puis 25 km/h atteints : déclencheur armé. */
    private fun armed(): WindowAutoCloseTrigger = trigger().apply {
        onReady(0)
        onTick(1_000, speedKmh = 25f, inPark = false)
    }

    // ── Lecture brute de READY ──────────────────────────────────────────────

    @Test
    fun `valeur brute de READY`() {
        assertEquals(true, WindowAutoCloseTrigger.readyFromRaw(1))
        assertEquals(false, WindowAutoCloseTrigger.readyFromRaw(0))
        // Hors 0/1 (signal invalide, lecture en échec) : on ne conclut rien.
        assertNull(WindowAutoCloseTrigger.readyFromRaw(-1))
        assertNull(WindowAutoCloseTrigger.readyFromRaw(255))
        assertNull(WindowAutoCloseTrigger.readyFromRaw(null))
    }

    // ── Armement ────────────────────────────────────────────────────────────

    @Test
    fun `non arme au passage en READY`() {
        val t = trigger()
        t.onReady(0)
        assertFalse(t.isArmed)
        assertEquals(Action.NONE, t.onReadyLost(1_000, inPark = true).action)
    }

    @Test
    fun `arme par la vitesse`() {
        val t = trigger()
        t.onReady(0)
        t.onTick(1_000, speedKmh = 19.9f, inPark = false)
        assertFalse(t.isArmed)
        t.onTick(2_000, speedKmh = 20f, inPark = false)
        assertTrue(t.isArmed)
    }

    @Test
    fun `arme par la duree en READY`() {
        val t = trigger()
        t.onReady(0)
        t.onTick(299_999, speedKmh = 0f, inPark = true)
        assertFalse(t.isArmed)
        t.onTick(300_000, speedKmh = 0f, inPark = true)
        assertTrue(t.isArmed)
    }

    @Test
    fun `hors READY la duree ne compte pas`() {
        val t = trigger(Arming(speedKmh = null, afterMs = 60_000, requireBoth = false))
        t.onTick(3_600_000, speedKmh = 0f, inPark = true)     // jamais passée en READY
        assertFalse(t.isArmed)
    }

    @Test
    fun `vitesse illisible n'arme pas`() {
        val t = trigger()
        t.onReady(0)
        t.onTick(1_000, speedKmh = null, inPark = null)
        assertFalse(t.isArmed)
    }

    @Test
    fun `vitesse seule - la duree n'arme pas`() {
        val t = trigger(Arming(speedKmh = 30f, afterMs = null, requireBoth = false))
        t.onReady(0)
        t.onTick(3_600_000, speedKmh = 10f, inPark = true)
        assertFalse(t.isArmed)
        t.onTick(3_601_000, speedKmh = 30f, inPark = false)
        assertTrue(t.isArmed)
    }

    @Test
    fun `duree seule - la vitesse n'arme pas`() {
        val t = trigger(Arming(speedKmh = null, afterMs = 60_000, requireBoth = false))
        t.onReady(0)
        t.onTick(10_000, speedKmh = 130f, inPark = false)
        assertFalse(t.isArmed)
        t.onTick(60_000, speedKmh = 0f, inPark = true)
        assertTrue(t.isArmed)
    }

    @Test
    fun `les deux conditions - vitesse atteinte plus tot puis duree`() {
        val t = trigger(speedOrTime.copy(requireBoth = true))
        t.onReady(0)
        t.onTick(10_000, speedKmh = 50f, inPark = false)
        assertFalse(t.isArmed)
        t.onTick(300_000, speedKmh = 0f, inPark = true)
        assertTrue(t.isArmed)
    }

    @Test
    fun `les deux conditions - la duree seule ne suffit pas`() {
        val t = trigger(speedOrTime.copy(requireBoth = true))
        t.onReady(0)
        t.onTick(600_000, speedKmh = 10f, inPark = false)
        assertFalse(t.isArmed)
    }

    @Test
    fun `aucune condition = jamais arme`() {
        val none = Arming(speedKmh = null, afterMs = null, requireBoth = false)
        assertFalse(none.isValid)
        val t = trigger(none)
        t.onReady(0)
        t.onTick(3_600_000, speedKmh = 130f, inPark = true)
        assertFalse(t.isArmed)
    }

    @Test
    fun `reglage modifie pris en compte au tic suivant`() {
        val t = trigger()
        t.onReady(0)
        t.onTick(1_000, speedKmh = 15f, inPark = false)
        assertFalse(t.isArmed)
        t.arming = speedOrTime.copy(speedKmh = 10f)
        t.onTick(2_000, speedKmh = 15f, inPark = false)
        assertTrue(t.isArmed)
    }

    // ── Déclenchement ───────────────────────────────────────────────────────

    @Test
    fun `sortie de READY en P apres avoir roule = fermeture programmee`() {
        val t = armed()
        assertEquals(Action.SCHEDULE, t.onReadyLost(10_000, inPark = true).action)
        assertTrue(t.isPending)
    }

    @Test
    fun `sortie de READY hors P ou rapport inconnu = rien`() {
        assertEquals(Action.NONE, armed().onReadyLost(10_000, inPark = false).action)
        assertEquals(Action.NONE, armed().onReadyLost(10_000, inPark = null).action)
    }

    @Test
    fun `fermeture une fois le delai ecoule`() {
        val t = armed()
        t.onReadyLost(10_000, inPark = true)
        assertEquals(Action.NONE, t.onTick(14_999, speedKmh = 0f, inPark = true).action)
        assertEquals(Action.CLOSE, t.onTick(15_000, speedKmh = 0f, inPark = true).action)
        assertFalse(t.isPending)
    }

    @Test
    fun `delai reglable, y compris zero`() {
        val t = armed()
        t.delayMs = 30_000
        t.onReadyLost(10_000, inPark = true)
        assertEquals(Action.NONE, t.onTick(39_999, speedKmh = 0f, inPark = true).action)
        assertEquals(Action.CLOSE, t.onTick(40_000, speedKmh = 0f, inPark = true).action)

        val immediate = armed().apply { delayMs = 0 }
        immediate.onReadyLost(10_000, inPark = true)
        assertEquals(Action.CLOSE, immediate.onTick(10_000, speedKmh = 0f, inPark = true).action)
    }

    @Test
    fun `retour en READY pendant le delai = annulation et nouveau trajet`() {
        val t = armed()
        t.onReadyLost(10_000, inPark = true)
        assertEquals(Action.CANCEL, t.onReady(12_000).action)
        assertFalse(t.isPending)
        assertFalse(t.isArmed)
        assertEquals(Action.NONE, t.onTick(20_000, speedKmh = 0f, inPark = true).action)
    }

    @Test
    fun `retour en READY sans attente = simple nouveau trajet`() {
        assertEquals(Action.NONE, armed().onReady(50_000).action)
    }

    @Test
    fun `levier sorti de P pendant le delai = annulation`() {
        val t = armed()
        t.onReadyLost(10_000, inPark = true)
        assertEquals(Action.CANCEL, t.onTick(11_000, speedKmh = 0f, inPark = false).action)
    }

    @Test
    fun `rapport illisible pendant le delai n'annule pas`() {
        val t = armed()
        t.onReadyLost(10_000, inPark = true)
        assertEquals(Action.CLOSE, t.onTick(15_000, speedKmh = null, inPark = null).action)
    }

    @Test
    fun `abandon explicite de l'attente`() {
        val t = armed()
        assertFalse(t.cancelPending())
        t.onReadyLost(10_000, inPark = true)
        assertTrue(t.cancelPending())
        assertEquals(Action.NONE, t.onTick(20_000, speedKmh = 0f, inPark = true).action)
    }

    @Test
    fun `un declenchement desarme`() {
        val t = armed()
        t.onReadyLost(10_000, inPark = true)
        t.onTick(15_000, speedKmh = 0f, inPark = true)
        assertFalse(t.isArmed)
        assertEquals(Action.NONE, t.onReadyLost(200_000, inPark = true).action)
    }

    @Test
    fun `pause entre deux declenchements`() {
        val t = armed()
        t.onReadyLost(10_000, inPark = true)
        t.onReady(11_000)                                    // annulation, nouveau trajet
        t.onTick(12_000, speedKmh = 30f, inPark = false)     // réarmé en roulant
        assertEquals(Action.NONE, t.onReadyLost(40_000, inPark = true).action)
        t.onReady(41_000)
        t.onTick(42_000, speedKmh = 30f, inPark = false)
        assertEquals(Action.SCHEDULE, t.onReadyLost(70_000, inPark = true).action)
    }
}
