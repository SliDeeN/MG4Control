package com.mg4.control.model

import com.mg4.control.model.LeaveLock.Reading
import com.mg4.control.model.LeaveLock.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Écran d'essai du réglage d'origine « Lock when leaving » (`COMFORT_LOCK`, 0x2140d710) — ce que
 * l'écran affiche et ce qu'il conclut d'une consigne.
 *
 * L'écran de réglages d'origine masque la ligne quand la voiture répond 3 ; c'est le cas sur la
 * voiture d'essai (SWI133, 2026-10-11). L'essai consiste à envoyer quand même la consigne de
 * l'interrupteur d'origine, et à lire ce que la voiture répond ensuite : ces tests fixent la
 * lecture de cette réponse, pour ne pas prendre un refus pour un succès.
 */
class LeaveLockTest {

    // ── Sens d'une valeur lue ────────────────────────────────────────────────

    @Test
    fun `les trois valeurs que l ecran d origine interprete sont nommees`() {
        assertEquals("0 = coupé", LeaveLock.describe(0))
        assertEquals("1 = actif", LeaveLock.describe(1))
        assertEquals("3 = indisponible (ligne masquée par l\u2019origine)", LeaveLock.describe(3))
    }

    @Test
    fun `une valeur que l ecran d origine ne connait pas est dite inconnue`() {
        assertEquals("2 = valeur inconnue du code d\u2019origine", LeaveLock.describe(2))
        assertEquals("7 = valeur inconnue du code d\u2019origine", LeaveLock.describe(7))
    }

    @Test
    fun `une lecture impossible n est pas prise pour une valeur`() {
        assertEquals("illisible", LeaveLock.describe(null))
    }

    @Test
    fun `moins un rendu par une voie d origine est une lecture impossible`() {
        // VehiclePropertyManager et nos accesseurs rendent -1 quand le service ne répond pas.
        assertNull(LeaveLock.raw(-1))
        assertEquals(3, LeaveLock.raw(3))
        assertEquals(0, LeaveLock.raw(0))
    }

    // ── Suite d'une consigne ─────────────────────────────────────────────────

    @Test
    fun `activation refletee par la voiture`() {
        assertEquals(Verdict.ACCEPTED_ON, LeaveLock.verdict(on = true, after = 1))
    }

    @Test
    fun `coupure refletee par la voiture`() {
        assertEquals(Verdict.ACCEPTED_OFF, LeaveLock.verdict(on = false, after = 0))
    }

    @Test
    fun `consigne ignoree quand la voiture repond toujours indisponible`() {
        assertEquals(Verdict.IGNORED, LeaveLock.verdict(on = true, after = 3))
        assertEquals(Verdict.IGNORED, LeaveLock.verdict(on = false, after = 3))
    }

    @Test
    fun `consigne non refletee quand la voiture repond autre chose`() {
        // Activer et relire « coupé », ou l'inverse : la consigne n'a pas pris.
        assertEquals(Verdict.NOT_REFLECTED, LeaveLock.verdict(on = true, after = 0))
        assertEquals(Verdict.NOT_REFLECTED, LeaveLock.verdict(on = false, after = 1))
        assertEquals(Verdict.NOT_REFLECTED, LeaveLock.verdict(on = true, after = 2))
    }

    @Test
    fun `sans relecture il n y a rien a conclure`() {
        assertEquals(Verdict.UNREADABLE, LeaveLock.verdict(on = true, after = null))
    }

    @Test
    fun `seule l activation refletee invite a s eloigner avec la cle`() {
        assertTrue(Verdict.ACCEPTED_ON.text.contains("clé"))
        Verdict.values().filter { it != Verdict.ACCEPTED_ON }.forEach {
            assertTrue("${it.name} ne doit pas inviter à l'essai", !it.text.contains("éloigne"))
        }
    }

    // ── Verrouillage central et présence de la clé (contexte de l'essai) ─────

    @Test
    fun `zero est deverrouille et toute valeur positive verrouille`() {
        assertEquals("0 = déverrouillé", LeaveLock.lockState(0))
        assertEquals("1 = verrouillé", LeaveLock.lockState(1))
        assertEquals("4 = verrouillé", LeaveLock.lockState(4))
        assertEquals("illisible", LeaveLock.lockState(null))
    }

    @Test
    fun `le chargeur sans fil a huit signale la cle non detectee`() {
        assertEquals("8 = clé non détectée", LeaveLock.keyState(8))
    }

    @Test
    fun `les autres etats du chargeur sont donnes tels quels`() {
        // 2 (en charge) et 1 (en attente) ont été vus clé présente ; 0 est attendu sans chargeur.
        assertEquals("2", LeaveLock.keyState(2))
        assertEquals("0", LeaveLock.keyState(0))
        assertEquals("illisible", LeaveLock.keyState(null))
    }

    // ── Journal : ce qui a bougé entre deux lectures ─────────────────────────

    @Test
    fun `seules les valeurs qui ont change sont signalees dans l ordre de l ecran`() {
        val avant = listOf(Reading("Verrouillage", "0 = déverrouillé"), Reading("Clé", "2"), Reading("READY", "1"))
        val apres = listOf(Reading("Verrouillage", "1 = verrouillé"), Reading("Clé", "8 = clé non détectée"), Reading("READY", "1"))
        assertEquals(
            listOf("Verrouillage : 0 = déverrouillé \u2192 1 = verrouillé", "Clé : 2 \u2192 8 = clé non détectée"),
            LeaveLock.changes(avant, apres))
    }

    @Test
    fun `rien a signaler quand rien n a bouge`() {
        val lecture = listOf(Reading("Verrouillage", "0 = déverrouillé"), Reading("Clé", "2"))
        assertEquals(emptyList<String>(), LeaveLock.changes(lecture, lecture))
    }

    @Test
    fun `une valeur absente de la lecture precedente n est pas un changement`() {
        val avant = listOf(Reading("Clé", "2"))
        val apres = listOf(Reading("Clé", "2"), Reading("READY", "1"))
        assertEquals(emptyList<String>(), LeaveLock.changes(avant, apres))
    }
}
