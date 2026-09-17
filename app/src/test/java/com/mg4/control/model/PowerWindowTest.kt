package com.mg4.control.model

import com.mg4.control.model.WindowCommand.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vitres électriques — logique pure : propriétés, commandes, appui court et sécurité enfant.
 *
 * Les identifiants viennent des firmwares (VEHICLE_DRIVERWINDOW… dans com.android.car de SWI131,
 * SWI133 et SWI165) : une vitre inversée ferait bouger la mauvaise, sans rien signaler.
 */
class PowerWindowTest {

    @Test
    fun `chaque vitre pilote sa propriete du firmware`() {
        assertEquals(0x11603801, PowerWindow.FRONT_LEFT.propId)
        assertEquals(0x11603802, PowerWindow.FRONT_RIGHT.propId)
        assertEquals(0x11603803, PowerWindow.REAR_LEFT.propId)
        assertEquals(0x11603804, PowerWindow.REAR_RIGHT.propId)
    }

    @Test
    fun `seules les vitres arriere sont arriere`() {
        assertEquals(
            listOf(PowerWindow.REAR_LEFT, PowerWindow.REAR_RIGHT),
            PowerWindow.entries.filter { it.isRear }
        )
    }

    @Test
    fun `zones du verrou natif = rangee 2 gauche et droite`() {
        // VehicleAreaWindow AOSP : ROW_2_LEFT = 0x100, ROW_2_RIGHT = 0x400.
        assertEquals(0x100, PowerWindow.REAR_LEFT.lockArea)
        assertEquals(0x400, PowerWindow.REAR_RIGHT.lockArea)
    }

    @Test
    fun `seule la vitre conducteur a la descente auto native`() {
        // Mesuré en voiture le 2026-09-17 : la commande 4 n'ouvre que la vitre conducteur.
        assertEquals(listOf(PowerWindow.FRONT_LEFT), PowerWindow.entries.filter { it.hasNativeAutoDown })
    }

    @Test
    fun `position lue valide de 0 a 100`() {
        assertEquals(0f, WindowCommand.position(0f))
        assertEquals(42.5f, WindowCommand.position(42.5f))
        assertEquals(100f, WindowCommand.position(100f))
    }

    @Test
    fun `valeurs hors plage = vitre sans capteur`() {
        // Relevées en voiture : 127.5 (arrière) et 255 (passager) ne bougent jamais.
        assertNull(WindowCommand.position(127.5f))
        assertNull(WindowCommand.position(255f))
        assertNull(WindowCommand.position(-1f))
        assertNull(WindowCommand.position(Float.NaN))
        assertNull(WindowCommand.position(null))
    }

    @Test
    fun `commandes connues de winclose`() {
        assertEquals(0, WindowCommand.STOP)
        assertEquals(1, WindowCommand.manual(Direction.UP))
        assertEquals(3, WindowCommand.auto(Direction.UP))
        // Descente : supposée, à confirmer par le test brut en voiture.
        assertEquals(2, WindowCommand.manual(Direction.DOWN))
        assertEquals(4, WindowCommand.auto(Direction.DOWN))
    }

    @Test
    fun `seules les valeurs 0 a 7 sont envoyables`() {
        (0..7).forEach { assertTrue("valeur $it", WindowCommand.isValid(it)) }
        assertFalse(WindowCommand.isValid(-1))
        assertFalse(WindowCommand.isValid(8))
    }

    @Test
    fun `appui court sans mouvement auto en cours = commande auto`() {
        assertEquals(WindowCommand.AUTO_UP, WindowCommand.forShortPress(Direction.UP, lastAutoMs = null, nowMs = 10_000))
        assertEquals(WindowCommand.AUTO_DOWN, WindowCommand.forShortPress(Direction.DOWN, lastAutoMs = null, nowMs = 10_000))
    }

    @Test
    fun `appui court pendant un mouvement auto = stop, quel que soit le sens`() {
        val envoi = 10_000L
        assertEquals(WindowCommand.STOP, WindowCommand.forShortPress(Direction.UP, envoi, envoi + 1_000))
        assertEquals(WindowCommand.STOP, WindowCommand.forShortPress(Direction.DOWN, envoi, envoi + 1_000))
    }

    @Test
    fun `appui court une fois la course auto terminee = nouvelle commande auto`() {
        val envoi = 10_000L
        val apres = envoi + WindowCommand.AUTO_TRAVEL_MS
        assertEquals(WindowCommand.AUTO_DOWN, WindowCommand.forShortPress(Direction.DOWN, envoi, apres))
    }

    @Test
    fun `horloge qui recule = pas de stop`() {
        // Une heure système corrigée entre deux appuis ne doit pas bloquer la vitre en « stop ».
        assertEquals(WindowCommand.AUTO_UP, WindowCommand.forShortPress(Direction.UP, 50_000L, 10_000L))
    }

    @Test
    fun `securite enfant bloque les seules vitres arriere`() {
        assertTrue(WindowCommand.isBlocked(PowerWindow.REAR_LEFT, childLock = true))
        assertTrue(WindowCommand.isBlocked(PowerWindow.REAR_RIGHT, childLock = true))
        assertFalse(WindowCommand.isBlocked(PowerWindow.FRONT_LEFT, childLock = true))
        assertFalse(WindowCommand.isBlocked(PowerWindow.FRONT_RIGHT, childLock = true))
        PowerWindow.entries.forEach { assertFalse(WindowCommand.isBlocked(it, childLock = false)) }
    }

    @Test
    fun `tout ouvrir ou fermer ignore les vitres bloquees`() {
        assertEquals(PowerWindow.entries.toList(), WindowCommand.targetsForAll(childLock = false))
        assertEquals(
            listOf(PowerWindow.FRONT_LEFT, PowerWindow.FRONT_RIGHT),
            WindowCommand.targetsForAll(childLock = true)
        )
    }
}
