package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import des automatisations (2026-11-14, revue point 3) : liens refaits, reliés par le nom ou signalés. */
class RuleRefsTest {

    private fun lieu(id: String) = RuleCondition(RuleCondition.Type.PLACE, text = id)
    private fun profil(id: String) = RuleCondition(RuleCondition.Type.COMPARE, signal = RuleSignal.PROFILE, op = RuleOp.EQUALS, text = id)

    private val a = CustomRule(id = "A", name = "Départ", triggers = listOf(RuleTrigger(RuleTrigger.Type.BECOMES, condition = lieu("p1"))),
        condition = RuleCondition(RuleCondition.Type.ALL, children = listOf(profil("pr1"))),
        actions = listOf(
            RuleAction(RuleAction.Type.RUN_RULE, text = "B"),
            RuleAction(RuleAction.Type.IF, condition = lieu("p2"), then = listOf(
                RuleAction(RuleAction.Type.PLAY_SOUND, key = RuleLogic.RULE_SOUND + "s1"),
                RuleAction(RuleAction.Type.RULE_ENABLE, text = "X", value = "1"),
            )),
            RuleAction(RuleAction.Type.PROFILE, profileId = "pr1"),
            RuleAction(RuleAction.Type.CUSTOM_SHORTCUT, text = "c1"),
        ),
        exportNames = mapOf("PLACE:p1" to "Maison", "PLACE:p2" to "Travail", "PROFILE:pr1" to "Hiver", "SOUND:s1" to "carillon.mp3", "RULE:X" to "Ancienne"))
    private val b = CustomRule(id = "B", name = "Dégivrage", actions = listOf(RuleAction(RuleAction.Type.RULE_STOP, text = "A")))

    @Test
    fun `every link is found, nested blocks and trigger conditions included`() {
        assertEquals(listOf(RuleRef(RefKind.PLACE, "p1"), RuleRef(RefKind.PROFILE, "pr1"), RuleRef(RefKind.RULE, "B"),
            RuleRef(RefKind.PLACE, "p2"), RuleRef(RefKind.SOUND, "s1"), RuleRef(RefKind.RULE, "X"), RuleRef(RefKind.SHORTCUT, "c1")),
            RuleRefs.of(a))
    }

    @Test
    fun `import relinks copies, keeps local objects, links by name, flags the rest`() {
        // Sur l'autre voiture : un lieu « Maison » (id m9) et le raccourci c1 existent ; rien d'autre.
        val plan = RuleRefs.planImport(listOf(a, b), newId = { "n$it" },
            exists = { it == RuleRef(RefKind.SHORTCUT, "c1") },
            byName = { k, n -> if (k == RefKind.PLACE && n == "Maison") "m9" else null })
        val na = plan.rules[0]
        val nb = plan.rules[1]
        assertEquals("nA", na.id); assertFalse(na.enabled); assertNull(na.exportNames)
        assertEquals("nB", na.actions[0].text)                 // vers la copie, pas l'original
        assertEquals("nA", nb.actions[0].text)
        assertEquals("m9", na.triggers[0].condition!!.text)    // relié par le nom
        assertEquals("p2", na.actions[1].condition!!.text)     // manquant : gardé tel quel
        assertEquals("c1", na.actions[3].text)                 // existe ici : gardé
        val parStatut = plan.links.groupBy { it.status }
        assertEquals(2, parStatut[RuleRefs.Status.FROM_FILE]!!.size)
        assertEquals(listOf("Maison"), parStatut[RuleRefs.Status.BY_NAME]!!.map { it.name })
        assertEquals(setOf("Travail", "Hiver", "carillon.mp3", "Ancienne"), parStatut[RuleRefs.Status.MISSING]!!.map { it.name }.toSet())
        assertTrue("le profil vu deux fois n'est listé qu'une fois", plan.links.count { it.ref.kind == RefKind.PROFILE } == 1)
    }

    @Test
    fun `a chosen replacement rewrites only that link`() {
        val r = RuleRefs.rewrite(a) { if (it == RuleRef(RefKind.SOUND, "s1")) "s7" else null }
        assertEquals(RuleLogic.RULE_SOUND + "s7", r.actions[1].then!![0].key)
        assertEquals("B", r.actions[0].text)
        assertEquals(listOf(RuleRef(RefKind.PLACE, "p2")), RuleRefs.missing(a) { it.kind != RefKind.PLACE || it.id == "p1" }
            .filter { it.kind == RefKind.PLACE })
    }

    @Test
    fun `navigate keeps coordinates and relinks a place id`() {
        val r = CustomRule(id = "N", name = "Nav", actions = listOf(
            RuleAction(RuleAction.Type.NAVIGATE, text = "p9"),
            RuleAction(RuleAction.Type.NAVIGATE, text = "48.8566, 2.3522")))
        assertEquals(listOf(RuleRef(RefKind.PLACE, "p9")), RuleRefs.of(r))
        val n = RuleRefs.rewrite(r) { "m1" }
        assertEquals("m1", n.actions[0].text)
        assertEquals("48.8566, 2.3522", n.actions[1].text)
    }

    @Test
    fun `a rule left out of the import is flagged, not announced as rebuilt`() {
        // Liste presque pleine : seule A entre ; son lien vers B (restée dehors) est à réassocier.
        val plan = RuleRefs.planImport(listOf(a), newId = { "n$it" }, exists = { false }, byName = { _, _ -> null })
        val versB = plan.links.single { it.ref == RuleRef(RefKind.RULE, "B") }
        assertEquals(RuleRefs.Status.MISSING, versB.status)
        assertEquals("B", plan.rules[0].actions[0].text)
    }

    @Test
    fun `an ABRP account is a link like the others`() {
        val r = CustomRule(id = "Z", name = "ABRP", actions = listOf(RuleAction(RuleAction.Type.ABRP_START, text = "acc1")),
            exportNames = mapOf("ABRP:acc1" to "Ma MG4"))
        val plan = RuleRefs.planImport(listOf(r), newId = { "n$it" }, exists = { false },
            byName = { k, n -> if (k == RefKind.ABRP && n == "Ma MG4") "acc9" else null })
        assertEquals("acc9", plan.rules[0].actions[0].text)
        assertEquals(RuleRefs.Status.BY_NAME, plan.links.single().status)
    }

    @Test
    fun `a link to a left-out rule asks for a choice even when the original exists here`() {
        // Même voiture : la B d'origine existe, mais la copie de B n'a pas pu entrer.
        val plan = RuleRefs.planImport(listOf(a), newId = { "n$it" }, exists = { true }, byName = { _, _ -> "B" },
            leftOut = setOf("B"))
        // X existe ici et n'est pas écartée : gardée, sans lien listé ; seul le lien vers B l'est.
        val versB = plan.links.single { it.ref.kind == RefKind.RULE }
        assertEquals(RuleRefs.Status.MISSING, versB.status)
        // L'action enregistrée ne vise ni l'original « B » ni rien d'existant, jusqu'au choix.
        val cible = plan.rules[0].actions[0].text!!
        assertEquals(RuleRefs.UNRESOLVED + "B", cible)
        assertEquals(listOf(RuleRef(RefKind.RULE, cible)), RuleRefs.missing(plan.rules[0]) { it.id != cible }.filter { it.kind == RefKind.RULE })
        // Le choix fait dans le rapport (sur le lien qu'il montre) remplace bien cette cible.
        val choisie = RuleRefs.rewrite(plan.rules[0]) { if (it == versB.ref) "B2" else null }
        assertEquals("B2", choisie.actions[0].text)
    }
}
