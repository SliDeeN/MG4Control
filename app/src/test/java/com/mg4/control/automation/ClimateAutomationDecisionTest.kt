package com.mg4.control.automation

import com.mg4.control.automation.ClimateAutomationDecision.Outcome
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
        once: Boolean = false
    ) = ClimateAutomationSettings.Config(
        enabled, rule(hotOn, hotT), rule(coldOn, coldT), oncePerStart = once
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

    // ── Option « une seule fois par démarrage de la voiture » ──────────────

    @Test fun `sans l option - autorisee meme apres un declenchement`() {
        // Comportement d origine : les reglages repartent a chaque mise du contact.
        assertTrue(ClimateAutomationDecision.allowed(cfg(once = false), alreadyTriggered = true))
    }

    @Test fun `une seule fois - autorisee tant que rien n a ete applique`() {
        // Un premier contact entre les deux seuils n applique rien : l essai reste disponible.
        assertTrue(ClimateAutomationDecision.allowed(cfg(once = true), alreadyTriggered = false))
    }

    @Test fun `une seule fois - retenue apres un premier declenchement`() {
        assertFalse(ClimateAutomationDecision.allowed(cfg(once = true), alreadyTriggered = true))
    }
}
