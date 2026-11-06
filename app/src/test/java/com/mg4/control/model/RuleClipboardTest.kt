package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copier / Couper / Coller (2026-11-06) : où un élément copié a le droit d'aller. */
class RuleClipboardTest {

    private val MAX = 3
    private val simple = RuleCondition(RuleCondition.Type.COMPARE, signal = RuleSignal.READY, op = RuleOp.IS_TRUE)
    private fun groupe(vararg c: RuleCondition) = RuleCondition(RuleCondition.Type.ALL, children = c.toList())
    private val note = RuleAction(RuleAction.Type.NOTIFY, text = "x")
    private fun si(cond: RuleCondition = groupe(), then: List<RuleAction> = emptyList()) =
        RuleAction(RuleAction.Type.IF, condition = cond, then = then, otherwise = emptyList())

    @Test
    fun `profondeur des conditions`() {
        assertEquals(0, RuleClipboard.conditionDepth(simple))
        assertEquals(1, RuleClipboard.conditionDepth(groupe(simple)))
        assertEquals(2, RuleClipboard.conditionDepth(groupe(groupe(simple))))
    }

    @Test
    fun `une condition simple se colle partout, un groupe selon la place`() {
        // Même règle que l'éditeur : un sous-groupe se propose tant que profondeur < MAX - 1.
        for (p in 0..2) assertTrue(RuleClipboard.fitsCondition(simple, p, MAX))
        assertTrue(RuleClipboard.fitsCondition(groupe(simple), 1, MAX))
        assertFalse(RuleClipboard.fitsCondition(groupe(simple), 2, MAX))
        assertTrue(RuleClipboard.fitsCondition(groupe(groupe(simple)), 0, MAX))
        assertFalse(RuleClipboard.fitsCondition(groupe(groupe(simple)), 1, MAX))
    }

    @Test
    fun `blocs d'actions et leur contenu`() {
        assertEquals(0, RuleClipboard.actionDepth(note))
        assertEquals(1, RuleClipboard.actionDepth(si()))
        assertEquals(2, RuleClipboard.actionDepth(si(then = listOf(si()))))
        // Un sous-groupe dans la condition du Si compte aussi.
        assertEquals(2, RuleClipboard.actionDepth(si(cond = groupe(groupe(simple)))))
        assertTrue(RuleClipboard.fitsAction(note, 2, MAX))
        assertTrue(RuleClipboard.fitsAction(si(), 1, MAX))
        assertFalse(RuleClipboard.fitsAction(si(), 2, MAX))
        assertFalse(RuleClipboard.fitsAction(si(then = listOf(si())), 1, MAX))
    }

    @Test
    fun `une automatisation ne se lance pas elle-meme`() {
        val lance = RuleAction(RuleAction.Type.RUN_RULE, text = "moi")
        assertTrue(RuleClipboard.runsRule(lance, "moi"))
        assertTrue(RuleClipboard.runsRule(si(then = listOf(lance)), "moi"))
        assertFalse(RuleClipboard.runsRule(lance, "autre"))
        // L'arrêter, elle, n'est pas une boucle.
        assertFalse(RuleClipboard.runsRule(RuleAction(RuleAction.Type.RULE_STOP, text = "moi"), "moi"))
    }

    @Test
    fun `ajouter a une variable`() {
        assertEquals("1", RuleLogic.addTo(null, 1.0))
        assertEquals("4", RuleLogic.addTo("5", -1.0))
        assertEquals("2.5", RuleLogic.addTo("1.5", 1.0))
        assertEquals("3", RuleLogic.addTo("vrai", 3.0))
    }
}
