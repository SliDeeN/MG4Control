package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Rechargement des règles (2026-11-14) : modifier une autre règle ne remet plus à zéro les suivis
 * d'une règle ; un déclencheur modifié ou une règle réactivée repartent de zéro.
 */
class RuleWatchReuseTest {

    private val ready = RuleTrigger(RuleTrigger.Type.BECOMES, condition = RuleCondition(RuleCondition.Type.ALL), forSeconds = 30)
    private val heure = RuleTrigger(RuleTrigger.Type.TIME, at = 450)

    private class W(val t: RuleTrigger)

    @Test
    fun `unchanged triggers keep their watch, changed ones start fresh`() {
        val a = CustomRule(id = "a", name = "A", triggers = listOf(ready, heure))
        val anciens = a.triggers.map { W(it) }
        // Même règle (autre chose a changé ailleurs) : tout est gardé.
        val garde = reuseWatches(a, a.copy(name = "A bis"), anciens) { W(it) }
        assertSame(anciens[0], garde[0]); assertSame(anciens[1], garde[1])
        // Déclencheurs réordonnés : gardés quand même.
        val inverse = reuseWatches(a, a.copy(triggers = listOf(heure, ready)), anciens) { W(it) }
        assertSame(anciens[1], inverse[0]); assertSame(anciens[0], inverse[1])
        // Heure modifiée : seul ce suivi repart de zéro.
        val autreHeure = heure.copy(at = 480)
        val modif = reuseWatches(a, a.copy(triggers = listOf(ready, autreHeure)), anciens) { W(it) }
        assertSame(anciens[0], modif[0]); assertNotSame(anciens[1], modif[1]); assertEquals(autreHeure, modif[1].t)
    }

    @Test
    fun `a new or re-enabled rule starts fresh`() {
        val a = CustomRule(id = "a", name = "A", enabled = false, triggers = listOf(ready))
        val anciens = listOf(W(ready))
        assertNotSame("réactivée", anciens[0], reuseWatches(a, a.copy(enabled = true), anciens) { W(it) }[0])
        assertNotSame("nouvelle", anciens[0], reuseWatches(null, a, null) { W(it) }[0])
    }
}
