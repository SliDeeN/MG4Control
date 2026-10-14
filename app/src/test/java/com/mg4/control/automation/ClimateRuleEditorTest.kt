package com.mg4.control.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carte « A/C selon la température » — ce que les curseurs et les boutons ont le droit d'enregistrer.
 *
 * L'écran est passé des champs de saisie aux curseurs sans toucher aux réglages enregistrés : les
 * mêmes clés, les mêmes valeurs. Ce qui est à risque, c'est donc la TRADUCTION entre un geste et
 * ces réglages — un choix de recirculation mal converti enverrait « intérieur » pour « inchangée ».
 */
class ClimateRuleEditorTest {

    private val min = ClimateAutomationSettings.MIN_TEMP
    private val max = ClimateAutomationSettings.MAX_TEMP

    // ── Recirculation : un seul choix à quatre positions ─────────────────────
    // Avant : une case « Forcer » et trois boutons. « Inchangée » EST la case décochée.

    @Test
    fun `inchangee tant que la recirculation n est pas forcee`() {
        // Le mode enregistré ne compte pas : décochée, l'automatisation ne touchait à rien.
        assertEquals(0, ClimateRuleEditor.recircChoice(force = false, mode = 0))
        assertEquals(0, ClimateRuleEditor.recircChoice(force = false, mode = 2))
    }

    @Test
    fun `les trois modes viennent apres inchangee dans l ordre de la voiture`() {
        assertEquals(1, ClimateRuleEditor.recircChoice(force = true, mode = 0))   // intérieur
        assertEquals(2, ClimateRuleEditor.recircChoice(force = true, mode = 1))   // extérieur
        assertEquals(3, ClimateRuleEditor.recircChoice(force = true, mode = 2))   // auto
    }

    @Test
    fun `un mode enregistre hors echelle reste un choix affichable`() {
        assertEquals(3, ClimateRuleEditor.recircChoice(force = true, mode = 7))
        assertEquals(1, ClimateRuleEditor.recircChoice(force = true, mode = -1))
    }

    @Test
    fun `choisir inchangee ne force plus rien et n ecrit aucun mode`() {
        assertFalse(ClimateRuleEditor.recircForce(0))
        // null = le mode enregistré reste tel quel, comme quand on décochait la case.
        assertNull(ClimateRuleEditor.recircMode(0))
    }

    @Test
    fun `choisir un mode le force`() {
        assertTrue(ClimateRuleEditor.recircForce(1))
        assertEquals(0, ClimateRuleEditor.recircMode(1))
        assertEquals(1, ClimateRuleEditor.recircMode(2))
        assertEquals(2, ClimateRuleEditor.recircMode(3))
    }

    @Test
    fun `chaque choix se relit tel qu il a ete enregistre`() {
        for (choix in 1..3) {
            val mode = ClimateRuleEditor.recircMode(choix)!!
            assertEquals(choix, ClimateRuleEditor.recircChoice(ClimateRuleEditor.recircForce(choix), mode))
        }
    }

    // ── Seuils : le froid reste sous le chaud ────────────────────────────────
    // Deux seuils qui se croisent déclenchent les deux règles à la fois ; la décision retient
    // alors « chaud », ce que rien à l'écran ne laissait deviner.

    @Test
    fun `le seuil chaud ne descend pas jusqu au seuil froid`() {
        assertEquals(6, ClimateRuleEditor.hotThreshold(wanted = 3, cold = 5))
        assertEquals(6, ClimateRuleEditor.hotThreshold(wanted = 5, cold = 5))
        assertEquals(28, ClimateRuleEditor.hotThreshold(wanted = 28, cold = 5))
    }

    @Test
    fun `le seuil froid ne monte pas jusqu au seuil chaud`() {
        assertEquals(27, ClimateRuleEditor.coldThreshold(wanted = 30, hot = 28))
        assertEquals(27, ClimateRuleEditor.coldThreshold(wanted = 28, hot = 28))
        assertEquals(5, ClimateRuleEditor.coldThreshold(wanted = 5, hot = 28))
    }

    @Test
    fun `les seuils restent dans les bornes du curseur`() {
        assertEquals(max, ClimateRuleEditor.hotThreshold(wanted = 999, cold = 5))
        assertEquals(min, ClimateRuleEditor.coldThreshold(wanted = -999, hot = 28))
        // Un réglage ancien posé tout au bout ne doit pas pousser l'autre curseur hors de l'échelle.
        assertEquals(max, ClimateRuleEditor.hotThreshold(wanted = 10, cold = max))
        assertEquals(min, ClimateRuleEditor.coldThreshold(wanted = 10, hot = min))
    }

    // ── Curseurs aux bornes de la voiture ────────────────────────────────────

    @Test
    fun `une valeur enregistree hors des bornes de la voiture est ramenee dedans`() {
        // Ventilation 10 saisie autrefois, voiture à 7 crans : le curseur ne peut pas la montrer,
        // et un Slider posé hors de ses bornes fait planter l'écran.
        assertEquals(7, ClimateRuleEditor.sliderValue(stored = 10, min = 1, max = 7))
        assertEquals(17, ClimateRuleEditor.sliderValue(stored = 15, min = 17, max = 33))
        assertEquals(4, ClimateRuleEditor.sliderValue(stored = 4, min = 1, max = 7))
    }

    @Test
    fun `des bornes illisibles ne font pas planter le curseur`() {
        // Bornes inversées ou confondues (lecture véhicule douteuse) : on garde la valeur basse.
        assertEquals(5, ClimateRuleEditor.sliderValue(stored = 9, min = 5, max = 5))
        assertEquals(7, ClimateRuleEditor.sliderValue(stored = 9, min = 7, max = 3))
    }

    @Test
    fun `la ventilation accepte les onze crans des firmwares A9`() {
        // Le plafond de saisie était 10 : le onzième cran des A9 ne pouvait pas être choisi.
        assertEquals(11, ClimateAutomationSettings.clampFan(11))
        assertEquals(11, ClimateAutomationSettings.clampFan(40))
    }

    // ── Affichage ────────────────────────────────────────────────────────────

    @Test
    fun `le seuil s affiche avec son sens`() {
        assertEquals("\u2265 28 °C", ClimateRuleEditor.thresholdLabel(hot = true, value = 28))
        assertEquals("\u2264 -5 °C", ClimateRuleEditor.thresholdLabel(hot = false, value = -5))
    }

    @Test
    fun `quatre boutons de recirculation sur une ligne quand la place le permet`() {
        val assez = 4 * ClimateRuleEditor.MIN_BUTTON_DP
        assertEquals(4, ClimateRuleEditor.recircColumns(assez))
        assertEquals(4, ClimateRuleEditor.recircColumns(900f))
        // Sinon trois par ligne : « Auto » passe dessous, sous « Inchangée ».
        assertEquals(3, ClimateRuleEditor.recircColumns(assez - 1f))
        assertEquals(3, ClimateRuleEditor.recircColumns(0f))
    }
}
