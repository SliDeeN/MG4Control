package com.mg4.control.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identifiants des actions de raccourci. Ils sont ÉCRITS dans les préférences de chaque
 * utilisateur : en réattribuer un, ou en perdre un, changerait ou éteindrait un raccourci déjà
 * réglé sur une voiture.
 */
class ShortcutActionTest {

    @Test
    fun `chaque action a son identifiant`() {
        val ids = ShortcutAction.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `un identifiant inconnu ne donne aucune action`() {
        assertEquals(ShortcutAction.NONE, ShortcutAction.fromId(-1))
        assertEquals(ShortcutAction.NONE, ShortcutAction.fromId(9_999))
    }

    @Test
    fun `chaque action se retrouve par son identifiant`() {
        ShortcutAction.entries.forEach { assertEquals(it, ShortcutAction.fromId(it.id)) }
    }

    /**
     * Le pop-up HVAC a eu deux présentations, chacune sur son raccourci, le temps d'un essai
     * (octobre 2026). La nouvelle a remplacé l'ancienne sous l'identifiant d'origine, 41 ; le 43
     * de l'essai n'est plus une action, mais un raccourci qui le porte encore doit continuer
     * d'ouvrir le pop-up, pas s'éteindre en silence.
     */
    @Test
    fun `le raccourci de l essai du nouveau pop-up HVAC ouvre toujours le pop-up`() {
        assertEquals(ShortcutAction.HVAC_POPUP, ShortcutAction.fromId(43))
        assertEquals(41, ShortcutAction.HVAC_POPUP.id)
        assertTrue(ShortcutAction.entries.none { it.id == 43 })
    }
}
