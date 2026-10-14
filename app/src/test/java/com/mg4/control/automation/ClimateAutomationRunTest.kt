package com.mg4.control.automation

import com.mg4.control.automation.ClimateAutomationSettings.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que l'automatisation A/C retient d'un démarrage à l'autre évènement (issue #121).
 *
 * Journal d'un testeur sur SWI69, mode « Au démarrage seulement », 33,5 °C dehors : la règle chaud
 * est décidée au démarrage du service, mais la climatisation n'est pas encore joignable (4 ms
 * trop tôt) et le préréglage est abandonné. L'automatisation notait pourtant « appliquée » AVANT
 * l'envoi : l'unique occasion était consommée par l'échec, et ni le contact ni le READY qui
 * suivaient ne réessayaient.
 */
class ClimateAutomationRunTest {

    private fun rule() = ClimateAutomationSettings.Rule(
        active = true, threshold = 20, targetTemp = 21, fanLevel = 4,
        defrostFront = false, defrostRear = false, autoMode = false, loopMode = null
    )

    private fun cfg(trigger: Trigger) = ClimateAutomationSettings.Config(true, rule(), rule(), trigger = trigger)

    private fun autorisee(run: ClimateAutomationRun, trigger: Trigger) =
        ClimateAutomationDecision.allowed(cfg(trigger), run.evaluated, run.triggered)

    // ── Journal du 2026-10-13 : le profil du trajet précédent ────────────────
    // SWI69, « Au démarrage seulement ». 14:58:19, démarrage du service : le profil actif mémorisé
    // est encore HVAC1 (bloc clim), celui de la veille. 14:58:22, contact : c est ECO, le profil
    // par défaut, qui est appliqué — il ne touche pas à la climatisation. L automatisation
    // s était effacée devant HVAC1 et tenait cela pour SA décision : plus rien ensuite.

    @Test fun `s effacer devant le profil de la veille ne consomme pas l occasion du demarrage`() {
        val run = ClimateAutomationRun()

        // Démarrage du service : aucun profil appliqué depuis le démarrage de l application.
        if (ClimateAutomationDecision.yieldsToProfile(sessionProfileWritesClimate = null)) run.decidedNothing()

        // Contact, trois secondes plus tard : ECO vient d être appliqué, il ne règle pas la clim.
        assertTrue(autorisee(run, Trigger.START_ONLY))
        assertFalse(ClimateAutomationDecision.yieldsToProfile(sessionProfileWritesClimate = false))
    }

    @Test fun `le profil applique a ce demarrage decide pour la climatisation`() {
        // Cas inverse : le profil du démarrage porte un bloc clim. C est une décision, et l unique
        // occasion du mode « au démarrage seulement » est bien consommée.
        val run = ClimateAutomationRun()
        if (ClimateAutomationDecision.yieldsToProfile(sessionProfileWritesClimate = true)) run.decidedNothing()
        assertFalse(autorisee(run, Trigger.START_ONLY))
        assertTrue(autorisee(run, Trigger.ONCE_PER_START))
    }

    // ── Le défaut du journal ─────────────────────────────────────────────────

    @Test fun `un envoi qui n a pas pu partir ne consomme pas l occasion du demarrage`() {
        val run = ClimateAutomationRun()
        assertTrue(run.begin())
        run.end(sent = false, nowMs = 1_000L)

        // Le contact, trois secondes plus tard, doit pouvoir réessayer — dans les deux modes « une fois ».
        assertTrue(autorisee(run, Trigger.START_ONLY))
        assertTrue(autorisee(run, Trigger.ONCE_PER_START))
    }

    @Test fun `un envoi qui n a pas pu partir n arme pas l anti-rebond`() {
        val run = ClimateAutomationRun()
        run.begin()
        run.end(sent = false, nowMs = 1_000L)
        assertNull(run.debouncedFor(nowMs = 4_000L, windowMs = 60_000L))
    }

    @Test fun `apres un envoi rate un nouvel envoi peut partir`() {
        val run = ClimateAutomationRun()
        run.begin()
        run.end(sent = false, nowMs = 1_000L)
        assertTrue(run.begin())
    }

    // ── Envoi abouti ─────────────────────────────────────────────────────────

    @Test fun `un envoi abouti vaut decision dans les deux modes une fois`() {
        val run = ClimateAutomationRun()
        run.begin()
        run.end(sent = true, nowMs = 1_000L)

        assertFalse(autorisee(run, Trigger.START_ONLY))
        assertFalse(autorisee(run, Trigger.ONCE_PER_START))
        assertTrue(autorisee(run, Trigger.EVERY_READY))
    }

    @Test fun `un envoi abouti arme l anti-rebond pour la duree voulue`() {
        val run = ClimateAutomationRun()
        run.begin()
        run.end(sent = true, nowMs = 10_000L)

        assertEquals(5_000L, run.debouncedFor(nowMs = 15_000L, windowMs = 60_000L))
        assertNull(run.debouncedFor(nowMs = 70_000L, windowMs = 60_000L))
    }

    @Test fun `rien n est retenu avant le premier envoi`() {
        val run = ClimateAutomationRun()
        assertFalse(run.evaluated)
        assertFalse(run.triggered)
        assertNull(run.debouncedFor(nowMs = 5_000L, windowMs = 60_000L))
    }

    // ── Un seul envoi à la fois ──────────────────────────────────────────────

    @Test fun `pas de second envoi tant que le premier n est pas termine`() {
        // Les commandes de clim sont des bascules : deux envois entrelacés s'annuleraient. Or le
        // démarrage du service et le contact se suivent à trois secondes.
        val run = ClimateAutomationRun()
        assertTrue(run.begin())
        assertFalse(run.begin())
        run.end(sent = true, nowMs = 1_000L)
        assertTrue(run.begin())
    }

    // ── Décision sans envoi ──────────────────────────────────────────────────

    @Test fun `seuil non atteint - la decision est prise sans rien envoyer`() {
        val run = ClimateAutomationRun()
        run.decidedNothing()

        assertFalse(autorisee(run, Trigger.START_ONLY))      // une seule décision par démarrage
        assertTrue(autorisee(run, Trigger.ONCE_PER_START))   // reste armée : rien n'a été appliqué
        assertNull(run.debouncedFor(nowMs = 2_000L, windowMs = 60_000L))
    }

    // ── Attente de la climatisation ──────────────────────────────────────────

    @Test fun `clim deja joignable - aucun delai`() {
        val pauses = mutableListOf<Long>()
        assertTrue(ClimateAutomationRun.awaitReady(attempts = 5, pauseMs = 500L, sleep = { pauses += it }) { true })
        assertTrue(pauses.isEmpty())
    }

    @Test fun `clim joignable au troisieme essai - deux pauses`() {
        val pauses = mutableListOf<Long>()
        var essais = 0
        val ok = ClimateAutomationRun.awaitReady(attempts = 5, pauseMs = 500L, sleep = { pauses += it }) { ++essais >= 3 }
        assertTrue(ok)
        assertEquals(3, essais)
        assertEquals(listOf(500L, 500L), pauses)
    }

    @Test fun `clim jamais joignable - abandon apres le nombre d essais prevu`() {
        val pauses = mutableListOf<Long>()
        var essais = 0
        val ok = ClimateAutomationRun.awaitReady(attempts = 4, pauseMs = 500L, sleep = { pauses += it }) { essais++; false }
        assertFalse(ok)
        assertEquals(4, essais)
        assertEquals(3, pauses.size)   // pas de pause après le dernier essai
    }
}
