package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bibliothèque de modules de l'écran conducteur (2026-11-20, prototype validé). */
class ClusterModulesTest {

    private var n = 0
    private val ids = { "m${++n}" }
    private val noms: (ClusterModule) -> String = { it.key }

    @Test
    fun `old settings become library modules and nothing changes on screen`() {
        val pneus = ClusterLayout(slots = listOf(null, null, null, null))
        val paul = ClusterConfig(enabled = true, navigation = "tyres", media = "power", style = "discreet", layout = pneus)
        val marie = ClusterConfig(enabled = true, navigation = "tyres", media = "none", style = "discreet", layout = pneus)
        val vide = ClusterConfig()
        val (biblio, repris) = ClusterModuleDefs.migrate(listOf(paul, marie, vide), ids, noms)
        // Paul et Marie ont le même module Pneus : un seul dans la bibliothèque.
        assertEquals(2, biblio.size)
        val mPneus = biblio.first { it.type == "tyres" }
        val mPuis = biblio.first { it.type == "power" }
        assertEquals(listOf(mPneus.id), repris[0].navModules)
        assertEquals(listOf(mPuis.id), repris[0].mediaModules)
        assertEquals(listOf(mPneus.id), repris[1].navModules)
        assertEquals(emptyList<String>(), repris[1].mediaModules)
        assertEquals(emptyList<String>(), repris[2].navModules)
        // Le module garde le style, l'unité et la disposition de l'onglet.
        assertEquals("discreet", mPuis.style)
        assertEquals(pneus, mPneus.layout)
        assertEquals(ClusterModule.TYRES, ClusterModuleDefs.module(mPneus))
    }

    @Test
    fun `two different modules of the same type are numbered`() {
        val a = ClusterConfig(navigation = "consumption", style = "discreet")
        val b = ClusterConfig(navigation = "consumption", style = null)
        val (biblio, _) = ClusterModuleDefs.migrate(listOf(a, b), ids, noms)
        assertEquals(listOf("consumption", "consumption 2"), biblio.map { it.name })
    }

    @Test
    fun `migration keeps lists already made`() {
        val c = ClusterConfig(navigation = "power", navModules = listOf("x"), mediaModules = emptyList())
        val (biblio, repris) = ClusterModuleDefs.migrate(listOf(c), ids, noms)
        assertTrue(biblio.isEmpty())
        assertEquals(listOf("x"), repris[0].navModules)
    }

    @Test
    fun `tab lists resolve, move and lose deleted modules`() {
        val a = ClusterModuleDef("a", "A")
        val b = ClusterModuleDef("b", "B", type = "tyres")
        val c = ClusterConfig(navModules = listOf("b", "gone", "a"), mediaModules = listOf("a"))
        assertEquals(listOf(b, a), ClusterModuleDefs.listFor(c, ClusterTab.NAVIGATION, listOf(a, b)))
        assertEquals(listOf("a", "b"), ClusterModuleDefs.move(listOf("b", "a"), 1, -1))
        assertEquals(listOf("b", "a"), ClusterModuleDefs.move(listOf("b", "a"), 0, -1))
        val sans = ClusterModuleDefs.without(c, "a")
        assertEquals(listOf("b", "gone"), sans.navModules)
        assertEquals(emptyList<String>(), sans.mediaModules)
        assertEquals(listOf("z"), ClusterModuleDefs.ids(ClusterModuleDefs.withIds(c, ClusterTab.MEDIA, listOf("z")), ClusterTab.MEDIA))
    }

    @Test
    fun `a tyre module starts without slots, a gauge with the old defaults`() {
        assertTrue(ClusterModuleDefs.layout(ClusterModuleDef("p", "P", type = "tyres")).slots!!.all { it == null })
        val jauge = ClusterModuleDefs.layout(ClusterModuleDef("c", "C"))
        assertEquals(ClusterInfo.TRIP_AVERAGE, jauge.slots!![2]?.info)
        val cfg = ClusterModuleDefs.asConfig(ClusterModuleDef("c", "C", style = "discreet", unit = "kwh100"), ClusterConfig(powerMax = 150))
        assertEquals("discreet", cfg.style)
        assertEquals(150, cfg.powerMax)
    }

    /** Raccourci « Module suivant » : par profil et par onglet ; nouveau profil = premier, retour = dernier vu. */
    @Test
    fun `the shortcut cycles per profile and tab`() {
        val r = ClusterRotation()
        assertEquals(0, r.index("paul", ClusterTab.NAVIGATION, 3))
        assertEquals(1, r.next("paul", ClusterTab.NAVIGATION, 3))
        assertEquals(2, r.next("paul", ClusterTab.NAVIGATION, 3))
        assertEquals(0, r.next("paul", ClusterTab.NAVIGATION, 3))
        r.next("paul", ClusterTab.NAVIGATION, 3)
        // Autre profil : le premier ; autre onglet : le premier.
        assertEquals(0, r.index("marie", ClusterTab.NAVIGATION, 3))
        assertEquals(0, r.index("paul", ClusterTab.MEDIA, 2))
        // Retour sur Paul : là où il en était.
        assertEquals(1, r.index("paul", ClusterTab.NAVIGATION, 3))
        // Liste raccourcie : le rang reste dans la liste.
        assertEquals(1, r.index("paul", ClusterTab.NAVIGATION, 2))
        assertEquals(0, r.next("x", ClusterTab.NAVIGATION, 0))
        r.reset()
        assertEquals(0, r.index("paul", ClusterTab.NAVIGATION, 3))
        assertNull(null)
    }
}
