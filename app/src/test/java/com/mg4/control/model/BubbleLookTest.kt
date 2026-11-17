package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apparence des boutons flottants (issue #230) : sans réglage, l'allure d'avant ; sinon la sienne. */
class BubbleLookTest {

    private val bleu = BubbleColors.DEFAULT
    private fun alpha(c: Int) = c ushr 24
    private fun bulle() = FloatingButton(id = "a", action = "HVAC_POPUP")

    @Test
    fun `an untouched bubble looks exactly as before`() {
        val p = BubbleLook.paint(bulle(), bleu, toggle = null)
        assertEquals(0xE6, alpha(p.background))                      // fond sombre à 90 %
        assertEquals(0x17171A, p.background and 0xFFFFFF)
        assertEquals(bleu, p.border)
        assertFalse(p.halo)
        assertEquals(0xFF, alpha(BubbleLook.paint(bulle(), bleu, toggle = true).background))   // bascule activée : pleine
        assertEquals(0x59, alpha(BubbleLook.paint(bulle(), bleu, toggle = false).background))  // coupée : 35 %
        assertFalse(BubbleLook.dims(bulle()))
    }

    @Test
    fun `opacity, fill and border follow the bubble's own settings`() {
        val b = bulle().copy(opacity = 30, fill = BubbleLook.FILL_TINT, border = false)
        val p = BubbleLook.paint(b, bleu, toggle = null)
        assertEquals(77, alpha(p.background))                         // 30 %
        assertEquals(bleu and 0xFFFFFF, p.background and 0xFFFFFF)    // teinté : la couleur de la bulle
        assertNull(p.border)
        assertTrue(p.halo)                                            // presque sans fond : contour du texte
        val clair = BubbleLook.paint(bulle().copy(fill = BubbleLook.FILL_LIGHT, opacity = 10), bleu, toggle = null)
        assertFalse(clair.halo)                                       // texte sombre sur fond clair
    }

    @Test
    fun `an active toggle stays readable and moving stays opaque with the orange border`() {
        val transparente = bulle().copy(opacity = 0)
        assertEquals(153, alpha(BubbleLook.paint(transparente, bleu, toggle = true).background))   // 60 % au moins
        val enDeplacement = BubbleLook.paint(transparente.copy(border = false), bleu, toggle = null, moving = true)
        assertEquals(0xFF, alpha(enDeplacement.background))
        assertEquals(BubbleLook.MOVING_BORDER, enDeplacement.border)
    }

    @Test
    fun `copying the look takes the four settings, never the title, action or colour`() {
        val source = bulle().copy(id = "s", opacity = 40, fill = BubbleLook.FILL_LIGHT, border = false, dimIdle = true, color = 0xFF22C55E.toInt())
        val cible = FloatingButton(id = "c", action = "MEDIA_NEXT", customTitle = "Suivant", color = 0xFFF43F5E.toInt())
        val r = BubbleLook.copy(source, cible)
        assertEquals(listOf(40, BubbleLook.FILL_LIGHT, false, true), listOf(r.opacity, r.fill, r.border, r.dimIdle))
        assertEquals(listOf("c", "MEDIA_NEXT", "Suivant", 0xFFF43F5E.toInt()), listOf(r.id, r.action, r.customTitle, r.color))
        assertEquals("40 % · clair · sans bordure · atténuée",
            BubbleLook.summary(r, { if (it == BubbleLook.FILL_LIGHT) "clair" else it }, "sans bordure", "atténuée"))
    }
}
