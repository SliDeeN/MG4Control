package com.mg4.control.model

import com.mg4.control.model.WindowCommand.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vitres électriques — logique pure : propriétés, commandes, appui court, capteurs.
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
    fun `seule la vitre conducteur a les courses auto natives`() {
        // Mesuré en voiture : 4 n'ouvre que la vitre conducteur, et 3 a cessé de fermer les autres
        // après la calibration — leurs courses auto sont donc émulées dans les deux sens.
        assertEquals(listOf(PowerWindow.FRONT_LEFT), PowerWindow.entries.filter { it.hasNativeAuto })
    }

    @Test
    fun `seule la vitre conducteur a un capteur de position`() {
        // Mesuré en voiture : les autres restent figées sur 127.5 / 255 → calibration possible.
        assertEquals(listOf(PowerWindow.FRONT_LEFT), PowerWindow.entries.filter { it.hasPositionSensor })
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
    fun `commandes connues`() {
        assertEquals(0, WindowCommand.STOP)
        assertEquals(1, WindowCommand.manual(Direction.UP))
        assertEquals(2, WindowCommand.manual(Direction.DOWN))
        assertEquals(3, WindowCommand.auto(Direction.UP))
        assertEquals(4, WindowCommand.auto(Direction.DOWN))
    }

    @Test
    fun `sens de mouvement de chaque commande`() {
        assertEquals(Direction.UP, WindowCommand.directionOf(WindowCommand.MANUAL_UP))
        assertEquals(Direction.UP, WindowCommand.directionOf(WindowCommand.AUTO_UP))
        assertEquals(Direction.DOWN, WindowCommand.directionOf(WindowCommand.MANUAL_DOWN))
        assertEquals(Direction.DOWN, WindowCommand.directionOf(WindowCommand.AUTO_DOWN))
        assertNull(WindowCommand.directionOf(WindowCommand.STOP))
        (5..7).forEach { assertNull("valeur $it", WindowCommand.directionOf(it)) }
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
    fun `course emulee sans calibration = duree par defaut, dans les deux sens`() {
        assertEquals(WindowCommand.EMULATED_COURSE_MS, WindowCommand.emulatedCourseMs(Direction.DOWN, null))
        assertEquals(WindowCommand.EMULATED_COURSE_MS, WindowCommand.emulatedCourseMs(Direction.UP, null))
    }

    @Test
    fun `course emulee calibree = duree mesuree du bon sens`() {
        val cal = WindowCalibration(downMs = 3_000, upMs = 3_500)
        assertEquals(cal.emulatedOpenMs, WindowCommand.emulatedCourseMs(Direction.DOWN, cal))
        assertEquals(cal.emulatedCloseMs, WindowCommand.emulatedCourseMs(Direction.UP, cal))
    }

    @Test
    fun `seule la fermeture emulee s'arrete quand on quitte l'onglet`() {
        // Pas d'anti-pincement sur une fermeture émulée : elle n'avance que sous les yeux de l'utilisateur.
        assertTrue(WindowCommand.stopsWhenUnattended(WindowCommand.MANUAL_UP))
        assertFalse(WindowCommand.stopsWhenUnattended(WindowCommand.MANUAL_DOWN))
    }
}
