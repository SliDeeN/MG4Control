package com.mg4.control.automation

import com.mg4.control.automation.ClimateAutomationDecision.Outcome
import com.mg4.control.automation.ClimateAutomationSettings.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimateAutomationDecisionTest {

    // Seuls [active] et [threshold] comptent pour la decision ; le reste est du remplissage,
    // nomme pour que l ajout d un champ ne fasse pas glisser silencieusement les positions.
    private fun rule(active: Boolean, threshold: Int) =
        ClimateAutomationSettings.Rule(
            active = active, threshold = threshold, targetTemp = 21, fanLevel = 4,
            defrostFront = false, defrostRear = false, autoMode = false, loopMode = null
        )

    private fun cfg(
        enabled: Boolean = true,
        hotOn: Boolean = true, hotT: Int = 28,
        coldOn: Boolean = true, coldT: Int = 5,
        trigger: Trigger = Trigger.EVERY_READY
    ) = ClimateAutomationSettings.Config(
        enabled, rule(hotOn, hotT), rule(coldOn, coldT), trigger = trigger
    )

    @Test fun `desactive - aucune regle`() {
        assertEquals(Outcome.NONE, ClimateAutomationDecision.evaluate(cfg(enabled = false), 35f))
    }

    @Test fun `temp illisible - aucune regle`() {
        assertEquals(Outcome.NONE, ClimateAutomationDecision.evaluate(cfg(), null))
        assertEquals(Outcome.NONE, ClimateAutomationDecision.evaluate(cfg(), Float.NaN))
    }

    @Test fun `au dessus du seuil chaud - regle chaud`() {
        assertEquals(Outcome.HOT, ClimateAutomationDecision.evaluate(cfg(), 30f))
    }

    @Test fun `au seuil chaud - borne incluse`() {
        assertEquals(Outcome.HOT, ClimateAutomationDecision.evaluate(cfg(), 28f))
    }

    @Test fun `sous le seuil froid - regle froid`() {
        assertEquals(Outcome.COLD, ClimateAutomationDecision.evaluate(cfg(), 2f))
    }

    @Test fun `au seuil froid - borne incluse`() {
        assertEquals(Outcome.COLD, ClimateAutomationDecision.evaluate(cfg(), 5f))
    }

    @Test fun `entre les deux seuils - aucune regle`() {
        assertEquals(Outcome.NONE, ClimateAutomationDecision.evaluate(cfg(), 18f))
    }

    @Test fun `regle chaud desactivee - ignoree`() {
        assertEquals(Outcome.NONE, ClimateAutomationDecision.evaluate(cfg(hotOn = false), 35f))
    }

    @Test fun `regle froid desactivee - ignoree`() {
        assertEquals(Outcome.NONE, ClimateAutomationDecision.evaluate(cfg(coldOn = false), 0f))
    }

    @Test fun `seuils qui se chevauchent - chaud gagne de facon deterministe`() {
        // Configuration incohérente (chaud>=10 ET froid<=30) : 20 satisfait les deux.
        assertEquals(Outcome.HOT, ClimateAutomationDecision.evaluate(cfg(hotT = 10, coldT = 30), 20f))
    }

    // ── Mode de déclenchement (issue #121) ─────────────────────────────────
    // Deux faits sont suivis depuis le démarrage de l application : « une température lisible a
    // déjà été évaluée » et « une règle a déjà été appliquée ».

    @Test fun `a chaque READY - toujours autorisee`() {
        val c = cfg(trigger = Trigger.EVERY_READY)
        assertTrue(ClimateAutomationDecision.allowed(c, evaluated = true, triggered = true))
    }

    @Test fun `une fois par demarrage - autorisee tant que rien n a ete applique`() {
        // Seuil non atteint au démarrage : l occasion reste ouverte pour un contact ultérieur.
        val c = cfg(trigger = Trigger.ONCE_PER_START)
        assertTrue(ClimateAutomationDecision.allowed(c, evaluated = true, triggered = false))
        assertFalse(ClimateAutomationDecision.allowed(c, evaluated = true, triggered = true))
    }

    @Test fun `au demarrage seulement - une seule decision meme sans declenchement`() {
        // Le cas de l issue : rien au démarrage, puis le seuil est atteint après un arrêt.
        val c = cfg(trigger = Trigger.START_ONLY)
        assertTrue(ClimateAutomationDecision.allowed(c, evaluated = false, triggered = false))
        assertFalse(ClimateAutomationDecision.allowed(c, evaluated = true, triggered = false))
    }

    @Test fun `seule une temperature lisible vaut decision`() {
        assertTrue(ClimateAutomationDecision.decides(12.5f))
        assertFalse(ClimateAutomationDecision.decides(null))
        assertFalse(ClimateAutomationDecision.decides(Float.NaN))
    }

    @Test fun `mode par defaut - au demarrage seulement`() {
        assertEquals(Trigger.START_ONLY, Trigger.resolve(stored = null, legacyOnce = null))
    }

    @Test fun `ancienne case reprise telle quelle`() {
        // Cochée : une fois par démarrage. Décochée exprès : à chaque READY, comme avant.
        assertEquals(Trigger.ONCE_PER_START, Trigger.resolve(stored = null, legacyOnce = true))
        assertEquals(Trigger.EVERY_READY, Trigger.resolve(stored = null, legacyOnce = false))
    }

    @Test fun `le choix enregistre l emporte sur l ancienne case`() {
        assertEquals(Trigger.EVERY_READY, Trigger.resolve(stored = "EVERY_READY", legacyOnce = true))
        assertEquals(Trigger.START_ONLY, Trigger.resolve(stored = "illisible", legacyOnce = null))
    }
}
