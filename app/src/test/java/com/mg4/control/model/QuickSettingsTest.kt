package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pop-up Réglages rapides (issue #197) : la liste des tuiles. */
class QuickSettingsTest {

    @Test
    fun `jamais regle - les tuiles par defaut`() {
        assertEquals(QuickSettings.DEFAULT, QuickSettings.decode(null))
    }

    @Test
    fun `tout retire - liste vide, pas les tuiles par defaut`() {
        assertTrue(QuickSettings.decode("").isEmpty())
    }

    @Test
    fun `aller-retour, nom inconnu et doublon ecartes, huit au plus`() {
        val l = listOf(QuickTile.REGEN, QuickTile.AEB, QuickTile.BRIGHTNESS)
        assertEquals(l, QuickSettings.decode(QuickSettings.encode(l)))
        assertEquals(listOf(QuickTile.REGEN, QuickTile.AEB), QuickSettings.decode("REGEN,WARP_DRIVE,AEB,REGEN"))
        assertEquals(QuickSettings.MAX_TILES, QuickSettings.decode(QuickTile.entries.joinToString(",") { it.name }).size)
    }

    @Test
    fun `ajouter - pas de doublon ni de neuvieme`() {
        val huit = QuickTile.entries.take(8)
        assertEquals(huit, QuickSettings.ajouter(huit, QuickTile.BRIGHTNESS))
        assertEquals(listOf(QuickTile.REGEN), QuickSettings.ajouter(listOf(QuickTile.REGEN), QuickTile.REGEN))
        assertEquals(listOf(QuickTile.REGEN, QuickTile.AC), QuickSettings.ajouter(listOf(QuickTile.REGEN), QuickTile.AC))
    }

    @Test
    fun `deplacer`() {
        val l = listOf(QuickTile.ELK_MODE, QuickTile.CRUISE, QuickTile.REGEN)
        assertEquals(listOf(QuickTile.REGEN, QuickTile.ELK_MODE, QuickTile.CRUISE), QuickSettings.deplacer(l, 2, 0))
        assertEquals(l, QuickSettings.deplacer(l, 0, 5))
    }
}
