package com.mg4.control.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raccourci « Cycle de profils » — quel profil appliquer à l'appui, sans Android ni véhicule.
 *
 * Deux choses sont à risque. L'ORDRE, qui est celui choisi par l'utilisateur et pas celui de la
 * liste des profils. Et les profils qui DISPARAISSENT : un profil supprimé laisse son identifiant
 * dans le réglage, et le raccourci ne doit ni rester muet, ni appliquer un profil que
 * l'utilisateur avait volontairement laissé hors de son cycle.
 */
class ProfileCycleTest {

    private val tous = listOf("quotidien", "autoroute", "hiver", "eco", "sport")

    // ── Le cycle à parcourir ─────────────────────────────────────────────────

    @Test
    fun `sans cycle enregistre tous les profils sont parcourus dans l ordre de la liste`() {
        assertEquals(tous, ProfileCycle.resolve(null, tous))
    }

    @Test
    fun `le cycle enregistre garde l ordre choisi`() {
        // L'ordre des appuis de l'utilisateur, pas celui de la liste des profils.
        assertEquals(
            listOf("hiver", "quotidien", "sport"),
            ProfileCycle.resolve("hiver,quotidien,sport", tous))
    }

    @Test
    fun `un profil supprime sort du cycle`() {
        val restants = listOf("quotidien", "hiver", "sport")
        assertEquals(
            listOf("hiver", "quotidien"),
            ProfileCycle.resolve("hiver,autoroute,quotidien", restants))
    }

    @Test
    fun `un seul profil restant reste le cycle choisi`() {
        // Compléter avec les autres profils appliquerait ceux que l'utilisateur avait écartés.
        assertEquals(listOf("hiver"), ProfileCycle.resolve("hiver,autoroute", listOf("quotidien", "hiver", "sport")))
    }

    @Test
    fun `un cycle dont plus aucun profil n existe redevient le cycle par defaut`() {
        // Le réglage ne désigne plus rien : mieux vaut tous les profils qu'une touche sans effet.
        val restants = listOf("quotidien", "sport")
        assertEquals(restants, ProfileCycle.resolve("hiver,autoroute", restants))
    }

    @Test
    fun `une preference abimee ne casse rien`() {
        assertEquals(listOf("hiver", "sport"), ProfileCycle.resolve(" hiver ,,hiver, sport,inconnu", tous))
        assertEquals(tous, ProfileCycle.resolve("", tous))
        assertEquals(tous, ProfileCycle.resolve(" , ", tous))
    }

    @Test
    fun `l ordre enregistre se relit tel quel`() {
        val choisi = listOf("sport", "quotidien", "hiver")
        assertEquals(choisi, ProfileCycle.resolve(ProfileCycle.encode(choisi), tous))
    }

    // ── Le profil suivant ────────────────────────────────────────────────────

    private val cycle = listOf("quotidien", "autoroute", "hiver")

    @Test
    fun `le profil suivant est celui qui suit le profil actif et le cycle reboucle`() {
        assertEquals("autoroute", ProfileCycle.next(cycle, "quotidien"))
        assertEquals("hiver", ProfileCycle.next(cycle, "autoroute"))
        assertEquals("quotidien", ProfileCycle.next(cycle, "hiver"))
    }

    @Test
    fun `un profil actif hors cycle fait entrer par le premier`() {
        // Profil appliqué par Bluetooth ou par une automatisation, ou aucun profil appliqué :
        // ne rien faire passerait pour une panne du raccourci.
        assertEquals("quotidien", ProfileCycle.next(cycle, "sport"))
        assertEquals("quotidien", ProfileCycle.next(cycle, null))
    }

    @Test
    fun `sans profil il n y a rien a appliquer`() {
        assertNull(ProfileCycle.next(emptyList(), "quotidien"))
        assertNull(ProfileCycle.next(emptyList(), null))
    }

    @Test
    fun `un cycle d un seul profil le reapplique`() {
        assertEquals("hiver", ProfileCycle.next(listOf("hiver"), "hiver"))
        assertEquals("hiver", ProfileCycle.next(listOf("hiver"), "sport"))
    }

    // ── Appuis rapprochés ────────────────────────────────────────────────────
    // Le profil n'est appliqué qu'après un court délai sans nouvel appui : pendant ce délai, le
    // profil ACTIF n'a pas encore changé, et repartir de lui viserait deux fois le même profil.

    @Test
    fun `un premier appui vise le profil qui suit le profil actif`() {
        assertEquals("autoroute", ProfileCycle.target(cycle, actif = "quotidien", enAttente = null))
    }

    @Test
    fun `un second appui rapproche repart du profil vise et saute le precedent`() {
        assertEquals("hiver", ProfileCycle.target(cycle, actif = "quotidien", enAttente = "autoroute"))
        assertEquals("quotidien", ProfileCycle.target(cycle, actif = "quotidien", enAttente = "hiver"))
    }

    // ── Bouton de l'écran de réglage ─────────────────────────────────────────

    @Test
    fun `un nom court s affiche en entier sur son bouton`() {
        assertEquals("Quotidien", ProfileCycle.label("Quotidien"))
        assertEquals("Quotidien", ProfileCycle.label("  Quotidien "))
    }

    @Test
    fun `un nom long est raccourci pour laisser la ligne du rang visible`() {
        // Un nom fait jusqu'à 30 caractères : sur deux lignes, il chasserait le rang du bouton.
        val court = ProfileCycle.label("Autoroute longue distance hiver")
        assertTrue("« $court » trop long", court.length <= ProfileCycle.LABEL_MAX)
        assertTrue("« $court » sans points de suspension", court.endsWith("\u2026"))
        assertTrue("« $court » ne commence plus par le nom", court.startsWith("Autoroute"))
    }
}
