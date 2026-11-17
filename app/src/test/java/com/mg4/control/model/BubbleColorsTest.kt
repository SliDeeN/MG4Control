package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Couleur des boutons flottants (2026-11-04). */
class BubbleColorsTest {

    @Test
    fun `code couleur lu et ecrit`() {
        assertEquals("#A6C8FF", BubbleColors.hex(BubbleColors.DEFAULT))
        assertEquals(BubbleColors.DEFAULT, BubbleColors.parse("#a6c8ff"))
        assertEquals(BubbleColors.DEFAULT, BubbleColors.parse(" A6C8FF "))
        assertNull(BubbleColors.parse("#A6C8F"))
        assertNull(BubbleColors.parse("bleu"))
    }

    @Test
    fun `texte lisible sur la bulle pleine`() {
        assertEquals(0xFF0B1A33.toInt(), BubbleColors.textOn(BubbleColors.DEFAULT))
        assertEquals(0xFF0B1A33.toInt(), BubbleColors.textOn(0xFFFACC15.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), BubbleColors.textOn(0xFF1F2A44.toInt()))
    }

    @Test
    fun `aller-retour teinte saturation valeur`() {
        BubbleColors.PALETTE.forEach { c ->
            val (h, s, v) = BubbleColors.toHsv(c).toList()
            assertEquals(BubbleColors.hex(c), BubbleColors.hex(BubbleColors.fromHsv(h, s, v)))
        }
        assertEquals("#FF0000", BubbleColors.hex(BubbleColors.fromHsv(0f, 1f, 1f)))
        assertEquals("#0000FF", BubbleColors.hex(BubbleColors.fromHsv(240f, 1f, 1f)))
    }
}
