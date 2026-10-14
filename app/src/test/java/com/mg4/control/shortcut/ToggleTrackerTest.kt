package com.mg4.control.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bascule d'un raccourci (One Pedal, économie d'énergie, alertes…) : l'état à inverser vient du
 * véhicule quand sa lecture fait foi, de la dernière consigne sinon. Issue #114 : le premier appui
 * réactivait un One Pedal déjà activé par le profil, faute de lire l'état réel.
 */
class ToggleTrackerTest {

    private val attente = ToggleTracker.SETTLE_MS

    /** Un appui complet : lecture, consigne inverse, puis relecture une fois le véhicule stabilisé. */
    private fun appui(t: ToggleTracker, lu: Boolean?, relu: Boolean?, maintenant: Long): Boolean {
        val cible = !t.current(lu, maintenant)
        t.onReadBack(t.onCommand(cible, maintenant), relu)
        return cible
    }

    @Test
    fun `premier appui sur un reglage deja actif le coupe`() {
        assertTrue(ToggleTracker().current(read = true, nowMs = 0))
    }

    @Test
    fun `premier appui sur un reglage inactif l active`() {
        assertFalse(ToggleTracker().current(read = false, nowMs = 0))
    }

    @Test
    fun `etat illisible sans consigne passe pour inactif`() {
        assertFalse(ToggleTracker().current(read = null, nowMs = 0))
    }

    @Test
    fun `etat illisible retombe sur la derniere consigne`() {
        val t = ToggleTracker()
        t.onCommand(true, nowMs = 0)
        assertTrue(t.current(read = null, nowMs = attente + 1))
    }

    @Test
    fun `appuis rapproches suivent la consigne tant que le vehicule n a pas suivi`() {
        val t = ToggleTracker()
        t.onCommand(false, nowMs = 0)
        assertFalse(t.current(read = true, nowMs = attente - 1))
    }

    @Test
    fun `passe le delai la lecture reprend la main`() {
        val t = ToggleTracker()
        t.onCommand(false, nowMs = 0)
        assertTrue(t.current(read = true, nowMs = attente))
    }

    @Test
    fun `une activation confirmee rend la lecture fiable`() {
        val t = ToggleTracker()
        t.onReadBack(t.onCommand(true, nowMs = 0), read = true)
        // Le conducteur a coupé le réglage autrement (écran d'origine, Dashboard) : la lecture fait foi.
        assertFalse(t.current(read = false, nowMs = attente + 1))
    }

    @Test
    fun `sans confirmation une lecture inactif ne contredit pas la consigne`() {
        val t = ToggleTracker()
        t.onReadBack(t.onCommand(true, nowMs = 0), read = false)
        assertTrue(t.current(read = false, nowMs = attente + 1))
    }

    @Test
    fun `une lecture actif fait foi meme apres une consigne d arret`() {
        val t = ToggleTracker()
        t.onReadBack(t.onCommand(false, nowMs = 0), read = false)
        assertTrue(t.current(read = true, nowMs = attente + 1))
    }

    @Test
    fun `une relecture contraire retire la confiance`() {
        val t = ToggleTracker()
        t.onReadBack(t.onCommand(true, nowMs = 0), read = true)
        t.onReadBack(t.onCommand(false, nowMs = attente), read = true)
        assertFalse(t.current(read = true, nowMs = 3 * attente))
    }

    @Test
    fun `une relecture perimee est ignoree`() {
        val t = ToggleTracker()
        val ancienne = t.onCommand(true, nowMs = 0)
        t.onCommand(false, nowMs = 500)
        // Relecture du premier appui, arrivée après le second : elle ne dit rien de la consigne en cours.
        t.onReadBack(ancienne, read = true)
        assertTrue(t.current(read = true, nowMs = 500 + attente))
    }

    @Test
    fun `une relecture illisible ne change rien`() {
        val t = ToggleTracker()
        t.onReadBack(t.onCommand(true, nowMs = 0), read = true)
        t.onReadBack(t.onCommand(false, nowMs = attente), read = null)
        assertTrue(t.current(read = true, nowMs = 3 * attente))
    }

    @Test
    fun `issue 114 one pedal active par le profil puis trois appuis`() {
        val t = ToggleTracker()
        assertFalse(appui(t, lu = true, relu = false, maintenant = 0))
        assertTrue(appui(t, lu = false, relu = true, maintenant = 10_000))
        assertFalse(appui(t, lu = true, relu = false, maintenant = 20_000))
    }

    @Test
    fun `lecture qui ne suit jamais les appuis alternent comme avant`() {
        val t = ToggleTracker()
        val consignes = (0 until 4).map { appui(t, lu = false, relu = false, maintenant = it * 10_000L) }
        assertEquals(listOf(true, false, true, false), consignes)
    }

    @Test
    fun `lecture bloquee sur actif les appuis alternent quand meme`() {
        val t = ToggleTracker()
        val consignes = (0 until 4).map { appui(t, lu = true, relu = true, maintenant = it * 10_000L) }
        assertEquals(listOf(false, true, false, true), consignes)
    }

    @Test
    fun `lecture illisible les appuis alternent comme avant`() {
        val t = ToggleTracker()
        val consignes = (0 until 4).map { appui(t, lu = null, relu = null, maintenant = it * 10_000L) }
        assertEquals(listOf(true, false, true, false), consignes)
    }
}
