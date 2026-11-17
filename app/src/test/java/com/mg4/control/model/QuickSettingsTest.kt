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

    @Test
    fun `valeurs proposees - toutes par defaut, deux au moins`() {
        val regen = listOf(0, 1, 2, 3, 4, 5)
        assertEquals(regen, QuickSettings.kept(regen, null))
        assertEquals(listOf(2, 5), QuickSettings.kept(regen, listOf(5, 2)))
        // Une seule valeur encore valable (l'autre a disparu) : toutes.
        assertEquals(regen, QuickSettings.kept(regen, listOf(2, 99)))
        assertEquals(listOf(0, 1, 2, 3, 5), QuickSettings.toggleValue(regen, null, 4))
        assertEquals(listOf(2, 5), QuickSettings.toggleValue(regen, listOf(2, 5), 2))
        assertEquals(listOf(1, 2, 5), QuickSettings.toggleValue(regen, listOf(2, 5), 1))
    }

    @Test
    fun `valeurs proposees - aller-retour et bascule a deux`() {
        val m = mapOf(QuickTile.REGEN to listOf(2, 5), QuickTile.ELK_MODE to listOf(1, 5))
        assertEquals(m, QuickSettings.decodeValues(QuickSettings.encodeValues(m)))
        assertEquals(mapOf(QuickTile.REGEN to listOf(2)), QuickSettings.decodeValues("WARP:1;REGEN:2,x;bad"))
        assertTrue(QuickSettings.decodeValues(null).isEmpty())
        assertEquals(5, QuickSettings.nextOfTwo(listOf(2, 5), 2))
        assertEquals(2, QuickSettings.nextOfTwo(listOf(2, 5), 5))
        assertEquals(2, QuickSettings.nextOfTwo(listOf(2, 5), 3))
        assertEquals(2, QuickSettings.nextOfTwo(listOf(2, 5), null))
    }
}
